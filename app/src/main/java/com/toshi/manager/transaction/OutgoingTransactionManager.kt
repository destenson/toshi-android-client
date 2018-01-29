/*
 * 	Copyright (c) 2017. Toshi Inc
 *
 * 	This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.toshi.manager.transaction

import com.toshi.manager.model.ExternalPaymentTask
import com.toshi.manager.model.PaymentTask
import com.toshi.manager.model.ResendToshiPaymentTask
import com.toshi.manager.model.SentToshiPaymentTask
import com.toshi.manager.model.ToshiPaymentTask
import com.toshi.manager.store.PendingTransactionStore
import com.toshi.model.local.PendingTransaction
import com.toshi.model.local.Recipient
import com.toshi.model.local.SendState
import com.toshi.model.local.User
import com.toshi.model.sofa.Payment
import com.toshi.model.sofa.SofaAdapters
import com.toshi.model.sofa.SofaMessage
import com.toshi.util.LogUtil
import com.toshi.view.BaseApplication
import rx.Single
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import rx.subscriptions.CompositeSubscription

class OutgoingTransactionManager(
        private val pendingTransactionStore: PendingTransactionStore,
        private val transactionSigner: TransactionSigner
) {

    private val userManager by lazy { BaseApplication.get().userManager }
    private val sofaMessageManager by lazy { BaseApplication.get().sofaMessageManager }
    private val newOutgoingPaymentQueue by lazy { PublishSubject.create<PaymentTask>() }
    private val subscriptions by lazy { CompositeSubscription() }
    private var outgoingPaymentSub: Subscription? = null
    private val paymentErrorTask by lazy { PaymentErrorTask() }

    fun attachNewOutgoingPaymentSubscriber() {
        // Explicitly clear first to avoid double subscription
        clearSubscription()
        outgoingPaymentSub = newOutgoingPaymentQueue
                .subscribeOn(Schedulers.io())
                .subscribe(
                        { processNewOutgoingPayment(it) },
                        { LogUtil.e(javaClass, "Error while handling outgoing payment $it") }
                )

        subscriptions.add(outgoingPaymentSub)
    }

    private fun processNewOutgoingPayment(paymentTask: PaymentTask) {
        when (paymentTask) {
            is ResendToshiPaymentTask -> resendPayment(paymentTask)
            is ToshiPaymentTask -> sendToshiPayment(paymentTask)
            is ExternalPaymentTask -> sendExternalPayment(paymentTask)
        }
    }

    private fun sendToshiPayment(paymentTask: ToshiPaymentTask) {
        getUpdatedToshiPayment(paymentTask)
                .subscribe(
                        { handleOutgoingToshiPayment(it.first, it.second) },
                        { LogUtil.e(javaClass, "Error while sending payment $it") }
                )
    }

    private fun getUpdatedToshiPayment(paymentTask: ToshiPaymentTask): Single<Pair<ToshiPaymentTask, SofaMessage>> {
        val receiver = getReceiverFromTask(paymentTask)
        val sender = getSenderFromTask(paymentTask)
        return paymentTask.payment
                .generateLocalPrice()
                .map { storePayment(receiver, it, sender) }
                .map { Pair(paymentTask, it) }
                .subscribeOn(Schedulers.io())
    }

    private fun handleOutgoingToshiPayment(paymentTask: ToshiPaymentTask, storedSofaMessage: SofaMessage) {
        transactionSigner.signAndSendTransaction(paymentTask.unsignedTransaction)
                .map { SentToshiPaymentTask(paymentTask, it) }
                .subscribeOn(Schedulers.io())
                .subscribe(
                        { handleOutgoingToshiPaymentSuccess(it, storedSofaMessage) },
                        { handleOutgoingPaymentError(it, paymentTask.user, storedSofaMessage) }
                )
    }

    private fun handleOutgoingToshiPaymentSuccess(paymentTask: SentToshiPaymentTask, storedSofaMessage: SofaMessage) {
        val sentTransaction = paymentTask.sentTransaction
        val payment = paymentTask.payment
        val receiver = paymentTask.user
        val txHash = sentTransaction.txHash
        payment.txHash = txHash

        // Update the stored message with the transactions details
        val updatedMessage = generateMessageFromPayment(payment, getLocalUser())
        storedSofaMessage.payload = updatedMessage.payloadWithHeaders
        updateMessageState(receiver, storedSofaMessage, SendState.STATE_SENT)
        storeUnconfirmedTransaction(txHash, storedSofaMessage)
        sofaMessageManager.sendMessage(Recipient(receiver), storedSofaMessage)
    }

    private fun handleOutgoingPaymentError(error: Throwable, receiver: User, storedSofaMessage: SofaMessage) {
        updateMessageState(receiver, storedSofaMessage, SendState.STATE_FAILED)
        paymentErrorTask.handleOutgoingPaymentError(error, receiver, storedSofaMessage)
    }

    private fun resendPayment(paymentTask: ResendToshiPaymentTask) {
        val sofaMessageId = paymentTask.sofaMessage.privateKey
        sofaMessageManager.getSofaMessageById(sofaMessageId)
                .map { paymentTask.copy(sofaMessage = it) }
                .subscribe(
                        { handleOutgoingResendPayment(it) },
                        { LogUtil.e(javaClass, "Error while resending payment $it") }
                )
    }

    private fun handleOutgoingResendPayment(paymentTask: ResendToshiPaymentTask) {
        val sofaMessage = paymentTask.sofaMessage
        updateMessageState(paymentTask.user, sofaMessage, SendState.STATE_SENDING)
        handleOutgoingToshiPayment(paymentTask, sofaMessage)
    }

    private fun updateMessageState(user: User, sofaMessage: SofaMessage, @SendState.State sendState: Int) {
        sofaMessage.sendState = sendState
        updateMessage(user, sofaMessage)
    }

    private fun updateMessage(user: User, message: SofaMessage) {
        val recipient = Recipient(user)
        sofaMessageManager.updateMessage(recipient, message)
    }

    private fun sendExternalPayment(paymentTask: ExternalPaymentTask) {
        getUpdatedExternalPayment(paymentTask)
                .flatMap { transactionSigner.signAndSendTransaction(paymentTask.unsignedTransaction) }
                .subscribe(
                        {},
                        { handleOutgoingExternalPaymentError(it, paymentTask) }
                )
    }

    private fun getUpdatedExternalPayment(paymentTask: ExternalPaymentTask): Single<ExternalPaymentTask> {
        val receiver = getReceiverFromTask(paymentTask)
        val sender = getSenderFromTask(paymentTask)
        return paymentTask.payment
                .generateLocalPrice()
                .doOnSuccess { storePayment(receiver, it, sender) }
                .map { paymentTask }
                .subscribeOn(Schedulers.io())
    }

    private fun handleOutgoingExternalPaymentError(error: Throwable, paymentTask: ExternalPaymentTask) {
        paymentErrorTask.handleOutgoingExternalPaymentError(error, paymentTask)
    }

    private fun storeUnconfirmedTransaction(txHash: String, message: SofaMessage) {
        val pendingTransaction = PendingTransaction()
                .setSofaMessage(message)
                .setTxHash(txHash)
        pendingTransactionStore.save(pendingTransaction)
    }

    private fun storePayment(receiver: User?, payment: Payment, sender: User?): SofaMessage {
        val sofaMessage = generateMessageFromPayment(payment, sender)
        storeMessage(receiver, sofaMessage)
        return sofaMessage
    }

    private fun generateMessageFromPayment(payment: Payment, sender: User?): SofaMessage {
        val messageBody = SofaAdapters.get().toJson(payment)
        return SofaMessage().makeNewFromTransaction(payment.txHash, sender, messageBody)
    }

    private fun storeMessage(receiver: User?, message: SofaMessage) {
        // receiver will be null if this is an external payment
        receiver?.let {
            message.sendState = SendState.STATE_SENDING
            sofaMessageManager.saveTransaction(receiver, message)
        }
    }

    private fun getSenderFromTask(task: PaymentTask): User? {
        if (task is ToshiPaymentTask) return getLocalUser()
        if (task is ExternalPaymentTask) return getLocalUser()
        throw IllegalStateException("Unknown payment task action.")
    }

    private fun getReceiverFromTask(task: PaymentTask): User? {
        if (task is ToshiPaymentTask) return task.user
        if (task is ExternalPaymentTask) return null
        throw IllegalStateException("Unknown payment task action.")
    }

    fun addOutgoingToshiPaymentTask(paymentTask: ToshiPaymentTask) {
        addOutgoingPaymentTask(paymentTask)
    }

    fun addOutgoingResendPaymentTask(paymentTask: ResendToshiPaymentTask) {
        addOutgoingPaymentTask(paymentTask)
    }

    fun addOutgoingExternalPaymentTask(paymentTask: ExternalPaymentTask) {
        addOutgoingPaymentTask(paymentTask)
    }

    private fun getLocalUser() = userManager.getCurrentUser().toBlocking().value()
    private fun clearSubscription() = outgoingPaymentSub?.unsubscribe()
    private fun addOutgoingPaymentTask(paymentTask: PaymentTask) = newOutgoingPaymentQueue.onNext(paymentTask)
    fun clearSubscriptions() = subscriptions.clear()
}
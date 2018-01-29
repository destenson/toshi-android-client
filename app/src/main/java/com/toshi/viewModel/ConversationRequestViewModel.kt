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

package com.toshi.viewModel

import android.arch.lifecycle.ViewModel
import com.toshi.model.local.Conversation
import com.toshi.model.local.User
import com.toshi.util.LogUtil
import com.toshi.util.SingleLiveEvent
import com.toshi.view.BaseApplication
import rx.Single
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription

class ConversationRequestViewModel : ViewModel() {

    private val subscriptions by lazy { CompositeSubscription() }

    val conversationsAndLocalUser by lazy { SingleLiveEvent<Pair<List<Conversation>, User>>() }
    val updatedConversation by lazy { SingleLiveEvent<Conversation>() }
    val acceptConversation by lazy { SingleLiveEvent<Conversation>() }
    val rejectConversation by lazy { SingleLiveEvent<Conversation>() }

    init {
        attachSubscriber()
    }

    private fun attachSubscriber() {
        val sub = getSofaMessageManager()
                .registerForAllConversationChanges()
                .filter { !it.conversationStatus.isAccepted }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { updatedConversation.value = it },
                        { LogUtil.e(javaClass, "Error fetching conversations $it") }
                )

        subscriptions.add(sub)
    }

    fun getUnacceptedConversationsAndLocalUser() {
        val sub =
                Single.zip(
                        getSofaMessageManager().loadAllUnacceptedConversations(),
                        getUserManager().getCurrentUser(),
                        { conversations, localUser -> Pair(conversations, localUser) }
                )
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { conversationsAndLocalUser.value = it },
                        { LogUtil.e(javaClass, "Error fetching conversations $it") }
                )

        subscriptions.add(sub)
    }

    fun acceptConversation(conversation: Conversation) {
        val sub = getSofaMessageManager()
                .acceptConversation(conversation)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { acceptConversation.value = it },
                        { LogUtil.e(javaClass, "Error while accepting conversation $it") }
                )

        subscriptions.add(sub)
    }

    fun rejectConversation(conversation: Conversation) {
        val sub = getSofaMessageManager()
                .rejectConversation(conversation)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { rejectConversation.value = it },
                        { LogUtil.e(javaClass, "Error while accepting conversation $it") }
                )

        subscriptions.add(sub)
    }

    private fun getSofaMessageManager() = BaseApplication.get().sofaMessageManager

    private fun getUserManager() = BaseApplication.get().userManager

    override fun onCleared() {
        super.onCleared()
        subscriptions.clear()
    }
}
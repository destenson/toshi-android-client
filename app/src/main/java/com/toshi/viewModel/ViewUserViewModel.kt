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
import com.toshi.model.local.Report
import com.toshi.model.local.Review
import com.toshi.model.local.User
import com.toshi.model.network.ReputationScore
import com.toshi.model.network.ServerTime
import com.toshi.util.LogUtil
import com.toshi.util.SingleLiveEvent
import com.toshi.util.SoundManager
import com.toshi.view.BaseApplication
import rx.Completable
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription

class ViewUserViewModel : ViewModel() {

    private val subscriptions by lazy { CompositeSubscription() }

    val user by lazy { SingleLiveEvent<User>() }
    val isLocalUser by lazy { SingleLiveEvent<Boolean>() }
    val noUser by lazy { SingleLiveEvent<Unit>() }
    val reputation by lazy { SingleLiveEvent<ReputationScore>() }
    val isFavored by lazy { SingleLiveEvent<Boolean>() }
    val review by lazy { SingleLiveEvent<Boolean>() }
    val report by lazy { SingleLiveEvent<Boolean>() }
    val isUserBlocked by lazy { SingleLiveEvent<Boolean>() }
    val blocking by lazy { SingleLiveEvent<BlockingAction>() }

    fun getUserById(userAddress: String) {
        val sub = getRecipientManager()
                .getUserFromToshiId(userAddress)
                .doOnSuccess { fetchUserReputation(it.toshiId) }
                .doOnSuccess { isFavored(it) }
                .doOnSuccess { isLocalUser(it) }
                .doOnSuccess { isUserBlocked(it) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { user.value = it },
                        { noUser.value = Unit }
                )

        subscriptions.add(sub)
    }

    fun tryLookupByUsername(searchedForUsername: String) {
        val sub = getRecipientManager()
                .getUserFromUsername(searchedForUsername)
                .toObservable()
                .filter { it.usernameForEditing.toLowerCase() == searchedForUsername.toLowerCase() }
                .toSingle()
                .doOnSuccess { fetchUserReputation(it.toshiId) }
                .doOnSuccess { isFavored(it) }
                .doOnSuccess { isLocalUser(it) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { user.value = it },
                        { noUser.value = Unit }
                )

        subscriptions.add(sub)
    }

    private fun isFavored(user: User) {
        val sub = getRecipientManager()
                .isUserAContact(user)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { isFavored.value = it },
                        { LogUtil.e(javaClass, "Error while fetching local user $it") }
                )

        subscriptions.add(sub)
    }

    private fun isLocalUser(user: User) {
        val sub = getUserManager()
                .getCurrentUser()
                .map { user.toshiId == it.toshiId }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { isLocalUser.value = it },
                        { LogUtil.e(javaClass, "Error while fetching local user $it") }
                )

        subscriptions.add(sub)
    }

    private fun fetchUserReputation(userAddress: String) {
        val reputationSub = getReputationManager()
                .getReputationScore(userAddress)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { reputation.value = it },
                        { LogUtil.exception(javaClass, "Error during reputation fetching $it") }
                )

        subscriptions.add(reputationSub)
    }

    fun favorOrUnFavorUser(user: User) {
        val sub = getRecipientManager()
                .isUserAContact(user)
                .flatMapCompletable { favorOrUnFavorUser(user, it) }
                .subscribe(
                        { },
                        { LogUtil.exception(javaClass, "Error during saving contact $it") }
                )

        subscriptions.add(sub)
    }

    private fun favorOrUnFavorUser(user: User, isAContact: Boolean): Completable {
        val favoriteAction = if (isAContact) deleteContact(user)
        else saveContact(user)
                .doOnCompleted { SoundManager.getInstance().playSound(SoundManager.ADD_CONTACT) }

        return favoriteAction
                .observeOn(AndroidSchedulers.mainThread())
                .doOnCompleted { isFavored.value = !isAContact }
    }

    fun submitReview(review: Review) {
        val sub = getRecipientManager()
                .timestamp
                .flatMapCompletable { serverTime -> submitReview(review, serverTime) }
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { this.review.value = true },
                        { this.review.value = false }
                )

        subscriptions.add(sub)
    }

    private fun submitReview(review: Review, serverTime: ServerTime): Completable {
        return getReputationManager()
                .submitReview(review, serverTime.get())
                .toCompletable()
    }

    fun submitReport(report: Report) {
        val sub = getRecipientManager()
                .reportUser(report)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { this.report.value = true },
                        { this.report.value = false }
                )

        this.subscriptions.add(sub)
    }

    private fun isUserBlocked(user: User) {
        val sub = getRecipientManager()
                .isUserBlocked(user.toshiId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { isUserBlocked.value = it },
                        { LogUtil.e(javaClass, "Error while checking if user is blocking $it") }
                )

        this.subscriptions.add(sub)
    }

    fun blockUser(userAddress: String) {
        val sub = getRecipientManager()
                .blockUser(userAddress)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnCompleted { isUserBlocked.value = true }
                .subscribe(
                        { blocking.value = BlockingAction.BLOCKED },
                        { LogUtil.e(javaClass, "Error while blocking user $it") }
                )

        this.subscriptions.add(sub)
    }

    fun unblockUser(userAddress: String) {
        val sub = getRecipientManager()
                .unblockUser(userAddress)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnCompleted { isUserBlocked.value = false }
                .subscribe(
                        { blocking.value = BlockingAction.UNBLOCKED },
                        { LogUtil.e(javaClass, "Error while unblocking user $it") }
                )

        this.subscriptions.add(sub)
    }

    private fun getRecipientManager() = BaseApplication.get().recipientManager

    private fun getReputationManager() = BaseApplication.get().reputationManager

    private fun getUserManager() = BaseApplication.get().userManager

    private fun deleteContact(user: User) = getRecipientManager().deleteContact(user)

    private fun saveContact(user: User) = getRecipientManager().saveContact(user)

    fun checkIfLocalUserFromId(userAddress: String) = getLocalUser()?.toshiId == userAddress

    fun checkIfLocalUserFromUsername(searchedForUsername: String): Boolean {
        val localUsername = getLocalUser()?.usernameForEditing
        return localUsername?.toLowerCase().equals(searchedForUsername.toLowerCase())
    }

    private fun getLocalUser(): User? {
        return getUserManager()
                .getCurrentUser()
                .toBlocking()
                .value()
    }

    override fun onCleared() {
        subscriptions.clear()
    }
}

enum class BlockingAction {
    BLOCKED, UNBLOCKED
}
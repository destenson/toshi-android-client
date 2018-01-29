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

import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.ViewModel
import com.toshi.model.local.User
import com.toshi.model.network.ReputationScore
import com.toshi.view.BaseApplication
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription

class ViewProfileViewModel : ViewModel() {

    private val subscriptions by lazy { CompositeSubscription() }
    private val reputationManager by lazy { BaseApplication.get().reputationManager }
    private val userManager by lazy { BaseApplication.get().userManager }

    val user by lazy { MutableLiveData<User>() }
    val isConnected by lazy { MutableLiveData<Boolean>() }
    val reputation by lazy { MutableLiveData<ReputationScore>() }

    init {
        fetchUser()
        attachConnectionListeners()
    }

    private fun fetchUser() {
        val sub = userManager
                .getUserObservable()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { user.value = it; fetchUserReputation(it.toshiId) },
                        { user.value = null }
                )

        this.subscriptions.add(sub)
    }

    private fun attachConnectionListeners() {
        val sub = BaseApplication.get()
                .isConnectedSubject
                .subscribe(
                        { isConnected.value = it },
                        { isConnected.value = false }
                )

        this.subscriptions.add(sub)
    }

    private fun fetchUserReputation(userAddress: String) {
        val reputationSub = reputationManager
                .getReputationScore(userAddress)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { reputation.value = it },
                        { reputation.value = null }
                )

        this.subscriptions.add(reputationSub)
    }

    override fun onCleared() {
        super.onCleared()
        subscriptions.clear()
    }
}
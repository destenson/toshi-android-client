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
import com.toshi.model.network.Balance
import com.toshi.util.LogUtil
import com.toshi.util.SingleLiveEvent
import com.toshi.view.BaseApplication
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription

class MeViewModel : ViewModel() {

    private val subscriptions by lazy { CompositeSubscription() }

    val user by lazy { MutableLiveData<User>() }
    val balance by lazy { MutableLiveData<Balance>() }
    val singelBalance by lazy { SingleLiveEvent<Balance>() }
    val formattedBalance by lazy { MutableLiveData<String>() }

    init {
        fetchUser()
        attachBalanceSubscriber()
    }

    private fun fetchUser() {
        val sub = getUserManager()
                .getCurrentUserObservable()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { user.value = it },
                        { LogUtil.exception(javaClass, "Error during fetching user $it") }
                )

        subscriptions.add(sub)
    }

    private fun getUserManager() = BaseApplication.get().userManager

    private fun attachBalanceSubscriber() {
        val sub = getBalanceManager()
                .balanceObservable
                .observeOn(AndroidSchedulers.mainThread())
                .filter { balance -> balance != null }
                .doOnNext { getFormattedBalance(it) }
                .subscribe(
                        { balance.value = it },
                        { LogUtil.exception(javaClass, "Error during fetching balance $it") }
                )

        this.subscriptions.add(sub)
    }

    fun getFormattedBalance(balance: Balance) {
        val sub = balance
                .formattedLocalBalance
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { formattedBalance.value = it },
                        { LogUtil.exception(javaClass, "Error fetching formated balance $it") }
                )

        this.subscriptions.add(sub)
    }

    fun getBalance() {
        val sub = getBalanceManager()
                .balanceObservable
                .first()
                .toSingle()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { singelBalance.value = it },
                        { LogUtil.exception(javaClass, "Error showing dialog $it") }
                )

        this.subscriptions.add(sub)
    }

    private fun getBalanceManager() = BaseApplication.get().balanceManager

    override fun onCleared() {
        super.onCleared()
        subscriptions.clear()
    }
}
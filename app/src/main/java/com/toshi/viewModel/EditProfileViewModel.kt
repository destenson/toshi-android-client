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
import com.toshi.R
import com.toshi.model.local.User
import com.toshi.model.network.UserDetails
import com.toshi.util.SingleLiveEvent
import com.toshi.view.BaseApplication
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription

class EditProfileViewModel : ViewModel() {

    private val subscriptions by lazy { CompositeSubscription() }
    private val userManager by lazy { BaseApplication.get().userManager }

    val user by lazy { MutableLiveData<User>() }
    val userUpdated by lazy { SingleLiveEvent<Unit>() }
    val error by lazy { SingleLiveEvent<Int>() }
    val displayNameError by lazy { SingleLiveEvent<Int>() }
    val usernameError by lazy { SingleLiveEvent<Int>() }
    var capturedImagePath: String? = null

    init { getUser() }

    private fun getUser() {
        val sub = userManager
                .getUserObservable()
                .filter { user -> user != null }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { user.value = it },
                        { error.value = R.string.user_error }
                )

        this.subscriptions.add(sub)
    }

    fun updateUser(userDetails: UserDetails) {
        if (!isUserDetailsApproved(userDetails)) return
        val sub = userManager
                .updateUser(userDetails)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { userUpdated.value = Unit },
                        { error.value = R.string.user_update_error }
                )

        subscriptions.add(sub)
    }

    private fun isUserDetailsApproved(userDetails: UserDetails): Boolean {
        if (userDetails.name?.isEmpty() == true) {
            displayNameError.value = R.string.error__required
            return false
        }

        if (userDetails.username?.isEmpty() == true) {
            usernameError.value = R.string.error__required
            return false
        }

        if (userDetails.username?.contains(" ") == true) {
            usernameError.value = R.string.error__invalid_characters
            return false
        }
        return true
    }

    override fun onCleared() {
        super.onCleared()
        subscriptions.clear()
    }
}
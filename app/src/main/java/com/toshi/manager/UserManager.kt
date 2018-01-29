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

package com.toshi.manager

import android.content.Context
import com.toshi.crypto.HDWallet
import com.toshi.manager.network.IdService
import com.toshi.model.local.User
import com.toshi.model.network.ServerTime
import com.toshi.model.network.UserDetails
import com.toshi.util.FileNames
import com.toshi.util.FileUtil
import com.toshi.util.LogUtil
import com.toshi.util.SharedPrefsUtil
import com.toshi.view.BaseApplication
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.HttpException
import rx.Completable
import rx.Observable
import rx.Single
import rx.Subscription
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject
import java.io.File

class UserManager {

    companion object {
        private const val FORM_DATA_NAME = "Profile-Image-Upload"
        private const val OLD_USER_ID = "uid"
        private const val USER_ID = "uid_v2"
    }

    private val recipientManager by lazy { BaseApplication.get().recipientManager }
    private val isConnectedSubject by lazy { BaseApplication.get().isConnectedSubject }

    private val userSubject by lazy { BehaviorSubject.create<User>() }
    private val prefs by lazy { BaseApplication.get().getSharedPreferences(FileNames.USER_PREFS, Context.MODE_PRIVATE) }
    private var connectivitySub: Subscription? = null
    private lateinit var wallet: HDWallet

    fun init(wallet: HDWallet): Completable {
        this.wallet = wallet
        attachConnectivityListener()
        return initUser()
    }

    private fun attachConnectivityListener() {
        // Whenever the network changes init the user.
        // This is dumb and potentially inefficient but it shouldn't have
        // any adverse effects and it is easy to improve later.
        clearSubscriptions()
        connectivitySub = isConnectedSubject
                .skip(1) // Skip the cached value in the subject.
                .subscribe(
                        { handleConnectivity(it) },
                        { LogUtil.exception(javaClass, "Error while initiating user $it") }
                )
    }

    private fun handleConnectivity(isConnected: Boolean) {
        if (isConnected) initUser()
                .subscribeOn(Schedulers.io())
                .subscribe({}, {})
    }

    private fun initUser(): Completable {
        return when {
            userNeedsToRegister() -> registerNewUser()
            userNeedsToMigrate() -> migrateUser()
            SharedPrefsUtil.shouldForceUserUpdate() -> forceUpdateUser()
            else -> fetchUser().toCompletable()
        }
    }

    private fun userNeedsToRegister(): Boolean {
        val oldUserId = prefs.getString(OLD_USER_ID, null)
        val newUserId = prefs.getString(USER_ID, null)
        val expectedAddress = wallet.ownerAddress
        val userId = newUserId ?: oldUserId
        return userId == null || userId != expectedAddress
    }

    private fun userNeedsToMigrate(): Boolean {
        val userId = prefs.getString(USER_ID, null)
        val expectedAddress = wallet.ownerAddress
        return userId == null || userId != expectedAddress
    }

    private fun registerNewUser(): Completable {
        return getTimestamp()
                .flatMap { registerNewUserWithTimestamp(it) }
                .doOnSuccess { updateCurrentUser(it) }
                .doOnError { handleUserRegistrationFailed(it) }
                .doOnError { LogUtil.exception(javaClass, "Error while registering user with timestamp") }
                .toCompletable()
    }

    private fun registerNewUserWithTimestamp(serverTime: ServerTime): Single<User> {
        val userDetails = UserDetails().setPaymentAddress(wallet.paymentAddress)
        SharedPrefsUtil.setForceUserUpdate(false)
        return IdService.getApi()
                .registerUser(userDetails, serverTime.get())
    }

    private fun handleUserRegistrationFailed(throwable: Throwable) {
        if (throwable is HttpException && throwable.code() == 400) forceFetchUserFromNetwork()
    }

    fun getCurrentUserObservable(): Observable<User> {
        forceFetchUserFromNetwork()
        return userSubject.asObservable()
    }

    private fun forceFetchUserFromNetwork(): Completable {
        return IdService
                .getApi()
                .forceGetUser(wallet.ownerAddress)
                .doOnSuccess { updateCurrentUser(it) }
                .doOnError { LogUtil.exception(javaClass, "Error while fetching user from network $it") }
                .toCompletable()
    }

    private fun fetchUser(): Single<User> {
        return recipientManager
                .getUserFromPaymentAddress(wallet.paymentAddress)
                .doOnSuccess { updateCurrentUser(it) }
                .doOnError { LogUtil.exception(javaClass, "Error while fetching user from network $it") }
    }

    private fun updateCurrentUser(user: User) {
        prefs.edit()
                .putString(USER_ID, user.toshiId)
                .apply()
        userSubject.onNext(user)
        recipientManager.cacheUser(user)
    }

    private fun migrateUser(): Completable {
        SharedPrefsUtil.setWasMigrated(true)
        return forceUpdateUser()
    }

    private fun forceUpdateUser(): Completable {
        val ud = UserDetails().setPaymentAddress(wallet.paymentAddress)
        return updateUser(ud)
                .doOnSuccess { SharedPrefsUtil.setForceUserUpdate(false) }
                .doOnError { LogUtil.exception(javaClass, "Error while updating user $it") }
                .toCompletable()
    }

    fun updateUser(userDetails: UserDetails): Single<User> {
        return getTimestamp()
                .flatMap { serverTime -> updateUserWithTimestamp(userDetails, serverTime) }
                .subscribeOn(Schedulers.io())
    }

    private fun updateUserWithTimestamp(userDetails: UserDetails, serverTime: ServerTime): Single<User> {
        return IdService.getApi()
                .updateUser(wallet.ownerAddress, userDetails, serverTime.get())
                .subscribeOn(Schedulers.io())
                .doOnSuccess { updateCurrentUser(it) }
    }

    fun uploadAvatar(file: File): Single<User> {
        val mimeType = FileUtil.getMimeTypeFromFilename(file.name) ?: return Single.error(IllegalArgumentException("Unable to determine file type from file."))
        val mediaType = MediaType.parse(mimeType)
        val requestFile = RequestBody.create(mediaType, file)
        val body = MultipartBody.Part.createFormData(FORM_DATA_NAME, file.name, requestFile)

        return getTimestamp()
                .subscribeOn(Schedulers.io())
                .flatMap { uploadFile(body, it) }
                .doOnSuccess { userSubject.onNext(it) }
    }

    private fun uploadFile(body: MultipartBody.Part, time: ServerTime) = IdService.getApi().uploadFile(body, time.get())

    private fun getTimestamp() = IdService.getApi().timestamp

    fun webLogin(loginToken: String): Completable {
        return getTimestamp()
                .flatMapCompletable { webLoginWithTimestamp(loginToken, it) }
                .subscribeOn(Schedulers.io())
    }

    private fun webLoginWithTimestamp(loginToken: String, serverTime: ServerTime?): Completable {
        if (serverTime == null) throw IllegalStateException("ServerTime was null")
        return IdService
                .getApi()
                .webLogin(loginToken, serverTime.get())
                .toCompletable()
    }

    fun getUserObservable(): Observable<User> = userSubject.asObservable()

    fun getCurrentUser(): Single<User> {
        return userSubject
                .first()
                .toSingle()
                .doOnError { LogUtil.exception(javaClass, "getCurrentUser $it") }
                .onErrorReturn(null)
    }

    fun clear() {
        clearSubscriptions()
        prefs.edit()
                .putString(USER_ID, null)
                .apply()
        userSubject.onNext(null)
    }

    private fun clearSubscriptions() = connectivitySub?.unsubscribe()
}
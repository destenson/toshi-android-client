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

package com.toshi.manager;


import com.toshi.extensions.StringUtils;
import com.toshi.manager.network.IdService;
import com.toshi.manager.store.BlockedUserStore;
import com.toshi.manager.store.ContactStore;
import com.toshi.manager.store.GroupStore;
import com.toshi.manager.store.UserStore;
import com.toshi.model.local.BlockedUser;
import com.toshi.model.local.Contact;
import com.toshi.model.local.Group;
import com.toshi.model.local.Recipient;
import com.toshi.model.local.Report;
import com.toshi.model.local.User;
import com.toshi.model.network.SearchResult;
import com.toshi.model.network.ServerTime;
import com.toshi.model.network.UserSearchResults;
import com.toshi.util.LogUtil;
import com.toshi.view.BaseApplication;

import java.io.IOException;
import java.util.List;

import rx.Completable;
import rx.Observable;
import rx.Single;
import rx.schedulers.Schedulers;

public class RecipientManager {

    private final ContactStore contactStore;
    private final GroupStore groupStore;
    private final UserStore userStore;
    private final BlockedUserStore blockedUserStore;

    /* package */ RecipientManager() {
        this.contactStore = new ContactStore();
        this.groupStore = new GroupStore();
        this.userStore = new UserStore();
        this.blockedUserStore = new BlockedUserStore();
    }

    public Single<Recipient> getFromId(final String recipientId) {
        if (StringUtils.isGroupId(recipientId)) {
            return getGroupFromId(recipientId).map(Recipient::new);
        }
        return getUserFromToshiId(recipientId).map(Recipient::new);
    }

    public Single<Group> getGroupFromId(final String id) {
        return this.groupStore.loadForId(id)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .doOnError(t -> LogUtil.exception(getClass(), "getGroupFromId", t));
    }

    public Single<User> getUserFromUsername(final String username) {
        return Single
                .concat(
                        this.userStore.loadForUsername(username),
                        this.fetchAndCacheFromNetworkByUsername(username))
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .first(this::isUserFresh)
                .doOnError(t -> LogUtil.exception(getClass(), "getUserFromUsername", t))
                .toSingle();
    }

    public Single<User> getUserFromToshiId(final String toshiId) {
        return Single
                .concat(
                        this.userStore.loadForToshiId(toshiId),
                        this.fetchAndCacheFromNetworkByToshiId(toshiId))
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .first(this::isUserFresh)
                .doOnError(t -> LogUtil.exception(getClass(), "getUserFromToshiId", t))
                .toSingle();
    }

    private boolean isUserFresh(final User user) {
        if (user == null) return false;
        if (!BaseApplication.get().isConnected()) return true;
        return !user.needsRefresh();
    }

    public Single<User> getUserFromPaymentAddress(final String paymentAddress) {
        return Single
                .concat(
                        this.userStore.loadForPaymentAddress(paymentAddress),
                        this.fetchAndCacheFromNetworkByPaymentAddress(paymentAddress).toSingle()
                )
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .first(this::isUserFresh)
                .doOnError(t -> LogUtil.exception(getClass(), "getUserFromPaymentAddress", t))
                .toSingle();
    }

    private Single<User> fetchAndCacheFromNetworkByUsername(final String username) {
        // It's the same endpoint
        return fetchAndCacheFromNetworkByToshiId(username);
    }

    private Single<User> fetchAndCacheFromNetworkByToshiId(final String userAddress) {
        return IdService
                .getApi()
                .getUser(userAddress)
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .doOnSuccess(this::cacheUser);
    }

    public Single<List<User>> fetchUsersFromToshiIds(final List<String> userIds) {
        return IdService
                .getApi()
                .getUsers(userIds)
                .map(SearchResult::getResults)
                .subscribeOn(Schedulers.io());
    }

    private Observable<User> fetchAndCacheFromNetworkByPaymentAddress(final String paymentAddress) {
        return IdService
                .getApi()
                .searchByPaymentAddress(paymentAddress)
                .toObservable()
                .filter(userSearchResults -> userSearchResults.getResults().size() > 0)
                .map(userSearchResults -> userSearchResults.getResults().get(0))
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .doOnNext(this::cacheUser)
                .doOnError(t -> LogUtil.exception(getClass(), "fetchAndCacheFromNetworkByPaymentAddress", t));
    }

    public void cacheUser(final User user) {
        this.userStore.save(user)
                .subscribe(
                        () -> {},
                        throwable -> LogUtil.e(getClass(), "Error while saving user to db " + throwable)
                );
    }

    public Single<List<User>> loadAllContacts() {
        return this.contactStore
                .loadAll()
                .toObservable()
                .flatMapIterable(contact -> contact)
                .map(Contact::getUser)
                .toList()
                .toSingle()
                .subscribeOn(Schedulers.io());
    }

    public Single<List<User>> loadAllUserContacts() {
        return this.contactStore
                .loadAll()
                .toObservable()
                .flatMapIterable(contact -> contact)
                .map(Contact::getUser)
                .filter(user -> !user.isApp())
                .toList()
                .toSingle()
                .subscribeOn(Schedulers.io());
    }

    public Single<List<Contact>> searchContacts(final String query) {
        return this.contactStore
                .searchByName(query)
                .subscribeOn(Schedulers.io());
    }

    public Single<List<User>> searchOnlineUsersAndApps(final String query) {
        return IdService
                .getApi()
                .searchByUsername(query)
                .subscribeOn(Schedulers.io())
                .map(UserSearchResults::getResults);
    }

    public Single<List<User>> searchOnlineUsers(final String query) {
        return IdService
                .getApi()
                .searchOnlyUsersByUsername(query)
                .subscribeOn(Schedulers.io())
                .map(UserSearchResults::getResults);
    }

    public Single<Boolean> isUserAContact(final User user) {
        return this.contactStore.userIsAContact(user)
                .subscribeOn(Schedulers.io());
    }

    public Completable deleteContact(final User user) {
        return this.contactStore.delete(user);
    }

    public Completable saveContact(final User user) {
        return this.contactStore.save(user);
    }

    public Single<Boolean> isUserBlocked(final String ownerAddress) {
        return this.blockedUserStore
                .isBlocked(ownerAddress)
                .subscribeOn(Schedulers.io());
    }

    public Completable blockUser(final String ownerAddress) {
        final BlockedUser blockedUser = new BlockedUser()
                .setOwnerAddress(ownerAddress);
        return Completable.fromAction(() ->
                this.blockedUserStore.save(blockedUser))
                .subscribeOn(Schedulers.io());
    }

    public Completable unblockUser(final String ownerAddress) {
        return Completable.fromAction(() ->
                this.blockedUserStore.delete(ownerAddress))
                .subscribeOn(Schedulers.io());
    }

    public Completable reportUser(final Report report) {
        return getTimestamp()
                .flatMap(serverTime ->
                        IdService
                        .getApi()
                        .reportUser(report, serverTime.get())
                )
                .subscribeOn(Schedulers.io())
                .toCompletable();
    }

    public Single<List<User>> getTopRatedPublicUsers(final int limit) {
        return IdService
                .getApi()
                .getUsers(true, true, false, limit)
                .map(UserSearchResults::getResults)
                .subscribeOn(Schedulers.io());
    }

    public Single<List<User>> getLatestPublicUsers(final int limit) {
        return IdService
                .getApi()
                .getUsers(true, false, true, limit)
                .map(UserSearchResults::getResults)
                .subscribeOn(Schedulers.io());
    }

    public Single<ServerTime> getTimestamp() {
        return IdService
                .getApi()
                .getTimestamp();
    }

    public void clear() {
        this.userStore.clear();
        clearCache();
    }

    private void clearCache() {
        try {
            IdService
                    .get()
                    .clearCache();
        } catch (IOException e) {
            LogUtil.exception(getClass(), "Error while clearing network cache", e);
        }
    }
}

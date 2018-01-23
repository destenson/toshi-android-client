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


import com.toshi.BuildConfig;
import com.toshi.R;
import com.toshi.crypto.HDWallet;
import com.toshi.crypto.signal.ChatService;
import com.toshi.crypto.signal.SignalPreferences;
import com.toshi.crypto.signal.store.ProtocolStore;
import com.toshi.crypto.signal.store.SignalTrustStore;
import com.toshi.manager.chat.SofaMessageReceiver;
import com.toshi.manager.chat.SofaMessageRegistration;
import com.toshi.manager.chat.SofaMessageSender;
import com.toshi.manager.chat.tasks.NewGroupNameTask;
import com.toshi.manager.chat.tasks.NewGroupMembersTask;
import com.toshi.manager.model.SofaMessageTask;
import com.toshi.manager.store.ConversationStore;
import com.toshi.model.local.Conversation;
import com.toshi.model.local.ConversationObservables;
import com.toshi.model.local.Group;
import com.toshi.model.local.IncomingMessage;
import com.toshi.model.local.Recipient;
import com.toshi.model.local.User;
import com.toshi.model.sofa.Init;
import com.toshi.model.sofa.SofaAdapters;
import com.toshi.model.sofa.SofaMessage;
import com.toshi.util.GcmPrefsUtil;
import com.toshi.util.LocaleUtil;
import com.toshi.util.LogUtil;
import com.toshi.view.BaseApplication;
import com.toshi.view.notification.ChatNotificationManager;

import org.jetbrains.annotations.NotNull;
import org.whispersystems.signalservice.internal.configuration.SignalServiceUrl;

import java.util.List;

import rx.Completable;
import rx.Observable;
import rx.Single;
import rx.Subscription;
import rx.schedulers.Schedulers;

public final class SofaMessageManager {
    private final ConversationStore conversationStore;

    private final SignalServiceUrl[] signalServiceUrls;
    private final String userAgent;

    private ChatService chatService;
    private ProtocolStore protocolStore;
    private SofaMessageReceiver messageReceiver;
    private SofaMessageRegistration sofaGcmRegister;
    private SofaMessageSender messageSender;
    private HDWallet wallet;
    private Subscription connectivitySub;

    /*package*/ SofaMessageManager() {
        this.conversationStore = new ConversationStore();
        this.userAgent = "Android " + BuildConfig.APPLICATION_ID + " - " + BuildConfig.VERSION_NAME +  ":" + BuildConfig.VERSION_CODE;
        this.signalServiceUrls = new SignalServiceUrl[1];
    }

    public final Completable init(final HDWallet wallet) {
        this.wallet = wallet;
        return initEverything();
    }

    // Will send the message to a remote peer
    // and store the message in the local database
    public final void sendAndSaveMessage(final Recipient receiver, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(receiver, message, SofaMessageTask.SEND_AND_SAVE);
        this.messageSender.addNewTask(messageTask);
    }

    // Will send the message to a remote peer
    // but not store the message in the local database
    public final void sendMessage(final Recipient recipient, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(recipient, message, SofaMessageTask.SEND_ONLY);
        this.messageSender.addNewTask(messageTask);
    }

    // Will send an init message to remote peer
    public final void sendInitMessage(final User sender, final Recipient recipient) {
        final Init initMessage = new Init()
                .setPaymentAddress(sender.getPaymentAddress())
                .setLanguage(LocaleUtil.getLocale().getLanguage());
        final String messageBody = SofaAdapters.get().toJson(initMessage);
        final SofaMessage sofaMessage = new SofaMessage().makeNew(sender, messageBody);
        final SofaMessageTask messageTask = new SofaMessageTask(recipient, sofaMessage, SofaMessageTask.SEND_ONLY);
        this.messageSender.addNewTask(messageTask);
    }

    // Create a new group
    public final Single<Conversation> createConversationFromGroup(final Group group) {
        return this.messageSender
                .createGroup(group)
                .flatMap(this.conversationStore::createNewConversationFromGroup);
    }

    public final Completable updateConversationFromGroup(final Group groupToSave, final Group groupToSend) {
        return BaseApplication
                .get()
                .getUserManager()
                .getCurrentUser()
                .map(User::getToshiId)
                .flatMapCompletable(localUserId -> updateGroup(groupToSave, localUserId))
                .andThen(messageSender.sendGroupUpdate(groupToSend))
                .subscribeOn(Schedulers.io());
    }

    private Completable updateGroup(final Group group, final String localUserId) {
        return updateNewParticipants(group, localUserId)
                .andThen(updateGroupName(group, localUserId))
                .andThen(updateGroupAvatar(group));
    }

    private Completable updateNewParticipants(final Group group, final String localUserId) {
        return new NewGroupMembersTask(this.conversationStore, true)
                .run(group.getId(), localUserId, group.getMemberIds())
                .onErrorComplete();
    }

    private Completable updateGroupName(final Group group, final String localUserId) {
        return new NewGroupNameTask(conversationStore, true)
                .run(localUserId, group.getId(), group.getTitle())
                .onErrorComplete();
    }

    private Completable updateGroupAvatar(final Group group) {
        if (group.getAvatar() == null) Completable.complete();
        return conversationStore.saveGroupAvatar(group.getId(), group.getAvatar())
                .onErrorComplete();
    }

    @NotNull
    public Completable leaveGroup(@NotNull final Group group) {
        return this.messageSender.leaveGroup(group)
                .andThen(this.conversationStore.deleteByThreadId(group.getId()))
                .doAfterTerminate(() -> ChatNotificationManager.removeNotificationsForConversation(group.getId()))
                .subscribeOn(Schedulers.io());
    }

    // Will store a transaction in the local database
    // but not send the message to a remote peer. It will also save the state as "SENDING".
    /* package */ final void saveTransaction(final User user, final SofaMessage message) {
        final Recipient recipient = new Recipient(user);
        final SofaMessageTask messageTask = new SofaMessageTask(recipient, message, SofaMessageTask.SAVE_TRANSACTION);
        this.messageSender.addNewTask(messageTask);
    }

    // Updates a pre-existing message.
    /* package */ final void updateMessage(final Recipient recipient, final SofaMessage message) {
        final SofaMessageTask messageTask = new SofaMessageTask(recipient, message, SofaMessageTask.UPDATE_MESSAGE);
        this.messageSender.addNewTask(messageTask);
    }

    public final void resumeMessageReceiving() {
        if (haveRegisteredWithServer() && this.wallet != null && this.messageReceiver != null) {
            this.messageReceiver.receiveMessagesAsync();
        }
    }

    private boolean haveRegisteredWithServer() {
        return SignalPreferences.getRegisteredWithServer();
    }

    public final void disconnect() {
        if (this.messageReceiver != null) {
            this.messageReceiver.shutdown();
        }
    }

    public final Single<List<Conversation>> loadAllAcceptedConversations() {
        return this.conversationStore.loadAllAcceptedConversation()
                .subscribeOn(Schedulers.io());
    }

    public final Single<List<Conversation>> loadAllUnacceptedConversations() {
        return this.conversationStore.loadAllUnacceptedConversation()
                .subscribeOn(Schedulers.io());
    }

    public final Single<Conversation> loadConversation(final String threadId) {
        return this.conversationStore.loadByThreadId(threadId)
                .subscribeOn(Schedulers.io());
    }

    public final Single<Conversation> loadConversationAndResetUnreadCounter(final String threadId) {
        return loadConversation(threadId)
                .flatMap(conversation -> createEmptyConversationIfNullAndSetToAccepted(conversation, threadId))
                .doOnSuccess(conversation -> this.conversationStore.resetUnreadMessageCounter(conversation.getThreadId()));
    }

    private Single<Conversation> createEmptyConversationIfNullAndSetToAccepted(final Conversation conversation, final String threadId) {
        if (conversation != null) return Single.just(conversation);
        return BaseApplication
                .get()
                .getRecipientManager()
                .getUserFromToshiId(threadId)
                .map(Recipient::new)
                .flatMap(this.conversationStore::createEmptyConversation);
    }

    public Completable deleteConversation(final Conversation conversation) {
        return this.conversationStore
                .deleteByThreadId(conversation.getThreadId())
                .subscribeOn(Schedulers.io());
    }

    public Completable deleteMessage(final Recipient recipient, final SofaMessage sofaMessage) {
        return this.conversationStore
                .deleteMessageById(recipient, sofaMessage);
    }

    public final Observable<Conversation> registerForAllConversationChanges() {
        return this.conversationStore.getConversationChangedObservable();
    }

    public final ConversationObservables registerForConversationChanges(final String threadId) {
        return this.conversationStore.registerForChanges(threadId);
    }

    public final Observable<SofaMessage> registerForDeletedMessages(final String threadId) {
        return this.conversationStore.registerForDeletedMessages(threadId);
    }

    public final void stopListeningForChanges(final String threadId) {
        this.conversationStore.stopListeningForChanges(threadId);
    }

    public final Single<Boolean> areUnreadMessages() {
        return Single
                .fromCallable(conversationStore::areUnreadMessages)
                .subscribeOn(Schedulers.io());
    }

    public Single<SofaMessage> getSofaMessageById(final String id) {
        return this.conversationStore.getSofaMessageById(id)
                .subscribeOn(Schedulers.io());
    }

    public Single<Boolean> isConversationMuted(final String threadId) {
        return this.conversationStore.loadByThreadId(threadId)
                .map(conversation -> conversation.getConversationStatus().isMuted())
                .subscribeOn(Schedulers.io());
    }

    public Completable muteConversation(final String threadId) {
        return this.conversationStore.loadByThreadId(threadId)
                .flatMap(this::muteConversation)
                .subscribeOn(Schedulers.io())
                .toCompletable();
    }

    public Completable unmuteConversation(final String threadId) {
        return this.conversationStore.loadByThreadId(threadId)
                .flatMap(this::unmuteConversation)
                .subscribeOn(Schedulers.io())
                .toCompletable();
    }

    public Single<Conversation> muteConversation(final Conversation conversation) {
        return this.conversationStore.muteConversation(conversation, true)
                .subscribeOn(Schedulers.io());
    }

    public Single<Conversation> unmuteConversation(final Conversation conversation) {
        return this.conversationStore.muteConversation(conversation, false)
                .subscribeOn(Schedulers.io());
    }

    public Single<Conversation> acceptConversation(final Conversation conversation) {
        return this.conversationStore.acceptConversation(conversation)
                .subscribeOn(Schedulers.io());
    }

    public Single<Conversation> rejectConversation(final Conversation conversation) {
        if (conversation.isGroup()) {
            return leaveGroup(conversation.getRecipient().getGroup())
                    .toSingle(() -> conversation);
        }
        return BaseApplication
                .get()
                .getRecipientManager()
                .blockUser(conversation.getThreadId())
                .andThen(deleteConversation(conversation))
                .toSingle(() -> conversation);
    }

    private Completable initEverything() {
        generateStores();
        initMessageSender();
        initMessageReceiver(this.messageSender);
        return initRegistrationTask()
                .doOnCompleted(this::attachConnectivityObserver);
    }

    private void attachConnectivityObserver() {
        clearConnectivitySubscription();

        this.connectivitySub =
                BaseApplication
                .get()
                .isConnectedSubject()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .filter(isConnected -> isConnected)
                .subscribe(
                        __ -> handleConnectivity(),
                        throwable -> LogUtil.exception(getClass(), "Error checking connection state", throwable)
                );
    }

    private void handleConnectivity() {
        redoRegistrationTask()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        () -> {},
                        throwable -> LogUtil.exception(getClass(), "Error during registration task", throwable)
                );
    }

    private void generateStores() {
        this.protocolStore = new ProtocolStore().init();
        final SignalTrustStore trustStore = new SignalTrustStore();

        final SignalServiceUrl signalServiceUrl = new SignalServiceUrl(
                BaseApplication.get().getResources().getString(R.string.chat_url),
                trustStore);
        this.signalServiceUrls[0] = signalServiceUrl;
        this.chatService = new ChatService(this.signalServiceUrls, this.wallet, this.protocolStore, this.userAgent);
    }

    private void initMessageReceiver(final SofaMessageSender messageSender) {
        if (this.messageReceiver != null) return;
        this.messageReceiver = new SofaMessageReceiver(
                this.wallet,
                this.protocolStore,
                this.conversationStore,
                this.signalServiceUrls,
                messageSender);
    }

    private void initMessageSender() {
        if (this.messageSender != null) return;
        this.messageSender = new SofaMessageSender(
                this.wallet,
                this.protocolStore,
                this.conversationStore,
                this.signalServiceUrls
        );
    }

    private Completable initRegistrationTask() {
        if (this.sofaGcmRegister != null) return Completable.complete();

        this.sofaGcmRegister = new SofaMessageRegistration(this.chatService, this.protocolStore);
        return this.sofaGcmRegister
                .registerIfNeeded()
                .doOnCompleted(this::handleRegistrationCompleted);
    }

    private Completable redoRegistrationTask() {
        if (this.sofaGcmRegister == null) {
            this.sofaGcmRegister = new SofaMessageRegistration(this.chatService, this.protocolStore);
        }

        return this.sofaGcmRegister
                .registerIfNeededWithOnboarding()
                .doOnCompleted(this::handleRegistrationCompleted);
    }

    private void handleRegistrationCompleted() {
        if (this.messageReceiver == null) return;
        this.messageReceiver.receiveMessagesAsync();
    }

    public Completable tryUnregisterGcm() {
        if (this.sofaGcmRegister == null) {
            return Completable.error(new NullPointerException("Unable to register as class hasn't been initialised yet."));
        }
        return this.sofaGcmRegister.tryUnregisterGcm();
    }

    public Completable forceRegisterChatGcm() {
        if (this.sofaGcmRegister == null) {
            return Completable.error(new NullPointerException("Unable to register as class hasn't been initialised yet."));
        }
        return this.sofaGcmRegister.forceRegisterChatGcm();
    }

    public void resendPendingMessage(final SofaMessage sofaMessage) {
        this.messageSender.sendPendingMessage(sofaMessage);
    }

    public Single<IncomingMessage> fetchLatestMessage() throws InterruptedException {
        while (this.messageReceiver == null) Thread.sleep(200);
        return this.messageReceiver.fetchLatestMessage();
    }

    public void clear() {
        clearMessageReceiver();
        clearMessageSender();
        clearGcmRegistration();
        clearConnectivitySubscription();
        this.protocolStore.deleteAllSessions();
        GcmPrefsUtil.clear();
    }

    private void clearMessageReceiver() {
        if (this.messageReceiver != null) {
            this.messageReceiver.shutdown();
            this.messageReceiver = null;
        }
    }

    private void clearMessageSender() {
        if (this.messageSender != null) {
            this.messageSender.clear();
            this.messageSender = null;
        }
    }

    private void clearGcmRegistration() {
        if (this.sofaGcmRegister != null) {
            this.sofaGcmRegister.clear();
            this.sofaGcmRegister = null;
        }
    }

    private void clearConnectivitySubscription() {
        if (this.connectivitySub == null) return;
        this.connectivitySub.unsubscribe();
    }
}

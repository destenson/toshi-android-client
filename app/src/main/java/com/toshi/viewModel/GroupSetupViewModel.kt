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
import android.graphics.Bitmap
import android.net.Uri
import com.toshi.extensions.toIdenticon
import com.toshi.model.local.Conversation
import com.toshi.model.local.Group
import com.toshi.model.local.User
import com.toshi.util.ImageUtil
import com.toshi.util.SingleLiveEvent
import com.toshi.view.BaseApplication
import rx.android.schedulers.AndroidSchedulers
import rx.subscriptions.CompositeSubscription

class GroupSetupViewModel : ViewModel() {

    private val sofaMessageManager by lazy { BaseApplication.get().sofaMessageManager }
    private val userManager by lazy { BaseApplication.get().userManager }
    private val subscriptions by lazy { CompositeSubscription() }

    val conversationCreated by lazy { SingleLiveEvent<Conversation>() }
    val group by lazy { SingleLiveEvent<Group>() }
    val error by lazy { SingleLiveEvent<Throwable>() }
    val isCreatingGroup by lazy { MutableLiveData<Boolean>() }
    var selectedParticipants: List<User>? = null
    var avatarUri: Uri? = null

    fun createGroup(participants: List<User>,
                    avatarUri: Uri?,
                    groupName: String) {
        if (isCreatingGroup.value == true) return
        val subscription = generateAvatarFromUri(avatarUri)
                .map { avatar -> tryGeneratePlaceholderAvatar(avatar, groupName) }
                .map { avatar -> createGroupObject(participants, avatar, groupName) }
                .flatMap { addCurrentUserToGroup(it) }
                .flatMap { createConversationFromGroup(it) }
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { isCreatingGroup.value = true }
                .doAfterTerminate { isCreatingGroup.value = false }
                .subscribe(
                        { conversationCreated.value = it },
                        { error.value = it }
                )
        this.subscriptions.add(subscription)
    }

    private fun createGroupObject(participants: List<User>,
                                  avatar: Bitmap?,
                                  groupName: String): Group {
        return Group(participants)
                .setTitle(groupName)
                .setAvatar(avatar)
    }

    private fun addCurrentUserToGroup(group: Group) = userManager.getCurrentUser().map { group.addMember(it) }

    private fun createConversationFromGroup(group: Group) = sofaMessageManager.createConversationFromGroup(group)

    private fun generateAvatarFromUri(avatarUri: Uri?) = ImageUtil.loadAsBitmap(avatarUri, BaseApplication.get())

    private fun tryGeneratePlaceholderAvatar(avatar: Bitmap?, groupName: String) = avatar.let { avatar } ?: groupName.toIdenticon()

    override fun onCleared() {
        super.onCleared()
        subscriptions.clear()
    }
}

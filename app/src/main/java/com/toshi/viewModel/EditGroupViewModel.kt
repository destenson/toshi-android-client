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
import com.toshi.R
import com.toshi.model.local.Group
import com.toshi.util.ImageUtil
import com.toshi.util.SingleLiveEvent
import com.toshi.view.BaseApplication
import rx.Single
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subscriptions.CompositeSubscription

class EditGroupViewModel(val groupId: String) : ViewModel() {

    private val subscriptions by lazy { CompositeSubscription() }
    private val recipientManager by lazy { BaseApplication.get().recipientManager }
    private val sofaMessageManager by lazy { BaseApplication.get().sofaMessageManager }

    val group by lazy { MutableLiveData<Group>() }
    val isUpdatingGroup by lazy { MutableLiveData<Boolean>() }
    val updatedGroup by lazy { SingleLiveEvent<Boolean>() }
    val error by lazy { SingleLiveEvent<Int>() }

    var capturedImagePath: String? = null
    var avatarUri: Uri? = null

    fun fetchGroup() {
        val sub = recipientManager
                .getGroupFromId(groupId)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        { group.value = it },
                        { error.value = R.string.unable_to_fetch_group }
                )
        this.subscriptions.add(sub)
    }

    fun updateGroup(avatarUri: Uri?, groupName: String) {
        if (isUpdatingGroup.value == true) return
        val sub = Single.zip(
                    recipientManager.getGroupFromId(groupId),
                    generateAvatarFromUri(avatarUri),
                    { group, avatar -> Pair(group, avatar) }
                )
                .map { updateGroupObjects(it.first, it.second, groupName) }
                .flatMapCompletable { saveUpdatedGroup(it.first, it.second) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe { isUpdatingGroup.value = true }
                .doAfterTerminate { isUpdatingGroup.value = false }
                .subscribe(
                        { updatedGroup.value = true },
                        { error.value = R.string.unable_to_update_group }
                )
        this.subscriptions.add(sub)
    }

    private fun updateGroupObjects(group: Group, newAvatar: Bitmap?, groupName: String): Pair<Group, Group> {
        val groupToSave = updateGroupObjectToSave(group, newAvatar, groupName)
        val groupToSend = updateGroupObjectToSend(group, newAvatar, groupName)
        return Pair(groupToSave, groupToSend)
    }

    private fun updateGroupObjectToSave(group: Group, newAvatar: Bitmap?, groupName: String): Group {
        val groupToSave = Group().copy(group)
        newAvatar?.let { groupToSave.setAvatar(newAvatar) }
        groupToSave.title = groupName
        return groupToSave
    }

    private fun updateGroupObjectToSend(group: Group, newAvatar: Bitmap?, groupName: String): Group {
        val groupToSend = Group().copy(group)
        groupToSend.setAvatar(newAvatar)
        groupToSend.title = groupName
        return groupToSend
    }

    private fun saveUpdatedGroup(groupToSave: Group, groupToSend: Group) = sofaMessageManager.updateConversationFromGroup(groupToSave, groupToSend)

    private fun generateAvatarFromUri(avatarUri: Uri?) = ImageUtil.loadAsBitmap(avatarUri, BaseApplication.get())

    override fun onCleared() {
        super.onCleared()
        subscriptions.clear()
    }
}
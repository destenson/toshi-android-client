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

package com.toshi.model.local;


import android.graphics.Bitmap;
import android.support.annotation.NonNull;

import com.toshi.view.BaseApplication;

import org.spongycastle.util.encoders.Hex;
import org.whispersystems.signalservice.api.messages.SignalServiceGroup;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import io.realm.RealmList;
import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;
import rx.Single;

public class Group extends RealmObject {

    // This can be anything except 40/42, it needs to not collide with
    // the length of a public address as we distinguish groups from individuals
    // based on the length of IDs
    public static final int GROUP_ID_LENGTH = 32;

    @PrimaryKey
    private String id;
    private String title;
    private RealmList<User> members;
    private Avatar avatar;

    public Group(){}

    public Group(final List<User> members) {
        this.id = generateId();
        this.members = new RealmList<>();
        this.members.addAll(members);
    }

    public Group(final SignalServiceGroup signalServiceGroup) {
        this.id = Hex.toHexString(signalServiceGroup.getGroupId());
        this.title = signalServiceGroup.getName().orNull();
    }

    public Group copy(final Group group) {
        this.id = group.getId();
        this.title = group.getTitle();
        this.members = (RealmList<User>) group.getMembers();
        this.avatar = group.getAvatar();
        return this;
    }

    public Group addMember(final User member) {
        if (!this.members.contains(member)) this.members.add(member);
        return this;
    }

    public Group addMembers(final List<User> members) {
        for (User user : members) addMember(user);
        return this;
    }

    public Group removeMember(final User member) {
        this.members.remove(member);
        return this;
    }

    @NonNull
    public String getId() {
        return this.id;
    }

    @NonNull
    public byte[] getIdBytes() {
        return Hex.decode(this.id);
    }

    @NonNull
    public String getTitle() {
        return this.title == null ? "" : this.title;
    }

    public Group setTitle(final String title) {
        this.title = title;
        return this;
    }

    @NonNull
    public List<User> getMembers() {
        return this.members;
    }

    public Avatar getAvatar() {
        return this.avatar;
    }

    public Group setAvatar(final Bitmap avatar) {
        this.avatar = new Avatar(avatar);
        return this;
    }

    public Group setAvatar(final Avatar avatar) {
        this.avatar = avatar;
        return this;
    }

    private String generateId() {
        try {
            byte[] groupId = new byte[GROUP_ID_LENGTH / 2];
            SecureRandom.getInstance("SHA1PRNG").nextBytes(groupId);
            return Hex.toHexString(groupId);
        } catch (final NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    // Helper functions
    public List<String> getMemberIds() {
        if (this.members == null) {
            return Collections.emptyList();
        }
        final List<String> ids = new LinkedList<>();
        for (final User member : this.members) {
            ids.add(member.getToshiId());
        }
        return ids;
    }

    public List<SignalServiceAddress> getMemberAddresses() {
        if (this.members == null) {
            return Collections.emptyList();
        }
        final List<SignalServiceAddress> ids = new LinkedList<>();
        for (final User member : this.members) {
            ids.add(new SignalServiceAddress(member.getToshiId()));
        }
        return ids;
    }

    /* package */ boolean hasAvatar() {
        return this.avatar != null && this.avatar.getBytes() != null;
    }

    public static Single<Group> fromSignalGroup(final SignalServiceGroup signalGroup) {
        return fromId(Hex.toHexString(signalGroup.getGroupId()));
    }

    public static Single<Group> fromId(final String id) {
        return BaseApplication
                .get()
                .getRecipientManager()
                .getGroupFromId(id);
    }

    public static Group emptyGroup(final byte[] id) {
        final Group group = new Group();
        group.id = Hex.toHexString(id);
        return group;
    }

    public void cascadeDelete() {
        // Don't remove these users because they might be used somewhere else
        if (this.avatar != null) this.avatar.deleteFromRealm();
        deleteFromRealm();
    }
}

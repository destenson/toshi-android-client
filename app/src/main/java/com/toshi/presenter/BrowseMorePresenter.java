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

package com.toshi.presenter;

import android.content.Intent;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;

import com.toshi.R;
import com.toshi.manager.AppsManager;
import com.toshi.manager.RecipientManager;
import com.toshi.model.local.ToshiEntity;
import com.toshi.model.local.User;
import com.toshi.model.network.App;
import com.toshi.model.network.Dapp;
import com.toshi.util.BrowseType;
import com.toshi.util.LogUtil;
import com.toshi.view.BaseApplication;
import com.toshi.view.activity.BrowseMoreActivity;
import com.toshi.view.activity.ViewDappActivity;
import com.toshi.view.activity.ViewUserActivity;
import com.toshi.view.adapter.BrowseAdapter;
import com.toshi.view.custom.HorizontalLineDivider;

import java.util.List;

import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

import static com.toshi.util.BrowseType.VIEW_TYPE_FEATURED_DAPPS;
import static com.toshi.util.BrowseType.VIEW_TYPE_LATEST_PUBLIC_USERS;
import static com.toshi.util.BrowseType.VIEW_TYPE_TOP_RATED_APPS;
import static com.toshi.util.BrowseType.VIEW_TYPE_TOP_RATED_PUBLIC_USERS;

public class BrowseMorePresenter implements Presenter<BrowseMoreActivity> {

    private BrowseMoreActivity activity;
    private CompositeSubscription subscriptions;
    private List<? extends ToshiEntity> browseList;

    private boolean firstTimeAttaching = true;
    private int scrollPosition = 0;

    @Override
    public void onViewAttached(BrowseMoreActivity view) {
        this.activity = view;

        if (this.firstTimeAttaching) {
            this.firstTimeAttaching = false;
            initLongLivingObjects();
        }
        initShortLivingObjects();
    }

    private void initLongLivingObjects() {
        this.subscriptions = new CompositeSubscription();
    }

    private void initShortLivingObjects() {
        initToolbar();
        initClickListeners();
        initRecyclerView();
        fetchData();
    }

    private void initToolbar() {
        this.activity.getBinding().title.setText(getTitle());
    }

    private String getTitle() {
        switch (getViewType()) {
            case VIEW_TYPE_TOP_RATED_APPS: {
                return this.activity.getString(R.string.top_rated);
            }
            case VIEW_TYPE_FEATURED_DAPPS: {
                return this.activity.getString(R.string.featured);
            }
            case VIEW_TYPE_TOP_RATED_PUBLIC_USERS: {
                return this.activity.getString(R.string.top_rated_public_users);
            }
            case VIEW_TYPE_LATEST_PUBLIC_USERS:
            default: {
                return this.activity.getString(R.string.latest_public_users);
            }
        }
    }

    private void initClickListeners() {
        this.activity.getBinding().closeButton.setOnClickListener(__ -> this.activity.finish());
    }

    private void initRecyclerView() {
        final RecyclerView recyclerView = this.activity.getBinding().browseList;
        recyclerView.setLayoutManager(new LinearLayoutManager(this.activity));
        recyclerView.setItemAnimator(new DefaultItemAnimator());

        final int dividerLeftPadding = this.activity.getResources().getDimensionPixelSize(R.dimen.avatar_size_small)
                + this.activity.getResources().getDimensionPixelSize(R.dimen.activity_horizontal_margin)
                + this.activity.getResources().getDimensionPixelSize(R.dimen.list_item_avatar_margin);
        final int dividerRightPadding = this.activity.getResources().getDimensionPixelSize(R.dimen.activity_horizontal_margin);
        final HorizontalLineDivider lineDivider =
                new HorizontalLineDivider(ContextCompat.getColor(this.activity, R.color.divider))
                        .setLeftPadding(dividerLeftPadding)
                        .setRightPadding(dividerRightPadding);
        recyclerView.addItemDecoration(lineDivider);

        final boolean isPublicUserViewType = getViewType() == VIEW_TYPE_LATEST_PUBLIC_USERS
                || getViewType() == VIEW_TYPE_TOP_RATED_PUBLIC_USERS;

        final BrowseAdapter adapter = isPublicUserViewType
                ? new BrowseAdapter<User>(5)
                : new BrowseAdapter<App>(6);
        if (getViewType() == VIEW_TYPE_FEATURED_DAPPS) {
            adapter.setOnItemClickListener(this::handleDappItemClicked);
        } else {
            adapter.setOnItemClickListener(this::handleItemClicked);
        }
        recyclerView.setAdapter(adapter);
    }

    private void handleItemClicked(final Object elem) {
        if (elem == null) return;
        final Intent intent = new Intent(this.activity, ViewUserActivity.class)
                .putExtra(ViewUserActivity.EXTRA__USER_ADDRESS, ((ToshiEntity) elem).getToshiId());
        this.activity.startActivity(intent);
    }

    private void handleDappItemClicked(final Object elem) {
        if (elem == null) return;
        final Dapp dapp = ((Dapp) elem);
        final Intent intent = new Intent(this.activity, ViewDappActivity.class)
                .putExtra(ViewDappActivity.EXTRA__DAPP_ADDRESS, dapp.getAddress())
                .putExtra(ViewDappActivity.EXTRA__DAPP_AVATAR, dapp.getAvatar())
                .putExtra(ViewDappActivity.EXTRA__DAPP_ABOUT, dapp.getAbout())
                .putExtra(ViewDappActivity.EXTRA__DAPP_NAME, dapp.getName());
        this.activity.startActivity(intent);
    }

    private void fetchData() {
        if (getViewType() == VIEW_TYPE_TOP_RATED_APPS) {
            fetchTopRatedApps();
        } else if (getViewType() == VIEW_TYPE_FEATURED_DAPPS) {
            fetchFeaturedDapps();
        } else if (getViewType() == VIEW_TYPE_TOP_RATED_PUBLIC_USERS) {
            fetchTopRatedPublicUsers();
        } else {
            fetchLatestPublicUsers();
        }
    }

    @SuppressWarnings("WrongConstant")
    private @BrowseType.Type int getViewType() {
        return this.activity.getIntent().getIntExtra(BrowseMoreActivity.VIEW_TYPE, VIEW_TYPE_LATEST_PUBLIC_USERS);
    }

    private void fetchTopRatedApps() {
        if (this.browseList != null && this.browseList.size() > 0) {
            handleApps(this.browseList);
            scrollToRetainedPosition();
            return;
        }

        final Subscription sub =
                getAppManager()
                .getTopRatedApps(100)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleApps,
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private void fetchFeaturedDapps() {
        if (this.browseList != null && this.browseList.size() > 0) {
            handleApps(this.browseList);
            scrollToRetainedPosition();
            return;
        }

        final Subscription sub =
                getAppManager()
                .getFeaturedDapps(100)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleApps,
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private AppsManager getAppManager() {
        return BaseApplication
                .get()
                .getToshiManager()
                .getAppsManager();
    }

    private void handleApps(final List<? extends ToshiEntity> apps) {
        getAdapter().setItems(apps);
        this.browseList = apps;
    }

    private void fetchTopRatedPublicUsers() {
        if (this.browseList != null && this.browseList.size() > 0) {
            handleUsers(this.browseList);
            scrollToRetainedPosition();
            return;
        }

        final Subscription sub =
                getRecipinentManager()
                .getTopRatedPublicUsers(100)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleUsers,
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private void fetchLatestPublicUsers() {
        if (this.browseList != null && this.browseList.size() > 0) {
            handleUsers(this.browseList);
            scrollToRetainedPosition();
            return;
        }

        final Subscription sub =
                getRecipinentManager()
                .getLatestPublicUsers(100)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        this::handleUsers,
                        this::handleError
                );

        this.subscriptions.add(sub);
    }

    private RecipientManager getRecipinentManager() {
        return BaseApplication
                .get()
                .getToshiManager()
                .getRecipientManager();
    }

    private void handleUsers(final List<? extends ToshiEntity> users) {
        getAdapter().setItems(users);
        this.browseList = users;
    }

    private BrowseAdapter getAdapter() {
        return (BrowseAdapter) this.activity.getBinding().browseList.getAdapter();
    }

    private void scrollToRetainedPosition() {
        this.activity.getBinding().browseList.getLayoutManager().scrollToPosition(this.scrollPosition);
    }

    private void handleError(final Throwable throwable) {
        LogUtil.exception(getClass(), "Error fetching app/public users", throwable);
    }

    @Override
    public void onViewDetached() {
        setScrollState();
        this.subscriptions.clear();
        this.activity = null;
    }

    private void setScrollState() {
        if (this.activity == null) return;
        final LinearLayoutManager layoutManager = (LinearLayoutManager) this.activity.getBinding().browseList.getLayoutManager();
        this.scrollPosition = layoutManager.findFirstCompletelyVisibleItemPosition();
    }

    @Override
    public void onDestroyed() {
        this.subscriptions = null;
        this.activity = null;
    }
}

package com.bakkenbaeck.token.network;

import com.bakkenbaeck.token.model.network.Apps;

import retrofit2.Response;
import retrofit2.http.GET;
import retrofit2.http.Query;
import rx.Observable;

public interface DirectoryInterface {

    @GET("v1/apps/")
    Observable<Response<Apps>> getApps();

    @GET("v1/apps/featured")
    Observable<Response<Apps>> getFeaturedApps();

    @GET("v1/search/apps/")
    Observable<Response<Apps>> searchApps(@Query("query") String searchString);
}
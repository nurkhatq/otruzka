package com.otgruzka.tsd.api

import retrofit2.http.*

interface WmsApi {

    @FormUrlEncoded
    @POST("auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String
    ): LoginResponse

    @GET("sessions/active")
    suspend fun getActiveSession(): WmsSession?

    @POST("sessions/")
    suspend fun createSession(@Body body: CreateSessionBody): WmsSession

    @PATCH("sessions/{batchId}")
    suspend fun updateSession(
        @Path("batchId") batchId: String,
        @Query("status") status: String
    ): WmsSession

    @POST("scan/lock")
    suspend fun scanLock(@Body request: ScanRequest): ScanResult

    @DELETE("scan/lock/{code}")
    suspend fun releaseLock(@Path("code") code: String): Map<String, Boolean>

    @GET("orders/")
    suspend fun getOrders(
        @Query("state") state: String? = null,
        @Query("status") status: String? = null,
        @Query("assembled") assembled: Boolean? = null,
        @Query("page") page: Int = 0,
        @Query("page_size") pageSize: Int = 100
    ): List<KaspiOrder>

    @GET("sessions/")
    suspend fun getSessions(
        @Query("page") page: Int = 0,
        @Query("page_size") pageSize: Int = 20
    ): List<WmsSession>
}

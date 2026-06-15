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
    suspend fun scanLock(@Body body: ScanLockRequest): ScanLockResponse

    @DELETE("scan/lock/{orderCode}")
    suspend fun releaseLock(@Path("orderCode") code: String): Map<String, Boolean>

    @POST("scan/create-demands")
    suspend fun createDemands(@Body body: CreateDemandsRequest): DemandJobResponse

    @GET("scan/demand-job/{jobId}")
    suspend fun getDemandJob(@Path("jobId") jobId: String): DemandJobResponse

    @POST("scan/cache-refresh")
    suspend fun refreshCache(): Map<String, Int>

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
        @Query("page_size") pageSize: Int = 20,
        @Query("warehouse_id") warehouseId: Int? = null,
        @Query("user_id") userId: Int? = null,
        @Query("search") search: String? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null,
    ): SessionListResponse

    @GET("users/list")
    suspend fun getUsersList(): List<UserItem>

    @GET("sessions/{batchId}/stats")
    suspend fun getSessionStats(@Path("batchId") batchId: String): SessionStats

    @GET("sessions/{batchId}/scans")
    suspend fun getSessionScans(
        @Path("batchId") batchId: String,
        @Query("page") page: Int = 0,
        @Query("page_size") pageSize: Int = 50,
        @Query("scan_result") scanResult: String? = null
    ): SessionScansResponse
}

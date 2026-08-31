package com.otgruzka.tsd.api

import retrofit2.http.*

/** API ядра novamanya (qoimams.asia) — раздел «Сборка». */
interface CoreApi {

    @FormUrlEncoded
    @POST("auth/login")
    suspend fun login(
        @Field("username") username: String,
        @Field("password") password: String
    ): CoreLoginResponse

    @GET("auth/me")
    suspend fun me(): CoreUser

    @GET("stores")
    suspend fun stores(): List<CoreStore>

    // ─── Смена ───────────────────────────────────────────────────────────────

    @GET("picker/sessions/me")
    suspend fun sessionMe(
        @Query("store_id") storeId: Int,
        @Query("city") city: String
    ): PickerSessionMe

    @POST("picker/sessions/start")
    suspend fun startSession(
        @Query("store_id") storeId: Int,
        @Query("city") city: String
    ): PickerStartResponse

    @POST("picker/sessions/end")
    suspend fun endSession(): PickerEndResponse

    // ─── Задания ─────────────────────────────────────────────────────────────

    @GET("picker/tasks/{id}")
    suspend fun getTask(@Path("id") id: Int): PickerTask

    @POST("picker/tasks/{id}/claim")
    suspend fun claimTask(@Path("id") id: Int): PickerTask

    @POST("picker/tasks/{id}/scan")
    suspend fun scan(@Path("id") id: Int, @Body body: PickerScanBody): PickerTask

    @POST("picker/tasks/{id}/bulk-scan")
    suspend fun bulkScan(@Path("id") id: Int, @Body body: PickerBulkScanBody): PickerTask

    @POST("picker/tasks/{id}/complete")
    suspend fun completeTask(@Path("id") id: Int): PickerCompleteResponse

    @POST("picker/tasks/{id}/release")
    suspend fun releaseTask(@Path("id") id: Int): Map<String, Boolean>

    // ─── История ─────────────────────────────────────────────────────────────

    @GET("picker/history")
    suspend fun history(
        @Query("limit") limit: Int = 50,
        @Query("session_id") sessionId: Int? = null
    ): PickerHistoryResponse

    @POST("picker/tasks/{id}/reprint")
    suspend fun reprint(@Path("id") id: Int): PickerReprintResponse

    // ─── Справочное ──────────────────────────────────────────────────────────

    /** Ячейки, где лежит товар: sku → [{code}]. */
    @GET("cells/for-skus")
    suspend fun cellsForSkus(@Query("skus") skus: String): Map<String, List<CoreCell>>
}

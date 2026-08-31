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

    // ─── ТСД-отгрузка (/tsd) ─────────────────────────────────────────────────

    @POST("tsd/shifts")
    suspend fun createShift(): TsdShift

    @GET("tsd/shifts/active")
    suspend fun getActiveShift(): TsdShift?

    @PATCH("tsd/shifts/{id}")
    suspend fun updateShift(
        @Path("id") shiftId: String,
        @Query("status") status: String
    ): TsdShift

    @GET("tsd/shifts")
    suspend fun getShifts(
        @Query("page") page: Int = 0,
        @Query("page_size") pageSize: Int = 20,
        @Query("city") city: String? = null,
        @Query("username") username: String? = null,
        @Query("search") search: String? = null,
        @Query("date_from") dateFrom: String? = null,
        @Query("date_to") dateTo: String? = null,
    ): TsdShiftsResponse

    @GET("tsd/shifts/dates")
    suspend fun getShiftDates(@Query("city") city: String? = null): List<TsdShiftDate>

    @GET("tsd/shifts/{id}/stats")
    suspend fun getShiftStats(@Path("id") shiftId: String): TsdShiftStats

    @GET("tsd/shifts/{id}/scans")
    suspend fun getShiftScans(
        @Path("id") shiftId: String,
        @Query("page") page: Int = 0,
        @Query("page_size") pageSize: Int = 50,
        @Query("scan_result") scanResult: String? = null,
        @Query("search") search: String? = null,
    ): TsdScansResponse

    @POST("tsd/scan")
    suspend fun tsdScan(@Body body: TsdScanBody): TsdScanResponse

    @DELETE("tsd/scan/{orderCode}")
    suspend fun tsdReleaseScan(@Path("orderCode") code: String): Map<String, Boolean>

    @POST("tsd/ship")
    suspend fun tsdShip(@Body body: TsdShipBody): TsdShipResponse

    @GET("tsd/pickup-orders")
    suspend fun tsdPickupOrders(): List<TsdPickupOrder>

    @GET("tsd/users")
    suspend fun tsdUsers(): List<TsdUser>

    // ─── Приёмка отмен/возвратов (/tsd/returns) ──────────────────────────────

    @POST("tsd/returns/scan-order")
    suspend fun returnsScanOrder(@Body body: ReturnScanOrderBody): ReturnScanOrderResponse

    @POST("tsd/returns/{id}/scan-item")
    suspend fun returnsScanItem(
        @Path("id") id: Int, @Body body: ReturnItemScanBody
    ): ReturnItemScanResponse

    @POST("tsd/returns/{id}/complete")
    suspend fun returnsComplete(
        @Path("id") id: Int, @Body body: ReturnCompleteBody
    ): ReturnCompleteResponse

    @POST("tsd/returns/{id}/cancel")
    suspend fun returnsCancel(@Path("id") id: Int): Map<String, String>

    @GET("tsd/returns")
    suspend fun returnsList(
        @Query("page") page: Int = 0,
        @Query("page_size") pageSize: Int = 30,
        @Query("status") status: String? = null,
        @Query("username") username: String? = null,
    ): ReturnsListResponse

    @GET("tsd/returns/expected")
    suspend fun returnsExpected(): List<ReturnExpectedItem>

    @GET("tsd/returns/{id}")
    suspend fun returnsGet(@Path("id") id: Int): ReturnReceiving
}

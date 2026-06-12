package com.otgruzka.tsd.api

import com.google.gson.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Query
import retrofit2.http.Url

interface MoySkladApi {

    @GET("entity/customerorder")
    suspend fun searchOrders(
        @Query("filter") filter: String
    ): ListResponse<CustomerOrder>

    @GET("entity/customerorder")
    suspend fun getOrders(
        @Query("filter") filter: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int
    ): ListResponse<CustomerOrder>

    // Загружаем агента по его href (динамический URL)
    @GET
    suspend fun getAgent(@Url url: String): AgentDetail

    // Загружаем позиции по href с expand=assortment (работает на sub-endpoint)
    @GET
    suspend fun getPositions(
        @Url url: String,
        @Query(value = "expand", encoded = true) expand: String
    ): ListResponse<OrderPosition>

    @PUT("entity/demand/new")
    suspend fun getDemandTemplate(@Body body: JsonObject): JsonObject

    @POST("entity/demand")
    suspend fun createDemand(@Body body: JsonObject): JsonObject

    @GET("entity/demand")
    suspend fun getDemands(
        @Query("filter") filter: String,
        @Query("limit") limit: Int
    ): ListResponse<Demand>
}

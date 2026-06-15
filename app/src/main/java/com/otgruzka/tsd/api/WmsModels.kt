package com.otgruzka.tsd.api

data class LoginResponse(
    val access_token: String,
    val token_type: String,
    val user: WmsUser
)

data class WmsUser(
    val id: Int,
    val username: String,
    val full_name: String,
    val warehouse_id: Int,
    val role: String
)

data class WmsSession(
    val batch_id: String,
    val status: String,
    val order_count: Int,
    val started_at: String
)

data class ScanRequest(
    val order_code: String,
    val session_batch_id: String
)

data class ScanResult(
    val result: String,      // SUCCESS | NOT_FOUND | ALREADY_LOCKED | CANCELLING
    val order_code: String,
    val lock_acquired: Boolean,
    val lock_holder: String?,
    val message: String?,
    val order: ScannedOrderInfo?
)

data class ScannedOrderInfo(
    val kaspi_status: String,
    val customer_name: String?,
    val total_price: Double,
    val assembled: Boolean,
    val express: Boolean,
    val waybill_number: String?
)

data class KaspiOrder(
    val order_code: String,
    val kaspi_status: String,
    val kaspi_state: String?,
    val customer_name: String?,
    val customer_phone: String?,
    val total_price: Double,
    val assembled: Boolean,
    val express: Boolean,
    val is_cancelling: Boolean,
    val delivery_mode: String?,
    val waybill_number: String?,
    val creation_date: String?
)

data class CreateSessionBody(val notes: String? = null)

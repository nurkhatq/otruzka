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
    val shipped_count: Int = 0,
    val scan_count: Int = 0,
    val started_at: String,
    val completed_at: String? = null,
    val warehouse_id: Int = 0,
    val user_name: String? = null
)

data class SessionListResponse(
    val total: Int,
    val page: Int,
    val page_size: Int,
    val items: List<WmsSession>
)

data class SessionStats(
    val batch_id: String,
    val status: String,
    val warehouse_id: Int,
    val user_name: String?,
    val started_at: String,
    val completed_at: String?,
    val duration_sec: Int?,
    val total_scanned: Int,
    val by_result: Map<String, Int>,
    val by_demand: Map<String, Int>
)

data class SessionScansResponse(
    val total: Int,
    val page: Int,
    val page_size: Int,
    val items: List<SessionScan>
)

data class ScanLockRequest(
    val order_code: String,
    val session_batch_id: String
)

data class ScanOrderInfo(
    val customer_name: String?,
    val total_price: Double,
    val assembled: Boolean,
    val express: Boolean,
    val source: String           // "moysklad" | "kaspi"
)

data class ScanLockResponse(
    val result: String,          // SUCCESS | ALREADY_SHIPPED | CANCELLING | NOT_FOUND | ALREADY_LOCKED
    val order_code: String,
    val lock_acquired: Boolean,
    val lock_holder: String? = null,
    val order: ScanOrderInfo? = null,
    val message: String? = null
)

data class CreateDemandsRequest(
    val codes: List<String>,
    val session_batch_id: String? = null,
)

data class DemandResult(
    val code: String,
    val status: String,          // CREATED | ALREADY_SHIPPED | NOT_IN_MS | ERROR
    val demand_name: String? = null,
    val demand_id: String? = null,
    val detail: String? = null,
)

data class CreateDemandsResponse(val results: List<DemandResult>)

data class DemandJobResponse(
    val job_id: String?,
    val status: String,   // PROCESSING | DONE | ERROR | NOT_FOUND
    val done: Int,
    val total: Int,
    val results: List<DemandResult>? = null
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

data class UserItem(val id: Int, val full_name: String, val warehouse_id: Int)

data class SessionDateItem(
    val date: String,
    val session_count: Int,
    val total_orders: Int,
    val shipped_count: Int = 0,
)

data class CreateSessionBody(val notes: String? = null)

// ─── Inventory models ─────────────────────────────────────────────────────────

data class InvSession(
    val session_id: String,
    val warehouse_id: Int,
    val status: String,
    val notes: String? = null,
    val started_at: String,
    val completed_at: String? = null,
    val created_by_name: String? = null,
    val total: Int? = null
)

data class InvSessionStats(
    val session_id: String,
    val status: String,
    val warehouse_id: Int,
    val created_by_name: String?,
    val started_at: String,
    val completed_at: String?,
    val duration_sec: Int?,
    val total: Int,
    val by_status: Map<String, Int>
)

data class InvScanItem(
    val id: Int,
    val barcode: String,
    val product_name: String?,
    val ms_stock: Double?,
    val counter_name: String?,
    val counter_qty: Int,
    val counter_at: String?,
    val verifier_name: String?,
    val verifier_qty: Int?,
    val verifier_at: String?,
    val status: String,
    val notes: String?
)

data class InvItemsResponse(
    val total: Int,
    val page: Int,
    val page_size: Int,
    val items: List<InvScanItem>
)

data class InvSessionsResponse(
    val total: Int,
    val page: Int,
    val page_size: Int,
    val items: List<InvSession>
)

data class InvProductInfo(
    val barcode: String,
    val product_name: String?,
    val ms_stock: Double?
)

data class InvScanBody(
    val session_id: String,
    val barcode: String,
    val quantity: Int,
    val notes: String? = null
)

data class InvScanResponse(
    val id: Int,
    val barcode: String,
    val product_name: String?,
    val ms_stock: Double?,
    val counter_qty: Int,
    val verifier_qty: Int?,
    val status: String,   // PENDING | MATCHED | VERIFIED | DISCREPANCY
    val role: String,     // counter | recount | verifier
    val message: String
)

data class SessionScan(
    val order_code: String,
    val customer_name: String?,
    val total_price: Double,
    val scan_result: String,      // SUCCESS | ALREADY_LOCKED | ALREADY_SHIPPED | CANCELLING | NOT_FOUND | KASPI_ONLY
    val demand_status: String?,   // CREATED | NOT_IN_MS | ERROR | null
    val demand_name: String?,
    val lock_holder: String?,
    val scanned_at: String
)

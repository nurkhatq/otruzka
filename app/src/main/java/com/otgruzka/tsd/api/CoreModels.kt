package com.otgruzka.tsd.api

// ─── Модели ядра novamanya (qoimams.asia) — раздел «Сборка» ──────────────────

data class CoreLoginResponse(
    val access_token: String,
    val token_type: String?
)

data class CoreUser(
    val id: Int,
    val username: String,
    val full_name: String?,
    val role: String,
    val warehouse_id: Int?,
    val city: String?
)

data class CoreStore(
    val id: Int,
    val code: String?,
    val name: String?
)

data class PickerScanInfo(
    val barcode_scanned: String?,
    val match_status: String?,
    val qty_done: Int?,
    val scanned_at: String?
)

data class KitComponent(
    val sku: String?,
    val name: String?,
    val qty: Int?
)

data class PickerOrderItem(
    val order_code: String,
    val position_index: Int?,
    val offer_code: String?,
    val name: String?,
    val quantity: Int?,
    val expected_barcode: String?,
    val is_kit: Boolean?,
    val components: List<KitComponent>?,
    val images: List<String>?,
    val num_positions: Int?,
    val scan: PickerScanInfo?
)

data class PickerTask(
    val id: Int,
    val store_id: Int?,
    val city: String?,
    val task_type: String?,
    val offer_code: String?,
    val product_name: String?,
    val expected_barcode: String?,
    val orders: List<PickerOrderItem>?,
    val total_orders: Int,
    val total_qty: Int?,
    val scanned_qty: Int,
    val picker_username: String?,
    val status: String?,
    val created_at: String?,
    val claimed_at: String?,
    val completed_at: String?,
    val waybill_job_id: Long?,
    val pdf_filename: String?   // приходит только из /picker/history
)

data class PickerSessionInfo(
    val id: Int,
    val started_at: String?
)

data class PickerSessionMe(
    val in_session: Boolean,
    val session: PickerSessionInfo?,
    val tasks: List<PickerTask>?,
    val active_sessions_count: Int?
)

data class PickerStartResponse(
    val session_id: Int?,
    val assigned: Int?,
    val already_active: Boolean?
)

data class PickerEndResponse(
    val ended: Boolean?,
    val released_tasks: Int?
)

data class PickerScanBody(
    val order_code: String,
    val position_index: Int = 0,
    val barcode: String? = null,
    val match_status: String = "matched"
)

data class PickerBulkScanBody(
    val barcode: String?,
    val quantity: Int
)

data class PickerCompleteResponse(
    val task_id: Int?,
    val status: String?,
    val total_orders: Int?,
    val scanned: Int?,
    val no_barcode: Int?,
    val cancelled_orders: Int?,
    val pdf_filenames: List<String>?
)

data class PickerHistoryResponse(
    val tasks: List<PickerTask>?
)

data class PickerReprintResponse(
    val queued: Boolean?,
    val filename: String?
)

data class CoreCell(
    val code: String?
)

// ─── ТСД-отгрузка (/tsd) — форма совместима со старым wms-backend ────────────

data class TsdShift(
    val id: Long,
    val batch_id: String,
    val status: String,
    val order_count: Int,
    val scan_count: Int = 0,
    val shipped_count: Int = 0,
    val started_at: String?,
    val completed_at: String? = null,
    val city: String? = null,
    val user_name: String? = null
)

data class TsdShiftsResponse(
    val total: Int,
    val items: List<TsdShift>
)

data class TsdShiftDate(
    val date: String,
    val session_count: Int,
    val total_orders: Int,
    val shipped_count: Int = 0
)

data class TsdShiftStats(
    val batch_id: String,
    val status: String,
    val city: String?,
    val user_name: String?,
    val started_at: String?,
    val completed_at: String?,
    val duration_sec: Int?,
    val total_scanned: Int,
    val by_result: Map<String, Int>,
    val by_demand: Map<String, Int>
)

data class TsdScanItem(
    val order_code: String,
    val customer_name: String?,
    val total_price: Double,
    val scan_result: String,
    val demand_status: String?,
    val demand_name: String?,
    val lock_holder: String?,
    val scanned_at: String
)

data class TsdScansResponse(
    val total: Int,
    val items: List<TsdScanItem>
)

data class TsdScanBody(val code: String)

data class TsdOrderInfo(
    val order_code: String?,
    val customer_name: String?,
    val total_price: Double,
    val assembled: Boolean,
    val express: Boolean,
    val is_cancelling: Boolean,
    val source: String?
)

data class TsdScanResponse(
    val result: String,   // SUCCESS | ALREADY_SHIPPED | ALREADY_LOCKED | CANCELLING | NOT_FOUND
    val order_code: String?,
    val lock_acquired: Boolean,
    val order: TsdOrderInfo?,
    val lock_holder: String?,
    val duplicate: Boolean = false
)

data class TsdShipBody(val codes: List<String>)

data class TsdShipResult(
    val code: String,
    val status: String    // SHIPPED | SKIPPED
)

data class TsdShipResponse(
    val shipped: Int,
    val results: List<TsdShipResult>
)

data class TsdPickupOrder(
    val order_code: String,
    val customer_name: String?,
    val total_price: Double,
    val assembled: Boolean,
    val express: Boolean,
    val is_cancelling: Boolean
)

data class TsdUser(
    val id: Int,
    val username: String,
    val full_name: String,
    val city: String?
)

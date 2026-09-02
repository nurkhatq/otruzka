package com.otgruzka.tsd.api

/**
 * Инвентаризация с ТСД (ядро, /inventory).
 *
 * Количества приходят СТРОКАМИ («2», «1.5») — тот же контракт, что у возвратов:
 * сервер отдаёт Numeric без лишних нулей.
 *
 * В слепом режиме `qty_expected` и `delta` приходят null — учётный остаток
 * прячет СЕРВЕР, а не экран, поэтому подогнать факт под учёт нельзя в принципе.
 */

data class InvCount(
    val id: Int,
    val number: String,
    val warehouse_id: Int,
    val zone: String?,
    val status: String,
    val blind: Boolean,
    val comment: String?,
    val progress: InvProgress?,
)

data class InvProgress(
    val cells_total: Int,
    val pending: Int,
    val in_progress: Int,
    val counted: Int,
    val posted: Int,
)

data class InvCell(
    val id: Int,
    val code: String,
    val barcode: String?,
    val zone: String?,
    val warehouse_id: Int,
)

data class InvTotals(val positions: Int, val units: String?)

data class InvLine(
    val line_id: Int,
    val product_id: Long,
    val name: String?,
    val sku: String?,
    val qty_counted: String?,
    val qty_expected: String?,   // null в слепом режиме
    val delta: String?,          // null в слепом режиме
    val counted: Boolean,
    val is_foreign: Boolean,
    val images: List<String>?,
    val barcodes: List<String>?,
)

/** Полное состояние ячейки: экран всегда пересобирается из ответа сервера. */
data class InvSession(
    val count_cell_id: Int,
    val count_id: Int,
    val number: String?,
    val blind: Boolean,
    val status: String,
    val mode: String?,
    val username: String?,
    val cell: InvCell?,
    val totals: InvTotals?,
    val lines: List<InvLine>?,
)

data class InvClaimBody(
    val barcode: String? = null,
    val cell_id: Int? = null,
    val count_id: Int? = null,
    val force: Boolean = false,
    val device_id: String? = null,
)

/** result: OK | UNKNOWN_CELL | NO_COUNT | AMBIGUOUS_COUNT | NOT_IN_SCOPE |
 *          WRONG_WAREHOUSE | LOCKED | STALE | ALREADY_COUNTED | IN_OTHER_COUNT */
data class InvClaimResponse(
    val result: String,
    val resumed: Boolean = false,
    val session: InvSession? = null,
    val holder: String? = null,
    val holder_since: String? = null,
    val counted_lines: Int = 0,
    val counts: List<InvCount>? = null,
    val cell_zone: String? = null,
    val count_zone: String? = null,
)

data class InvScanBody(
    val code: String,
    val qty: String = "1",
    val product_id: Long? = null,
    /** UUID: тот же ref при ретрае не удваивает штуку — оффлайн-очереди нет. */
    val client_ref: String? = null,
)

data class InvSetQtyBody(
    val product_id: Long,
    val qty: String,
    val client_ref: String? = null,
)

data class InvCandidate(val product_id: Long, val name: String?, val main_sku: String?)

data class InvWarn(val code: String?)

/** result: OK | UNKNOWN | AMBIGUOUS | DUPLICATE | NOTHING */
data class InvScanResponse(
    val result: String,
    val product_id: Long? = null,
    val candidates: List<InvCandidate>? = null,
    val warn: InvWarn? = null,
    val session: InvSession? = null,
)

data class InvCompleteBody(val mode: String = "full", val note: String? = null)

data class InvCompleteResponse(
    val result: String,
    val mode: String?,
    val diff_count: Int = 0,
    val document_id: Int? = null,
)

data class InvReleaseBody(val reset: Boolean = false)

data class InvActive(val my_cell: InvSession?)

data class InvBoardRow(
    val count_cell_id: Int,
    val cell_id: Int,
    val cell_code: String?,
    val zone: String?,
    val status: String,
    val username: String?,
    val stale: Boolean = false,
    val lines_counted: Int = 0,
    val document_number: String?,
)

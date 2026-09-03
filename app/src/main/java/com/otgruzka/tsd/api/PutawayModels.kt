package com.otgruzka.tsd.api

/**
 * Раскладка товара по ячейкам (/putaway).
 *
 * Скан полки → сканы товара → «Готово»: накопленное уезжает одним документом
 * перемещения. Количества строками («2», «1.5»), как во всех ручках ядра.
 */

data class PwCell(
    val id: Int,
    val code: String,
    val barcode: String?,
    val zone: String?,
    val warehouse_id: Int,
)

data class PwRow(
    val product_id: Long,
    val name: String?,
    val sku: String? = null,
    val qty: String?,
)

data class PwState(
    val cell: PwCell?,
    /** Кто прямо сейчас считает эту ячейку — просто предупреждение. */
    val counting_by: String?,
    val on_shelf: List<PwRow>?,
    val pending: List<PwRow>?,
)

data class PwOpenBody(val barcode: String? = null, val cell_id: Int? = null)

/** result: OK | UNKNOWN_CELL */
data class PwOpenResponse(val result: String, val state: PwState?)

data class PwScanBody(
    val code: String,
    val qty: String = "1",
    val product_id: Long? = null,
    val client_ref: String? = null,
)

/** result: OK | UNKNOWN | AMBIGUOUS | DUPLICATE | NO_COST | NOTHING */
data class PwScanResponse(
    val result: String,
    val product_id: Long? = null,
    val candidates: List<InvCandidate>? = null,
    val available: String? = null,
    /** Сколько из этого скана легло СВЕРХ учёта — уйдёт оприходованием. */
    val surplus: String? = null,
    val state: PwState?,
)

data class PwCommitResponse(
    val document_id: Int?,
    val document_number: String?,
    val moved_positions: Int = 0,
    val moved_qty: String?,
    /** Излишек уходит отдельным документом оприходования. */
    val surplus_document_number: String? = null,
    val surplus_qty: String? = null,
)

package com.otgruzka.tsd.api

/**
 * Раскладка товара по ячейкам (/putaway).
 *
 * Скан полки → сканы товара → «Готово»: накопленное уезжает одним документом
 * перемещения. Количества строками («2», «1.5»), как во всех ручках ядра.
 *
 * Режим «откуда → куда»: перед полкой сканируется ячейка-источник, и товар
 * берётся ТОЛЬКО из неё (перекладка между ячейками хранения и между полками
 * МХ). Без источника ядро подбирает его само, как раньше.
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
    /** Из какой ячейки взято (только для строк «положено в эту сессию»). */
    val source_cell_code: String? = null,
)

data class PwState(
    val cell: PwCell?,
    /** Откуда перекладываем; null = источник подбирает ядро. */
    val source: PwCell? = null,
    /** Кто прямо сейчас считает эту ячейку — просто предупреждение. */
    val counting_by: String?,
    val on_shelf: List<PwRow>?,
    val pending: List<PwRow>?,
)

data class PwOpenBody(
    val barcode: String? = null,
    val cell_id: Int? = null,
    val source_barcode: String? = null,
    val source_cell_id: Int? = null,
)

/**
 * result: OK | UNKNOWN_CELL
 * | UNKNOWN_SOURCE | SOURCE_IS_TARGET | SOURCE_OTHER_WAREHOUSE | SOURCE_BAD_ZONE
 */
data class PwOpenResponse(val result: String, val state: PwState?)

data class PwScanBody(
    val code: String,
    val qty: String = "1",
    val product_id: Long? = null,
    val client_ref: String? = null,
    /** Ячейка «откуда» — та же, что подтвердил /open. */
    val source_cell_id: Int? = null,
)

/**
 * result: OK | UNKNOWN | AMBIGUOUS | DUPLICATE | NO_COST | NOTHING
 * | IN_PICKING — товар числится на полках МХ, а кладём в хранение: обратного
 *   хода нет, скан не записан (в `available` — сколько лежит на полках)
 * | UNKNOWN_SOURCE | SOURCE_IS_TARGET | SOURCE_OTHER_WAREHOUSE | SOURCE_BAD_ZONE
 */
data class PwScanResponse(
    val result: String,
    val product_id: Long? = null,
    val candidates: List<InvCandidate>? = null,
    val available: String? = null,
    /** Сколько из этого скана легло СВЕРХ учёта — уйдёт оприходованием. */
    val surplus: String? = null,
    /** Сколько осталось в ячейке-источнике по учёту (если она указана). */
    val in_source: String? = null,
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

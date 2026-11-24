package com.otgruzka.tsd.api

/**
 * Приёмка с ТСД без заказа поставщику (/intakes, ядро, 2026-11-24).
 *
 * Товар приехал, а ЗП не завели (или приехало новое, чего в каталоге нет).
 * Склад сканирует, вписывает количество и нажимает «Завершить» — приёмка
 * `ПТ-000001` ждёт подтверждения на сайте в «Заказах поставщику»: там дописывают
 * карточки новым товарам и поставщика, ядро заводит заказ и проводит приёмку.
 */

data class IntakeWarehouse(val id: Int, val code: String, val city: String)

data class IntakeProduct(
    val id: Int,
    val name: String,
    val main_sku: String?,
    val brand: String?,
    val photo: String?,
    val barcodes: List<String>?,
    val has_weight: Boolean,
)

data class IntakeLine(
    val id: Long,
    /** null — штрихкода в каталоге нет: «новый товар», карточку заведут на сайте. */
    val product_id: Int?,
    val barcode: String?,
    val qty: Int,
    val name_hint: String?,
    val updated_by: String?,
    val product: IntakeProduct?,
)

data class Intake(
    val id: Long,
    val number: String,
    /** open | submitted | confirmed | cancelled */
    val status: String,
    val warehouse_id: Int,
    val warehouse_name: String?,
    val comment: String?,
    /** Поставщик, которого назвали на ТСД при старте приёмки. */
    val supplier_name: String?,
    val counterparty_id: Int?,
    val created_by: String,
    val created_at: String?,
    val submitted_by: String?,
    val lines_count: Int,
    val qty_total: Int,
    val unknown_count: Int,
    val lines: List<IntakeLine>,
)

data class IntakeBoard(val items: List<Intake>, val total: Int, val counts: Map<String, Int>?)

data class IntakeCreateBody(
    val warehouse_id: Int,
    val comment: String? = null,
    val supplier_name: String? = null,
    val counterparty_id: Int? = null,
)

/** Контрагент (поставщик) из справочника ядра — для подсказки при старте приёмки. */
data class IntakeSupplier(val id: Int, val name: String)

data class IntakeScanBody(
    /** Скан или код, набранный руками; null — товар без штрихкода, тогда нужен `name`. */
    val code: String?,
    /** Выбор из нескольких карточек на один штрихкод (после AMBIGUOUS). */
    val product_id: Int? = null,
    val qty: Int,
    /** UUID скана: повтор после обрыва связи ничего не прибавит. */
    val client_ref: String,
    /** Название: у товара без штрихкода обязательно, у неизвестного кода — подсказка сайту. */
    val name: String? = null,
)

/** result: OK | NEW | AMBIGUOUS | KIT | DUPLICATE */
data class IntakeScanResponse(
    val result: String,
    val message: String?,
    val candidates: List<InvCandidate>?,
    val line: IntakeLine?,
    val intake: Intake,
)

/** `name_hint` — название нового товара (у строки без штрихкода не стереть). */
data class IntakeLineQtyBody(val qty: Int, val name_hint: String? = null)

data class IntakeReasonBody(val reason: String? = null)

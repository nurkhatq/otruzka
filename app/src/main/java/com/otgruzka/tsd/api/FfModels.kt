package com.otgruzka.tsd.api

/**
 * Фулфилмент: приёмка товара клиента коробками (/ff).
 *
 * Поставка = что клиент везёт (план, заводит менеджер на сайте), с штуками в
 * коробке — коробки «исходят из поставки». На ТСД: скан товара → коробки уже
 * подставлены из плана (`suggested_*`) → проверить → «ПЕЧАТЬ»: ядро заводит
 * коробки, приходует товар и ставит этикетки в очередь принт-станции — они
 * печатаются сами. Количества строками («48», «1.5»), как во всех ручках ядра.
 */

data class FfSupply(
    val id: Long,
    val number: String,
    val status: String,
    val city: String,
    val owner_name: String,
    val expected_at: String?,
    val comment: String?,
    val expected_qty: String,
    val received_qty: String,
    val boxes: Int,
    /** Коробок по плану; null — коробку сообщили не по всем товарам. */
    val boxes_expected: Int?,
    val positions: Int,
)

data class FfRow(
    val product_id: Long,
    val name: String,
    val sku: String?,
    val barcode: String?,
    val expected: String,
    /** Штук в коробке по плану (null — не сообщили). */
    val per_box: String?,
    val boxes_expected: Int?,
    val received: String,
    val boxes: Int,
    val diff: String,
)

data class FfPrint(val job_id: Long, val status: String, val printed_at: String?)

data class FfEntry(
    val id: Long,
    val product_id: Long,
    val name: String,
    val per_box: String,
    val boxes: Int,
    val qty: String,
    val first_box: String?,
    val last_box: String?,
    val operator: String?,
    val created_at: String,
    val cancelled: Boolean,
    val print: FfPrint?,
)

/** Поставка целиком: шапка + план против факта + вводы (свежие сверху). */
data class FfSupplyDetail(
    val id: Long,
    val number: String,
    val status: String,
    val city: String,
    val owner_name: String,
    val expected_qty: String,
    val received_qty: String,
    val boxes: Int,
    val boxes_expected: Int?,
    val positions: Int,
    val rows: List<FfRow>,
    val entries: List<FfEntry>,
)

data class FfScanBody(val code: String)

data class FfProduct(
    val product_id: Long,
    val name: String,
    val sku: String?,
    val barcode: String?,
    /** null — товара нет в плане поставки (принять можно, будет расхождение). */
    val expected: String?,
    /** Штук в коробке по плану и сколько коробок это даёт (полные + неполная). */
    val per_box_plan: String?,
    val boxes_expected: Int?,
    val received: String,
    val boxes: Int,
    /** Что подставить: коробки из плана на недовезённый остаток (полные, потом
     *  одна неполная); без коробки в плане — прошлый ввод / квант карточки. */
    val suggested_per_box: String?,
    val suggested_boxes: Int?,
)

data class FfBoxItem(val product_id: Long, val name: String, val qty: String)

data class FfBox(
    val code: String,
    val owner: String,
    val warehouse: String,
    val supply_number: String?,
    val index: Int?,
    val total: Int?,
    val contents: List<FfBoxItem>,
    val active: Boolean,
)

/** result: OK | AMBIGUOUS | UNKNOWN | BOX | NOT_BOX */
data class FfScanResponse(
    val result: String,
    val message: String? = null,
    val product: FfProduct? = null,
    val candidates: List<InvCandidate>? = null,
    val box: FfBox? = null,
)

data class FfEntryBody(
    val product_id: Long,
    val per_box: String,
    val boxes: Int,
    /** UUID ввода: ретрай после обрыва связи не заводит вторую пачку коробок. */
    val client_ref: String,
)

/** result: OK | DUPLICATE (этот ввод уже записан) | REFUSED (текст в message) */
data class FfEntryResponse(
    val result: String,
    val message: String? = null,
    val entry: FfEntry? = null,
    /** Этикетки ушли в очередь принт-станции. */
    val printed: Boolean? = null,
    val product: FfProduct? = null,
)

/** result: OK | REFUSED (коробки уже вскрывали / поставка закрыта) */
data class FfCancelResponse(
    val result: String,
    val message: String? = null,
    val product: FfProduct? = null,
)

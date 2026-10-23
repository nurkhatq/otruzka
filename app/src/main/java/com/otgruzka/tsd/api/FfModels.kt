package com.otgruzka.tsd.api

/**
 * Фулфилмент: приёмка товара клиента коробками (/ff).
 *
 * Поставка = что клиент везёт (план, заводит менеджер на сайте). На ТСД: скан
 * товара → штук в коробке × коробок → «ПЕЧАТЬ»: ядро заводит коробки, приходует
 * товар и ставит этикетки в очередь принт-станции — они печатаются сами.
 * Количества строками («48», «1.5»), как во всех ручках ядра.
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
    val positions: Int,
)

data class FfRow(
    val product_id: Long,
    val name: String,
    val sku: String?,
    val barcode: String?,
    val expected: String,
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
    val received: String,
    val boxes: Int,
    /** Сколько штук в коробке подставить: как в прошлый раз / квант карточки. */
    val suggested_per_box: String?,
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

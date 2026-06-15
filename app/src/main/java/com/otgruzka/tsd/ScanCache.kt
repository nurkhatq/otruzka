package com.otgruzka.tsd

import com.otgruzka.tsd.api.KaspiOrder
import com.otgruzka.tsd.api.WmsSession

enum class ScanStatus { CHECKING, READY, SHIPPED, KASPI_ONLY, CANCELLING, NOT_FOUND, LOCKED_BY_OTHER }

data class ScannedItem(
    val code: String,
    val status: ScanStatus,
    val customerName: String? = null,
    val totalPrice: Double = 0.0,
    val assembled: Boolean = false,
    val express: Boolean = false,
    val lockHolder: String? = null,
    val source: String? = null,
)

object ScanCache {
    var currentSession: WmsSession? = null
    var pickupOrders: List<KaspiOrder> = emptyList()
    var pickupLoaded: Boolean = false
    var confirmedPickups: MutableSet<String> = mutableSetOf()
}

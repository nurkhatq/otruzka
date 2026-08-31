package com.otgruzka.tsd

import com.otgruzka.tsd.api.TsdPickupOrder
import com.otgruzka.tsd.api.TsdShift

enum class ScanStatus { CHECKING, READY, SHIPPED, CANCELLING, NOT_FOUND, LOCKED_BY_OTHER }

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
    var currentSession: TsdShift? = null
    var pickupOrders: List<TsdPickupOrder> = emptyList()
    var pickupLoaded: Boolean = false
    var confirmedPickups: MutableSet<String> = mutableSetOf()
}

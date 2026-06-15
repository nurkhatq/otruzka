package com.otgruzka.tsd

import com.otgruzka.tsd.api.KaspiOrder
import com.otgruzka.tsd.api.WmsSession

object ScanCache {
    var currentSession: WmsSession? = null
    var pickupOrders: List<KaspiOrder> = emptyList()
    var pickupLoaded: Boolean = false
    var confirmedPickups: MutableSet<String> = mutableSetOf()
}

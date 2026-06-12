package com.otgruzka.tsd

import com.otgruzka.tsd.api.CustomerOrder
import com.otgruzka.tsd.api.OrderPosition

object OrderRepository {
    var currentOrder: CustomerOrder? = null
    var agentName: String = ""
    var positions: List<OrderPosition> = emptyList()
}

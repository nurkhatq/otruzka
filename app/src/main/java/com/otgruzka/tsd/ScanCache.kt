package com.otgruzka.tsd

enum class ScanStatus { CHECKING, READY, SHIPPED, NOT_FOUND }

object ScanCache {
    val orderNameStatus = mutableMapOf<String, Boolean>()
    var isLoaded = false
    var isLoading = false
    var pendingOrders = emptyList<String>()
}

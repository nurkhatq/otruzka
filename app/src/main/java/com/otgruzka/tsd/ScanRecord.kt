package com.otgruzka.tsd

data class ScanRecord(
    val orderCode: String,
    val customerName: String?,
    val totalPrice: Double,
    val result: String,       // SUCCESS | ALREADY_LOCKED | CANCELLING | NOT_FOUND
    val lockHolder: String?,  // filled when ALREADY_LOCKED
    val assembled: Boolean,
    val express: Boolean,
    val isPickup: Boolean,    // true = самовывоз, false = сканирование
    val timestamp: Long = System.currentTimeMillis(),
    val batchId: String,
    val tsdId: String
)

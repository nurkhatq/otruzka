package com.otgruzka.tsd.api

data class Meta(
    val href: String,
    val type: String,
    val mediaType: String,
    val metadataHref: String? = null
)

data class MetaWrapper(
    val meta: Meta
)

// Заказ покупателя — agent и positions содержат только meta.href в основном запросе
data class CustomerOrder(
    val id: String,
    val name: String,
    val meta: Meta,
    val agent: MetaWrapper,
    val organization: MetaWrapper? = null,
    val store: MetaWrapper? = null,
    val demands: List<MetaWrapper>? = null,
    val positions: MetaWrapper? = null
)

// Ответ при GET на href агента (контрагента)
data class AgentDetail(
    val id: String? = null,
    val name: String? = null
)

// Позиция заказа с раскрытым ассортиментом (?expand=assortment)
data class OrderPosition(
    val id: String,
    val quantity: Double,
    val price: Double,
    val assortment: AssortmentDetail
)

data class AssortmentDetail(
    val id: String? = null,
    val name: String? = null,
    val meta: Meta
)

data class ResponseMeta(
    val size: Int = 0
)

data class ListResponse<T>(
    val meta: ResponseMeta? = null,
    val rows: List<T>
)

data class Demand(
    val id: String,
    val name: String? = null,
    val customerOrder: MetaWrapper? = null
)

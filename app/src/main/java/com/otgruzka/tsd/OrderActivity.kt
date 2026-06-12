package com.otgruzka.tsd

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.otgruzka.tsd.api.ApiClient
import kotlinx.coroutines.launch
import java.time.LocalDate

class OrderActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var llResults: LinearLayout
    private lateinit var btnBack: Button

    private data class ResultRow(val tvStatus: TextView, val tvDetail: TextView)
    private val rowMap = mutableMapOf<String, ResultRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order)

        tvTitle = findViewById(R.id.tvTitle)
        llResults = findViewById(R.id.llResults)
        btnBack = findViewById(R.id.btnBack)

        btnBack.setOnClickListener { finish() }

        val orders = ScanCache.pendingOrders
        tvTitle.text = "Отгрузки  ·  ${orders.size}"

        orders.forEach { orderName ->
            val row = addCard(
                status = "ПОИСК",
                statusColor = getColor(R.color.secondary),
                detail = orderName
            )
            rowMap[orderName] = row
        }

        lifecycleScope.launch {
            var created = 0
            var skipped = 0
            var failed = 0

            orders.forEach { orderName ->
                val row = rowMap[orderName] ?: return@forEach
                try {
                    val searchResult = ApiClient.api.searchOrders("name=$orderName")
                    if (searchResult.rows.isEmpty()) {
                        row.tvStatus.text = "НЕ НАЙДЕН"
                        row.tvStatus.setTextColor(getColor(R.color.error))
                        failed++
                        return@forEach
                    }

                    val order = searchResult.rows[0]

                    if (!order.demands.isNullOrEmpty()) {
                        row.tvStatus.text = "УЖЕ ОТГРУЖЕНА"
                        row.tvStatus.setTextColor(getColor(R.color.warning))
                        skipped++
                        return@forEach
                    }

                    val templateBody = JsonObject().apply {
                        add("customerOrder", JsonObject().apply {
                            add("meta", JsonObject().apply {
                                addProperty("href", order.meta.href)
                                order.meta.metadataHref?.let { addProperty("metadataHref", it) }
                                addProperty("type", "customerorder")
                                addProperty("mediaType", "application/json")
                            })
                        })
                    }
                    val template = ApiClient.api.getDemandTemplate(templateBody)
                    template.addProperty("applicable", true)
                    val demand = ApiClient.api.createDemand(template)
                    val demandName = demand.get("name")?.asString ?: "—"

                    row.tvStatus.text = "СОЗДАНА"
                    row.tvStatus.setTextColor(getColor(R.color.success))
                    row.tvDetail.text = "$orderName  /  $demandName"
                    ScanCache.orderNameStatus[orderName] = true
                    created++

                } catch (e: retrofit2.HttpException) {
                    val body = e.response()?.errorBody()?.string()?.take(60) ?: ""
                    row.tvStatus.text = "ОШИБКА  ${e.code()}"
                    row.tvStatus.setTextColor(getColor(R.color.error))
                    row.tvDetail.text = "$orderName  —  $body"
                    failed++
                } catch (e: Exception) {
                    row.tvStatus.text = "ОШИБКА"
                    row.tvStatus.setTextColor(getColor(R.color.error))
                    row.tvDetail.text = "$orderName  —  ${e.message?.take(60)}"
                    failed++
                }
            }

            if (created > 0) saveCacheToDisk()
            addSummaryCard(created, skipped, failed)
            btnBack.visibility = View.VISIBLE
        }
    }

    private fun addCard(status: String, statusColor: Int, detail: String): ResultRow {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            elevation = dp(2).toFloat()
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(8)) }
        }
        val tvStatus = TextView(this).apply {
            text = status
            textSize = 11f
            setTextColor(statusColor)
            letterSpacing = 0.08f
        }
        val tvDetail = TextView(this).apply {
            text = detail
            textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.on_background))
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(tvStatus)
        card.addView(tvDetail)
        llResults.addView(card)
        return ResultRow(tvStatus, tvDetail)
    }

    private fun addSummaryCard(created: Int, skipped: Int, failed: Int) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            elevation = dp(2).toFloat()
            setPadding(dp(20), dp(16), dp(20), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, dp(8), 0, 0) }
        }
        val tvLabel = TextView(this).apply {
            text = "02 · ИТОГО"
            textSize = 11f
            setTextColor(getColor(R.color.secondary))
            letterSpacing = 0.08f
        }
        val tvSummary = TextView(this).apply {
            text = "Создано $created  ·  Пропущено $skipped  ·  Не найдено $failed"
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.on_background))
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(tvLabel)
        card.addView(tvSummary)
        llResults.addView(card)
    }

    private fun saveCacheToDisk() {
        val json = Gson().toJson(ScanCache.orderNameStatus)
        getSharedPreferences("scan_cache", Context.MODE_PRIVATE).edit()
            .putString("orders_json", json)
            .putString("saved_date", LocalDate.now().toString())
            .apply()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

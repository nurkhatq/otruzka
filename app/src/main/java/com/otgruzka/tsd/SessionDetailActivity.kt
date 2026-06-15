package com.otgruzka.tsd

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.SessionScan
import com.otgruzka.tsd.api.SessionStats
import com.otgruzka.tsd.api.WmsApiClient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class SessionDetailActivity : AppCompatActivity() {

    private lateinit var api: com.otgruzka.tsd.api.WmsApi
    private lateinit var batchId: String

    private lateinit var scanListContainer: LinearLayout
    private lateinit var btnLoadMore: Button
    private lateinit var progressBar: ProgressBar

    private var currentPage = 0
    private val pageSize = 50
    private var totalScans = 0
    private var isLoadingScans = false
    private var currentFilter: String? = null
    private var currentSearch: String? = null

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    // filter pill buttons: tag -> button
    private val filterButtons = mutableMapOf<String?, TextView>()

    // scan_result → (русский текст, цвет)
    private val scanResultLabels = mapOf(
        "SUCCESS"         to ("Готово к отгрузке"  to Color.parseColor("#1A6B36")),
        "ALREADY_SHIPPED" to ("Уже был отгружен"   to Color.parseColor("#5956E8")),
        "ALREADY_LOCKED"  to ("Занят другим ТСД"   to Color.parseColor("#D96000")),
        "KASPI_ONLY"      to ("Только в Kaspi"      to Color.parseColor("#9896A8")),
        "CANCELLING"      to ("Отменяется"          to Color.parseColor("#C42828")),
        "NOT_FOUND"       to ("Не найден"           to Color.parseColor("#9896A8"))
    )

    // demand_status → (русский текст, цвет)
    private val demandStatusLabels = mapOf(
        "CREATED"   to ("Отгрузка создана"       to Color.parseColor("#1A6B36")),
        "NOT_IN_MS" to ("Не импортирован в МС"   to Color.parseColor("#D96000")),
        "ERROR"     to ("Ошибка при создании"    to Color.parseColor("#C42828"))
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = WmsApiClient.build(this)
        batchId = intent.getStringExtra("batch_id") ?: run { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EDE8E0"))
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            elevation = dp(3).toFloat()
        }
        topBar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        topBar.addView(TextView(this).apply {
            text = "Детали смены"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(topBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // Progress bar
        progressBar = ProgressBar(this).apply {
            visibility = View.VISIBLE
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(4)
            )
        }
        root.addView(progressBar)

        // ScrollView
        val scroll = ScrollView(this)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(40))
        }

        // Placeholder for header card — added after stats load
        val headerPlaceholder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(headerPlaceholder)

        // Stats grid placeholder
        val statsPlaceholder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(statsPlaceholder)

        // Search field
        val etSearch = EditText(this).apply {
            hint = "Поиск по номеру заказа"
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A1A"))
            setHintTextColor(Color.parseColor("#BCBAC8"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE); cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    searchRunnable?.let { searchHandler.removeCallbacks(it) }
                    searchRunnable = Runnable {
                        currentSearch = s?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        resetScans()
                    }
                    searchHandler.postDelayed(searchRunnable!!, 400)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        inner.addView(etSearch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); bottomMargin = dp(4) })

        // Filter pills card
        val filterCard = buildFilterCard()
        inner.addView(filterCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12); bottomMargin = dp(8) })

        // Scans list
        scanListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        inner.addView(scanListContainer)

        // Load more button
        btnLoadMore = Button(this).apply {
            text = "Загрузить ещё"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#5956E8"))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { loadScansPage() }
        }
        inner.addView(btnLoadMore, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        scroll.addView(inner)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)

        // Load stats and first scans
        lifecycleScope.launch {
            try {
                val stats = api.getSessionStats(batchId)
                progressBar.visibility = View.GONE

                // Build header card
                headerPlaceholder.addView(buildHeaderCard(stats))
                headerPlaceholder.addView(spacer(dp(12)))

                // Build stats grid
                statsPlaceholder.addView(buildStatsGrid(stats))
                statsPlaceholder.addView(spacer(dp(12)))

            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                headerPlaceholder.addView(errorLabel("Ошибка загрузки: ${e.message?.take(60)}"))
            }
        }

        loadScansPage()
    }

    private fun buildHeaderCard(stats: SessionStats): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(Color.WHITE, 12)
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Date range
        val startStr = formatDateTime(stats.started_at)
        val endStr = if (!stats.completed_at.isNullOrBlank()) formatDateTime(stats.completed_at) else "—"
        card.addView(infoRow("Дата:", "$startStr — $endStr"))

        // Duration
        if (stats.duration_sec != null && stats.duration_sec > 0) {
            val h = stats.duration_sec / 3600
            val m = (stats.duration_sec % 3600) / 60
            val durStr = when {
                h > 0 -> "$h ч $m мин"
                else  -> "$m мин"
            }
            card.addView(infoRow("Длительность:", durStr))
        }

        // Employee
        if (!stats.user_name.isNullOrBlank()) {
            card.addView(infoRow("Сотрудник:", stats.user_name))
        }

        // Warehouse
        val whName = WmsAuth.WAREHOUSE_NAMES[stats.warehouse_id] ?: "Склад ${stats.warehouse_id}"
        card.addView(infoRow("Склад:", whName))

        return card
    }

    private fun infoRow(label: String, value: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.parseColor("#9896A8"))
            layoutParams = LinearLayout.LayoutParams(dp(110), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(TextView(this).apply {
            text = value
            textSize = 13f
            setTextColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    private fun buildStatsGrid(stats: SessionStats): View {
        // 2-column grid: left column and right column
        val gridRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val gridRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val gridRow3 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val totalScanned = stats.total_scanned
        val shipped = stats.by_demand["CREATED"] ?: 0
        val success = stats.by_result["SUCCESS"] ?: 0
        val kaspiOnly = stats.by_result["KASPI_ONLY"] ?: 0
        val alreadyLocked = stats.by_result["ALREADY_LOCKED"] ?: 0
        val notFound = stats.by_result["NOT_FOUND"] ?: 0

        gridRow1.addView(statCard("Всего сканов", totalScanned, Color.parseColor("#5956E8")))
        gridRow1.addView(statCard("Отгружено", shipped, Color.parseColor("#1A6B36")))
        gridRow2.addView(statCard("Готово", success, Color.parseColor("#2E8B57")))
        gridRow2.addView(statCard("Нет в МС", kaspiOnly, Color.parseColor("#D96000")))
        gridRow3.addView(statCard("Занят ТСД", alreadyLocked, Color.parseColor("#D4A000")))
        gridRow3.addView(statCard("Не найден", notFound, Color.parseColor("#9896A8")))

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wrapper.addView(gridRow1, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        wrapper.addView(gridRow2, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
        wrapper.addView(gridRow3, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        return wrapper
    }

    private fun statCard(label: String, value: Int, valueColor: Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(Color.WHITE, 12)
            elevation = dp(1).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply {
                leftMargin = dp(0)
                rightMargin = dp(0)
            }
        }
        card.addView(TextView(this).apply {
            text = value.toString()
            textSize = 24f
            setTypeface(null, Typeface.BOLD)
            setTextColor(valueColor)
        })
        card.addView(TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.parseColor("#9896A8"))
            setPadding(0, dp(2), 0, 0)
        })
        val lp = card.layoutParams as LinearLayout.LayoutParams
        lp.setMargins(dp(0), dp(0), dp(6), dp(0))
        card.layoutParams = lp
        return card
    }

    private fun buildFilterCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(Color.WHITE, 12)
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val scrollH = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val pillRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        // filter tag -> label
        val filters = listOf(
            null          to "Все",
            "SUCCESS"     to "Готово к отгр.",
            "ALREADY_SHIPPED" to "Отгружено",
            "KASPI_ONLY"  to "Нет в МС",
            "ALREADY_LOCKED" to "Занят ТСД",
            "CANCELLING"  to "Отменяется",
            "NOT_FOUND"   to "Не найден"
        )

        filters.forEach { (tag, label) ->
            val pill = TextView(this).apply {
                text = label
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setTypeface(null, if (tag == currentFilter) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (tag == currentFilter) Color.WHITE else Color.parseColor("#5956E8"))
                background = pillDrawable(
                    if (tag == currentFilter) Color.parseColor("#5956E8") else Color.parseColor("#F0EFFC")
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener { onFilterSelected(tag) }
            }
            filterButtons[tag] = pill
            pillRow.addView(pill)
        }

        scrollH.addView(pillRow)
        card.addView(scrollH)
        return card
    }

    private fun onFilterSelected(tag: String?) {
        currentFilter = tag
        filterButtons.forEach { (k, btn) ->
            val isActive = k == tag
            btn.setTypeface(null, if (isActive) Typeface.BOLD else Typeface.NORMAL)
            btn.setTextColor(if (isActive) Color.WHITE else Color.parseColor("#5956E8"))
            btn.background = pillDrawable(
                if (isActive) Color.parseColor("#5956E8") else Color.parseColor("#F0EFFC")
            )
        }
        resetScans()
    }

    private fun resetScans() {
        currentPage = 0
        totalScans = 0
        scanListContainer.removeAllViews()
        btnLoadMore.visibility = View.GONE
        loadScansPage()
    }

    private fun loadScansPage() {
        if (isLoadingScans) return
        isLoadingScans = true
        progressBar.visibility = View.VISIBLE
        btnLoadMore.isEnabled = false

        lifecycleScope.launch {
            try {
                val resp = api.getSessionScans(
                    batchId = batchId,
                    page = currentPage,
                    pageSize = pageSize,
                    scanResult = currentFilter,
                    search = currentSearch,
                )
                totalScans = resp.total
                currentPage++

                if (resp.items.isEmpty() && currentPage == 1) {
                    scanListContainer.addView(emptyLabel("Нет сканов"))
                } else {
                    resp.items.forEach { scan ->
                        scanListContainer.addView(buildScanRow(scan))
                        scanListContainer.addView(spacer(dp(6)))
                    }
                }

                val hasMore = totalScans > currentPage * pageSize
                btnLoadMore.visibility = if (hasMore) View.VISIBLE else View.GONE
                btnLoadMore.isEnabled = true

            } catch (e: Exception) {
                scanListContainer.addView(errorLabel("Ошибка загрузки: ${e.message?.take(60)}"))
            }
            isLoadingScans = false
            progressBar.visibility = View.GONE
        }
    }

    private fun buildScanRow(scan: SessionScan): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(Color.WHITE, 8)
            elevation = dp(1).toFloat()
            setPadding(dp(14), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Row 1: status label left, time right
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val (resultLabel, resultColor) = scanResultLabels[scan.scan_result]
            ?: ("Неизвестно" to Color.parseColor("#9896A8"))

        row1.addView(TextView(this).apply {
            text = resultLabel
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(resultColor)
            letterSpacing = 0.04f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row1.addView(TextView(this).apply {
            text = formatTime(scan.scanned_at)
            textSize = 11f
            setTextColor(Color.parseColor("#9896A8"))
            gravity = Gravity.END
        })
        card.addView(row1)

        // Order code
        card.addView(TextView(this).apply {
            text = scan.order_code
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(0, dp(4), 0, 0)
        })

        // Customer name
        if (!scan.customer_name.isNullOrBlank()) {
            card.addView(TextView(this).apply {
                text = scan.customer_name
                textSize = 12f
                setTextColor(Color.parseColor("#9896A8"))
                setPadding(0, dp(2), 0, 0)
            })
        }

        // Demand status
        if (!scan.demand_status.isNullOrBlank()) {
            val (demandLabel, demandColor) = demandStatusLabels[scan.demand_status]
                ?: (scan.demand_status to Color.parseColor("#9896A8"))
            card.addView(TextView(this).apply {
                text = "→ $demandLabel"
                textSize = 12f
                setTextColor(demandColor)
                setPadding(0, dp(3), 0, 0)
            })
            if (scan.demand_status == "CREATED" && !scan.demand_name.isNullOrBlank()) {
                card.addView(TextView(this).apply {
                    text = scan.demand_name
                    textSize = 11f
                    setTextColor(Color.parseColor("#9896A8"))
                    setPadding(0, dp(2), 0, 0)
                })
            }
        }

        return card
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun formatDateTime(isoStr: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale("ru"))
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(isoStr) ?: return isoStr
            val out = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru"))
            out.timeZone = TimeZone.getDefault()
            out.format(date)
        } catch (_: Exception) { isoStr.take(16).replace("T", " ") }
    }

    private fun formatTime(isoStr: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale("ru"))
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(isoStr) ?: return isoStr
            val out = SimpleDateFormat("HH:mm", Locale("ru"))
            out.timeZone = TimeZone.getDefault()
            out.format(date)
        } catch (_: Exception) { isoStr.takeLast(8).take(5) }
    }

    private fun cardDrawable(bg: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(radius).toFloat()
        }

    private fun pillDrawable(bg: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(20).toFloat()
        }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun emptyLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#9896A8"))
        gravity = Gravity.CENTER
        setPadding(0, dp(24), 0, dp(8))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun errorLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#C42828"))
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(8))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

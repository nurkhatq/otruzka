package com.otgruzka.tsd

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.UserItem
import com.otgruzka.tsd.api.WmsApiClient
import com.otgruzka.tsd.api.WmsSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var api: com.otgruzka.tsd.api.WmsApi
    private lateinit var sessionList: LinearLayout
    private lateinit var btnMore: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var etSearch: EditText
    private lateinit var tvDateFrom: TextView
    private lateinit var tvDateTo: TextView
    private lateinit var tvCounter: TextView

    private val sessions = mutableListOf<WmsSession>()
    private var currentPage = 0
    private val pageSize = 20
    private var totalCount = 0
    private var isLoading = false

    private var filterSearch: String? = null
    private var filterWarehouseId: Int? = null
    private var filterUserId: Int? = null
    private var filterDateFrom: String? = null
    private var filterDateTo: String? = null

    private val warehouseList = listOf(
        0 to "Все склады",
        1 to "PP1 Шымкент",
        2 to "PP2 Алматы",
        5 to "PP5 Астана"
    )
    private val userItems = mutableListOf<UserItem>()
    private lateinit var userAdapter: ArrayAdapter<String>
    private var isAdmin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = WmsApiClient.build(this)
        isAdmin = WmsAuth.getUser(this)?.role == "admin"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0EDE8"))
        }

        // ── Top bar ──────────────────────────────────────────────────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }
        topBar.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(0, 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        topBar.addView(TextView(this).apply {
            text = "История смен"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        tvCounter = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.parseColor("#9896A8"))
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(3), dp(6), dp(3))
            background = roundedBg(Color.parseColor("#F3F1EE"), dp(8))
        }
        topBar.addView(tvCounter)
        root.addView(topBar, lp(matchW, wrapH))

        // ── Filter card ───────────────────────────────────────────────────────
        val filterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // Search field
        etSearch = EditText(this).apply {
            hint = "Номер отгрузки или заказа"
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A1A"))
            setHintTextColor(Color.parseColor("#BCBAC8"))
            background = roundedBg(Color.parseColor("#F3F1EE"), dp(10))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setSingleLine(true)
            layoutParams = lp(matchW, wrapH).apply { bottomMargin = dp(10) }
        }
        filterCard.addView(etSearch)

        // Warehouse + User spinners row
        val spinnersRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(matchW, wrapH).apply { bottomMargin = dp(10) }
        }

        val whSpinner = makeSpinner(warehouseList.map { it.second })
        whSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                filterWarehouseId = if (pos == 0) null else warehouseList[pos].first
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnersRow.addView(whSpinner, lp(0, dp(44), 1f).apply { rightMargin = dp(8) })

        userAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf("Все сотрудники"))
        userAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val userSpinner = Spinner(this).apply {
            background = roundedBg(Color.parseColor("#F3F1EE"), dp(10))
            adapter = userAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    filterUserId = if (pos == 0) null else userItems.getOrNull(pos - 1)?.id
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        spinnersRow.addView(userSpinner, lp(0, dp(44), 1f))
        filterCard.addView(spinnersRow)

        // Date + Apply row
        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvDateFrom = TextView(this).apply {
            text = "От: все даты"
            textSize = 12f
            setTextColor(Color.parseColor("#5956E8"))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedBg(Color.parseColor("#EEEDFB"), dp(8))
            gravity = Gravity.CENTER
            layoutParams = lp(0, wrapH, 1f).apply { rightMargin = dp(6) }
            setOnClickListener { pickDate(true) }
        }
        tvDateTo = TextView(this).apply {
            text = "До: все даты"
            textSize = 12f
            setTextColor(Color.parseColor("#5956E8"))
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = roundedBg(Color.parseColor("#EEEDFB"), dp(8))
            gravity = Gravity.CENTER
            layoutParams = lp(0, wrapH, 1f).apply { rightMargin = dp(6) }
            setOnClickListener { pickDate(false) }
        }
        dateRow.addView(tvDateFrom)
        dateRow.addView(tvDateTo)
        dateRow.addView(Button(this).apply {
            text = "Найти"
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#5956E8"))
            layoutParams = lp(dp(76), dp(36))
            setOnClickListener { applyFilters() }
        })
        filterCard.addView(dateRow)
        root.addView(filterCard, lp(matchW, wrapH))

        // Progress bar
        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
            layoutParams = lp(matchW, dp(3))
        }
        root.addView(progressBar)

        // ── Scroll area ───────────────────────────────────────────────────────
        val scroll = ScrollView(this)
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(40))
        }

        sessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        inner.addView(sessionList)

        btnMore = Button(this).apply {
            text = "Ещё смены"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#5956E8"))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { loadNextPage() }
        }
        inner.addView(btnMore, lp(matchW, wrapH))
        scroll.addView(inner)
        root.addView(scroll, lp(matchW, 0, 1f))

        setContentView(root)

        loadUsers()
        loadNextPage()
    }

    private fun makeSpinner(items: List<String>): Spinner {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return Spinner(this).apply {
            background = roundedBg(Color.parseColor("#F3F1EE"), dp(10))
            this.adapter = adapter
        }
    }

    private fun loadUsers() {
        lifecycleScope.launch {
            try {
                val users = api.getUsersList()
                userItems.clear()
                userItems.addAll(users)
                val names = mutableListOf("Все сотрудники")
                names.addAll(users.map { it.full_name })
                userAdapter.clear()
                userAdapter.addAll(names)
                userAdapter.notifyDataSetChanged()
            } catch (_: Exception) {}
        }
    }

    private fun applyFilters() {
        filterSearch = etSearch.text.toString().trim().takeIf { it.isNotEmpty() }
        sessions.clear()
        sessionList.removeAllViews()
        currentPage = 0
        totalCount = 0
        btnMore.visibility = View.GONE
        loadNextPage()
    }

    private fun pickDate(isFrom: Boolean) {
        val cal = Calendar.getInstance()
        DatePickerDialog(this, { _, year, month, day ->
            val str = "%04d-%02d-%02d".format(year, month + 1, day)
            val display = "%02d.%02d.%04d".format(day, month + 1, year)
            if (isFrom) { filterDateFrom = str; tvDateFrom.text = "От: $display" }
            else { filterDateTo = str; tvDateTo.text = "До: $display" }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun loadNextPage() {
        if (isLoading) return
        isLoading = true
        progressBar.visibility = View.VISIBLE
        btnMore.isEnabled = false

        lifecycleScope.launch {
            try {
                val resp = api.getSessions(
                    page = currentPage,
                    pageSize = pageSize,
                    warehouseId = filterWarehouseId,
                    userId = filterUserId,
                    search = filterSearch,
                    dateFrom = filterDateFrom,
                    dateTo = filterDateTo
                )
                totalCount = resp.total
                sessions.addAll(resp.items)
                currentPage++

                tvCounter.text = if (totalCount > 0) "$totalCount смен" else ""

                resp.items.forEach { s ->
                    sessionList.addView(buildCard(s))
                    sessionList.addView(spacer(dp(8)))
                }

                val hasMore = sessions.size < totalCount
                btnMore.visibility = if (hasMore) View.VISIBLE else View.GONE
                btnMore.isEnabled = true

                if (sessions.isEmpty()) {
                    sessionList.addView(emptyLabel("Нет смен по заданным фильтрам"))
                }
            } catch (e: Exception) {
                sessionList.addView(emptyLabel("Ошибка загрузки: ${e.message?.take(60)}"))
            }
            isLoading = false
            progressBar.visibility = View.GONE
        }
    }

    // ── Card builder ──────────────────────────────────────────────────────────

    private fun buildCard(s: WmsSession): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.WHITE, dp(14))
            elevation = dp(1).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(12))
            layoutParams = lp(matchW, wrapH)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(
                    Intent(this@HistoryActivity, SessionDetailActivity::class.java)
                        .putExtra("batch_id", s.batch_id)
                )
            }
        }

        // Row 1: batch_id + status badge
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row1.addView(TextView(this).apply {
            text = s.batch_id
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            layoutParams = lp(0, wrapH, 1f)
        })
        row1.addView(statusBadge(s.status))
        card.addView(row1)

        // Date/time
        card.addView(TextView(this).apply {
            text = formatDateTime(s.started_at)
            textSize = 12f
            setTextColor(Color.parseColor("#9896A8"))
            setPadding(0, dp(3), 0, dp(10))
        })

        // Divider
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#F0EDE8"))
            layoutParams = lp(matchW, 1).apply { bottomMargin = dp(10) }
        })

        // Row 2: warehouse | employee
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val whName = WmsAuth.WAREHOUSE_NAMES[s.warehouse_id] ?: "Склад ${s.warehouse_id}"
        row2.addView(TextView(this).apply {
            text = whName
            textSize = 12f
            setTextColor(Color.parseColor("#6B6880"))
            layoutParams = lp(0, wrapH, 1f)
        })
        if (!s.user_name.isNullOrBlank()) {
            row2.addView(TextView(this).apply {
                text = s.user_name
                textSize = 12f
                setTextColor(Color.parseColor("#9896A8"))
            })
        }
        card.addView(row2)

        // Order count chip
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(7), 0, 0)
            addView(TextView(this@HistoryActivity).apply {
                text = "${s.order_count} заказов"
                textSize = 11f
                setTextColor(Color.parseColor("#6B6880"))
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = roundedBg(Color.parseColor("#F0EDE8"), dp(6))
            })
        })

        return card
    }

    private fun statusBadge(status: String): TextView {
        val (label, fg, bg) = when (status) {
            "COMPLETED" -> Triple("Завершена", "#1A6B36", "#E6F4EC")
            "CANCELLED" -> Triple("Отменена",  "#C42828", "#FDEAEA")
            "ACTIVE"    -> Triple("Активна",   "#5956E8", "#EEEDFB")
            else        -> Triple(status,       "#9896A8", "#F3F3F5")
        }
        return TextView(this).apply {
            text = label
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(fg))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedBg(Color.parseColor(bg), dp(6))
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatDateTime(isoStr: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale("ru"))
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(isoStr) ?: return isoStr
            val out = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale("ru"))
            out.timeZone = TimeZone.getDefault()
            out.format(date)
        } catch (_: Exception) { isoStr.take(16).replace("T", " ") }
    }

    private fun roundedBg(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = lp(matchW, h)
    }

    private fun emptyLabel(msg: String) = TextView(this).apply {
        text = msg
        textSize = 13f
        setTextColor(Color.parseColor("#9896A8"))
        gravity = Gravity.CENTER
        setPadding(0, dp(32), 0, dp(8))
        layoutParams = lp(matchW, wrapH)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight)

    private val matchW = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapH  = LinearLayout.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

package com.otgruzka.tsd

import android.content.Intent
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
import com.otgruzka.tsd.api.SessionDateItem
import com.otgruzka.tsd.api.UserItem
import com.otgruzka.tsd.api.WmsApiClient
import com.otgruzka.tsd.api.WmsSession
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private enum class ViewMode { DATE_LIST, SESSION_LIST }

    private lateinit var api: com.otgruzka.tsd.api.WmsApi

    private var viewMode = ViewMode.DATE_LIST
    private var selectedDate: String? = null

    private val sessions = mutableListOf<WmsSession>()
    private val userItems = mutableListOf<UserItem>()
    private var filterWarehouseId: Int? = null
    private var filterUserId: Int? = null
    private var filterSearch: String? = null
    private var currentPage = 0
    private val pageSize = 30
    private var totalCount = 0
    private var isLoading = false

    private lateinit var tvTitle: TextView
    private lateinit var contentLayout: LinearLayout
    private lateinit var progressBar: ProgressBar
    private var sessionList: LinearLayout? = null
    private var btnMore: Button? = null

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    private val warehouseList = listOf(
        0 to "Все склады",
        1 to "PP1 Шымкент",
        2 to "PP2 Алматы",
        5 to "PP5 Астана"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = WmsApiClient.build(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0EDE8"))
        }

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
            setOnClickListener { handleBack() }
        })
        tvTitle = TextView(this).apply {
            text = "История смен"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            layoutParams = lp(0, wrapH, 1f)
        }
        topBar.addView(tvTitle)
        root.addView(topBar, lp(matchW, wrapH))

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            isIndeterminate = true
        }
        root.addView(progressBar, lp(matchW, dp(3)))

        val scroll = ScrollView(this)
        contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(40))
        }
        scroll.addView(contentLayout)
        root.addView(scroll, lp(matchW, 0, 1f))

        setContentView(root)

        lifecycleScope.launch {
            try { userItems.addAll(api.getUsersList()) } catch (_: Exception) {}
        }

        showDateList()
    }

    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (viewMode == ViewMode.SESSION_LIST) showDateList() else finish()
    }

    // ─── DATE LIST ────────────────────────────────────────────────────────────

    private fun showDateList() {
        viewMode = ViewMode.DATE_LIST
        selectedDate = null
        tvTitle.text = "История смен"
        contentLayout.removeAllViews()
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val dates = api.getSessionDates()
                progressBar.visibility = View.GONE
                if (dates.isEmpty()) {
                    contentLayout.addView(emptyLabel("Нет сессий")); return@launch
                }
                dates.forEach { item ->
                    contentLayout.addView(buildDateCard(item))
                    contentLayout.addView(spacer(dp(8)))
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                contentLayout.addView(emptyLabel("Ошибка: ${e.message?.take(60)}"))
            }
        }
    }

    private fun buildDateCard(item: SessionDateItem): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(Color.WHITE, dp(14))
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(18), dp(18), dp(18))
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { openDateSessions(item.date) }
        }

        val cal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#EEEDFB"))
            layoutParams = lp(dp(52), dp(52)).apply { rightMargin = dp(14) }
            background = roundedBg(Color.parseColor("#EEEDFB"), dp(10))
        }
        val parts = item.date.split("-")
        cal.addView(TextView(this).apply {
            text = if (parts.size == 3) parts[2] else "??"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#5956E8"))
            gravity = Gravity.CENTER
        })
        cal.addView(TextView(this).apply {
            text = if (parts.size == 3) monthShort(parts[1].toIntOrNull() ?: 0) else ""
            textSize = 10f
            setTextColor(Color.parseColor("#5956E8"))
            gravity = Gravity.CENTER
        })
        card.addView(cal)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(0, wrapH, 1f)
        }
        info.addView(TextView(this).apply {
            text = formatDateDisplay(item.date)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
        })
        info.addView(TextView(this).apply {
            text = "${item.session_count} смен · ${item.total_orders} заказов"
            textSize = 12f
            setTextColor(Color.parseColor("#9896A8"))
            setPadding(0, dp(3), 0, 0)
        })
        card.addView(info)

        card.addView(TextView(this).apply {
            text = "›"
            textSize = 22f
            setTextColor(Color.parseColor("#BCBAC8"))
        })
        return card
    }

    private fun monthShort(m: Int) = arrayOf("","ЯНВ","ФЕВ","МАР","АПР","МАЙ","ИЮН","ИЮЛ","АВГ","СЕН","ОКТ","НОЯ","ДЕК").getOrElse(m) { "" }

    // ─── SESSION LIST ─────────────────────────────────────────────────────────

    private fun openDateSessions(date: String) {
        viewMode = ViewMode.SESSION_LIST
        selectedDate = date
        tvTitle.text = formatDateDisplay(date)
        filterWarehouseId = null
        filterUserId = null
        filterSearch = null
        sessions.clear()
        currentPage = 0
        totalCount = 0
        contentLayout.removeAllViews()
        buildSessionListUI()
        loadNextPage()
    }

    private fun buildSessionListUI() {
        val filterCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.WHITE, dp(12))
            elevation = dp(1).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val etSearch = EditText(this).apply {
            hint = "Поиск по номеру заказа или отгрузки"
            textSize = 14f
            setTextColor(Color.parseColor("#1A1A1A"))
            setHintTextColor(Color.parseColor("#BCBAC8"))
            background = roundedBg(Color.parseColor("#F3F1EE"), dp(10))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setSingleLine(true)
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    searchRunnable?.let { searchHandler.removeCallbacks(it) }
                    searchRunnable = Runnable {
                        filterSearch = s?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        resetAndLoad()
                    }
                    searchHandler.postDelayed(searchRunnable!!, 400)
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        filterCard.addView(etSearch, lp(matchW, wrapH).apply { bottomMargin = dp(10) })

        val whAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, warehouseList.map { it.second })
        whAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val whSpinner = Spinner(this).apply {
            background = roundedBg(Color.parseColor("#F3F1EE"), dp(10))
            adapter = whAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val v2 = if (pos == 0) null else warehouseList[pos].first
                    if (v2 != filterWarehouseId) { filterWarehouseId = v2; resetAndLoad() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        val userNames = mutableListOf("Все сотрудники").also { it.addAll(userItems.map { u -> u.full_name }) }
        val userAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, userNames)
        userAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val userSpinner = Spinner(this).apply {
            background = roundedBg(Color.parseColor("#F3F1EE"), dp(10))
            adapter = userAdapter
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val v2 = if (pos == 0) null else userItems.getOrNull(pos - 1)?.id
                    if (v2 != filterUserId) { filterUserId = v2; resetAndLoad() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        filterCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(whSpinner, lp(0, dp(44), 1f).apply { rightMargin = dp(8) })
            addView(userSpinner, lp(0, dp(44), 1f))
        })
        contentLayout.addView(filterCard, lp(matchW, wrapH).apply { bottomMargin = dp(12) })

        sessionList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        contentLayout.addView(sessionList!!, lp(matchW, wrapH))

        btnMore = Button(this).apply {
            text = "Ещё смены"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#5956E8"))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { loadNextPage() }
        }
        contentLayout.addView(btnMore!!, lp(matchW, wrapH))
    }

    private fun resetAndLoad() {
        sessions.clear()
        sessionList?.removeAllViews()
        currentPage = 0
        totalCount = 0
        btnMore?.visibility = View.GONE
        loadNextPage()
    }

    private fun loadNextPage() {
        if (isLoading) return
        isLoading = true
        progressBar.visibility = View.VISIBLE
        btnMore?.isEnabled = false

        lifecycleScope.launch {
            try {
                val resp = api.getSessions(
                    page = currentPage,
                    pageSize = pageSize,
                    warehouseId = filterWarehouseId,
                    userId = filterUserId,
                    search = filterSearch,
                    dateFrom = selectedDate,
                    dateTo = selectedDate,
                )
                totalCount = resp.total
                sessions.addAll(resp.items)
                currentPage++

                resp.items.forEach { s ->
                    sessionList?.addView(buildCard(s))
                    sessionList?.addView(spacer(dp(8)))
                }
                if (sessions.isEmpty())
                    sessionList?.addView(emptyLabel("Нет смен за ${formatDateDisplay(selectedDate ?: "")}"))

                val hasMore = sessions.size < totalCount
                btnMore?.visibility = if (hasMore) View.VISIBLE else View.GONE
                btnMore?.isEnabled = true
            } catch (e: Exception) {
                sessionList?.addView(emptyLabel("Ошибка: ${e.message?.take(60)}"))
            }
            isLoading = false
            progressBar.visibility = View.GONE
        }
    }

    private fun buildCard(s: WmsSession): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.WHITE, dp(14))
            elevation = dp(1).toFloat()
            setPadding(dp(16), dp(14), dp(16), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@HistoryActivity, SessionDetailActivity::class.java)
                    .putExtra("batch_id", s.batch_id))
            }
        }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row1.addView(TextView(this).apply {
            text = s.batch_id; textSize = 13f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor("#1A1A1A"))
            layoutParams = lp(0, wrapH, 1f)
        })
        row1.addView(statusBadge(s.status))
        card.addView(row1)

        card.addView(TextView(this).apply {
            text = formatTime(s.started_at); textSize = 12f
            setTextColor(Color.parseColor("#9896A8")); setPadding(0, dp(3), 0, dp(10))
        })
        card.addView(View(this).apply {
            setBackgroundColor(Color.parseColor("#F0EDE8"))
            layoutParams = lp(matchW, 1).apply { bottomMargin = dp(10) }
        })

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row2.addView(TextView(this).apply {
            text = WmsAuth.WAREHOUSE_NAMES[s.warehouse_id] ?: "Склад ${s.warehouse_id}"
            textSize = 12f; setTextColor(Color.parseColor("#6B6880")); layoutParams = lp(0, wrapH, 1f)
        })
        if (!s.user_name.isNullOrBlank()) {
            row2.addView(TextView(this).apply {
                text = s.user_name; textSize = 12f; setTextColor(Color.parseColor("#9896A8"))
            })
        }
        card.addView(row2)

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0)
            addView(TextView(this@HistoryActivity).apply {
                text = "${s.order_count} заказов"; textSize = 11f
                setTextColor(Color.parseColor("#6B6880")); setPadding(dp(8), dp(3), dp(8), dp(3))
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
            text = label; textSize = 11f
            setTypeface(null, Typeface.BOLD); setTextColor(Color.parseColor(fg))
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = roundedBg(Color.parseColor(bg), dp(6))
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun formatDateDisplay(isoDate: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(sdf.parse(isoDate) ?: return isoDate)
        } catch (_: Exception) { isoDate }
    }

    private fun formatTime(isoStr: String): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val out = SimpleDateFormat("HH:mm", Locale.getDefault())
            out.timeZone = TimeZone.getDefault()
            out.format(sdf.parse(isoStr) ?: return isoStr)
        } catch (_: Exception) { isoStr.take(5) }
    }

    private fun roundedBg(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    private fun spacer(h: Int) = View(this).apply { layoutParams = lp(matchW, h) }
    private fun emptyLabel(msg: String) = TextView(this).apply {
        text = msg; textSize = 13f; setTextColor(Color.parseColor("#9896A8"))
        gravity = Gravity.CENTER; setPadding(0, dp(32), 0, dp(8)); layoutParams = lp(matchW, wrapH)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private val matchW = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapH  = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

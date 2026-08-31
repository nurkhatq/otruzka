package com.otgruzka.tsd

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CoreApi
import com.otgruzka.tsd.api.CoreApiClient
import com.otgruzka.tsd.api.PickerSessionMe
import com.otgruzka.tsd.api.PickerTask
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Сборщик — дизайн скопирован с waybills (/picker): вкладки «Задания / История»,
 * карточки заданий с прогрессом, сессия start/end, история с повторной печатью.
 */
class PickerTasksActivity : AppCompatActivity() {

    companion object {
        // Категории ядра → буква и цвета чипа как в waybills (A фиолетовый,
        // B синий, C оранжевый)
        fun catChip(type: String?): Triple<String, Int, Int> = when (type) {
            "mass_single" -> Triple("A", Color.parseColor("#F3E8FF"), Color.parseColor("#7E22CE"))
            "kit", "multi" -> Triple("C", Color.parseColor("#FFEDD5"), Color.parseColor("#C2410C"))
            "CANCEL" -> Triple("ОТМ", Color.parseColor("#FEE2E2"), Color.parseColor("#B91C1C"))
            else -> Triple("B", Color.parseColor("#DBEAFE"), Color.parseColor("#1D4ED8"))
        }

        fun catLabel(type: String?): String = when (type) {
            "mass_single" -> "Массовые"
            "single" -> "Одиночные"
            "multi_qty" -> "Мультикол-во"
            "kit" -> "Наборы"
            "multi" -> "Мультипозиция"
            "CANCEL" -> "Отмена"
            else -> type ?: "—"
        }

        // Палитра waybills (Tailwind)
        val BG = Color.parseColor("#F9FAFB")
        val WHITE = Color.WHITE
        val BORDER = Color.parseColor("#E5E7EB")
        val TEXT = Color.parseColor("#111827")
        val MUTED = Color.parseColor("#6B7280")
        val FAINT = Color.parseColor("#9CA3AF")
        val BLUE = Color.parseColor("#2563EB")
        val GREEN = Color.parseColor("#22C55E")
        val YELLOW = Color.parseColor("#EAB308")
        val RED = Color.parseColor("#DC2626")
    }

    private lateinit var api: CoreApi
    private lateinit var tvUser: TextView
    private lateinit var tvStoreCity: TextView
    private lateinit var tabTasks: TextView
    private lateinit var tabHistory: TextView
    private lateinit var llBody: LinearLayout

    private var me: PickerSessionMe? = null
    private var history: List<PickerTask> = emptyList()
    private var tab = "tasks"
    private var historySearch = ""
    private var reprintingId: Int? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CoreAuth.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        api = CoreApiClient.build(this)
        setContentView(buildUi())
        renderHeader()
    }

    override fun onResume() {
        super.onResume()
        if (CoreAuth.isLoggedIn(this)) load()
    }

    // ─── Skeleton ────────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }

        // Header (белый, sticky)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WHITE)
            elevation = dp(2).toFloat()
            setPadding(dp(8), dp(12), dp(16), dp(12))
        }
        top.addView(TextView(this).apply {
            text = "←"; textSize = 22f
            setTextColor(BLUE)
            setPadding(dp(12), dp(2), dp(12), dp(2))
            setOnClickListener { finish() }
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvUser = TextView(this).apply {
            text = "Сборщик"
            textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
        }
        tvStoreCity = TextView(this).apply {
            textSize = 11f; setTextColor(BLUE)
            setPadding(0, dp(1), 0, 0)
            setOnClickListener { changeStoreOrCity() }
        }
        col.addView(tvUser); col.addView(tvStoreCity)
        top.addView(col)
        root.addView(top)

        // Tabs
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(WHITE)
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        tabTasks = tabButton("Задания") { switchTab("tasks") }
        tabHistory = tabButton("История") { switchTab("history") }
        tabs.addView(tabTasks, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(8) })
        tabs.addView(tabHistory, LinearLayout.LayoutParams(0, dp(38), 1f))
        root.addView(tabs)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0)
                .apply { weight = 1f }
            isFillViewport = true
        }
        llBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(llBody)
        root.addView(scroll)
        return root
    }

    private fun tabButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f; setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun renderHeader() {
        val name = CoreAuth.fullName(this) ?: CoreAuth.username(this) ?: ""
        tvUser.text = "Сборщик · $name"
        val store = CoreAuth.storeName(this) ?: "магазин?"
        tvStoreCity.text = "$store · ${CoreAuth.cityLabel(CoreAuth.city(this))} ▾"
    }

    private fun switchTab(t: String) {
        tab = t
        if (t == "history") loadHistory()
        render()
    }

    // ─── Data ────────────────────────────────────────────────────────────────

    private fun load() {
        if (loading) return
        val storeId = CoreAuth.storeId(this)
        val city = CoreAuth.city(this)
        if (storeId == 0 || city.isNullOrBlank()) {
            llBody.removeAllViews()
            llBody.addView(emptyNote("Выберите магазин и город — нажмите строку под заголовком"))
            return
        }
        loading = true
        lifecycleScope.launch {
            try {
                me = api.sessionMe(storeId, city)
                if (tab == "history") loadHistory() else render()
            } catch (e: Exception) {
                llBody.removeAllViews()
                llBody.addView(emptyNote("Нет связи: ${e.message?.take(50)}"))
            } finally {
                loading = false
            }
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            try {
                val resp = api.history(limit = 100, sessionId = me?.session?.id)
                history = resp.tasks ?: emptyList()
                render()
            } catch (_: Exception) {}
        }
    }

    // ─── Render ──────────────────────────────────────────────────────────────

    private fun render() {
        styleTab(tabTasks, tab == "tasks",
            "Задания" + (me?.tasks?.size?.takeIf { it > 0 }?.let { " ($it)" } ?: ""))
        styleTab(tabHistory, tab == "history",
            "История" + (history.size.takeIf { it > 0 }?.let { " ($it)" } ?: ""))
        llBody.removeAllViews()
        if (tab == "tasks") renderTasks() else renderHistory()
    }

    private fun styleTab(btn: TextView, active: Boolean, label: String) {
        btn.text = label
        btn.setTextColor(if (active) WHITE else MUTED)
        btn.background = rounded(if (active) BLUE else Color.parseColor("#F3F4F6"), 10)
    }

    private fun renderTasks() {
        val m = me
        if (m == null) { llBody.addView(emptyNote("Загрузка…")); return }

        if (!m.in_session) {
            // Не в сессии — большая синяя кнопка, как в waybills
            val card = card()
            card.addView(TextView(this).apply {
                text = "Сессия не начата"
                textSize = 15f; setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(TEXT)
            })
            card.addView(TextView(this).apply {
                text = "Начните сессию — задания раздаются автоматически"
                textSize = 12f; gravity = Gravity.CENTER
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, dp(12))
            })
            card.addView(bigButton("Начать сессию", BLUE) { startSession() })
            llBody.addView(card)
            return
        }

        val cnt = m.active_sessions_count ?: 1
        llBody.addView(TextView(this).apply {
            text = "Сборщиков на смене: $cnt"
            textSize = 12f; setTextColor(MUTED)
            setPadding(dp(4), 0, 0, dp(10))
        })

        val tasks = m.tasks ?: emptyList()
        if (tasks.isEmpty()) {
            val empty = card(dashed = true)
            empty.addView(TextView(this).apply {
                text = "Заданий пока нет"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(FAINT)
                setPadding(0, dp(20), 0, dp(20))
            })
            llBody.addView(empty)
        }
        tasks.forEach { t ->
            llBody.addView(taskCard(t))
            llBody.addView(spacer(dp(8)))
        }

        llBody.addView(spacer(dp(12)))
        llBody.addView(bigButton("Завершить сессию", RED) { endSession() })
    }

    /** Карточка задания как TaskCard в waybills: чип, имя, N заказов, прогресс. */
    private fun taskCard(t: PickerTask): View {
        val isDone = t.scanned_qty >= t.total_orders
        val inProgress = t.scanned_qty > 0 && !isDone
        val (letter, chipBg, chipFg) = catChip(t.task_type)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBorder(WHITE,
                if (isDone) Color.parseColor("#BBF7D0")
                else if (inProgress) Color.parseColor("#FDE047") else BORDER, 16)
            alpha = if (isDone) 0.7f else 1f
            setPadding(dp(14), dp(13), dp(14), dp(13))
            setOnClickListener {
                startActivity(
                    Intent(this@PickerTasksActivity, PickerTaskActivity::class.java)
                        .putExtra("task_id", t.id)
                )
            }
        }
        row.addView(TextView(this).apply {
            text = letter
            textSize = 12f; setTypeface(null, Typeface.BOLD)
            setTextColor(chipFg)
            background = rounded(chipBg, 8)
            setPadding(dp(9), dp(4), dp(9), dp(4))
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(10); rightMargin = dp(8) }
        }
        col.addView(TextView(this).apply {
            text = "#${t.id} · ${catLabel(t.task_type)}"
            textSize = 10f; setTextColor(FAINT)
        })
        col.addView(TextView(this).apply {
            text = t.product_name ?: "—"
            textSize = 14f; maxLines = 2
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
        })
        col.addView(TextView(this).apply {
            val n = t.total_orders
            text = "$n заказ" + if (n == 1) "" else if (n < 5) "а" else "ов"
            textSize = 11f; setTextColor(MUTED)
        })
        if (inProgress || isDone) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = t.total_orders.coerceAtLeast(1)
                progress = t.scanned_qty
                progressTintList = android.content.res.ColorStateList.valueOf(
                    if (isDone) GREEN else YELLOW
                )
            }
            col.addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(5)
            ).apply { topMargin = dp(6) })
        }
        row.addView(col)
        row.addView(TextView(this).apply {
            text = "${t.scanned_qty}/${t.total_orders}"
            textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(BLUE)
        })
        return row
    }

    private fun renderHistory() {
        // Поиск как в waybills
        val et = EditText(this).apply {
            hint = "Поиск по названию или номеру заказа…"
            setText(historySearch)
            textSize = 13f
            setTextColor(TEXT); setHintTextColor(FAINT)
            background = roundedBorder(WHITE, BORDER, 12)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    historySearch = s?.toString() ?: ""
                    renderHistoryList()
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        llBody.addView(et)
        llBody.addView(spacer(dp(10)))
        llHistoryList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        llBody.addView(llHistoryList)
        renderHistoryList()
    }

    private var llHistoryList: LinearLayout? = null

    private fun renderHistoryList() {
        val list = llHistoryList ?: return
        list.removeAllViews()
        val q = historySearch.trim().lowercase()
        val filtered = if (q.isEmpty()) history else history.filter { t ->
            (t.product_name ?: "").lowercase().contains(q) ||
                t.orders.orEmpty().any { it.order_code.contains(q, ignoreCase = true) }
        }
        if (filtered.isEmpty()) {
            val empty = card(dashed = true)
            empty.addView(TextView(this).apply {
                text = if (q.isNotEmpty()) "Ничего не найдено" else "Завершённых заданий пока нет"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(FAINT)
                setPadding(0, dp(20), 0, dp(20))
            })
            list.addView(empty)
            return
        }
        filtered.forEach { t ->
            list.addView(historyRow(t))
            list.addView(spacer(dp(8)))
        }
    }

    private fun historyRow(t: PickerTask): View {
        val (letter, chipBg, chipFg) = catChip(t.task_type)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBorder(WHITE, BORDER, 16)
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        row.addView(TextView(this).apply {
            text = letter
            textSize = 11f; setTypeface(null, Typeface.BOLD)
            setTextColor(chipFg)
            background = rounded(chipBg, 6)
            setPadding(dp(7), dp(2), dp(7), dp(2))
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(10) }
        }
        col.addView(TextView(this).apply {
            text = t.product_name ?: "—"
            textSize = 13f; maxLines = 2
            setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
        })
        val codes = t.orders.orEmpty().map { it.order_code }.distinct()
        col.addView(TextView(this).apply {
            text = codes.take(2).joinToString(", ") +
                if (codes.size > 2) " +${codes.size - 2}" else ""
            textSize = 11f; setTextColor(MUTED)
            setPadding(0, dp(2), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = "#${t.id} · ${t.total_orders} поз." +
                (fmtTime(t.completed_at)?.let { " · $it" } ?: "")
            textSize = 11f; setTextColor(FAINT)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        if (t.pdf_filename != null) {
            row.addView(Button(this).apply {
                text = if (reprintingId == t.id) "…" else "Печать"
                textSize = 12f; isAllCaps = false
                isEnabled = reprintingId != t.id
                setTextColor(Color.parseColor("#374151"))
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#F3F4F6"))
                setOnClickListener { reprint(t.id) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)
            ).apply { leftMargin = dp(8); gravity = Gravity.CENTER_VERTICAL })
        }
        return row
    }

    // ─── Actions ─────────────────────────────────────────────────────────────

    private fun startSession() {
        val storeId = CoreAuth.storeId(this)
        val city = CoreAuth.city(this) ?: return
        lifecycleScope.launch {
            try {
                val r = api.startSession(storeId, city)
                toast("Сессия начата · заданий: ${r.assigned ?: 0}")
                load()
            } catch (e: Exception) { toast("Ошибка: ${e.message?.take(50)}") }
        }
    }

    private fun endSession() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Завершить сессию?")
            .setMessage("Незапущенные задания вернутся в очередь.")
            .setPositiveButton("Завершить") { _, _ ->
                lifecycleScope.launch {
                    try {
                        api.endSession()
                        toast("Сессия завершена")
                        load()
                    } catch (e: Exception) { toast("Ошибка: ${e.message?.take(50)}") }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun reprint(taskId: Int) {
        if (reprintingId != null) return
        reprintingId = taskId
        renderHistoryList()
        lifecycleScope.launch {
            try {
                api.reprint(taskId)
                toast("Накладная отправлена на принт-станцию")
            } catch (e: Exception) {
                toast("Ошибка печати: ${e.message?.take(60)}")
            } finally {
                reprintingId = null
                renderHistoryList()
            }
        }
    }

    private fun changeStoreOrCity() {
        lifecycleScope.launch {
            val stores = try { api.stores() } catch (_: Exception) { emptyList() }
            val names = stores.map { it.name ?: it.code ?: "Магазин ${it.id}" }
            val cityLocked = CoreAuth.cityLocked(this@PickerTasksActivity)
            val cityKeys = CoreAuth.CITY_NAMES.keys.toList()

            val storePick: (() -> Unit) -> Unit = { next ->
                if (stores.size > 1) {
                    android.app.AlertDialog.Builder(this@PickerTasksActivity)
                        .setTitle("Магазин")
                        .setItems(names.toTypedArray()) { _, i ->
                            CoreAuth.setStore(this@PickerTasksActivity, stores[i].id, names[i])
                            next()
                        }
                        .show()
                } else next()
            }
            storePick {
                if (!cityLocked) {
                    android.app.AlertDialog.Builder(this@PickerTasksActivity)
                        .setTitle("Город сборки")
                        .setItems(CoreAuth.CITY_NAMES.values.toTypedArray()) { _, i ->
                            CoreAuth.setCity(this@PickerTasksActivity, cityKeys[i])
                            renderHeader(); load()
                        }
                        .show()
                } else {
                    renderHeader(); load()
                }
            }
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun fmtTime(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return try {
            val clean = iso.substringBefore(".").replace("Z", "").replace("T", " ")
            val inFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outFmt = SimpleDateFormat("HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Almaty")
            }
            outFmt.format(inFmt.parse(clean)!!)
        } catch (_: Exception) { null }
    }

    private fun card(dashed: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = if (dashed) dashedBorder() else roundedBorder(WHITE, BORDER, 16)
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    private fun bigButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 15f; isAllCaps = false
        setTypeface(null, Typeface.BOLD)
        setTextColor(WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        )
        setOnClickListener { onClick() }
    }

    private fun emptyNote(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f; gravity = Gravity.CENTER
        setTextColor(MUTED)
        setPadding(dp(10), dp(30), dp(10), dp(30))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(bg: Int, r: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(r).toFloat()
        }

    private fun roundedBorder(bg: Int, stroke: Int, r: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(r).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun dashedBorder() =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(WHITE); cornerRadius = dp(16).toFloat()
            setStroke(dp(2), BORDER, dp(6).toFloat(), dp(5).toFloat())
        }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

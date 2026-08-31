package com.otgruzka.tsd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CoreApi
import com.otgruzka.tsd.api.CoreApiClient
import com.otgruzka.tsd.api.ReturnExpectedItem
import com.otgruzka.tsd.api.ReturnReceiving
import com.otgruzka.tsd.api.ReturnScanOrderBody
import com.otgruzka.tsd.api.ReturnScanOrderResponse
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Приёмка отмен/возвратов Kaspi — делает менеджер: скан коробки → экран
 * поштучного скана (ReturnReceiveActivity) → годное в ДХ, брак в БРАК.
 * Дизайн — как у сборщика (waybills): вкладки, pill-табы, карточки.
 * Заказ, не покидавший склад, приёмки не требует — жёлтая карточка
 * «разложите по ячейкам».
 */
class ReturnsActivity : AppCompatActivity() {

    companion object {
        fun kindChip(kind: String?): Triple<String, Int, Int> = when (kind) {
            "cancel_shipped" -> Triple(
                "ОТМЕНА", Color.parseColor("#FEE2E2"), Color.parseColor("#B91C1C")
            )
            else -> Triple(
                "ВОЗВРАТ", Color.parseColor("#FFEDD5"), Color.parseColor("#C2410C")
            )
        }
    }

    private val BG = PickerTasksActivity.BG
    private val WHITE = Color.WHITE
    private val BORDER = PickerTasksActivity.BORDER
    private val TEXT = PickerTasksActivity.TEXT
    private val MUTED = PickerTasksActivity.MUTED
    private val FAINT = PickerTasksActivity.FAINT
    private val BLUE = PickerTasksActivity.BLUE
    private val GREEN = PickerTasksActivity.GREEN
    private val RED = PickerTasksActivity.RED
    private val YELLOW_BG = Color.parseColor("#FEF9C3")
    private val YELLOW_TX = Color.parseColor("#A16207")
    private val RED_BG = Color.parseColor("#FEF2F2")
    private val RED_TX = Color.parseColor("#991B1B")

    private lateinit var api: CoreApi
    private lateinit var tabReceive: TextView
    private lateinit var tabHistory: TextView
    private lateinit var llBody: LinearLayout
    private lateinit var tvScanBuffer: TextView

    private var tab = "receive"
    private var busy = false
    private var myActive: List<ReturnReceiving> = emptyList()
    private var expected: List<ReturnExpectedItem> = emptyList()
    private var history: List<ReturnReceiving> = emptyList()
    private val barcodeBuf = StringBuilder()

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val barcode = intent.getStringExtra("scandata")
                ?: intent.getStringExtra("data")
                ?: intent.getStringExtra("SCAN_RESULT")
                ?: return
            handleBarcode(barcode.trim())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CoreAuth.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        api = CoreApiClient.build(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction("com.android.scanner.broadcast")
            addAction("nlscan.action.SCANNER_RESULT")
            addAction("com.sunmi.scan")
            addAction("com.honeywell.decode.intent.action.EDIT_DATA")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(scanReceiver, filter)
        }
        load()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val ch = event.unicodeChar.toChar()
            when {
                event.keyCode == KeyEvent.KEYCODE_ENTER && barcodeBuf.isNotEmpty() -> {
                    val raw = barcodeBuf.toString().trim()
                    barcodeBuf.clear()
                    if (::tvScanBuffer.isInitialized) tvScanBuffer.text = ""
                    handleBarcode(raw)
                    return true
                }
                ch > ' ' -> {
                    barcodeBuf.append(ch)
                    if (::tvScanBuffer.isInitialized) tvScanBuffer.text = barcodeBuf.toString()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ─── Skeleton ────────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }
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
        val name = CoreAuth.fullName(this) ?: CoreAuth.username(this) ?: ""
        top.addView(TextView(this).apply {
            text = "Приёмка возвратов · $name"
            textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(top)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(WHITE)
            setPadding(dp(16), 0, dp(16), dp(10))
        }
        tabReceive = tabButton("Приёмка") { switchTab("receive") }
        tabHistory = tabButton("История") { switchTab("history") }
        tabs.addView(tabReceive, LinearLayout.LayoutParams(0, dp(38), 1f).apply { rightMargin = dp(8) })
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

    private fun switchTab(t: String) {
        tab = t
        load()
    }

    // ─── Data ────────────────────────────────────────────────────────────────

    private fun load() {
        lifecycleScope.launch {
            try {
                if (tab == "receive") {
                    myActive = api.returnsList(
                        status = "IN_PROGRESS", username = CoreAuth.username(this@ReturnsActivity)
                    ).items ?: emptyList()
                    expected = try { api.returnsExpected() } catch (_: Exception) { emptyList() }
                } else {
                    history = (api.returnsList(pageSize = 50).items ?: emptyList())
                        .filter { it.status != "IN_PROGRESS" }
                }
                render()
            } catch (e: Exception) {
                llBody.removeAllViews()
                llBody.addView(emptyNote(
                    if (e.message?.contains("403") == true)
                        "Нет доступа к приёмке возвратов — обратитесь к администратору"
                    else "Нет связи: ${e.message?.take(50)}"
                ))
            }
        }
    }

    // ─── Scan ────────────────────────────────────────────────────────────────

    private fun handleBarcode(raw: String) {
        if (busy || tab != "receive") return
        val code = raw.replace(Regex("-\\d+$"), "")   // Mindeo добавляет счётчик
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.returnsScanOrder(ReturnScanOrderBody(code))
                onScanResult(res)
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    private fun onScanResult(res: ReturnScanOrderResponse) {
        when (res.result) {
            "OK" -> {
                Beep.ok()
                val id = res.receiving?.id ?: return
                startActivity(
                    Intent(this, ReturnReceiveActivity::class.java)
                        .putExtra("receiving_id", id)
                )
            }
            "NOT_SHIPPED" -> {
                Beep.warn()
                renderPutback(res)
            }
            "ALREADY_RECEIVED" -> {
                Beep.warn()
                showMsg("Заказ уже принят полностью", YELLOW_BG, YELLOW_TX)
            }
            "LOCKED" -> {
                Beep.error()
                showMsg("Приёмку уже ведёт: ${res.holder ?: "другой сотрудник"}", RED_BG, RED_TX)
            }
            "NOT_RETURNABLE" -> {
                Beep.error()
                showMsg(
                    "Заказ не отменён и не возвратный (статус: ${res.order?.kaspi_status ?: "?"})",
                    RED_BG, RED_TX
                )
            }
            else -> {
                Beep.error()
                showMsg("Заказ не найден", RED_BG, RED_TX)
            }
        }
    }

    // ─── Render ──────────────────────────────────────────────────────────────

    private var tvMsg: TextView? = null

    private fun showMsg(text: String, bg: Int, fg: Int) {
        tvMsg?.apply {
            visibility = View.VISIBLE
            this.text = text
            background = rounded(bg, 12)
            setTextColor(fg)
        }
    }

    private fun render() {
        styleTab(tabReceive, tab == "receive", "Приёмка")
        styleTab(tabHistory, tab == "history", "История" +
            (history.size.takeIf { it > 0 && tab == "history" }?.let { " ($it)" } ?: ""))
        llBody.removeAllViews()
        if (tab == "receive") renderReceive() else renderHistory()
    }

    private fun styleTab(btn: TextView, active: Boolean, label: String) {
        btn.text = label
        btn.setTextColor(if (active) WHITE else MUTED)
        btn.background = rounded(if (active) BLUE else Color.parseColor("#F3F4F6"), 10)
    }

    private fun renderReceive() {
        // Приглашение к скану
        val invite = card(dashed = true)
        invite.addView(TextView(this).apply {
            text = "Отсканируйте код заказа\nс коробки возврата"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            setPadding(0, dp(14), 0, dp(4))
        })
        tvScanBuffer = TextView(this).apply {
            textSize = 13f; gravity = Gravity.CENTER
            setTextColor(FAINT)
            hint = "ожидание сканирования…"
            setPadding(0, 0, 0, dp(14))
        }
        invite.addView(tvScanBuffer)
        llBody.addView(invite)
        llBody.addView(spacer(dp(10)))

        tvMsg = TextView(this).apply {
            textSize = 13f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        llBody.addView(tvMsg)
        llBody.addView(spacer(dp(10)))

        // Незавершённые приёмки — продолжить
        if (myActive.isNotEmpty()) {
            llBody.addView(sectionLabel("НЕЗАВЕРШЁННЫЕ ПРИЁМКИ"))
            myActive.forEach { r ->
                llBody.addView(receivingCard(r, clickable = true))
                llBody.addView(spacer(dp(8)))
            }
            llBody.addView(spacer(dp(4)))
        }

        // Возвраты в пути
        llBody.addView(sectionLabel("ВОЗВРАТЫ В ПУТИ / ОЖИДАЮТСЯ"))
        if (expected.isEmpty()) {
            val empty = card(dashed = true)
            empty.addView(TextView(this).apply {
                text = "Kaspi пока не сообщает о возвратах"
                textSize = 12f; gravity = Gravity.CENTER
                setTextColor(FAINT)
                setPadding(0, dp(14), 0, dp(14))
            })
            llBody.addView(empty)
        }
        expected.forEach { e ->
            llBody.addView(expectedRow(e))
            llBody.addView(spacer(dp(6)))
        }
    }

    /** NOT_SHIPPED: жёлтая карточка «что куда разложить». */
    private fun renderPutback(res: ReturnScanOrderResponse) {
        showMsg(
            "Заказ ${res.order?.order_code ?: ""} не покидал склад — приёмка не требуется. " +
                "Разложите товары по ячейкам:",
            YELLOW_BG, YELLOW_TX
        )
        // Вставим список сразу под сообщением
        val idx = llBody.indexOfChild(tvMsg) + 2
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBorder2(YELLOW_BG, Color.parseColor("#FDE047"), 12)
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }
        (res.items ?: emptyList()).forEach { it ->
            card.addView(TextView(this).apply {
                val cells = it.cells.orEmpty()
                text = "• ${it.name ?: "товар"} ×${it.qty ?: "1"}" +
                    (if (cells.isNotEmpty()) "  📍${cells.joinToString(",")}" else "  (ячейка не указана)")
                textSize = 12f; setTextColor(YELLOW_TX)
                setPadding(0, dp(2), 0, 0)
            })
        }
        llBody.addView(card, idx)
    }

    private fun receivingCard(r: ReturnReceiving, clickable: Boolean): View {
        val (label, chipBg, chipFg) = kindChip(r.kind)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBorder(WHITE, Color.parseColor("#FDE047"), 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            if (clickable) setOnClickListener {
                startActivity(
                    Intent(this@ReturnsActivity, ReturnReceiveActivity::class.java)
                        .putExtra("receiving_id", r.id)
                )
            }
        }
        row.addView(chip(label, chipBg, chipFg))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(10); rightMargin = dp(8) }
        }
        col.addView(TextView(this).apply {
            text = r.order_code
            textSize = 14f; setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
        })
        col.addView(TextView(this).apply {
            text = "начата ${fmtTime(r.started_at) ?: ""} · продолжить →"
            textSize = 11f; setTextColor(MUTED)
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = "${r.total_scanned ?: "0"}/${r.total_expected ?: "?"}"
            textSize = 16f; setTypeface(null, Typeface.BOLD)
            setTextColor(BLUE)
        })
        return row
    }

    private fun expectedRow(e: ReturnExpectedItem): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedBorder(WHITE, BORDER, 12)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        val badge = when {
            e.incoming == true -> Triple("ЕДЕТ", Color.parseColor("#DBEAFE"), Color.parseColor("#1D4ED8"))
            else -> Triple("ВОЗВРАТ", Color.parseColor("#FFEDD5"), Color.parseColor("#C2410C"))
        }
        row.addView(chip(badge.first, badge.second, badge.third))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(10) }
        }
        col.addView(TextView(this).apply {
            text = e.order_code ?: "—"
            textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
        })
        col.addView(TextView(this).apply {
            text = (e.customer_name ?: "") +
                (if (e.has_received == true) " · часть уже принята" else "")
            textSize = 11f; setTextColor(MUTED)
        })
        row.addView(col)
        return row
    }

    private fun renderHistory() {
        if (history.isEmpty()) {
            val empty = card(dashed = true)
            empty.addView(TextView(this).apply {
                text = "Принятых возвратов пока нет"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(FAINT)
                setPadding(0, dp(20), 0, dp(20))
            })
            llBody.addView(empty)
            return
        }
        history.forEach { r ->
            val (label, chipBg, chipFg) = kindChip(r.kind)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBorder(WHITE, BORDER, 16)
                alpha = if (r.status == "CANCELLED") 0.6f else 1f
                setPadding(dp(14), dp(11), dp(14), dp(11))
            }
            row.addView(chip(label, chipBg, chipFg))
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { leftMargin = dp(10) }
            }
            col.addView(TextView(this).apply {
                text = r.order_code
                textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(TEXT)
            })
            col.addView(TextView(this).apply {
                text = when (r.status) {
                    "CANCELLED" -> "приёмка отменена"
                    else -> "принято ${r.total_scanned ?: "?"} из ${r.total_expected ?: "?"}" +
                        (if (!r.stock_restore) " · журнальная" else "")
                } + (fmtTime(r.completed_at)?.let { " · $it" } ?: "")
                textSize = 11f; setTextColor(MUTED)
                setPadding(0, dp(2), 0, 0)
            })
            col.addView(TextView(this).apply {
                text = r.username ?: ""
                textSize = 11f; setTextColor(FAINT)
            })
            row.addView(col)
            row.addView(TextView(this).apply {
                text = if (r.status == "CANCELLED") "✕" else "✓"
                textSize = 17f; setTypeface(null, Typeface.BOLD)
                setTextColor(if (r.status == "CANCELLED") RED else GREEN)
            })
            llBody.addView(row)
            llBody.addView(spacer(dp(8)))
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun chip(label: String, bg: Int, fg: Int) = TextView(this).apply {
        text = label
        textSize = 10f; setTypeface(null, Typeface.BOLD)
        setTextColor(fg)
        background = rounded(bg, 6)
        setPadding(dp(7), dp(3), dp(7), dp(3))
    }

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 10f; letterSpacing = 0.08f
        setTypeface(null, Typeface.BOLD)
        setTextColor(MUTED)
        setPadding(dp(2), dp(6), 0, dp(6))
    }

    private fun fmtTime(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return try {
            val clean = iso.substringBefore(".").substringBefore("+").replace("Z", "").replace("T", " ")
            val inFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outFmt = SimpleDateFormat("dd.MM HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Almaty")
            }
            outFmt.format(inFmt.parse(clean)!!)
        } catch (_: Exception) { null }
    }

    private fun card(dashed: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = if (dashed) dashedBorder() else roundedBorder(WHITE, BORDER, 16)
        setPadding(dp(16), dp(6), dp(16), dp(6))
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

    private fun roundedBorder2(bg: Int, stroke: Int, r: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(r).toFloat()
            setStroke(dp(2), stroke)
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

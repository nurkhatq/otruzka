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
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CoreApi
import com.otgruzka.tsd.api.CoreApiClient
import com.otgruzka.tsd.api.ReturnCompleteBody
import com.otgruzka.tsd.api.ReturnDefectLine
import com.otgruzka.tsd.api.ReturnItemScanBody
import com.otgruzka.tsd.api.ReturnLine
import com.otgruzka.tsd.api.ReturnReceiving
import kotlinx.coroutines.launch
import retrofit2.HttpException

/**
 * Экран приёмки одного возврата: «СЕЙЧАС ПРИНИМАЕМ» → скан каждой вещи →
 * «Завершить» с отметкой брака (по умолчанию всё годное). Статус скана решает
 * сервер. Для возврата неполный скан — норма Kaspi («остальное у покупателя»),
 * для отмены — предупреждение. Зеркало PickerTaskActivity по дизайну.
 */
class ReturnReceiveActivity : AppCompatActivity() {

    private val BG = PickerTasksActivity.BG
    private val WHITE = Color.WHITE
    private val BORDER = PickerTasksActivity.BORDER
    private val TEXT = PickerTasksActivity.TEXT
    private val MUTED = PickerTasksActivity.MUTED
    private val FAINT = PickerTasksActivity.FAINT
    private val BLUE = PickerTasksActivity.BLUE
    private val BLUE_L = Color.parseColor("#BFDBFE")
    private val GREEN = Color.parseColor("#16A34A")
    private val GREEN_BG = Color.parseColor("#F0FDF4")
    private val GREEN_BR = Color.parseColor("#86EFAC")
    private val GREEN_TX = Color.parseColor("#166534")
    private val ORANGE = Color.parseColor("#F97316")
    private val ORANGE_BG = Color.parseColor("#FFF7ED")
    private val ORANGE_BR = Color.parseColor("#FED7AA")
    private val ORANGE_TX = Color.parseColor("#C2410C")
    private val RED_BG = Color.parseColor("#FEF2F2")
    private val RED_BR = Color.parseColor("#FCA5A5")
    private val RED_TX = Color.parseColor("#991B1B")
    private val PURPLE = Color.parseColor("#9333EA")
    private val PURPLE_BG = Color.parseColor("#FAF5FF")
    private val PURPLE_BR = Color.parseColor("#D8B4FE")
    private val PURPLE_TX = Color.parseColor("#6B21A8")
    private val YELLOW_TX = Color.parseColor("#A16207")

    private lateinit var api: CoreApi
    private var receivingId = 0
    private var rec: ReturnReceiving? = null
    private var busy = false
    private var completing = false
    private val barcodeBuf = StringBuilder()

    private lateinit var tvTitle: TextView
    private lateinit var tvChip: TextView
    private lateinit var tvCustomer: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvScanBuffer: TextView
    private lateinit var llContent: LinearLayout
    private lateinit var llBottom: LinearLayout

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
        receivingId = intent.getIntExtra("receiving_id", 0)
        if (receivingId == 0 || !CoreAuth.isLoggedIn(this)) { finish(); return }
        api = CoreApiClient.build(this)
        setContentView(buildUi())
        loadReceiving()
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
                    barcodeBuf.clear(); tvScanBuffer.text = ""
                    handleBarcode(raw)
                    return true
                }
                ch > ' ' -> {
                    barcodeBuf.append(ch); tvScanBuffer.text = barcodeBuf.toString()
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
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvTitle = TextView(this).apply {
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
        }
        tvChip = TextView(this).apply {
            textSize = 10f; setTypeface(null, Typeface.BOLD)
            setPadding(dp(7), dp(2), dp(7), dp(2))
        }
        row1.addView(tvTitle)
        row1.addView(tvChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        row1.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row1.addView(TextView(this).apply {
            text = "← Назад"
            textSize = 13f; setTextColor(BLUE)
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setOnClickListener { finish() }
        })
        head.addView(row1)
        tvCustomer = TextView(this).apply {
            textSize = 12f; setTextColor(MUTED)
        }
        head.addView(tvCustomer)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#22C55E"))
        }
        head.addView(progressBar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(8)
        ).apply { topMargin = dp(8) })
        tvProgress = TextView(this).apply {
            textSize = 11f; setTextColor(MUTED)
            setPadding(0, dp(3), 0, 0)
        }
        head.addView(tvProgress)
        tvScanBuffer = TextView(this).apply {
            textSize = 11f; setTextColor(FAINT)
        }
        head.addView(tvScanBuffer)
        root.addView(head)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0)
                .apply { weight = 1f }
            isFillViewport = true
        }
        llContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(llContent)
        root.addView(scroll)

        llBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(6), dp(16), dp(8))
        }
        llBottom.addView(TextView(this).apply {
            text = "Отменить приёмку"
            textSize = 12f; gravity = Gravity.CENTER
            setTextColor(FAINT)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { confirmCancel() }
        })
        root.addView(llBottom)
        return root
    }

    // ─── Data ────────────────────────────────────────────────────────────────

    private fun loadReceiving() {
        lifecycleScope.launch {
            try {
                onReceiving(api.returnsGet(receivingId))
            } catch (e: Exception) {
                toast("Ошибка загрузки: ${e.message?.take(60)}")
                finish()
            }
        }
    }

    private fun onReceiving(r: ReturnReceiving) {
        rec = r
        render()
    }

    // ─── Числа-строки ядра («2», «1.5») ─────────────────────────────────────

    private fun n(s: String?): Double = s?.toDoubleOrNull() ?: 0.0
    private fun fmtN(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun expected(l: ReturnLine) = n(l.qty_expected)
    private fun scanned(l: ReturnLine) = n(l.qty_scanned)
    private fun nextPending(): ReturnLine? =
        rec?.lines.orEmpty().firstOrNull { scanned(it) < expected(it) }
    private fun allDone(): Boolean = nextPending() == null
    private fun anyScanned(): Boolean = rec?.lines.orEmpty().any { scanned(it) > 0 }

    // ─── Scan ────────────────────────────────────────────────────────────────

    private fun handleBarcode(raw: String) {
        if (busy || completing || rec == null) return
        val code = raw.replace(Regex("-\\d+$"), "")   // Mindeo добавляет счётчик
        scanItem(code, null)
    }

    private fun scanItem(barcode: String, productId: Long?) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.returnsScanItem(
                    receivingId, ReturnItemScanBody(barcode, productId)
                )
                res.receiving?.let { rec = it }
                render()
                when (res.result) {
                    "MATCHED" -> { Beep.ok(); showMsg("✓ Принято", GREEN_BG, GREEN_TX) }
                    "EXCESS" -> {
                        Beep.warn()
                        showMsg("Уже принято нужное количество", Color.parseColor("#FEF9C3"), YELLOW_TX)
                    }
                    "UNEXPECTED" -> {
                        Beep.error()
                        showMsg("Товар не из этого заказа", RED_BG, RED_TX)
                    }
                    "UNKNOWN" -> {
                        Beep.error()
                        showMsg("Штрихкод не найден в системе", RED_BG, RED_TX)
                    }
                    "AMBIGUOUS" -> {
                        Beep.warn()
                        askCandidate(barcode, res.candidates.orEmpty()
                            .map { it.product_id to (it.name ?: "товар #${it.product_id}") })
                    }
                }
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    /** Один ШК подошёл к двум товарам заказа — уточняем у менеджера. */
    private fun askCandidate(barcode: String, candidates: List<Pair<Long, String>>) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Какой это товар?")
            .setItems(candidates.map { it.second }.toTypedArray()) { _, i ->
                scanItem(barcode, candidates[i].first)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Complete ────────────────────────────────────────────────────────────

    /** «Завершить» → диалог брака (по умолчанию всё годное) → complete. */
    private fun startComplete() {
        val r = rec ?: return
        val scannedLines = r.lines.orEmpty().filter { scanned(it) > 0 }
        if (scannedLines.isEmpty()) { toast("Ничего не отсканировано"); return }

        if (!allDone()) {
            // Неполный скан: возврат — норма Kaspi, отмена — предупреждение
            val missing = r.lines.orEmpty().sumOf { expected(it) - scanned(it) }
            val isReturn = r.kind != "cancel_shipped"
            android.app.AlertDialog.Builder(this)
                .setTitle(if (isReturn) "Принята часть заказа" else "⚠ Не всё отсканировано")
                .setMessage(
                    if (isReturn)
                        "Принято ${r.total_scanned} из ${r.total_expected}. Остальное " +
                            "осталось у покупателя — для возвратов Kaspi это нормально. Завершить?"
                    else
                        "Отменённый заказ должен вернуться целиком, но ${fmtN(missing)} поз. " +
                            "не отсканировано. Проверьте коробку ещё раз! Завершить с нехваткой?"
                )
                .setPositiveButton("Завершить") { _, _ -> askDefects(scannedLines, force = true) }
                .setNegativeButton("Отмена", null)
                .show()
        } else {
            askDefects(scannedLines, force = false)
        }
    }

    /** Диалог брака: по строке степпер «из них брак: 0». Ноль лишних действий,
     *  если брака нет. */
    private fun askDefects(lines: List<ReturnLine>, force: Boolean) {
        val defects = HashMap<Int, Int>()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val counters = HashMap<Int, TextView>()
        lines.forEach { l ->
            defects[l.line_id] = 0
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = l.name ?: l.offer_code ?: "—"
                textSize = 13f; maxLines = 2
                setTextColor(TEXT)
            })
            col.addView(TextView(this).apply {
                text = "принято ${l.qty_scanned ?: "0"} шт · из них брак:"
                textSize = 11f; setTextColor(MUTED)
            })
            row.addView(col)
            row.addView(TextView(this).apply {
                text = "−"; textSize = 20f; setTextColor(RED_TX)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener {
                    val v = (defects[l.line_id] ?: 0) - 1
                    defects[l.line_id] = maxOf(0, v)
                    counters[l.line_id]?.text = defects[l.line_id].toString()
                }
            })
            val counter = TextView(this).apply {
                text = "0"
                textSize = 16f; setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(TEXT)
                minWidth = dp(30)
            }
            counters[l.line_id] = counter
            row.addView(counter)
            row.addView(TextView(this).apply {
                text = "+"; textSize = 20f; setTextColor(RED_TX)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                setOnClickListener {
                    val cap = scanned(l).toInt()
                    val v = (defects[l.line_id] ?: 0) + 1
                    defects[l.line_id] = minOf(cap, v)
                    counters[l.line_id]?.text = defects[l.line_id].toString()
                }
            })
            container.addView(row)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Брак есть?")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Завершить приёмку") { _, _ ->
                doComplete(force, defects.filterValues { it > 0 })
            }
            .setNegativeButton("Назад", null)
            .show()
    }

    private fun doComplete(force: Boolean, defects: Map<Int, Int>) {
        if (completing) return
        completing = true
        render()
        lifecycleScope.launch {
            try {
                val res = api.returnsComplete(
                    receivingId,
                    ReturnCompleteBody(
                        force = force,
                        defects = defects.map { (id, q) -> ReturnDefectLine(id, q.toString()) }
                    )
                )
                val msg = if (res.stock_restored == true) {
                    "Принято. Годное: ${res.good_qty} → ДХ" +
                        (if (n(res.defect_qty) > 0) ", брак: ${res.defect_qty} → зона брака" else "") +
                        (res.document_number?.let { " ($it)" } ?: "")
                } else {
                    "Принято журнально — остатки не двигались (заказ отгружался до складского учёта)"
                }
                Toast.makeText(this@ReturnReceiveActivity, msg, Toast.LENGTH_LONG).show()
                Beep.ok()
                finish()
            } catch (e: HttpException) {
                completing = false
                if (e.code() == 409) {
                    // Бэкстоп: сервер увидел неполный скан — покажем диалог заново
                    startComplete()
                } else {
                    toast("Ошибка завершения: HTTP ${e.code()}")
                }
                render()
            } catch (e: Exception) {
                completing = false
                toast("Ошибка завершения: ${e.message?.take(60)}")
                render()
            }
        }
    }

    private fun confirmCancel() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Отменить приёмку?")
            .setMessage("Сканы этой приёмки будут отброшены, заказ освободится.")
            .setPositiveButton("Отменить приёмку") { _, _ ->
                lifecycleScope.launch {
                    try {
                        api.returnsCancel(receivingId)
                        toast("Приёмка отменена")
                        finish()
                    } catch (e: Exception) { toast("Ошибка: ${e.message?.take(60)}") }
                }
            }
            .setNegativeButton("Назад", null)
            .show()
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
        val r = rec ?: return
        val (label, chipBg, chipFg) = ReturnsActivity.kindChip(r.kind)
        tvTitle.text = "Приёмка ${r.order_code}"
        tvChip.text = label
        tvChip.setTextColor(chipFg)
        tvChip.background = rounded(chipBg, 6)
        tvCustomer.text = if (r.stock_restore) "Годное → ДХ, брак → зона брака"
            else "Журнальная приёмка — остатки не двигаются"
        val te = n(r.total_expected)
        val ts = n(r.total_scanned)
        progressBar.max = (te.toInt()).coerceAtLeast(1)
        progressBar.progress = ts.toInt()
        tvProgress.text = "${fmtN(ts)} / ${fmtN(te)} шт"

        llContent.removeAllViews()

        // Журнальный баннер (заказ отгружался до складского учёта)
        if (!r.stock_restore) {
            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBorder2(PURPLE_BG, PURPLE_BR, 12)
                setPadding(dp(12), dp(9), dp(12), dp(10))
            }
            banner.addView(TextView(this).apply {
                text = "Заказ отгружался до складского учёта — приёмка журнальная, " +
                    "разложите товар по полкам вручную"
                textSize = 11f; setTextColor(PURPLE_TX)
            })
            llContent.addView(banner)
            llContent.addView(spacer(dp(10)))
        }

        tvMsg = TextView(this).apply {
            textSize = 14f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        llContent.addView(tvMsg)
        llContent.addView(spacer(dp(10)))

        val done = allDone()
        if (!done) renderCurrent()

        // Все приняты — зелёная панель
        if (done) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBorder2(GREEN_BG, GREEN_BR, 16)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            card.addView(TextView(this).apply {
                text = "Все позиции отсканированы!"
                textSize = 17f; setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(GREEN_TX)
            })
            card.addView(TextView(this).apply {
                text = "${fmtN(ts)} из ${fmtN(te)}"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(GREEN)
                setPadding(0, dp(2), 0, dp(12))
            })
            card.addView(Button(this).apply {
                text = if (completing) "Завершаем…" else "Завершить приёмку"
                textSize = 15f; isAllCaps = false
                setTypeface(null, Typeface.BOLD)
                setTextColor(WHITE)
                isEnabled = !completing
                backgroundTintList = android.content.res.ColorStateList.valueOf(GREEN)
                setOnClickListener { startComplete() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
            llContent.addView(card)
            llContent.addView(spacer(dp(12)))
        }

        // Частичное завершение
        if (!done && anyScanned()) {
            val isReturn = r.kind != "cancel_shipped"
            val strip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBorder2(ORANGE_BG, ORANGE_BR, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = if (isReturn) "В коробке была часть заказа?" else "В коробке не всё?"
                textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(ORANGE_TX)
            })
            col.addView(TextView(this).apply {
                text = if (isReturn) "Для возвратов Kaspi это нормально"
                    else "Отмена должна вернуться целиком"
                textSize = 11f; setTextColor(ORANGE)
            })
            strip.addView(col)
            strip.addView(Button(this).apply {
                text = "Завершить"
                textSize = 12f; isAllCaps = false
                setTypeface(null, Typeface.BOLD)
                setTextColor(WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ORANGE)
                setOnClickListener { startComplete() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)))
            llContent.addView(strip)
            llContent.addView(spacer(dp(12)))
        }

        // Список позиций
        llContent.addView(TextView(this).apply {
            text = "СОСТАВ ВОЗВРАТА"
            textSize = 10f; letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(MUTED)
            setPadding(dp(2), 0, 0, dp(6))
        })
        renderLines(r)

        llBottom.visibility = if (r.status == "IN_PROGRESS") View.VISIBLE else View.GONE
    }

    /** «СЕЙЧАС ПРИНИМАЕМ» — синяя карточка как у сборщика. */
    private fun renderCurrent() {
        val cur = nextPending() ?: return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBorder2(WHITE, BLUE_L, 16)
            setPadding(dp(14), dp(11), dp(14), dp(13))
        }
        card.addView(TextView(this).apply {
            text = "СЕЙЧАС ПРИНИМАЕМ"
            textSize = 10f; letterSpacing = 0.06f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BLUE)
        })
        card.addView(TextView(this).apply {
            text = cur.name ?: cur.offer_code ?: "—"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
            setPadding(0, dp(3), 0, 0)
        })
        if (expected(cur) > 1) {
            card.addView(TextView(this).apply {
                text = "Принято ${cur.qty_scanned ?: "0"} / ${cur.qty_expected} шт"
                textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(BLUE)
                setPadding(0, dp(3), 0, 0)
            })
        }
        val bcs = cur.barcodes.orEmpty()
        card.addView(TextView(this).apply {
            text = if (bcs.isNotEmpty()) "ШК: ${bcs.take(2).joinToString(", ")}"
                else "Штрихкод не задан — скан запишется как неизвестный"
            textSize = 11f
            setTextColor(if (bcs.isNotEmpty()) FAINT else ORANGE)
            setPadding(0, dp(2), 0, 0)
        })
        addImagesRow(card, cur.images.orEmpty().take(4), 130)
        llContent.addView(card)
        llContent.addView(spacer(dp(10)))
    }

    private fun renderLines(r: ReturnReceiving) {
        val next = nextPending()
        r.lines.orEmpty().forEach { l ->
            val sc = scanned(l)
            val exp = expected(l)
            val lineDone = sc >= exp
            val isCurrent = next != null && l.line_id == next.line_id
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isCurrent)
                    roundedBorder2(WHITE, BLUE, 12)
                else
                    roundedBorder(WHITE, Color.parseColor("#F3F4F6"), 12)
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            row.addView(TextView(this).apply {
                text = when {
                    lineDone -> "✓"
                    sc > 0 -> "●"
                    else -> "○"
                }
                textSize = 16f; setTypeface(null, Typeface.BOLD)
                setTextColor(when {
                    lineDone -> Color.parseColor("#22C55E")
                    sc > 0 -> BLUE
                    else -> Color.parseColor("#E5E7EB")
                })
                setPadding(0, 0, dp(10), 0)
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = l.name ?: l.offer_code ?: "—"
                textSize = 13f; maxLines = 2
                setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(TEXT)
            })
            col.addView(TextView(this).apply {
                text = (l.main_sku ?: l.offer_code ?: "") +
                    (if (exp > 1) " · ${fmtN(sc)}/${fmtN(exp)} шт" else "")
                textSize = 11f; setTextColor(FAINT)
                setPadding(0, dp(1), 0, 0)
            })
            row.addView(col)
            row.addView(TextView(this).apply {
                text = when {
                    lineDone -> "Принято"
                    sc > 0 -> "${fmtN(sc)}/${fmtN(exp)}"
                    isCurrent -> "← текущий"
                    else -> "Ожидает"
                }
                textSize = 11f; setTypeface(null, Typeface.BOLD)
                setTextColor(when {
                    lineDone -> Color.parseColor("#16A34A")
                    sc > 0 || isCurrent -> BLUE
                    else -> Color.parseColor("#D1D5DB")
                })
            })
            llContent.addView(row)
            llContent.addView(spacer(dp(6)))
        }
    }

    private fun addImagesRow(card: LinearLayout, images: List<String>, sizeDp: Int) {
        if (images.isEmpty()) return
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        images.forEach { url ->
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                background = rounded(Color.parseColor("#F9FAFB"), 12)
                layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
                    .apply { rightMargin = dp(8); topMargin = dp(8) }
            }
            ImageLoader.load(url, iv)
            row.addView(iv)
        }
        hs.addView(row)
        card.addView(hs)
    }

    // ─── Small helpers ───────────────────────────────────────────────────────

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

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

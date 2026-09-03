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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CoreApi
import com.otgruzka.tsd.api.CoreApiClient
import com.otgruzka.tsd.api.PwOpenBody
import com.otgruzka.tsd.api.PwScanBody
import com.otgruzka.tsd.api.PwState
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Раскладка товара по ячейкам.
 *
 * Скан полки → сканы товара → «ГОТОВО»: накопленное уезжает ОДНИМ документом
 * перемещения, товар едет со своей партией и себестоимостью.
 *
 * Почему это не инвентаризация: пересчёт приводит остаток ячейки к
 * посчитанному, то есть оприходовал бы товар излишком из воздуха, а в общей
 * «ДХ» он остался бы числиться. Раскладка ничего не создаёт — только переносит.
 */
class PutawayActivity : AppCompatActivity() {

    private val BG = PickerTasksActivity.BG
    private val WHITE = Color.WHITE
    private val BORDER = PickerTasksActivity.BORDER
    private val TEXT = PickerTasksActivity.TEXT
    private val MUTED = PickerTasksActivity.MUTED
    private val FAINT = PickerTasksActivity.FAINT
    private val BLUE = PickerTasksActivity.BLUE
    private val BLUE_BG = Color.parseColor("#EFF6FF")
    private val GREEN = Color.parseColor("#16A34A")
    private val ORANGE_BG = Color.parseColor("#FFF7ED")
    private val ORANGE_TX = Color.parseColor("#C2410C")
    private val RED_BG = Color.parseColor("#FEF2F2")
    private val RED_TX = Color.parseColor("#991B1B")

    private lateinit var api: CoreApi
    private var state: PwState? = null
    private var busy = false
    private var countByPick = true
    private var pendingCode: String? = null

    private val barcodeBuf = StringBuilder()

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvTotals: TextView
    private lateinit var tvMode: TextView
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
        if (!CoreAuth.isLoggedIn(this)) { finish(); return }
        api = CoreApiClient.build(this)
        setContentView(buildUi())
        render()
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

    // ─── Каркас ──────────────────────────────────────────────────────────────

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
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tvTitle = TextView(this).apply {
            textSize = 19f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
        }
        row.addView(tvTitle)
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(TextView(this).apply {
            text = "← Назад"
            textSize = 13f; setTextColor(BLUE)
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        })
        head.addView(row)
        tvSubtitle = TextView(this).apply { textSize = 12f; setTextColor(MUTED) }
        head.addView(tvSubtitle)
        tvTotals = TextView(this).apply {
            textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
            setPadding(0, dp(4), 0, 0)
        }
        head.addView(tvTotals)
        tvMode = TextView(this).apply {
            textSize = 12f; setTextColor(BLUE)
            setPadding(0, dp(4), 0, 0)
            setOnClickListener { countByPick = !countByPick; pendingCode = null; render() }
        }
        head.addView(tvMode)
        tvScanBuffer = TextView(this).apply { textSize = 11f; setTextColor(FAINT) }
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
            setPadding(dp(16), dp(8), dp(16), dp(10))
        }
        root.addView(llBottom)
        return root
    }

    // ─── Сканы ───────────────────────────────────────────────────────────────

    private fun n(s: String?): Double = s?.toDoubleOrNull() ?: 0.0
    private fun fmtN(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    private fun handleBarcode(raw: String) {
        if (busy) return
        val code = raw.trim()
        if (code.isEmpty()) return
        if (state == null || code.startsWith("CELL-", ignoreCase = true)) {
            openCell(code)
            return
        }
        if (!countByPick && pendingCode == null) {
            pendingCode = code; Beep.ok(); render(); return
        }
        scanItem(code, null, "1")
    }

    private fun openCell(barcode: String) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.pwOpen(PwOpenBody(barcode = barcode))
                if (res.result == "OK") {
                    state = res.state
                    Beep.ok()
                    render()
                    res.state?.counting_by?.let {
                        showMsg("Ячейку сейчас считает $it — товар учтётся", ORANGE_BG, ORANGE_TX)
                    }
                } else {
                    Beep.error()
                    render()
                    showMsg("Ячейка не найдена — это точно её штрихкод?", RED_BG, RED_TX)
                }
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    private fun scanItem(code: String, productId: Long?, qty: String, unitCost: String? = null) {
        val cell = state?.cell ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.pwScan(
                    cell.id,
                    PwScanBody(code, qty, productId, UUID.randomUUID().toString(), unitCost),
                )
                state = res.state ?: state
                pendingCode = null
                render()
                when (res.result) {
                    "OK" -> {
                        val over = res.surplus?.toDoubleOrNull() ?: 0.0
                        if (over > 0) {
                            // Нашли больше, чем числится — кладём и приходуем
                            Beep.warn()
                            showMsg(
                                "Сверх учёта: ${fmtN(over)} шт — оформлю излишком",
                                ORANGE_BG, ORANGE_TX,
                            )
                        } else Beep.ok()
                    }
                    "AMBIGUOUS" -> {
                        Beep.warn()
                        askCandidate(code, res.candidates.orEmpty().map {
                            it.product_id to (it.name ?: it.main_sku ?: "товар ${it.product_id}")
                        }, qty)
                    }
                    "UNKNOWN" -> {
                        Beep.error()
                        showMsg(
                            "Товар не найден в каталоге. Отложите и позовите менеджера",
                            RED_BG, RED_TX,
                        )
                    }
                    "NO_COST" -> {
                        // Товара нет в системе вообще — но он в руках у человека.
                        // Спрашиваем закупочную цену, а не отказываем.
                        Beep.warn()
                        askPrice(code, productId, qty)
                    }
                    "DUPLICATE" -> Beep.warn()
                }
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    private fun undo() {
        val cell = state?.cell ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.pwUndo(cell.id)
                state = res.state ?: state
                render()
                if (res.result == "NOTHING") {
                    Beep.warn(); showMsg("Откатывать нечего", ORANGE_BG, ORANGE_TX)
                } else Beep.ok()
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    private fun commit() {
        val cell = state?.cell ?: return
        if (busy) return
        if (state?.pending.orEmpty().isEmpty()) {
            Beep.warn(); showMsg("Сначала отсканируйте товар", ORANGE_BG, ORANGE_TX); return
        }
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.pwCommit(cell.id)
                Beep.ok()
                state = null
                pendingCode = null
                render()
                val over = res.surplus_qty?.toDoubleOrNull() ?: 0.0
                toast(
                    buildString {
                        append("Разложено: ${res.moved_qty ?: "0"} шт")
                        res.document_number?.let { append(" · $it") }
                        if (over > 0) append("\nИзлишек: ${fmtN(over)} шт · ${res.surplus_document_number}")
                    }
                )
            } catch (e: Exception) {
                Beep.error()
                showMsg("Не удалось разложить: ${e.message?.take(70)}", RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    private fun askCandidate(code: String, candidates: List<Pair<Long, String>>, qty: String) {
        if (candidates.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Какой это товар?")
            .setItems(candidates.map { it.second }.toTypedArray()) { _, i ->
                scanItem(code, candidates[i].first, qty)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askPrice(code: String, productId: Long?, qty: String) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "цена за штуку, ₸"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Новый товар на складе")
            .setMessage(
                "Этого товара в системе нет. Укажите закупочную цену за штуку — " +
                    "без неё он ляжет на склад по нулю и продастся себе в убыток."
            )
            .setView(input)
            .setPositiveButton("Положить") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) scanItem(code, productId, qty, v)
                else showMsg("Без цены положить нельзя", ORANGE_BG, ORANGE_TX)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askQty(productId: Long, name: String?) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(name ?: "Сколько кладём?")
            .setView(input)
            .setPositiveButton("Добавить") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) scanItem("", productId, v)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Отрисовка ───────────────────────────────────────────────────────────

    private fun render() {
        val st = state
        llContent.removeAllViews()
        llBottom.removeAllViews()

        if (st?.cell == null) {
            tvTitle.text = "Сканируй ячейку"
            tvSubtitle.text = "Куда кладём товар"
            tvTotals.text = ""
            tvMode.text = ""
            llContent.addView(TextView(this).apply {
                text = "Полка не выбрана.\n\nОтсканируй штрихкод полки, потом товар — " +
                    "он переедет сюда вместе со своей себестоимостью."
                textSize = 14f; setTextColor(MUTED)
            })
            return
        }

        val zone = when (st.cell.zone) {
            "picking" -> "МХ"
            "reserve" -> "ДХ"
            "defect" -> "Брак"
            else -> st.cell.zone ?: ""
        }
        tvTitle.text = st.cell.code
        tvSubtitle.text = buildString {
            append("зона $zone")
            st.counting_by?.let { append(" · считает $it") }
        }
        val pending = st.pending.orEmpty()
        val units = pending.sumOf { n(it.qty) }
        tvTotals.text = "кладём: ${pending.size} позиц. · ${fmtN(units)} шт"
        tvMode.text = if (countByPick) "Режим: пик = +1 (сменить)" else
            if (pendingCode == null) "Режим: скан + количество (сменить)"
            else "Отсканирован $pendingCode: введи количество"

        if (pending.isEmpty()) {
            llContent.addView(TextView(this).apply {
                text = "Сканируй товар — каждый пик кладёт штуку на эту полку."
                textSize = 14f; setTextColor(MUTED)
            })
        }
        for (r in pending) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBorder(WHITE, BORDER, 14)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { askQty(r.product_id, r.name) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = r.name ?: "—"
                textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
            })
            info.addView(TextView(this).apply {
                text = r.sku ?: ""
                textSize = 12f; setTextColor(MUTED)
            })
            card.addView(info)
            card.addView(TextView(this).apply {
                text = fmtN(n(r.qty))
                textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(GREEN)
                setPadding(dp(10), 0, dp(6), 0)
            })
            llContent.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }

        // Что уже лежит на полке по учёту — чтобы не искать это в другом месте
        val shelf = st.on_shelf.orEmpty()
        if (shelf.isNotEmpty()) {
            llContent.addView(TextView(this).apply {
                text = "УЖЕ НА ПОЛКЕ"
                textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(FAINT)
                setPadding(dp(2), dp(14), 0, dp(6))
            })
            for (r in shelf) {
                llContent.addView(TextView(this).apply {
                    text = "${r.name ?: "—"} · ${fmtN(n(r.qty))} шт"
                    textSize = 13f; setTextColor(MUTED)
                    background = rounded(BLUE_BG, 10)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) })
            }
        }

        llBottom.addView(TextView(this).apply {
            text = "ГОТОВО — РАЗЛОЖИТЬ"
            textSize = 17f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(GREEN, 14)
            setPadding(0, dp(15), 0, dp(15))
            setOnClickListener { commit() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = "Отменить последний скан"
            textSize = 13f; setTextColor(BLUE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { undo() }
        })
        row.addView(TextView(this).apply {
            text = "Другая полка"
            textSize = 13f; setTextColor(FAINT)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener {
                if (state?.pending.orEmpty().isNotEmpty()) {
                    Beep.warn()
                    showMsg("Сначала «Готово» — иначе набранное потеряется", ORANGE_BG, ORANGE_TX)
                } else { state = null; render() }
            }
        })
        llBottom.addView(row)
    }

    private fun showMsg(text: String, bg: Int, fg: Int) {
        llContent.removeAllViews()
        llContent.addView(TextView(this).apply {
            this.text = text
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(fg)
            background = rounded(bg, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        })
        llContent.postDelayed({ render() }, 2600)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(bg: Int, r: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(r).toFloat()
        }

    private fun roundedBorder(bg: Int, stroke: Int, r: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(r).toFloat(); setStroke(dp(1), stroke)
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

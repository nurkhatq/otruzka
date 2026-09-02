package com.otgruzka.tsd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.otgruzka.tsd.api.InvClaimBody
import com.otgruzka.tsd.api.InvCompleteBody
import com.otgruzka.tsd.api.InvLine
import com.otgruzka.tsd.api.InvReleaseBody
import com.otgruzka.tsd.api.InvScanBody
import com.otgruzka.tsd.api.InvSession
import com.otgruzka.tsd.api.InvSetQtyBody
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Рабочий экран пересчёта одной ячейки.
 *
 * Поток: сканируешь ШК ячейки → она твоя (второй ТСД увидит «считает Иван») →
 * сканируешь товар, каждый пик = +1 → «ЗАВЕРШИТЬ ЯЧЕЙКУ» → снова «сканируй
 * ячейку». Скан ШК ДРУГОЙ ячейки предлагает закрыть текущую и перейти — так
 * счётчик не тыкает лишние кнопки.
 *
 * В слепом режиме учётный остаток не приходит с сервера вовсе, поэтому подогнать
 * факт под учёт физически нельзя.
 *
 * Оффлайна нет (в приложении нет локальной БД), поэтому каждый скан несёт
 * client_ref: повтор после таймаута сервер отобьёт как DUPLICATE, а не добавит
 * лишнюю штуку.
 */
class InventoryCellActivity : AppCompatActivity() {

    private val BG = PickerTasksActivity.BG
    private val WHITE = Color.WHITE
    private val BORDER = PickerTasksActivity.BORDER
    private val TEXT = PickerTasksActivity.TEXT
    private val MUTED = PickerTasksActivity.MUTED
    private val FAINT = PickerTasksActivity.FAINT
    private val BLUE = PickerTasksActivity.BLUE
    private val GREEN = Color.parseColor("#16A34A")
    private val GREEN_BG = Color.parseColor("#F0FDF4")
    private val GREEN_TX = Color.parseColor("#166534")
    private val ORANGE_BG = Color.parseColor("#FFF7ED")
    private val ORANGE_TX = Color.parseColor("#C2410C")
    private val RED = Color.parseColor("#DC2626")
    private val RED_BG = Color.parseColor("#FEF2F2")
    private val RED_TX = Color.parseColor("#991B1B")

    private lateinit var api: CoreApi
    private var countId: Int? = null
    private var session: InvSession? = null
    private var busy = false
    private var countByPick = true   // «пик = +1»; иначе скан + ввод количества
    private var pendingCode: String? = null

    private val barcodeBuf = StringBuilder()
    private val beat = Handler(Looper.getMainLooper())
    private val beatTick = object : Runnable {
        override fun run() {
            val id = session?.count_cell_id
            if (id != null) {
                lifecycleScope.launch { runCatching { api.invHeartbeat(id) } }
            }
            beat.postDelayed(this, 60_000)
        }
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvChip: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvTotals: TextView
    private lateinit var tvScanBuffer: TextView
    private lateinit var tvMode: TextView
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
        countId = intent.getIntExtra("count_id", 0).takeIf { it > 0 }
        api = CoreApiClient.build(this)
        setContentView(buildUi())
        val cellId = intent.getIntExtra("count_cell_id", 0)
        if (cellId > 0) loadCell(cellId) else render()
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
        beat.postDelayed(beatTick, 60_000)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(scanReceiver) } catch (_: Exception) {}
        beat.removeCallbacks(beatTick)
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
        tvChip = TextView(this).apply {
            textSize = 10f; setTypeface(null, Typeface.BOLD)
            setPadding(dp(7), dp(2), dp(7), dp(2))
        }
        row.addView(tvTitle)
        row.addView(tvChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(8) })
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(TextView(this).apply {
            text = "← Назад"
            textSize = 13f; setTextColor(BLUE)
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setOnClickListener { finish() }
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
            setOnClickListener {
                countByPick = !countByPick
                pendingCode = null
                render()
            }
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

    // ─── Данные ──────────────────────────────────────────────────────────────

    private fun loadCell(id: Int) {
        lifecycleScope.launch {
            try {
                session = api.invCell(id)
                render()
            } catch (e: Exception) {
                toast("Ошибка: ${e.message?.take(60)}")
                render()
            }
        }
    }

    private fun n(s: String?): Double = s?.toDoubleOrNull() ?: 0.0
    private fun fmtN(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ─── Сканы ───────────────────────────────────────────────────────────────

    private fun handleBarcode(raw: String) {
        if (busy) return
        val code = raw.trim()
        if (code.isEmpty()) return
        val cur = session
        if (cur == null) { claim(code, force = false); return }
        // ШК ячейки, а не товара: предлагаем закрыть текущую и перейти дальше
        if (code.startsWith("CELL-", ignoreCase = true)) {
            if (code.equals(cur.cell?.barcode, ignoreCase = true)) {
                Beep.warn(); showMsg("Эта ячейка уже открыта", ORANGE_BG, ORANGE_TX)
                return
            }
            askSwitch(code)
            return
        }
        if (!countByPick && pendingCode == null) {
            pendingCode = code
            Beep.ok()
            render()
            return
        }
        scanItem(code, null, "1")
    }

    private fun claim(barcode: String, force: Boolean) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.invClaim(
                    InvClaimBody(
                        barcode = barcode,
                        count_id = countId,
                        force = force,
                        device_id = Build.MODEL,
                    )
                )
                // Любой исход, кроме OK, означает «ячейки у меня нет»: экран надо
                // перерисовать, иначе после завершения одной ячейки и неудачного
                // перехода на занятую человек продолжает видеть старый список и
                // думает, что всё ещё считает её.
                if (res.result != "OK") render()
                when (res.result) {
                    "OK" -> {
                        session = res.session
                        countId = res.session?.count_id ?: countId
                        Beep.ok()
                        render()
                        if (res.resumed) showMsg("Продолжаем ячейку", GREEN_BG, GREEN_TX)
                    }
                    "LOCKED" -> {
                        Beep.error()
                        showMsg(
                            "Ячейку считает ${res.holder ?: "другой"} " +
                                "(${res.counted_lines} позиц.)", RED_BG, RED_TX,
                        )
                    }
                    "STALE" -> askTakeover(barcode, res.holder, res.counted_lines)
                    "ALREADY_COUNTED" -> {
                        Beep.warn()
                        showMsg("Ячейка уже посчитана", ORANGE_BG, ORANGE_TX)
                    }
                    "NOT_IN_SCOPE" -> {
                        Beep.error()
                        showMsg(
                            "Эта ячейка не из пересчёта (зона ${res.cell_zone})",
                            RED_BG, RED_TX,
                        )
                    }
                    "UNKNOWN_CELL" -> {
                        Beep.error()
                        showMsg("Ячейка не найдена — это точно её штрихкод?", RED_BG, RED_TX)
                    }
                    "NO_COUNT" -> {
                        Beep.error()
                        showMsg("Для этого склада нет открытого пересчёта", RED_BG, RED_TX)
                    }
                    "AMBIGUOUS_COUNT" -> askCount(barcode, res.counts.orEmpty())
                    "IN_OTHER_COUNT" -> {
                        Beep.error()
                        showMsg("Ячейка занята другим пересчётом", RED_BG, RED_TX)
                    }
                    else -> {
                        Beep.error()
                        showMsg("Не удалось взять ячейку: ${res.result}", RED_BG, RED_TX)
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

    private fun scanItem(code: String, productId: Long?, qty: String) {
        val cur = session ?: return
        if (busy) return
        busy = true
        val ref = UUID.randomUUID().toString()
        lifecycleScope.launch {
            try {
                val res = api.invScan(
                    cur.count_cell_id,
                    InvScanBody(code = code, qty = qty, product_id = productId, client_ref = ref),
                )
                session = res.session ?: session
                pendingCode = null
                // Список рисуем ДО плашки: showMsg занимает то же место, и вызов
                // render() после него стирал бы сообщение в тот же кадр.
                render()
                when (res.result) {
                    "OK" -> {
                        if (res.warn?.code == "FOREIGN") {
                            Beep.warn()
                            showMsg(
                                "Этого товара тут не числилось — принимаю как излишек",
                                ORANGE_BG, ORANGE_TX,
                            )
                        } else Beep.ok()
                    }
                    "AMBIGUOUS" -> {
                        Beep.warn()
                        askCandidate(code, res.candidates.orEmpty().map {
                            it.product_id to (it.name ?: it.main_sku ?: "товар ${it.product_id}")
                        })
                    }
                    "UNKNOWN" -> {
                        Beep.error()
                        showMsg(
                            "Товар не найден в каталоге. Отложите отдельно и позовите менеджера",
                            RED_BG, RED_TX,
                        )
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

    private fun setQty(productId: Long, qty: String) {
        val cur = session ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.invSetQty(
                    cur.count_cell_id,
                    InvSetQtyBody(productId, qty, UUID.randomUUID().toString()),
                )
                session = res.session ?: session
                Beep.ok()
                render()
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    private fun undo() {
        val cur = session ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.invUndo(cur.count_cell_id)
                session = res.session ?: session
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

    private fun complete() {
        val cur = session ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.invComplete(cur.count_cell_id, InvCompleteBody(mode = "full"))
                Beep.ok()
                session = null
                pendingCode = null
                toast(
                    if (res.diff_count > 0) "Ячейка закрыта · расхождений: ${res.diff_count}"
                    else "Ячейка закрыта, всё сошлось",
                )
                render()
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    private fun release() {
        val cur = session ?: return
        busy = true
        lifecycleScope.launch {
            try {
                api.invRelease(cur.count_cell_id, InvReleaseBody(reset = false))
                session = null
                pendingCode = null
                toast("Ячейка отпущена — посчитанное сохранено")
                render()
            } catch (e: Exception) {
                toast("Ошибка: ${e.message?.take(60)}")
            } finally {
                busy = false
            }
        }
    }

    // ─── Диалоги ─────────────────────────────────────────────────────────────

    /** Один ШК ведёт на несколько товаров (они не уникальны) — выбирает человек. */
    private fun askCandidate(code: String, candidates: List<Pair<Long, String>>) {
        if (candidates.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle("Какой это товар?")
            .setItems(candidates.map { it.second }.toTypedArray()) { _, i ->
                scanItem(code, candidates[i].first, "1")
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askCount(barcode: String, counts: List<com.otgruzka.tsd.api.InvCount>) {
        if (counts.isEmpty()) return
        android.app.AlertDialog.Builder(this)
            .setTitle("В какой пересчёт?")
            .setItems(counts.map { "${it.number} · ${it.zone ?: "все зоны"}" }.toTypedArray()) { _, i ->
                countId = counts[i].id
                claim(barcode, force = false)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askTakeover(barcode: String, holder: String?, counted: Int) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Ячейку держит ${holder ?: "другой"}")
            .setMessage("Связи с ним нет давно, посчитано позиций: $counted.")
            .setPositiveButton("Продолжить его подсчёт") { _, _ -> claim(barcode, force = true) }
            .setNegativeButton("Назад", null)
            .show()
    }

    private fun askSwitch(barcode: String) {
        val code = session?.cell?.code ?: "текущую"
        android.app.AlertDialog.Builder(this)
            .setTitle("Перейти к другой ячейке?")
            .setMessage("Завершить $code и открыть новую?")
            .setPositiveButton("Завершить и перейти") { _, _ ->
                val cur = session ?: return@setPositiveButton
                busy = true
                lifecycleScope.launch {
                    try {
                        api.invComplete(cur.count_cell_id, InvCompleteBody(mode = "full"))
                        session = null
                    } catch (e: Exception) {
                        toast("Ошибка: ${e.message?.take(60)}")
                    } finally {
                        busy = false
                    }
                    claim(barcode, force = false)
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun askQty(line: InvLine) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(fmtN(n(line.qty_counted)))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(line.name ?: "Количество")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = input.text.toString().trim()
                if (v.isNotEmpty()) setQty(line.product_id, v)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Отрисовка ───────────────────────────────────────────────────────────

    private fun render() {
        val cur = session
        llContent.removeAllViews()
        llBottom.removeAllViews()

        if (cur == null) {
            tvTitle.text = "Сканируй ячейку"
            tvChip.visibility = View.GONE
            tvSubtitle.text = "Наведи сканер на штрихкод полки"
            tvTotals.text = ""
            tvMode.text = ""
            llContent.addView(TextView(this).apply {
                text = "Ячейка не выбрана.\n\nОтсканируй штрихкод ячейки — она станет твоей, " +
                    "остальные ТСД увидят, что её считаешь ты."
                textSize = 14f; setTextColor(MUTED)
            })
            return
        }

        tvTitle.text = cur.cell?.code ?: "Ячейка"
        tvChip.visibility = View.VISIBLE
        // Бейдж показывает то, что человек РЕАЛЬНО видит на этом экране: в слепой
        // кампании проводящему учёт отдаётся, и «СЛЕПОЙ» рядом с видимым
        // остатком был бы обманом.
        if (cur.blind_for_me) {
            tvChip.text = "СЛЕПОЙ"
            tvChip.background = rounded(Color.parseColor("#E0E7FF"), 8)
            tvChip.setTextColor(Color.parseColor("#3730A3"))
        } else {
            tvChip.text = if (cur.blind) "УЧЁТ ВИДЕН" else "ОТКРЫТЫЙ"
            tvChip.background = rounded(Color.parseColor("#DCFCE7"), 8)
            tvChip.setTextColor(GREEN_TX)
        }
        val zone = when (cur.cell?.zone) {
            "picking" -> "МХ"
            "reserve" -> "ДХ"
            "defect" -> "Брак"
            else -> cur.cell?.zone ?: ""
        }
        tvSubtitle.text = "${cur.number ?: ""} · зона $zone"
        tvTotals.text = "позиций: ${cur.totals?.positions ?: 0} · штук: ${cur.totals?.units ?: "0"}"
        tvMode.text = if (countByPick) "Режим: пик = +1 (сменить)" else
            if (pendingCode == null) "Режим: скан + количество (сменить)"
            else "Отсканирован ${pendingCode}: введи количество"

        val lines = cur.lines.orEmpty().sortedByDescending { it.counted }
        if (lines.none { it.counted }) {
            llContent.addView(TextView(this).apply {
                text = "Сканируй товар — каждый пик прибавляет штуку."
                textSize = 14f; setTextColor(MUTED)
            })
        }
        for (l in lines) {
            if (!l.counted) continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBorder(
                    if (l.is_foreign) ORANGE_BG else WHITE,
                    if (l.is_foreign) Color.parseColor("#FED7AA") else BORDER, 14,
                )
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { askQty(l) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            info.addView(TextView(this).apply {
                text = l.name ?: "—"
                textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
            })
            info.addView(TextView(this).apply {
                text = buildString {
                    append(l.sku ?: "")
                    if (l.is_foreign) append(" · не числился тут")
                }
                textSize = 12f; setTextColor(MUTED)
            })
            card.addView(info)
            // Счёт показываем как «факт / сколько должно быть» — оператору сразу
            // видно, сколько ещё осталось найти. В слепом режиме учёт с сервера не
            // приходит вовсе, тогда остаётся одно число.
            val counted = n(l.qty_counted)
            val expected = l.qty_expected?.let { n(it) }
            card.addView(TextView(this).apply {
                text = fmtN(counted)
                textSize = 22f; setTypeface(null, Typeface.BOLD)
                setTextColor(
                    when {
                        expected == null -> GREEN
                        counted == expected -> GREEN   // сошлось
                        counted > expected -> ORANGE_TX // нашли больше, чем числится
                        else -> TEXT                    // ещё не всё найдено
                    }
                )
                setPadding(dp(10), 0, 0, 0)
            })
            if (expected != null) {
                card.addView(TextView(this).apply {
                    text = "/${fmtN(expected)}"
                    textSize = 15f; setTextColor(MUTED)
                    setPadding(dp(1), dp(4), dp(6), 0)
                })
            }
            llContent.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }

        val btn = TextView(this).apply {
            text = "ЗАВЕРШИТЬ ЯЧЕЙКУ"
            textSize = 17f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(GREEN, 14)
            setPadding(0, dp(15), 0, dp(15))
            // Без диалога подтверждения: нажал — завершилось (решение владельца)
            setOnClickListener { complete() }
        }
        llBottom.addView(btn, LinearLayout.LayoutParams(
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
            text = "Отпустить ячейку"
            textSize = 13f; setTextColor(FAINT)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { release() }
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

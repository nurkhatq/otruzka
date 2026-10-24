package com.otgruzka.tsd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import com.otgruzka.tsd.api.FfEntryBody
import com.otgruzka.tsd.api.FfProduct
import com.otgruzka.tsd.api.FfScanBody
import com.otgruzka.tsd.api.FfSupply
import com.otgruzka.tsd.api.FfSupplyDetail
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.HttpException
import java.util.UUID

/**
 * Фулфилмент: приёмка товара клиента коробками.
 *
 * Сначала — список открытых поставок (их заводит менеджер на сайте: Склад →
 * Фулфилмент). В поставке: скан товара → штук в коробке и сколько коробок уже
 * подставлены из плана (коробки «исходят из поставки»: полные, потом одна
 * неполная на остаток) → сверить с коробками → «ПЕЧАТЬ». Ядро заводит коробки,
 * приходует товар на склад клиента и ставит этикетки в очередь принт-станции —
 * они выходят сами, как накладные сборки.
 * На каждой этикетке свой номер коробки (K000123): скан этикетки показывает,
 * чья коробка и сколько в ней.
 *
 * Ошиблись количеством — «Отменить последнее» (пока коробки не вскрывали),
 * этикетки этих коробок снять. Всё принято — «ЗАВЕРШИТЬ ПРИЁМКУ»: поставка
 * сверяется с планом, менеджер печатает акт для клиента.
 */
class FfReceiveActivity : AppCompatActivity() {

    private val BG = PickerTasksActivity.BG
    private val WHITE = Color.WHITE
    private val BORDER = PickerTasksActivity.BORDER
    private val TEXT = PickerTasksActivity.TEXT
    private val MUTED = PickerTasksActivity.MUTED
    private val FAINT = PickerTasksActivity.FAINT
    private val BLUE = PickerTasksActivity.BLUE
    private val BLUE_BG = Color.parseColor("#EFF6FF")
    private val GREEN = Color.parseColor("#16A34A")
    private val GREEN_BG = Color.parseColor("#F0FDF4")
    private val ORANGE_BG = Color.parseColor("#FFF7ED")
    private val ORANGE_TX = Color.parseColor("#C2410C")
    private val RED_BG = Color.parseColor("#FEF2F2")
    private val RED_TX = Color.parseColor("#991B1B")

    private lateinit var api: CoreApi
    private var supplies: List<FfSupply> = emptyList()
    private var supply: FfSupplyDetail? = null
    private var busy = false
    // Пока открыт любой диалог, сканы игнорируем: иначе второй скан открыл бы
    // второй диалог поверх первого. Счётчик, а не флаг: подтверждение «точно
    // 48 коробок?» открывается раньше, чем закрывается диалог количества.
    private var openDialogs = 0
    private var listError: String? = null

    private val barcodeBuf = StringBuilder()

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvTotals: TextView
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
        loadSupplies()
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

    private fun n(s: String?): Double = s?.toDoubleOrNull() ?: 0.0
    private fun fmtN(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    private fun fmtS(s: String?): String = fmtN(n(s))

    /** Как план ложится в коробки: «10 × 48 + 1 × 20» (так же считает ядро). */
    private fun boxPlan(qty: String?, perBox: String?): String? {
        val q = n(qty).toLong()
        val per = n(perBox).toLong()
        if (q <= 0 || per <= 0) return null
        val full = q / per
        val rest = q - full * per
        return listOfNotNull(
            if (full > 0) "$full × $per" else null,
            if (rest > 0) "1 × $rest" else null,
        ).joinToString(" + ")
    }

    /** «5 из 11 кор.» — когда коробки известны по плану, иначе «5 кор.». */
    private fun boxesOf(got: Int, planned: Int?): String =
        if (planned != null) "$got из $planned кор." else "$got кор."

    /** Текст отказа ядра (`detail`), а не «HTTP 409». */
    private fun errText(e: Exception): String {
        if (e is HttpException) {
            if (e.code() == 403) return "Нет права «Приёмка» — попросите администратора включить"
            val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
            val detail = try { body?.let { JSONObject(it).optString("detail") } } catch (_: Exception) { null }
            return if (!detail.isNullOrBlank()) detail else "Ошибка сервера: HTTP ${e.code()}"
        }
        return "Нет связи: ${e.message?.take(60)}"
    }

    private fun loadSupplies() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                supplies = api.ffSupplies("open")
                listError = null
            } catch (e: Exception) {
                listError = errText(e)
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun openSupply(id: Long) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                supply = api.ffSupply(id)
                Beep.ok()
            } catch (e: Exception) {
                Beep.error()
                showMsg(errText(e), RED_BG, RED_TX)
            } finally {
                busy = false
                render()
            }
        }
    }

    private suspend fun refreshSupply() {
        val s = supply ?: return
        supply = api.ffSupply(s.id)
    }

    // ─── Сканы ───────────────────────────────────────────────────────────────

    private fun handleBarcode(raw: String) {
        if (busy || openDialogs > 0) return
        val code = raw.trim()
        if (code.isEmpty()) return
        val s = supply
        if (s == null) {
            Beep.warn()
            showMsg("Сначала выберите поставку из списка", ORANGE_BG, ORANGE_TX)
            return
        }
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.ffScan(s.id, FfScanBody(code))
                when (res.result) {
                    "OK" -> { Beep.ok(); res.product?.let { askBoxes(it) } }
                    "AMBIGUOUS" -> {
                        Beep.warn()
                        val c = res.candidates.orEmpty()
                        show(android.app.AlertDialog.Builder(this@FfReceiveActivity)
                            .setTitle("Какой это товар?")
                            .setItems(c.map { it.name ?: it.main_sku ?: "товар ${it.product_id}" }
                                .toTypedArray()) { _, i ->
                                // Артикул однозначен — пересканируем им, чтобы
                                // получить план и подсказку количества.
                                c[i].main_sku?.let { handleBarcode(it) }
                            }
                            .setNegativeButton("Отмена", null))
                    }
                    "BOX" -> {
                        Beep.ok()
                        val b = res.box!!
                        val inside = if (b.contents.isEmpty()) "пустая"
                            else b.contents.joinToString("\n") { "${it.name} — ${fmtS(it.qty)} шт" }
                        val pos = if (b.index != null && b.total != null) " · коробка ${b.index} из ${b.total}" else ""
                        showMsg(
                            "Коробка ${b.code}${if (!b.active) " (закрыта)" else ""}\n" +
                                "${b.owner} · ${b.supply_number ?: "—"}$pos\n\n$inside",
                            BLUE_BG, TEXT, 5000,
                        )
                    }
                    "NOT_BOX" -> {
                        Beep.warn(); showMsg(res.message ?: "Это не коробка", ORANGE_BG, ORANGE_TX)
                    }
                    else -> {
                        Beep.error()
                        showMsg(
                            "Товар не найден в каталоге.\n\nОтложите его и позовите менеджера.",
                            RED_BG, RED_TX,
                        )
                    }
                }
            } catch (e: Exception) {
                Beep.error()
                showMsg(errText(e), RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    /** Диалог «штук в коробке × коробок» → ПЕЧАТЬ. */
    private fun askBoxes(p: FfProduct) {
        val clientRef = UUID.randomUUID().toString()  // один на диалог: двойной тап не задвоит
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = p.name
            textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
        })
        val plan = boxPlan(p.expected, p.per_box_plan)
        box.addView(TextView(this).apply {
            text = if (p.expected == null) {
                "Этого товара НЕТ в поставке — принять можно, в акте будет расхождение"
            } else {
                "По плану ${fmtS(p.expected)} шт${plan?.let { " = $it" } ?: ""}\n" +
                    "принято ${fmtS(p.received)} шт, ${boxesOf(p.boxes, p.boxes_expected)}"
            }
            textSize = 13f
            setTextColor(if (p.expected == null) ORANGE_TX else MUTED)
            setPadding(0, dp(4), 0, dp(10))
        })
        // Подсказка — пока по плану есть что принимать (сверх плана считает кладовщик).
        if (p.expected != null && n(p.expected) > n(p.received) &&
            p.suggested_boxes != null && p.suggested_per_box != null
        ) {
            box.addView(TextView(this).apply {
                // штук в коробке не из плана (прошлый ввод / карточка) — «из поставки» не пишем
                text = if (p.per_box_plan != null) "Цифры — из поставки. Сверьте с коробками и нажмите «ПЕЧАТЬ»"
                    else "Сверьте цифры с коробками и нажмите «ПЕЧАТЬ»"
                textSize = 12f; setTextColor(BLUE)
                setPadding(0, 0, 0, dp(8))
            })
        }
        fun field(label: String, value: String): EditText {
            box.addView(TextView(this).apply {
                text = label; textSize = 13f; setTextColor(MUTED)
            })
            val et = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setText(value)
                textSize = 22f
                setSelectAllOnFocus(true)
            }
            box.addView(et)
            return et
        }
        val suggestedPer = p.suggested_per_box?.let { fmtS(it) } ?: ""
        val suggestedBoxes = (p.suggested_boxes ?: 1).toString()
        val etPer = field("Штук в коробке", suggestedPer)
        val etBoxes = field("Сколько коробок", suggestedBoxes)
        val tvTotal = TextView(this).apply {
            textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(GREEN)
            setPadding(0, dp(6), 0, dp(4))
        }
        box.addView(tvTotal)
        fun recount() {
            val per = etPer.text.toString().toIntOrNull() ?: 0
            val cnt = etBoxes.text.toString().toIntOrNull() ?: 0
            tvTotal.text = if (per > 0 && cnt > 0) "Итого: $cnt кор. × $per = ${per * cnt} шт" else ""
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = recount()
        }
        etPer.addTextChangedListener(watcher)
        etBoxes.addTextChangedListener(watcher)
        recount()

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Принять коробки")
            .setView(box)
            .setPositiveButton("ПЕЧАТЬ") { _, _ ->
                val per = etPer.text.toString().toIntOrNull() ?: 0
                val cnt = etBoxes.text.toString().toIntOrNull() ?: 0
                if (per <= 0 || cnt <= 0) {
                    Beep.error()
                    showMsg("Укажите штук в коробке и сколько коробок — числа больше нуля", RED_BG, RED_TX)
                    return@setPositiveButton
                }
                // Переспрос ловит опечатку (300 вместо 30). Цифры из поставки
                // не трогали — опечатки нет, этикетки сверят с коробками.
                val untouched = per.toString() == suggestedPer && cnt.toString() == suggestedBoxes
                if (cnt > 30 && !untouched) {
                    show(android.app.AlertDialog.Builder(this)
                        .setTitle("Точно $cnt коробок?")
                        .setMessage("Будет напечатано $cnt этикеток по $per шт — всего ${per * cnt} шт.")
                        .setPositiveButton("Да, печатать") { _, _ -> submit(p, per, cnt, clientRef) }
                        .setNegativeButton("Исправить") { _, _ -> askBoxes(p) })
                } else submit(p, per, cnt, clientRef)
            }
            .setNegativeButton("Отмена", null)
        show(dialog)
    }

    private fun submit(p: FfProduct, per: Int, cnt: Int, clientRef: String) {
        val s = supply ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.ffAddEntry(
                    s.id, FfEntryBody(p.product_id, per.toString(), cnt, clientRef)
                )
                if (res.result == "REFUSED") {
                    Beep.error()
                    refreshSupply(); render()
                    showMsg(res.message ?: "Не принято", RED_BG, RED_TX)
                    return@launch
                }
                refreshSupply()
                render()
                val e = res.entry
                val boxes = if (e?.first_box != null) {
                    if (e.last_box != null && e.last_box != e.first_box) "${e.first_box}…${e.last_box}" else e.first_box
                } else ""
                if (res.printed == true) {
                    Beep.ok()
                    showMsg(
                        "Принято: $cnt кор. × $per = ${per * cnt} шт\n" +
                            "Этикетки печатаются: $boxes\nНаклейте по одной на коробку.",
                        GREEN_BG, GREEN, 4500,
                    )
                } else {
                    Beep.warn()
                    showMsg(
                        "Принято: $cnt кор. × $per = ${per * cnt} шт ($boxes)\n\n" +
                            "Этикетки на принтер не ушли — распечатайте их с компьютера: " +
                            "Склад → Фулфилмент → поставка ${s.number}.",
                        ORANGE_BG, ORANGE_TX, 6000,
                    )
                }
            } catch (e: Exception) {
                Beep.error()
                // Связь оборвалась после отправки — повтор с тем же client_ref
                // ничего не задвоит, поэтому просто предлагаем нажать ещё раз.
                showMsg(errText(e) + "\n\nПроверьте список ниже и при необходимости повторите.",
                    RED_BG, RED_TX, 5000)
                try { refreshSupply(); render() } catch (_: Exception) {}
            } finally {
                busy = false
            }
        }
    }

    private fun cancelLast() {
        val s = supply ?: return
        val last = s.entries.firstOrNull { !it.cancelled }
        if (last == null) {
            Beep.warn(); showMsg("Отменять нечего", ORANGE_BG, ORANGE_TX); return
        }
        val range = if (last.last_box != null && last.last_box != last.first_box)
            "${last.first_box}…${last.last_box}" else last.first_box ?: ""
        show(android.app.AlertDialog.Builder(this)
            .setTitle("Отменить последнее?")
            .setMessage(
                "${last.name}\n${last.boxes} кор. × ${fmtS(last.per_box)} = ${fmtS(last.qty)} шт\n" +
                    "Коробки $range\n\nСнимите с них этикетки."
            )
            .setPositiveButton("Отменить") { _, _ ->
                if (busy) return@setPositiveButton
                busy = true
                lifecycleScope.launch {
                    try {
                        val res = api.ffCancelEntry(last.id)
                        refreshSupply(); render()
                        if (res.result == "OK") {
                            Beep.ok(); showMsg("Отменено. Снимите этикетки $range", GREEN_BG, GREEN)
                        } else {
                            Beep.error(); showMsg(res.message ?: "Отменить нельзя", RED_BG, RED_TX)
                        }
                    } catch (e: Exception) {
                        Beep.error(); showMsg(errText(e), RED_BG, RED_TX)
                    } finally {
                        busy = false
                    }
                }
            }
            .setNegativeButton("Нет", null))
    }

    private fun closeSupply() {
        val s = supply ?: return
        if (s.entries.none { !it.cancelled }) {
            Beep.warn(); showMsg("Ещё ничего не принято", ORANGE_BG, ORANGE_TX); return
        }
        val diff = n(s.received_qty) - n(s.expected_qty)
        val tail = when {
            diff < 0 -> "\nНедовоз: ${fmtN(-diff)} шт"
            diff > 0 -> "\nСверх плана: ${fmtN(diff)} шт"
            else -> "\nВсё сходится с планом"
        }
        show(android.app.AlertDialog.Builder(this)
            .setTitle("Завершить приёмку ${s.number}?")
            .setMessage(
                "План ${fmtS(s.expected_qty)} шт, принято ${fmtS(s.received_qty)} шт, " +
                    "${boxesOf(s.boxes, s.boxes_expected)}$tail\n\nДобавить коробки в эту поставку будет нельзя."
            )
            .setPositiveButton("Завершить") { _, _ ->
                if (busy) return@setPositiveButton
                busy = true
                lifecycleScope.launch {
                    try {
                        api.ffClose(s.id)
                        Beep.ok()
                        toast("Поставка ${s.number} принята. Акт — на сайте: Склад → Фулфилмент")
                        supply = null
                        busy = false
                        loadSupplies()
                    } catch (e: Exception) {
                        busy = false
                        Beep.error(); showMsg(errText(e), RED_BG, RED_TX)
                    }
                }
            }
            .setNegativeButton("Нет", null))
    }

    // ─── Отрисовка ───────────────────────────────────────────────────────────

    private fun render() {
        llContent.removeAllViews()
        llBottom.removeAllViews()
        val s = supply
        if (s == null) renderList() else renderSupply(s)
    }

    private fun renderList() {
        tvTitle.text = "Приёмка коробок"
        tvSubtitle.text = "Поставки клиентов фулфилмента"
        tvTotals.text = ""
        listError?.let {
            llContent.addView(note(it, RED_BG, RED_TX))
        }
        if (listError == null && supplies.isEmpty()) {
            llContent.addView(TextView(this).apply {
                text = if (busy) "Загрузка…" else
                    "Открытых поставок нет.\n\nПоставку заводит менеджер на сайте: Склад → Фулфилмент."
                textSize = 14f; setTextColor(MUTED)
            })
        }
        for (sp in supplies) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBorder(WHITE, BORDER, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { openSupply(sp.id) }
            }
            card.addView(TextView(this).apply {
                text = "${sp.number} · ${sp.owner_name}"
                textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
            })
            card.addView(TextView(this).apply {
                text = "${sp.city} · принято ${fmtS(sp.received_qty)} из ${fmtS(sp.expected_qty)} шт · " +
                    boxesOf(sp.boxes, sp.boxes_expected)
                textSize = 13f; setTextColor(MUTED)
            })
            sp.comment?.takeIf { it.isNotBlank() }?.let { c ->
                card.addView(TextView(this).apply { text = c; textSize = 12f; setTextColor(FAINT) })
            }
            llContent.addView(card, lp().apply { bottomMargin = dp(8) })
        }
        llBottom.addView(button("ОБНОВИТЬ", BLUE_BG, BLUE) { loadSupplies() }, lp())
    }

    private fun renderSupply(s: FfSupplyDetail) {
        tvTitle.text = s.number
        tvSubtitle.text = "${s.owner_name} · ${s.city}"
        tvTotals.text = "принято ${fmtS(s.received_qty)} из ${fmtS(s.expected_qty)} шт · " +
            boxesOf(s.boxes, s.boxes_expected)

        llContent.addView(
            note("Сканируйте товар на коробке — коробки подставятся из поставки: сверьте и " +
                "нажмите «ПЕЧАТЬ». Этикетки напечатаются сами.", BLUE_BG, BLUE),
        )
        llContent.addView(section("ПЛАН И ФАКТ"))
        for (r in s.rows) {
            val expected = n(r.expected)
            val received = n(r.received)
            val color = when {
                expected == 0.0 -> ORANGE_TX
                received >= expected -> GREEN
                else -> TEXT
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBorder(WHITE, BORDER, 12)
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            card.addView(TextView(this).apply {
                text = r.name
                textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
            })
            card.addView(TextView(this).apply {
                text = if (expected == 0.0) "нет в плане · принято ${fmtN(received)} шт · ${r.boxes} кор."
                    else "принято ${fmtN(received)} из ${fmtN(expected)} шт · " +
                        boxesOf(r.boxes, r.boxes_expected)
                textSize = 13f; setTextColor(color)
            })
            boxPlan(r.expected, r.per_box)?.let { plan ->
                card.addView(TextView(this).apply {
                    text = "по плану $plan"
                    textSize = 12f; setTextColor(FAINT)
                })
            }
            llContent.addView(card, lp().apply { bottomMargin = dp(6) })
        }

        if (s.entries.isNotEmpty()) {
            llContent.addView(section("ПОСЛЕДНИЕ КОРОБКИ"))
            for (e in s.entries.take(10)) {
                val range = if (e.last_box != null && e.last_box != e.first_box)
                    "${e.first_box}…${e.last_box}" else e.first_box ?: ""
                val printed = when {
                    e.cancelled -> "отменено"
                    e.print == null -> "не печатались"
                    e.print.status == "done" -> "напечатаны"
                    else -> "в очереди принтера"
                }
                llContent.addView(TextView(this).apply {
                    text = "${e.name}\n${e.boxes} × ${fmtS(e.per_box)} = ${fmtS(e.qty)} шт · $range · $printed"
                    textSize = 12f
                    setTextColor(if (e.cancelled) FAINT else MUTED)
                    if (e.cancelled) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    background = rounded(if (e.cancelled) BG else BLUE_BG, 10)
                    setPadding(dp(10), dp(7), dp(10), dp(7))
                }, lp().apply { bottomMargin = dp(6) })
            }
        }

        llBottom.addView(button("ЗАВЕРШИТЬ ПРИЁМКУ", GREEN, Color.WHITE) { closeSupply() }, lp())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = "Отменить последнее"
            textSize = 13f; setTextColor(BLUE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { cancelLast() }
        })
        row.addView(TextView(this).apply {
            text = "Другая поставка"
            textSize = 13f; setTextColor(FAINT)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { supply = null; render(); loadSupplies() }
        })
        llBottom.addView(row)
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(FAINT)
        setPadding(dp(2), dp(14), 0, dp(6))
    }

    private fun note(text: String, bg: Int, fg: Int) = TextView(this).apply {
        this.text = text
        textSize = 13f; setTextColor(fg)
        background = rounded(bg, 12)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun button(label: String, bg: Int, fg: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 16f; setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(fg)
        background = rounded(bg, 14)
        setPadding(0, dp(14), 0, dp(14))
        setOnClickListener { onClick() }
    }

    private fun show(b: android.app.AlertDialog.Builder): android.app.AlertDialog {
        val d = b.create()
        d.setOnShowListener { openDialogs++ }
        d.setOnDismissListener { openDialogs = maxOf(0, openDialogs - 1) }
        d.show()
        return d
    }

    private fun showMsg(text: String, bg: Int, fg: Int, ms: Long = 2800) {
        llContent.removeAllViews()
        llContent.addView(TextView(this).apply {
            this.text = text
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(fg)
            background = rounded(bg, 14)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        })
        llContent.postDelayed({ if (!isFinishing) render() }, ms)
    }

    private fun lp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
    )

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

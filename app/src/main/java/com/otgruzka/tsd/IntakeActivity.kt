package com.otgruzka.tsd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CoreApi
import com.otgruzka.tsd.api.CoreApiClient
import com.otgruzka.tsd.api.Intake
import com.otgruzka.tsd.api.IntakeCreateBody
import com.otgruzka.tsd.api.IntakeLine
import com.otgruzka.tsd.api.IntakeLineQtyBody
import com.otgruzka.tsd.api.IntakeReasonBody
import com.otgruzka.tsd.api.IntakeScanBody
import com.otgruzka.tsd.api.IntakeSupplier
import com.otgruzka.tsd.api.IntakeWarehouse
import com.otgruzka.tsd.api.InvCandidate
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.HttpException
import java.util.UUID

/**
 * Приёмка с ТСД без заказа поставщику (владелец 2026-11-24).
 *
 * Товар приехал, а ЗП никто не завёл, или приехало новое. Склад: «Новая приёмка»
 * → скан товара → сколько штук → ещё товар… → «ЗАВЕРШИТЬ». Приёмка уходит на
 * сайт в «Заказы поставщику → С ТСД»: там дописывают карточки новым товарам и
 * поставщика, ядро заводит заказ и проводит приёмку. Штрихкода нет в каталоге —
 * строка «НОВЫЙ ТОВАР» по коду, карточку заведут на сайте. Один код на две
 * карточки — ТСД спрашивает какую. Наборы не принимаем.
 *
 * Повтор запроса после обрыва связи с тем же client_ref ничего не прибавляет.
 */
class IntakeActivity : AppCompatActivity() {

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
    private val RED = Color.parseColor("#DC2626")
    private val RED_BG = Color.parseColor("#FEF2F2")
    private val RED_TX = Color.parseColor("#991B1B")

    private lateinit var api: CoreApi
    private var intakes: List<Intake> = emptyList()
    private var warehouses: List<IntakeWarehouse> = emptyList()
    private var suppliers: List<IntakeSupplier> = emptyList()
    private var intake: Intake? = null
    private var busy = false
    // Пока открыт диалог, сканы игнорируем: второй скан открыл бы второй диалог
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
            handleBarcode(barcode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CoreAuth.isLoggedIn(this)) { finish(); return }
        api = CoreApiClient.build(this)
        setContentView(buildUi())
        render()
        loadList()
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
        if (openDialogs > 0) return super.dispatchKeyEvent(event)  // цифры идут в поле диалога
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
            textSize = 14f; setTextColor(BLUE)
            setPadding(dp(12), dp(10), dp(4), dp(10))
            setOnClickListener {
                if (intake != null) { intake = null; render(); loadList() } else finish()
            }
        })
        head.addView(row)
        tvSubtitle = TextView(this).apply { textSize = 12f; setTextColor(MUTED) }
        head.addView(tvSubtitle)
        tvTotals = TextView(this).apply {
            textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
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

    private fun loadList() {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                intakes = api.intakes("active", "open").items
                warehouses = api.intakeWarehouses()
                try { suppliers = api.intakeSuppliers() } catch (_: Exception) {}
                listError = null
            } catch (e: Exception) {
                listError = errText(e)
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun openIntake(id: Long) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                intake = api.intake(id)
                Beep.ok()
            } catch (e: Exception) {
                Beep.error(); showMsg(errText(e), RED_BG, RED_TX)
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun createIntake() {
        if (busy) return
        when {
            warehouses.isEmpty() -> {
                Beep.error()
                showMsg("Не вижу склада, куда принимать. Проверьте, что у вас закреплён склад.", RED_BG, RED_TX)
            }
            warehouses.size == 1 -> warnOpen(warehouses[0].id)
            else -> show(android.app.AlertDialog.Builder(this)
                .setTitle("На какой склад?")
                .setItems(warehouses.map { it.city }.toTypedArray()) { _, i ->
                    warnOpen(warehouses[i].id)
                }
                .setNegativeButton("Отмена", null))
        }
    }

    /** На складе уже идёт приёмка: одна поставка — одна приёмка, даже с трёх ТСД
     *  (владелец 2026-11-24). Та же поставка — продолжаем в ней, другая — новая. */
    private fun warnOpen(warehouseId: Int) {
        val open = intakes.filter { it.warehouse_id == warehouseId }
        if (open.isEmpty()) { askSupplier(warehouseId); return }
        val names = open.map { "${it.number} · ${it.supplier_name ?: "поставщик не назван"} · ${it.created_by}" }
        show(android.app.AlertDialog.Builder(this)
            .setTitle("Уже идёт приёмка")
            .setMessage(
                "На складе уже принимают:\n" + names.joinToString("\n") +
                    "\n\nЭто та же поставка? Тогда продолжайте в ней, второй раз заводить не надо. " +
                    "Другой поставщик или отдельная поставка — новая приёмка."
            )
            .setPositiveButton(if (open.size == 1) "Продолжить в ней" else "Продолжить в одной из них") { _, _ ->
                if (open.size == 1) openIntake(open[0].id)
                else show(android.app.AlertDialog.Builder(this)
                    .setTitle("В какой?")
                    .setItems(names.toTypedArray()) { _, i -> openIntake(open[i].id) }
                    .setNegativeButton("Отмена", null))
            }
            .setNegativeButton("Новая приёмка") { _, _ -> askSupplier(warehouseId) }
            .setNeutralButton("Отмена", null))
    }

    /** От кого поставка: подсказка из справочника контрагентов, можно вписать своё. */
    private fun askSupplier(warehouseId: Int) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = "Чтобы другие ТСД и сайт видели, чья это поставка"
            textSize = 13f; setTextColor(MUTED); setPadding(0, 0, 0, dp(8))
        })
        val et = AutoCompleteTextView(this).apply {
            hint = "Поставщик"
            textSize = 18f
            threshold = 1
            setAdapter(ArrayAdapter(this@IntakeActivity, android.R.layout.simple_dropdown_item_1line,
                suppliers.map { it.name }))
        }
        box.addView(et)
        var dialog: android.app.AlertDialog? = null
        fun submit() {
            val name = et.text.toString().trim()
            if (name.isEmpty()) { Beep.error(); toast("Впишите поставщика"); return }
            val cp = suppliers.firstOrNull { it.name.equals(name, ignoreCase = true) }
            dialog?.dismiss()
            createIn(warehouseId, name, cp?.id)
        }
        et.setOnEditorActionListener { _, _, _ -> submit(); true }
        dialog = show(android.app.AlertDialog.Builder(this)
            .setTitle("От кого поставка?")
            .setView(box)
            .setPositiveButton("НАЧАТЬ ПРИЁМКУ", null)
            .setNegativeButton("Отмена", null))
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
        et.requestFocus()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
    }

    private fun createIn(warehouseId: Int, supplierName: String, counterpartyId: Int?) {
        busy = true
        lifecycleScope.launch {
            try {
                intake = api.intakeCreate(IntakeCreateBody(warehouseId, null, supplierName, counterpartyId))
                Beep.ok()
            } catch (e: Exception) {
                Beep.error(); showMsg(errText(e), RED_BG, RED_TX)
            } finally {
                busy = false
                render()
            }
        }
    }

    // ─── Сканы ───────────────────────────────────────────────────────────────

    private fun handleBarcode(raw: String) {
        if (busy || openDialogs > 0) return
        val code = raw.trim().replace(Regex("-\\d+$"), "")  // Mindeo добавляет счётчик
        if (code.isEmpty()) return
        val it = intake
        if (it == null) {
            Beep.warn()
            showMsg("Сначала откройте или начните приёмку", ORANGE_BG, ORANGE_TX)
            return
        }
        // Сначала узнаём, что это за товар (qty=0 не бывает: шлём разведочный запрос с 1
        // штукой нельзя — он прибавит). Поэтому спрашиваем количество до запроса, а имя
        // товара покажем после ответа. Код, которого нет в каталоге, тоже принимаем.
        askQty(
            title = "Сколько штук?",
            note = "Штрихкод $code",
            initial = 1,
        ) { qty -> sendScan(code, null, qty, UUID.randomUUID().toString()) }
    }

    private fun sendScan(code: String?, productId: Int?, qty: Int, clientRef: String) {
        val it = intake ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val res = api.intakeScan(it.id, IntakeScanBody(code, productId, qty, clientRef))
                intake = res.intake
                when (res.result) {
                    "OK" -> {
                        Beep.ok()
                        val ln = res.line
                        render()
                        showMsg(
                            "✓ ${ln?.product?.name ?: "товар"}\n+$qty шт, всего ${ln?.qty ?: qty}",
                            GREEN_BG, GREEN,
                        )
                    }
                    "NEW" -> {
                        Beep.warn()
                        val ln = res.line
                        render()
                        showMsg(
                            "НОВЫЙ ТОВАР $code\n+$qty шт, всего ${ln?.qty ?: qty}\n\n" +
                                "Карточки в каталоге нет — её заведут на сайте при подтверждении.",
                            ORANGE_BG, ORANGE_TX, 4000,
                        )
                    }
                    "AMBIGUOUS" -> {
                        Beep.warn()
                        val c = res.candidates.orEmpty()
                        show(android.app.AlertDialog.Builder(this@IntakeActivity)
                            .setTitle("Какой это товар?")
                            .setItems(c.map { it.name ?: it.main_sku ?: "товар ${it.product_id}" }
                                .toTypedArray()) { _, i ->
                                sendScan(code, c[i].product_id.toInt(), qty, clientRef)
                            }
                            .setNegativeButton("Отмена", null))
                    }
                    "KIT" -> { Beep.error(); render(); showMsg(res.message ?: "Это набор", RED_BG, RED_TX, 4000) }
                    "DUPLICATE" -> { Beep.warn(); render(); showMsg("Этот скан уже учтён", ORANGE_BG, ORANGE_TX) }
                    else -> { Beep.error(); render(); showMsg(res.message ?: "Не принято", RED_BG, RED_TX) }
                }
            } catch (e: Exception) {
                Beep.error()
                showMsg(errText(e) + "\n\nПроверьте список и при необходимости отсканируйте снова.",
                    RED_BG, RED_TX, 5000)
                try { intake = api.intake(it.id) } catch (_: Exception) {}
            } finally {
                busy = false
            }
        }
    }

    /** Диалог количества: большое поле, −/+, Enter подтверждает. */
    private fun askQty(title: String, note: String, initial: Int, onOk: (Int) -> Unit) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = note; textSize = 14f; setTextColor(MUTED); setPadding(0, 0, 0, dp(8))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial.toString())
            textSize = 28f
            gravity = Gravity.CENTER
            setSelectAllOnFocus(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fun step(d: Int) {
            val v = (et.text.toString().toIntOrNull() ?: 0) + d
            et.setText(maxOf(0, v).toString())
        }
        row.addView(stepButton("−") { step(-1) })
        row.addView(et)
        row.addView(stepButton("+") { step(1) })
        box.addView(row)
        var dialog: android.app.AlertDialog? = null
        fun submit() {
            val v = et.text.toString().toIntOrNull()
            if (v == null || v <= 0) {
                Beep.error(); toast("Количество — число больше нуля"); return
            }
            if (v > 100_000) { Beep.error(); toast("Не больше 100 000"); return }
            dialog?.dismiss()
            onOk(v)
        }
        et.setOnEditorActionListener { _, _, _ -> submit(); true }
        dialog = show(android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("ГОТОВО", null)
            .setNegativeButton("Отмена", null))
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).apply {
            textSize = 16f
            setOnClickListener { submit() }
        }
        et.requestFocus()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
    }

    private fun stepButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 26f; setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(BLUE)
        background = rounded(BLUE_BG, 12)
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
        setOnClickListener { onClick() }
    }

    /** Поправить количество строки: тап по строке. Ноль — убрать. */
    private fun editLine(ln: IntakeLine) {
        val name = ln.product?.name ?: "Новый товар ${ln.barcode}"
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(TextView(this).apply {
            text = name; textSize = 15f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
        })
        box.addView(TextView(this).apply {
            text = "Сейчас ${ln.qty} шт. Впишите, сколько должно быть; 0 — убрать строку."
            textSize = 13f; setTextColor(MUTED); setPadding(0, dp(4), 0, dp(8))
        })
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(ln.qty.toString())
            textSize = 26f; gravity = Gravity.CENTER
            setSelectAllOnFocus(true)
        }
        box.addView(et)
        show(android.app.AlertDialog.Builder(this)
            .setTitle("Поправить количество")
            .setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = et.text.toString().toIntOrNull()
                if (v == null || v < 0) { Beep.error(); toast("Число от 0"); return@setPositiveButton }
                setLineQty(ln, v)
            }
            .setNegativeButton("Отмена", null))
    }

    private fun setLineQty(ln: IntakeLine, qty: Int) {
        val it = intake ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                intake = api.intakeSetLine(it.id, ln.id, IntakeLineQtyBody(qty))
                Beep.ok()
                render()
                showMsg(if (qty == 0) "Строка убрана" else "Исправлено: $qty шт", GREEN_BG, GREEN)
            } catch (e: Exception) {
                Beep.error(); showMsg(errText(e), RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    private fun submitIntake() {
        val it = intake ?: return
        if (it.lines.isEmpty()) {
            Beep.warn(); showMsg("Ещё ничего не отсканировано", ORANGE_BG, ORANGE_TX); return
        }
        val unknown = it.lines.count { l -> l.product_id == null }
        val tail = if (unknown > 0) "\nНовых товаров без карточки: $unknown — карточки заведут на сайте." else ""
        show(android.app.AlertDialog.Builder(this)
            .setTitle("Завершить ${it.number}?")
            .setMessage(
                "${it.lines.size} поз., ${it.qty_total} шт.$tail\n\n" +
                    "Приёмка уйдёт на сайт: Склад → Заказы поставщику → С ТСД. " +
                    "Добавить товар сюда после этого будет нельзя."
            )
            .setPositiveButton("Завершить") { _, _ ->
                if (busy) return@setPositiveButton
                busy = true
                lifecycleScope.launch {
                    try {
                        api.intakeSubmit(it.id)
                        Beep.ok()
                        toast("${it.number} отправлена на подтверждение")
                        intake = null
                        busy = false
                        loadList()
                    } catch (e: Exception) {
                        busy = false
                        Beep.error(); showMsg(errText(e), RED_BG, RED_TX)
                    }
                }
            }
            .setNegativeButton("Нет", null))
    }

    private fun cancelIntake() {
        val it = intake ?: return
        show(android.app.AlertDialog.Builder(this)
            .setTitle("Отменить ${it.number}?")
            .setMessage("Отсканированное пропадёт, на склад ничего не встанет.")
            .setPositiveButton("Отменить приёмку") { _, _ ->
                if (busy) return@setPositiveButton
                busy = true
                lifecycleScope.launch {
                    try {
                        api.intakeCancel(it.id, IntakeReasonBody("с ТСД"))
                        Beep.ok()
                        toast("${it.number} отменена")
                        intake = null
                        busy = false
                        loadList()
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
        val it = intake
        if (it == null) renderList() else renderIntake(it)
    }

    private fun renderList() {
        tvTitle.text = "Приёмка"
        tvSubtitle.text = "Товар приехал без заказа поставщику"
        tvTotals.text = ""
        listError?.let { llContent.addView(note(it, RED_BG, RED_TX)) }
        if (listError == null && intakes.isEmpty()) {
            llContent.addView(TextView(this).apply {
                text = if (busy) "Загрузка…" else
                    "Открытых приёмок нет.\n\nНажмите «НОВАЯ ПРИЁМКА», сканируйте товар и вписывайте количество."
                textSize = 15f; setTextColor(MUTED)
            })
        }
        if (intakes.isNotEmpty()) llContent.addView(section("НЕЗАВЕРШЁННЫЕ"))
        for (i in intakes) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBorder(WHITE, BORDER, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener { openIntake(i.id) }
            }
            card.addView(TextView(this).apply {
                text = "${i.number} · ${i.supplier_name ?: "поставщик не назван"}"
                textSize = 17f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
            })
            card.addView(TextView(this).apply {
                text = i.warehouse_name ?: ""
                textSize = 13f; setTextColor(MUTED)
            })
            card.addView(TextView(this).apply {
                text = "${i.lines_count} поз. · ${i.qty_total} шт · начал ${i.created_by}" +
                    (if (i.unknown_count > 0) " · новых ${i.unknown_count}" else "")
                textSize = 13f; setTextColor(MUTED)
            })
            i.comment?.takeIf { c -> c.isNotBlank() }?.let { c ->
                card.addView(TextView(this).apply { text = c; textSize = 12f; setTextColor(FAINT) })
            }
            llContent.addView(card, lp().apply { bottomMargin = dp(8) })
        }
        llBottom.addView(button("НОВАЯ ПРИЁМКА", GREEN, Color.WHITE) { createIntake() }, lp())
        llBottom.addView(TextView(this).apply {
            text = "Обновить список"
            textSize = 14f; setTextColor(BLUE); gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(6))
            setOnClickListener { loadList() }
        })
    }

    private fun renderIntake(it: Intake) {
        tvTitle.text = it.number
        tvSubtitle.text = "${it.supplier_name ?: "поставщик не назван"} · ${it.warehouse_name ?: ""} · начал ${it.created_by}"
        tvTotals.text = "${it.lines.size} поз. · ${it.qty_total} шт" +
            (if (it.unknown_count > 0) " · новых товаров ${it.unknown_count}" else "")

        llContent.addView(
            note("Сканируйте товар и вписывайте, сколько штук приехало. Нажать на строку — поправить количество.",
                BLUE_BG, BLUE),
        )
        if (it.lines.isEmpty()) {
            llContent.addView(TextView(this).apply {
                text = "Пока ничего не отсканировано"
                textSize = 14f; setTextColor(MUTED); setPadding(dp(2), dp(16), 0, 0)
            })
        } else {
            llContent.addView(section("ОТСКАНИРОВАНО"))
        }
        for (ln in it.lines.reversed()) {
            val p = ln.product
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBorder(if (p == null) ORANGE_BG else WHITE, BORDER, 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { editLine(ln) }
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = p?.name ?: "НОВЫЙ ТОВАР"
                textSize = 15f; setTypeface(null, Typeface.BOLD)
                setTextColor(if (p == null) ORANGE_TX else TEXT)
            })
            col.addView(TextView(this).apply {
                text = if (p == null) "${ln.barcode} · карточки нет, заведут на сайте"
                    else listOfNotNull(p.main_sku, ln.barcode).joinToString(" · ")
                textSize = 12f; setTextColor(if (p == null) ORANGE_TX else FAINT)
            })
            card.addView(col)
            card.addView(TextView(this).apply {
                text = "${ln.qty}"
                textSize = 22f; setTypeface(null, Typeface.BOLD)
                setTextColor(if (p == null) ORANGE_TX else GREEN)
                setPadding(dp(10), 0, 0, 0)
            })
            llContent.addView(card, lp().apply { bottomMargin = dp(6) })
        }

        llBottom.addView(button("ЗАВЕРШИТЬ ПРИЁМКУ", GREEN, Color.WHITE) { submitIntake() }, lp())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = "Отменить приёмку"
            textSize = 14f; setTextColor(RED)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { cancelIntake() }
        })
        row.addView(TextView(this).apply {
            text = "Другая приёмка"
            textSize = 14f; setTextColor(FAINT)
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener { intake = null; render(); loadList() }
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
        textSize = 17f; setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(fg)
        background = rounded(bg, 14)
        setPadding(0, dp(16), 0, dp(16))
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
            textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(fg)
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

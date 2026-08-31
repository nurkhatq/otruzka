package com.otgruzka.tsd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CoreApi
import com.otgruzka.tsd.api.CoreApiClient
import com.otgruzka.tsd.api.PickerBulkScanBody
import com.otgruzka.tsd.api.PickerOrderItem
import com.otgruzka.tsd.api.PickerScanBody
import com.otgruzka.tsd.api.PickerTask
import kotlinx.coroutines.launch

/**
 * Экран задания сборщика: скан аппаратной пикалкой (BroadcastReceiver + клавиатурный
 * ввод, как в отгрузке). Сервер сам решает статус скана (совпал/неизвестный/без ШК) —
 * логика та же, что у веб-сборщика на qoimams.asia.
 */
class PickerTaskActivity : AppCompatActivity() {

    private lateinit var api: CoreApi
    private var taskId = 0
    private var task: PickerTask? = null
    private var busy = false

    /** Режим: per — каждая позиция пикается отдельно; bulk — один скан + кол-во. */
    private var bulkMode = false
    private var modeInitialized = false
    private var bulkBarcode: String? = null
    private var bulkWrong: String? = null

    private var cellsBySku: Map<String, List<String>> = emptyMap()
    private val barcodeBuf = StringBuilder()

    // Views
    private lateinit var tvProgress: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvChip: TextView
    private lateinit var tvScanBuffer: TextView
    private lateinit var tvMsg: TextView
    private lateinit var llModeToggle: LinearLayout
    private lateinit var btnModePer: TextView
    private lateinit var btnModeBulk: TextView
    private lateinit var llCurrent: LinearLayout
    private lateinit var llBulk: LinearLayout
    private lateinit var llOrders: LinearLayout
    private lateinit var btnNoBarcode: Button
    private lateinit var btnComplete: Button
    private lateinit var tvRelease: TextView
    private lateinit var etBulkQty: EditText

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
        taskId = intent.getIntExtra("task_id", 0)
        if (taskId == 0 || !CoreAuth.isLoggedIn(this)) { finish(); return }
        api = CoreApiClient.build(this)
        setContentView(buildUi())
        loadTask()
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

    // ─── Keyboard-wedge scan input ───────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Во время ввода количества (bulk) клавиатура работает как обычно
        if (bulkBarcode != null) return super.dispatchKeyEvent(event)
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

    // ─── UI skeleton ─────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
            fitsSystemWindows = true
        }

        // Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            elevation = dp(3).toFloat()
            setPadding(dp(8), dp(10), dp(16), dp(10))
        }
        top.addView(TextView(this).apply {
            text = "←"
            textSize = 22f
            setTextColor(getColor(R.color.primary))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
        })
        tvChip = TextView(this).apply {
            textSize = 10f; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
            background = cardDrawable(0x145956E8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        top.addView(tvChip)
        tvTitle = TextView(this).apply {
            textSize = 14f; maxLines = 2
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(10); rightMargin = dp(8) }
        }
        top.addView(tvTitle)
        tvProgress = TextView(this).apply {
            textSize = 18f; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
        }
        top.addView(tvProgress)
        root.addView(top)

        // Scan buffer indicator
        tvScanBuffer = TextView(this).apply {
            textSize = 11f
            setTextColor(getColor(R.color.secondary))
            setPadding(dp(20), dp(2), dp(20), 0)
        }
        root.addView(tvScanBuffer)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0)
                .apply { weight = 1f }
            isFillViewport = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(20))
        }

        // Режим (только для массовых)
        llModeToggle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, 0, 0, dp(8))
        }
        btnModePer = modeButton("По одному") { setMode(false) }
        btnModeBulk = modeButton("Скан + кол-во") { setMode(true) }
        llModeToggle.addView(btnModePer, LinearLayout.LayoutParams(0, dp(38), 1f))
        llModeToggle.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        llModeToggle.addView(btnModeBulk, LinearLayout.LayoutParams(0, dp(38), 1f))
        body.addView(llModeToggle)

        // Сообщение о последнем скане
        tvMsg = TextView(this).apply {
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        body.addView(tvMsg, wrapLp(0, dp(8)))

        // Текущая позиция / bulk-панель
        llCurrent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(llCurrent)
        llBulk = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(llBulk)

        btnNoBarcode = Button(this).apply {
            text = "Нет штрихкода на товаре"
            textSize = 13f; isAllCaps = false
            setTextColor(getColor(R.color.on_background))
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE8E8EE.toInt())
            setOnClickListener { scanNext(null) }
        }
        body.addView(btnNoBarcode, wrapLp(dp(8), dp(4)))

        // Список позиций
        body.addView(TextView(this).apply {
            text = "ПОЗИЦИИ"
            textSize = 11f; letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.secondary))
            setPadding(dp(4), dp(14), 0, dp(6))
        })
        llOrders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(llOrders)

        scroll.addView(body)
        root.addView(scroll)

        // Низ: завершить + вернуть
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }
        btnComplete = Button(this).apply {
            textSize = 15f; isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_primary))
            setOnClickListener { confirmComplete() }
        }
        bottom.addView(btnComplete, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        ))
        tvRelease = TextView(this).apply {
            text = "Вернуть задание в очередь"
            textSize = 12f; gravity = Gravity.CENTER
            setTextColor(getColor(R.color.secondary))
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { confirmRelease() }
        }
        bottom.addView(tvRelease)
        root.addView(bottom)

        return root
    }

    private fun modeButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 13f; setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun setMode(bulk: Boolean) {
        bulkMode = bulk
        bulkBarcode = null; bulkWrong = null
        render()
    }

    // ─── Data ────────────────────────────────────────────────────────────────

    private fun loadTask() {
        lifecycleScope.launch {
            try {
                onTask(api.getTask(taskId))
                loadCells()
            } catch (e: Exception) {
                toast("Ошибка загрузки: ${e.message?.take(60)}")
                finish()
            }
        }
    }

    private fun loadCells() {
        val skus = task?.orders.orEmpty().mapNotNull { it.offer_code }.distinct()
        if (skus.isEmpty()) return
        lifecycleScope.launch {
            try {
                val res = api.cellsForSkus(skus.joinToString(","))
                cellsBySku = res.mapValues { (_, v) -> v.mapNotNull { it.code } }
                render()
            } catch (_: Exception) {}
        }
    }

    private fun onTask(t: PickerTask) {
        task = t
        if (!modeInitialized) {
            // Массовые одиночные — по умолчанию быстрый режим «скан + кол-во»
            bulkMode = t.task_type == "mass_single"
            modeInitialized = true
        }
        render()
    }

    // ─── Scan logic ──────────────────────────────────────────────────────────

    private fun qtyDone(o: PickerOrderItem): Int {
        val s = o.scan ?: return 0
        if (s.match_status == "skipped") return 0
        return s.qty_done ?: 1
    }

    private fun required(o: PickerOrderItem): Int = o.quantity ?: 1

    private fun nextPending(): PickerOrderItem? =
        task?.orders.orEmpty().firstOrNull { qtyDone(it) < required(it) }

    private fun allDone(): Boolean = nextPending() == null

    private fun handleBarcode(raw: String) {
        if (busy || task == null) return
        val code = raw.replace(Regex("-\\d+$"), "")   // Mindeo добавляет счётчик к ШК
        if (bulkMode) {
            if (bulkBarcode != null) return  // уже вводим количество
            handleScanBulk(code)
        } else {
            scanNext(code)
        }
    }

    /** Один скан = одна позиция (или одна штука при qty>1). Статус решает сервер. */
    private fun scanNext(barcode: String?) {
        val t = task ?: return
        val target = nextPending() ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val updated = api.scan(
                    t.id,
                    PickerScanBody(
                        order_code = target.order_code,
                        position_index = target.position_index ?: 0,
                        barcode = barcode,
                        match_status = if (barcode == null) "no_barcode" else "matched",
                    )
                )
                onTask(updated)
                val upd = updated.orders.orEmpty().firstOrNull {
                    it.order_code == target.order_code &&
                        (it.position_index ?: 0) == (target.position_index ?: 0)
                }
                when (upd?.scan?.match_status) {
                    "matched" -> { Beep.ok(); showMsg("✓ Есть!", ok = true) }
                    "no_barcode" -> { Beep.warn(); showMsg("Отмечено без штрихкода", ok = false) }
                    else -> { Beep.warn(); showMsg("Штрихкод не тот — записан для сверки", ok = false) }
                }
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", ok = false)
            } finally {
                busy = false
            }
        }
    }

    private fun handleScanBulk(code: String) {
        val t = task ?: return
        val expected = t.expected_barcode
        if (expected != null && code != expected) {
            bulkWrong = code
            Beep.error()
            render()
            return
        }
        startBulkEntry(code)
    }

    private fun startBulkEntry(code: String) {
        bulkWrong = null
        bulkBarcode = code
        Beep.ok()
        render()
    }

    private fun confirmBulk() {
        val t = task ?: return
        val qty = etBulkQty.text.toString().toIntOrNull() ?: 0
        val remaining = t.orders.orEmpty().count { it.scan == null }
        if (qty < 1 || qty > remaining) {
            toast("Количество от 1 до $remaining"); return
        }
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val updated = api.bulkScan(t.id, PickerBulkScanBody(bulkBarcode, qty))
                bulkBarcode = null
                onTask(updated)
                Beep.ok()
                showMsg("✓ Подтверждено: $qty", ok = true)
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", ok = false)
            } finally {
                busy = false
            }
        }
    }

    // ─── Complete / release ──────────────────────────────────────────────────

    private fun confirmComplete() {
        val t = task ?: return
        if (allDone()) { doComplete(); return }
        val remaining = t.total_orders - t.scanned_qty
        android.app.AlertDialog.Builder(this)
            .setTitle("Товар закончился?")
            .setMessage("Собрано ${t.scanned_qty}/${t.total_orders}. " +
                "Недобранные заказы ($remaining) уйдут в очередь отмены менеджеру. Завершить?")
            .setPositiveButton("Завершить") { _, _ -> doComplete() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun doComplete() {
        if (busy) return
        busy = true
        btnComplete.isEnabled = false
        lifecycleScope.launch {
            try {
                val r = api.completeTask(taskId)
                val cancelled = r.cancelled_orders ?: 0
                val printed = !r.pdf_filenames.isNullOrEmpty()
                var msg = "Готово."
                if (cancelled > 0) msg += " В отмену: $cancelled."
                if (printed) msg += " Накладные отправлены на печать."
                toast(msg)
                finish()
            } catch (e: Exception) {
                toast("Ошибка завершения: ${e.message?.take(60)}")
                btnComplete.isEnabled = true
                busy = false
            }
        }
    }

    private fun confirmRelease() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Вернуть задание")
            .setMessage("Задание вернётся в очередь и достанется другому сборщику.")
            .setPositiveButton("Вернуть") { _, _ ->
                lifecycleScope.launch {
                    try {
                        api.releaseTask(taskId)
                        toast("Задание возвращено в очередь")
                        finish()
                    } catch (e: Exception) { toast("Ошибка: ${e.message?.take(60)}") }
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Render ──────────────────────────────────────────────────────────────

    private fun render() {
        val t = task ?: return
        tvChip.text = PickerTasksActivity.catLabel(t.task_type)
        tvTitle.text = t.product_name ?: "Задание #${t.id}"
        tvProgress.text = "${t.scanned_qty}/${t.total_orders}"

        val mine = t.picker_username == CoreAuth.username(this)
        val done = allDone()

        // Режим: только массовые
        llModeToggle.visibility =
            if (t.task_type == "mass_single" && mine && !done) View.VISIBLE else View.GONE
        styleModeButton(btnModePer, !bulkMode)
        styleModeButton(btnModeBulk, bulkMode)

        btnNoBarcode.visibility = if (mine && !done && !bulkMode) View.VISIBLE else View.GONE

        renderCurrent(mine, done)
        renderBulk(mine, done)
        renderOrders(t)

        // Кнопка завершения
        val anyScanned = t.orders.orEmpty().any { it.scan != null }
        when {
            !mine -> {
                btnComplete.visibility = View.GONE
                tvRelease.visibility = View.GONE
            }
            done -> {
                btnComplete.visibility = View.VISIBLE
                btnComplete.text = "Завершить задание — всё собрано ✓"
                btnComplete.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(R.color.success))
                tvRelease.visibility = View.GONE
            }
            anyScanned -> {
                btnComplete.visibility = View.VISIBLE
                btnComplete.text = "Товар закончился — завершить"
                btnComplete.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(R.color.warning))
                tvRelease.visibility = View.VISIBLE
            }
            else -> {
                btnComplete.visibility = View.GONE
                tvRelease.visibility = View.VISIBLE
            }
        }
    }

    private fun styleModeButton(btn: TextView, active: Boolean) {
        btn.setTextColor(getColor(if (active) R.color.on_primary else R.color.secondary))
        btn.background = cardDrawable(
            if (active) getColor(R.color.primary) else 0x14000000
        )
    }

    /** Карточка «СЕЙЧАС ИЩЕМ» — режим по одному. */
    private fun renderCurrent(mine: Boolean, done: Boolean) {
        llCurrent.removeAllViews()
        if (!mine || done || bulkMode) return
        val cur = nextPending() ?: return

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(getColor(R.color.surface))
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        card.addView(TextView(this).apply {
            text = "СЕЙЧАС ИЩЕМ"
            textSize = 10f; letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
        })
        card.addView(TextView(this).apply {
            text = cur.name ?: cur.offer_code ?: "—"
            textSize = 16f; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
            setPadding(0, dp(4), 0, 0)
        })
        card.addView(small("Заказ ${cur.order_code}" +
            if ((cur.num_positions ?: 1) > 1)
                " · позиция ${(cur.position_index ?: 0) + 1}/${cur.num_positions}" else ""))

        if (required(cur) > 1) {
            card.addView(TextView(this).apply {
                text = "Отсканировано ${qtyDone(cur)} / ${required(cur)} шт"
                textSize = 14f; setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.warning))
                setPadding(0, dp(4), 0, 0)
            })
        }

        val expected = cur.expected_barcode ?: task?.expected_barcode
        if (expected != null) {
            card.addView(small("ШК: $expected"))
        } else if (cur.is_kit == true) {
            card.addView(small("Набор — пикните штрихкод товара"))
        } else {
            card.addView(TextView(this).apply {
                text = "Штрихкод не задан в системе"
                textSize = 12f
                setTextColor(getColor(R.color.warning))
                setPadding(0, dp(2), 0, 0)
            })
        }

        // Компоненты набора
        val comps = cur.components.orEmpty()
        if (cur.is_kit == true && comps.isNotEmpty()) {
            comps.forEach { c ->
                card.addView(small("↳ ${c.name ?: c.sku ?: "компонент"}" +
                    if ((c.qty ?: 1) > 1) " ×${c.qty}" else ""))
            }
        }

        // Ячейки
        val cells = cellsBySku[cur.offer_code].orEmpty()
        if (cells.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = "📍 ${cells.joinToString(", ")}"
                textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.primary))
                setPadding(0, dp(4), 0, 0)
            })
        }

        // Фото
        val images = cur.images.orEmpty().take(4)
        if (images.isNotEmpty()) {
            val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            images.forEach { url ->
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = cardDrawable(0x0D000000)
                    layoutParams = LinearLayout.LayoutParams(dp(96), dp(96))
                        .apply { rightMargin = dp(8); topMargin = dp(8) }
                    setOnClickListener { showFullImage(url) }
                }
                ImageLoader.load(url, iv)
                row.addView(iv)
            }
            hs.addView(row)
            card.addView(hs)
        }

        llCurrent.addView(card)
    }

    /** Bulk-режим: подсказка / ошибка ШК / ввод количества. */
    private fun renderBulk(mine: Boolean, done: Boolean) {
        llBulk.removeAllViews()
        if (!mine || done || !bulkMode) return
        val t = task ?: return
        val remaining = t.orders.orEmpty().count { it.scan == null }

        val wrong = bulkWrong
        if (wrong != null) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(0x14D93025)
                setPadding(dp(16), dp(12), dp(16), dp(14))
            }
            card.addView(TextView(this).apply {
                text = "НЕВЕРНЫЙ ШТРИХКОД"
                textSize = 11f; setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(R.color.error))
            })
            card.addView(small(wrong))
            card.addView(small("Ожидается: ${t.product_name ?: ""} (${t.expected_barcode ?: "—"})"))
            val btns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            btns.addView(Button(this).apply {
                text = "Пересканировать"; isAllCaps = false; textSize = 13f
                setTextColor(getColor(R.color.on_background))
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE8E8EE.toInt())
                setOnClickListener { bulkWrong = null; render() }
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(8) })
            btns.addView(Button(this).apply {
                text = "Всё равно верно"; isAllCaps = false; textSize = 13f
                setTextColor(getColor(R.color.on_primary))
                backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.success))
                setOnClickListener { startBulkEntry(wrong) }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            card.addView(btns)
            llBulk.addView(card)
            return
        }

        val bc = bulkBarcode
        if (bc == null) {
            // Ждём скан
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable(getColor(R.color.surface))
                elevation = dp(2).toFloat()
                setPadding(dp(16), dp(14), dp(16), dp(16))
            }
            card.addView(TextView(this).apply {
                text = "Пикните штрихкод товара —\nзатем введите, сколько собрали"
                textSize = 14f; gravity = Gravity.CENTER
                setTextColor(getColor(R.color.on_background))
            })
            card.addView(small("Осталось заказов: $remaining").apply { gravity = Gravity.CENTER })
            val imgs = t.orders.orEmpty().firstOrNull()?.images.orEmpty().take(3)
            if (imgs.isNotEmpty()) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                }
                imgs.forEach { url ->
                    val iv = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        background = cardDrawable(0x0D000000)
                        layoutParams = LinearLayout.LayoutParams(dp(80), dp(80))
                            .apply { rightMargin = dp(8); topMargin = dp(10) }
                        setOnClickListener { showFullImage(url) }
                    }
                    ImageLoader.load(url, iv)
                    row.addView(iv)
                }
                card.addView(row)
            }
            llBulk.addView(card)
            return
        }

        // Ввод количества
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(0x145956E8)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        card.addView(TextView(this).apply {
            text = "Отсканировано: $bc"
            textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
        })
        card.addView(small("Осталось заказов: $remaining. Сколько собрали?"))

        val qtyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        etBulkQty = EditText(this).apply {
            setText(remaining.toString())
            textSize = 24f; gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setBackgroundResource(R.drawable.input_bg)
        }
        qtyRow.addView(qtyButton("−") {
            val v = (etBulkQty.text.toString().toIntOrNull() ?: 1) - 1
            etBulkQty.setText(maxOf(1, v).toString())
        })
        qtyRow.addView(etBulkQty, LinearLayout.LayoutParams(0, dp(52), 1f)
            .apply { leftMargin = dp(8); rightMargin = dp(8) })
        qtyRow.addView(qtyButton("+") {
            val v = (etBulkQty.text.toString().toIntOrNull() ?: 0) + 1
            etBulkQty.setText(minOf(remaining, v).toString())
        })
        card.addView(qtyRow)

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        btns.addView(Button(this).apply {
            text = "Отмена"; isAllCaps = false; textSize = 13f
            setTextColor(getColor(R.color.on_background))
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE8E8EE.toInt())
            setOnClickListener { bulkBarcode = null; render() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        btns.addView(Button(this).apply {
            text = "Подтвердить"; isAllCaps = false; textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
            setOnClickListener { confirmBulk() }
        }, LinearLayout.LayoutParams(0, dp(46), 1f))
        card.addView(btns)

        llBulk.addView(card)
    }

    private fun qtyButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 20f; isAllCaps = false
        setTextColor(getColor(R.color.on_background))
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE8E8EE.toInt())
        layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
        setOnClickListener { onClick() }
    }

    private fun renderOrders(t: PickerTask) {
        llOrders.removeAllViews()
        val next = nextPending()
        t.orders.orEmpty().forEach { o ->
            val doneQty = qtyDone(o)
            val req = required(o)
            val positionDone = doneQty >= req
            val isCurrent = !bulkMode && next != null &&
                o.order_code == next.order_code &&
                (o.position_index ?: 0) == (next.position_index ?: 0)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardDrawable(
                    when {
                        positionDone -> 0x1434A853
                        isCurrent -> 0x145956E8
                        else -> getColor(R.color.surface)
                    }
                )
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = o.name ?: o.offer_code ?: "—"
                textSize = 13f; maxLines = 2
                setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(getColor(R.color.on_background))
            })
            val cells = cellsBySku[o.offer_code].orEmpty()
            col.addView(TextView(this).apply {
                text = o.order_code +
                    (if ((o.num_positions ?: 1) > 1) " · поз. ${(o.position_index ?: 0) + 1}/${o.num_positions}" else "") +
                    (if (cells.isNotEmpty()) " · 📍${cells.joinToString(",")}" else "")
                textSize = 11f
                setTextColor(getColor(R.color.secondary))
                setPadding(0, dp(1), 0, 0)
            })
            row.addView(col)
            row.addView(TextView(this).apply {
                text = if (req > 1) "$doneQty/$req шт" else ""
                textSize = 12f
                setTextColor(getColor(R.color.secondary))
                setPadding(0, 0, dp(8), 0)
            })
            row.addView(TextView(this).apply {
                val st = if (positionDone) o.scan?.match_status else null
                text = when {
                    positionDone && st == "matched" -> "✓"
                    positionDone && st == "no_barcode" -> "без ШК"
                    positionDone -> "?"
                    isCurrent -> "← сейчас"
                    else -> "○"
                }
                textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(getColor(when {
                    positionDone && st == "matched" -> R.color.success
                    positionDone -> R.color.warning
                    isCurrent -> R.color.primary
                    else -> R.color.secondary
                }))
            })
            llOrders.addView(row)
            llOrders.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
            })
        }
    }

    private fun showMsg(text: String, ok: Boolean) {
        tvMsg.visibility = View.VISIBLE
        tvMsg.text = text
        tvMsg.background = cardDrawable(if (ok) 0x2234A853 else 0x22F9AB00)
        tvMsg.setTextColor(getColor(if (ok) R.color.success else R.color.warning))
    }

    private fun showFullImage(url: String) {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xEE111111.toInt())
        }
        ImageLoader.load(url, iv)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(iv)
        iv.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun small(s: String) = TextView(this).apply {
        text = s; textSize = 12f
        setTextColor(getColor(R.color.secondary))
        setPadding(0, dp(2), 0, 0)
    }

    private fun cardDrawable(bg: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(12).toFloat()
        }

    private fun wrapLp(marginTop: Int, marginBottom: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, marginTop, 0, marginBottom) }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

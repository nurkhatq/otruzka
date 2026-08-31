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
import android.widget.EditText
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
import com.otgruzka.tsd.api.PickerBulkScanBody
import com.otgruzka.tsd.api.PickerOrderItem
import com.otgruzka.tsd.api.PickerScanBody
import com.otgruzka.tsd.api.PickerTask
import kotlinx.coroutines.launch

/**
 * Экран задания сборщика — дизайн скопирован с waybills (/picker/[task_id]):
 * «СЕЙЧАС ИЩЕМ», режимы «по заказу» и «скан + кол-во», зелёная вспышка,
 * жёлтый «неизвестный ШК», оранжевое частичное завершение, состав пачки.
 * Скан — аппаратной пикалкой; статус скана решает сервер qoimams.
 */
class PickerTaskActivity : AppCompatActivity() {

    private val BG = PickerTasksActivity.BG
    private val WHITE = Color.WHITE
    private val BORDER = PickerTasksActivity.BORDER
    private val TEXT = PickerTasksActivity.TEXT
    private val MUTED = PickerTasksActivity.MUTED
    private val FAINT = PickerTasksActivity.FAINT
    private val BLUE = PickerTasksActivity.BLUE
    private val BLUE_L = Color.parseColor("#BFDBFE")
    private val PURPLE = Color.parseColor("#9333EA")
    private val PURPLE_BG = Color.parseColor("#FAF5FF")
    private val PURPLE_BR = Color.parseColor("#D8B4FE")
    private val PURPLE_TX = Color.parseColor("#6B21A8")
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
    private val YELLOW_TX = Color.parseColor("#A16207")

    private lateinit var api: CoreApi
    private var taskId = 0
    private var task: PickerTask? = null
    private var busy = false
    private var completing = false

    private var bulkMode = false
    private var modeInitialized = false
    private var bulkBarcode: String? = null
    private var bulkWrong: String? = null

    private var cellsBySku: Map<String, List<String>> = emptyMap()
    private val barcodeBuf = StringBuilder()

    // Views
    private lateinit var tvTaskNo: TextView
    private lateinit var tvChip: TextView
    private lateinit var tvName: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvScanBuffer: TextView
    private lateinit var llContent: LinearLayout
    private lateinit var llBottom: LinearLayout
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (bulkBarcode != null) return super.dispatchKeyEvent(event)  // вводим кол-во
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

        // Header как в waybills: Задание #N [тип], имя, прогресс-бар
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
        tvTaskNo = TextView(this).apply {
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
        }
        tvChip = TextView(this).apply {
            textSize = 10f; setTypeface(null, Typeface.BOLD)
            setPadding(dp(7), dp(2), dp(7), dp(2))
        }
        row1.addView(tvTaskNo)
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
        tvName = TextView(this).apply {
            textSize = 12f; maxLines = 2
            setTextColor(MUTED)
        }
        head.addView(tvName)
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

        // Низ: «Вернуть задание в очередь» (как в waybills fixed bottom)
        llBottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(WHITE)
            elevation = dp(6).toFloat()
            setPadding(dp(16), dp(6), dp(16), dp(8))
        }
        llBottom.addView(TextView(this).apply {
            text = "Вернуть задание в очередь"
            textSize = 12f; gravity = Gravity.CENTER
            setTextColor(FAINT)
            setPadding(0, dp(8), 0, dp(8))
            setOnClickListener { confirmRelease() }
        })
        root.addView(llBottom)

        return root
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
            bulkMode = t.task_type == "mass_single"  // тип A → быстрый режим
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
        if (busy || completing || task == null) return
        val code = raw.replace(Regex("-\\d+$"), "")   // Mindeo добавляет счётчик
        if (bulkMode && !allDone()) {
            if (bulkBarcode != null) return
            handleScanBulk(code)
        } else if (!allDone()) {
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
                val st = upd?.scan?.match_status
                when (st) {
                    "matched" -> { Beep.ok(); showMsg("✓ Есть!", GREEN_BG, GREEN_TX) }
                    "no_barcode" -> { Beep.warn(); showMsg("Отмечено без штрихкода", ORANGE_BG, ORANGE_TX) }
                    else -> { Beep.warn(); showMsg("Штрихкод не тот — записан для сверки", Color.parseColor("#FEF9C3"), YELLOW_TX) }
                }
                // Авто-завершение: единственный заказ полностью собран и совпал
                busy = false
                val all = updated.orders.orEmpty()
                if (allDone() && all.map { it.order_code }.distinct().size == 1 && st == "matched") {
                    doComplete()
                }
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", RED_BG, RED_TX)
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
                showMsg("✓ Подтверждено: $qty", GREEN_BG, GREEN_TX)
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    // ─── Complete / release ──────────────────────────────────────────────────

    private fun doComplete() {
        if (completing) return
        completing = true
        lifecycleScope.launch {
            try {
                val r = api.completeTask(taskId)
                val cancelled = r.cancelled_orders ?: 0
                var msg = "Готово."
                if (cancelled > 0) msg += " В отмену: $cancelled."
                if (r.pdf_queued || !r.pdf_filenames.isNullOrEmpty()) msg += " Печать отправлена."
                toast(msg)
                finish()
            } catch (e: Exception) {
                toast("Ошибка завершения: ${e.message?.take(60)}")
                completing = false
            }
        }
    }

    private fun confirmPartialComplete() {
        val t = task ?: return
        val remaining = t.total_orders - t.scanned_qty
        android.app.AlertDialog.Builder(this)
            .setTitle("Товар закончился?")
            .setMessage("$remaining заказ(ов) не собрано — уйдут на отмену менеджеру. Завершить?")
            .setPositiveButton("Завершить") { _, _ -> doComplete() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmRelease() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Вернуть задание в очередь?")
            .setMessage("Задание достанется другому сборщику.")
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
        val t = task ?: return
        val (letter, chipBg, chipFg) = PickerTasksActivity.catChip(t.task_type)
        tvTaskNo.text = "Задание #${t.id}"
        tvChip.text = letter
        tvChip.setTextColor(chipFg)
        tvChip.background = rounded(chipBg, 6)
        tvName.text = t.product_name ?: ""
        progressBar.max = t.total_orders.coerceAtLeast(1)
        progressBar.progress = t.scanned_qty
        tvProgress.text = "${t.scanned_qty} / ${t.total_orders} заказов"

        val mine = t.picker_username == CoreAuth.username(this)
        val done = allDone()
        val anyScanned = t.orders.orEmpty().any { it.scan != null }

        llContent.removeAllViews()

        // ── Режим сканирования (массовые) ──
        if (mine && !done && t.task_type == "mass_single") {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedBorder(WHITE, BORDER, 12)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            card.addView(TextView(this).apply {
                text = "Режим:"; textSize = 11f; setTextColor(MUTED)
                setPadding(0, 0, dp(8), 0)
            })
            card.addView(modePill("По заказу", !bulkMode, BLUE) { setMode(false) })
            card.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
            card.addView(modePill("Скан + кол-во", bulkMode, PURPLE) { setMode(true) })
            llContent.addView(card)
            llContent.addView(spacer(dp(10)))
        }

        // ── Сообщение о последнем скане ──
        tvMsg = TextView(this).apply {
            textSize = 14f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        llContent.addView(tvMsg)
        llContent.addView(spacer(dp(10)))

        if (mine && !done) {
            if (bulkMode) renderBulk(t) else renderCurrent(t)
        }

        // ── Все собраны — зелёная панель ──
        if (done) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBorder2(GREEN_BG, GREEN_BR, 16)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            }
            card.addView(TextView(this).apply {
                text = "Все заказы отсканированы!"
                textSize = 17f; setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(GREEN_TX)
            })
            card.addView(TextView(this).apply {
                text = "${t.scanned_qty} из ${t.total_orders}"
                textSize = 13f; gravity = Gravity.CENTER
                setTextColor(GREEN)
                setPadding(0, dp(2), 0, dp(12))
            })
            if (mine) {
                card.addView(Button(this).apply {
                    text = if (completing) "Завершаем…" else "Завершить задание"
                    textSize = 15f; isAllCaps = false
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(WHITE)
                    isEnabled = !completing
                    backgroundTintList = android.content.res.ColorStateList.valueOf(GREEN)
                    setOnClickListener { doComplete() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))
            }
            llContent.addView(card)
            llContent.addView(spacer(dp(12)))
        }

        // ── Частичное завершение (оранжевая полоса) ──
        if (mine && !done && anyScanned) {
            val remaining = t.total_orders - t.scanned_qty
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
                text = "Товар закончился?"
                textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(ORANGE_TX)
            })
            col.addView(TextView(this).apply {
                text = "$remaining не собрано — уйдут на отмену"
                textSize = 11f; setTextColor(ORANGE)
            })
            strip.addView(col)
            strip.addView(Button(this).apply {
                text = "Завершить"
                textSize = 12f; isAllCaps = false
                setTypeface(null, Typeface.BOLD)
                setTextColor(WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ORANGE)
                setOnClickListener { confirmPartialComplete() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)))
            llContent.addView(strip)
            llContent.addView(spacer(dp(12)))
        }

        // ── Состав пачки (не для массовых) ──
        if (t.task_type != "mass_single" && (t.orders?.size ?: 0) > 1) {
            llContent.addView(renderBatchContents(t))
            llContent.addView(spacer(dp(12)))
        }

        // ── Список заказов ──
        llContent.addView(TextView(this).apply {
            text = "СПИСОК ЗАКАЗОВ"
            textSize = 10f; letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(MUTED)
            setPadding(dp(2), 0, 0, dp(6))
        })
        renderOrders(t)

        llBottom.visibility = if (mine && t.status == "claimed" && !done) View.VISIBLE else View.GONE
    }

    private fun setMode(bulk: Boolean) {
        bulkMode = bulk
        bulkBarcode = null; bulkWrong = null
        render()
    }

    private fun modePill(label: String, active: Boolean, activeColor: Int, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 12f; setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (active) WHITE else MUTED)
            background = rounded(if (active) activeColor else Color.parseColor("#F3F4F6"), 8)
            setPadding(0, dp(8), 0, dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    /** «СЕЙЧАС ИЩЕМ» — синяя карточка как в waybills. */
    private fun renderCurrent(t: PickerTask) {
        val cur = nextPending() ?: return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBorder2(WHITE, BLUE_L, 16)
            setPadding(dp(14), dp(11), dp(14), dp(13))
        }
        card.addView(TextView(this).apply {
            text = "СЕЙЧАС ИЩЕМ"
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
        card.addView(TextView(this).apply {
            text = cur.order_code +
                if ((cur.num_positions ?: 1) > 1)
                    " · поз. ${(cur.position_index ?: 0) + 1}/${cur.num_positions}" else ""
            textSize = 11f; setTextColor(FAINT)
        })
        if (required(cur) > 1) {
            card.addView(TextView(this).apply {
                text = "Отсканировано ${qtyDone(cur)} / ${required(cur)} шт"
                textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(BLUE)
                setPadding(0, dp(3), 0, 0)
            })
        }
        val expected = cur.expected_barcode ?: t.expected_barcode
        when {
            expected != null -> card.addView(TextView(this).apply {
                text = "ШК: $expected"
                textSize = 11f; setTextColor(FAINT)
                setPadding(0, dp(2), 0, 0)
            })
            cur.is_kit == true -> card.addView(TextView(this).apply {
                text = "Набор — отсканируйте штрихкод"
                textSize = 11f; setTextColor(PURPLE)
                setPadding(0, dp(2), 0, 0)
            })
            else -> card.addView(TextView(this).apply {
                text = "Штрихкод не задан в системе"
                textSize = 11f; setTextColor(ORANGE)
                setPadding(0, dp(2), 0, 0)
            })
        }
        val comps = cur.components.orEmpty()
        if (cur.is_kit == true && comps.isNotEmpty()) {
            comps.forEach { c ->
                card.addView(TextView(this).apply {
                    text = "↳ ${c.name ?: c.sku ?: "компонент"}" +
                        if ((c.qty ?: 1) > 1) " ×${c.qty}" else ""
                    textSize = 11f; setTextColor(PURPLE)
                    setPadding(0, dp(1), 0, 0)
                })
            }
        }
        val cells = cellsBySku[cur.offer_code].orEmpty()
        if (cells.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = "📍 ${cells.joinToString(", ")}"
                textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#7C3AED"))
                setPadding(0, dp(3), 0, 0)
            })
        }
        addImagesRow(card, cur.images.orEmpty().take(4), 130)
        llContent.addView(card)
        llContent.addView(spacer(dp(10)))
    }

    /** Bulk-режим: фото + ожидание скана / красная ошибка / фиолетовый ввод кол-ва. */
    private fun renderBulk(t: PickerTask) {
        val remaining = t.orders.orEmpty().count { it.scan == null }

        val wrong = bulkWrong
        if (wrong != null) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBorder2(RED_BG, RED_BR, 16)
                setPadding(dp(14), dp(12), dp(14), dp(14))
            }
            card.addView(TextView(this).apply {
                text = "Неверный штрихкод"
                textSize = 13f; setTypeface(null, Typeface.BOLD)
                setTextColor(RED_TX)
            })
            card.addView(TextView(this).apply {
                text = wrong
                textSize = 11f; setTextColor(Color.parseColor("#DC2626"))
                setPadding(0, dp(2), 0, 0)
            })
            card.addView(TextView(this).apply {
                text = "Ожидается товар: ${t.product_name ?: ""}"
                textSize = 11f; setTextColor(Color.parseColor("#EF4444"))
                setPadding(0, dp(2), 0, 0)
            })
            val btns = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            btns.addView(grayButton("Пересканировать") { bulkWrong = null; render() })
            btns.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
            btns.addView(colorButton("Всё равно верно", GREEN) { startBulkEntry(wrong) })
            card.addView(btns)
            llContent.addView(card)
            llContent.addView(spacer(dp(10)))
            return
        }

        val bc = bulkBarcode
        if (bc == null) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBorder(WHITE, BORDER, 16)
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            card.addView(TextView(this).apply {
                text = t.product_name ?: ""
                textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(PURPLE)
            })
            addImagesRow(card, t.orders.orEmpty().firstOrNull()?.images.orEmpty().take(3), 130)
            card.addView(TextView(this).apply {
                text = "Отсканируйте штрихкод любого товара из задания"
                textSize = 11f; gravity = Gravity.CENTER
                setTextColor(FAINT)
                setPadding(0, dp(8), 0, 0)
            })
            llContent.addView(card)
            llContent.addView(spacer(dp(10)))
            return
        }

        // Ввод количества — фиолетовая панель
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBorder2(PURPLE_BG, PURPLE_BR, 16)
            setPadding(dp(14), dp(12), dp(14), dp(14))
        }
        card.addView(TextView(this).apply {
            text = "Отсканировано: $bc"
            textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(PURPLE_TX)
        })
        card.addView(TextView(this).apply {
            text = "Осталось заказов: $remaining. Сколько собрали?"
            textSize = 11f; setTextColor(PURPLE)
            setPadding(0, dp(2), 0, dp(8))
        })
        val qtyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        etBulkQty = EditText(this).apply {
            setText(remaining.toString())
            textSize = 22f; gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(PURPLE_TX)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            background = roundedBorder2(WHITE, PURPLE_BR, 12)
        }
        qtyRow.addView(stepButton("−") {
            val v = (etBulkQty.text.toString().toIntOrNull() ?: 1) - 1
            etBulkQty.setText(maxOf(1, v).toString())
        })
        qtyRow.addView(etBulkQty, LinearLayout.LayoutParams(0, dp(50), 1f)
            .apply { leftMargin = dp(8); rightMargin = dp(8) })
        qtyRow.addView(stepButton("+") {
            val v = (etBulkQty.text.toString().toIntOrNull() ?: 0) + 1
            etBulkQty.setText(minOf(remaining, v).toString())
        })
        card.addView(qtyRow)
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        btns.addView(grayButton("Пересканировать") { bulkBarcode = null; render() })
        btns.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        btns.addView(colorButton("Подтвердить", PURPLE) { confirmBulk() })
        card.addView(btns)
        llContent.addView(card)
        llContent.addView(spacer(dp(10)))
    }

    /** «Состав пачки» — синяя панель как в waybills (тип B). */
    private fun renderBatchContents(t: PickerTask): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBorder2(Color.parseColor("#EFF6FF"), Color.parseColor("#BFDBFE"), 12)
            setPadding(dp(12), dp(10), dp(12), dp(11))
        }
        card.addView(TextView(this).apply {
            text = "СОСТАВ ПАЧКИ"
            textSize = 10f; letterSpacing = 0.06f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1D4ED8"))
            setPadding(0, 0, 0, dp(4))
        })
        data class Uniq(var name: String, var qty: Int, val barcode: String?, val isKit: Boolean)
        val uniq = LinkedHashMap<String, Uniq>()
        t.orders.orEmpty().forEach { o ->
            val key = o.offer_code ?: o.name ?: "?"
            val ex = uniq[key]
            if (ex != null) ex.qty += o.quantity ?: 1
            else uniq[key] = Uniq(o.name ?: key, o.quantity ?: 1,
                o.expected_barcode, o.is_kit == true)
        }
        uniq.values.forEach { u ->
            val row = TextView(this).apply {
                text = "• ${u.name}" +
                    (if (u.qty > 1) "  ×${u.qty}" else "") +
                    (if (u.isKit) "  (комплект)" else u.barcode?.let { "  $it" } ?: "  нет ШК")
                textSize = 11f
                setTextColor(if (u.isKit) PURPLE else Color.parseColor("#374151"))
                setPadding(0, dp(2), 0, 0)
            }
            card.addView(row)
        }
        return card
    }

    private fun renderOrders(t: PickerTask) {
        val next = if (bulkMode) null else nextPending()
        t.orders.orEmpty().forEach { o ->
            val doneQty = qtyDone(o)
            val req = required(o)
            val positionDone = doneQty >= req
            val st = if (positionDone) o.scan?.match_status else null
            val isCurrent = next != null &&
                o.order_code == next.order_code &&
                (o.position_index ?: 0) == (next.position_index ?: 0)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = if (isCurrent)
                    roundedBorder2(WHITE, BLUE, 12)
                else
                    roundedBorder(WHITE, Color.parseColor("#F3F4F6"), 12)
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            // Иконка статуса как в waybills: ✓ ! × — ○ ●
            row.addView(TextView(this).apply {
                text = when {
                    !positionDone && doneQty > 0 -> "●"
                    st == "matched" -> "✓"
                    st == "unknown_barcode" -> "!"
                    st == "no_barcode" -> "×"
                    st == "skipped" -> "—"
                    positionDone -> "✓"
                    else -> "○"
                }
                textSize = 16f; setTypeface(null, Typeface.BOLD)
                setTextColor(when {
                    !positionDone && doneQty > 0 -> BLUE
                    st == "matched" -> Color.parseColor("#22C55E")
                    st == "unknown_barcode" -> Color.parseColor("#EAB308")
                    st == "no_barcode" -> Color.parseColor("#F87171")
                    positionDone -> Color.parseColor("#22C55E")
                    else -> Color.parseColor("#E5E7EB")
                })
                setPadding(0, 0, dp(10), 0)
            })
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this).apply {
                text = o.name ?: o.offer_code ?: "—"
                textSize = 13f; maxLines = 2
                setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(TEXT)
            })
            val cells = cellsBySku[o.offer_code].orEmpty()
            col.addView(TextView(this).apply {
                text = o.order_code +
                    (if ((o.num_positions ?: 1) > 1) " · поз. ${(o.position_index ?: 0) + 1}/${o.num_positions}" else "") +
                    (if (req > 1) " · ${if (doneQty > 0) "$doneQty/" else ""}$req шт" else "") +
                    (if (cells.isNotEmpty()) " · 📍${cells.joinToString(",")}" else "")
                textSize = 11f; setTextColor(FAINT)
                setPadding(0, dp(1), 0, 0)
            })
            o.scan?.barcode_scanned?.let { bcs ->
                col.addView(TextView(this).apply {
                    text = "✓ $bcs"
                    textSize = 10f; setTextColor(Color.parseColor("#16A34A"))
                })
            }
            row.addView(col)
            row.addView(TextView(this).apply {
                text = when {
                    !positionDone && doneQty > 0 -> "$doneQty/$req шт"
                    st == "matched" -> "Совпало"
                    st == "unknown_barcode" -> "Неизвестный ШК"
                    st == "no_barcode" -> "Нет ШК"
                    st == "skipped" -> "Пропущен"
                    positionDone -> "Готово"
                    isCurrent -> "← текущий"
                    else -> "Ожидает"
                }
                textSize = 11f; setTypeface(null, Typeface.BOLD)
                setTextColor(when {
                    !positionDone && doneQty > 0 -> BLUE
                    st == "matched" -> Color.parseColor("#16A34A")
                    st == "unknown_barcode" -> Color.parseColor("#CA8A04")
                    st == "no_barcode" -> Color.parseColor("#EF4444")
                    isCurrent -> BLUE
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
                setOnClickListener { showFullImage(url) }
            }
            ImageLoader.load(url, iv)
            row.addView(iv)
        }
        hs.addView(row)
        card.addView(hs)
    }

    private fun showFullImage(url: String) {
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.parseColor("#EB111111"))
        }
        ImageLoader.load(url, iv)
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(iv)
        iv.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    // ─── Small helpers ───────────────────────────────────────────────────────

    private fun grayButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor("#374151"))
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F3F4F6"))
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        setOnClickListener { onClick() }
    }

    private fun colorButton(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        setOnClickListener { onClick() }
    }

    private fun stepButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 20f; isAllCaps = false
        setTextColor(PURPLE_TX)
        backgroundTintList = android.content.res.ColorStateList.valueOf(WHITE)
        layoutParams = LinearLayout.LayoutParams(dp(50), dp(50))
        setOnClickListener { onClick() }
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

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

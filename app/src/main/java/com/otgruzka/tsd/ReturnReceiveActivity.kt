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
 * Экран приёмки одного возврата — решение ПО КАЖДОЙ ВЕЩИ сразу при скане:
 * скан → карточка товара + две большие кнопки «БРАК» (красная) и «ВЕРНУТЬ
 * НА СКЛАД» (зелёная, справа) → следующая вещь → когда всё принято, приёмка
 * завершается САМА и экран закрывается (сканируй следующую коробку).
 * Кнопки «Завершить» и диалога брака нет (решение владельца 2026-08-31).
 * Брак пишется на сервер сразу (resume не теряет решения).
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
    private val RED = Color.parseColor("#DC2626")
    private val RED_BG = Color.parseColor("#FEF2F2")
    private val RED_TX = Color.parseColor("#991B1B")
    private val PURPLE_BG = Color.parseColor("#FAF5FF")
    private val PURPLE_BR = Color.parseColor("#D8B4FE")
    private val PURPLE_TX = Color.parseColor("#6B21A8")
    private val YELLOW_TX = Color.parseColor("#A16207")

    private lateinit var api: CoreApi
    private var receivingId = 0
    private var rec: ReturnReceiving? = null
    private var busy = false
    private var completing = false

    // Вещь, по которой ждём решения БРАК/ВЕРНУТЬ (после MATCHED-скана)
    private var pendingLineId: Int? = null

    private val barcodeBuf = StringBuilder()

    private lateinit var tvTitle: TextView
    private lateinit var tvChip: TextView
    private lateinit var tvSubtitle: TextView
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
        tvSubtitle = TextView(this).apply {
            textSize = 12f; setTextColor(MUTED)
        }
        head.addView(tvSubtitle)
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
                rec = api.returnsGet(receivingId)
                render()
                // Resume полностью отсканированной сессии: решений больше не
                // ждём (брак уже на сервере) — завершаем сами, кнопки нет
                if (rec?.status == "IN_PROGRESS" && allDone() && anyScanned() &&
                    pendingLineId == null && !completing
                ) {
                    autoComplete()
                }
            } catch (e: Exception) {
                toast("Ошибка загрузки: ${e.message?.take(60)}")
                finish()
            }
        }
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
    private fun lineById(id: Int?): ReturnLine? =
        rec?.lines.orEmpty().firstOrNull { it.line_id == id }

    // ─── Scan ────────────────────────────────────────────────────────────────

    private fun handleBarcode(raw: String) {
        if (busy || completing || rec == null) return
        if (pendingLineId != null) {
            // Сначала реши по вещи в руках — потом сканируй следующую
            Beep.warn()
            showMsg("Сначала нажмите БРАК или ВЕРНУТЬ НА СКЛАД", ORANGE_BG, ORANGE_TX)
            return
        }
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
                when (res.result) {
                    "MATCHED" -> {
                        Beep.ok()
                        pendingLineId = res.receiving?.lines.orEmpty()
                            .firstOrNull { it.product_id == res.product_id }?.line_id
                        render()
                    }
                    "EXCESS" -> {
                        Beep.warn(); render()
                        showMsg("Уже принято нужное количество", Color.parseColor("#FEF9C3"), YELLOW_TX)
                    }
                    "UNEXPECTED" -> {
                        Beep.error(); render()
                        showMsg("Товар не из этого заказа", RED_BG, RED_TX)
                    }
                    "UNKNOWN" -> {
                        Beep.error(); render()
                        showMsg("Штрихкод не найден в системе", RED_BG, RED_TX)
                    }
                    "AMBIGUOUS" -> {
                        Beep.warn(); render()
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

    // ─── Решение по вещи: БРАК / ВЕРНУТЬ НА СКЛАД ────────────────────────────

    private fun decide(defect: Boolean) {
        val lineId = pendingLineId ?: return
        val line = lineById(lineId) ?: return
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                if (defect) {
                    val newDefect = n(line.qty_defect) + 1
                    val res = api.returnsLineDefect(
                        receivingId, ReturnDefectLine(lineId, fmtN(newDefect))
                    )
                    rec = res.receiving ?: api.returnsGet(receivingId)
                    Beep.warn()
                } else {
                    Beep.ok()
                }
                pendingLineId = null
                if (allDone()) {
                    autoComplete()
                } else {
                    render()
                    showMsg(
                        if (defect) "Брак отмечен — следующий товар"
                        else "✓ На склад — следующий товар",
                        if (defect) RED_BG else GREEN_BG,
                        if (defect) RED_TX else GREEN_TX
                    )
                }
            } catch (e: Exception) {
                Beep.error()
                showMsg("Ошибка: ${e.message?.take(60)}", RED_BG, RED_TX)
            } finally {
                busy = false
            }
        }
    }

    // ─── Завершение (автоматическое; частичное — одной кнопкой) ─────────────

    private fun autoComplete(force: Boolean = false) {
        if (completing) return
        completing = true
        render()
        lifecycleScope.launch {
            try {
                // брак уже сохранён по-вещно (line-defect) — defects не шлём
                val res = api.returnsComplete(receivingId, ReturnCompleteBody(force = force))
                val msg = if (res.stock_restored == true) {
                    "Заказ принят. Годное: ${res.good_qty} → ДХ" +
                        (if (n(res.defect_qty) > 0) ", брак: ${res.defect_qty} → зона брака" else "")
                } else {
                    "Принято журнально — остатки не двигались (заказ отгружался до складского учёта)"
                }
                Toast.makeText(this@ReturnReceiveActivity, msg, Toast.LENGTH_LONG).show()
                Beep.ok()
                finish()   // назад к скану следующей коробки
            } catch (e: HttpException) {
                completing = false
                if (e.code() == 409 && !force) {
                    // не всё отсканировано — спрашивает только частичный путь
                    confirmPartial()
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

    /** «В коробке больше ничего нет»: возврат — норма Kaspi, завершаем сразу;
     *  отмена должна вернуться целиком — одно предупреждение. */
    private fun confirmPartial() {
        val r = rec ?: return
        if (r.kind != "cancel_shipped") {
            autoComplete(force = true)
            return
        }
        val missing = r.lines.orEmpty().sumOf { expected(it) - scanned(it) }
        android.app.AlertDialog.Builder(this)
            .setTitle("⚠ Не всё вернулось")
            .setMessage(
                "Отменённый заказ должен вернуться целиком, но ${fmtN(missing)} поз. нет " +
                    "в коробке. Проверьте ещё раз! Завершить с нехваткой?"
            )
            .setPositiveButton("Завершить") { _, _ -> autoComplete(force = true) }
            .setNegativeButton("Назад", null)
            .show()
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
        tvSubtitle.text = if (r.stock_restore) "Скан товара → решение: брак или на склад"
            else "Журнальная приёмка — остатки не двигаются"
        val te = n(r.total_expected)
        val ts = n(r.total_scanned)
        progressBar.max = (te.toInt()).coerceAtLeast(1)
        progressBar.progress = ts.toInt()
        tvProgress.text = "${fmtN(ts)} / ${fmtN(te)} шт"

        llContent.removeAllViews()

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

        val pending = lineById(pendingLineId)
        when {
            completing -> {
                val card = card(GREEN_BG, GREEN_BR)
                card.addView(TextView(this).apply {
                    text = "Завершаем приёмку…"
                    textSize = 15f; setTypeface(null, Typeface.BOLD)
                    gravity = Gravity.CENTER
                    setTextColor(GREEN_TX)
                    setPadding(0, dp(12), 0, dp(12))
                })
                llContent.addView(card)
                llContent.addView(spacer(dp(12)))
            }
            pending != null -> renderDecision(pending)
            !allDone() -> renderCurrent()
        }

        // «В коробке больше ничего нет» — частичный возврат (норма Kaspi)
        if (!completing && pending == null && !allDone() && anyScanned()) {
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
                text = "В коробке больше ничего нет?"
                textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(ORANGE_TX)
            })
            col.addView(TextView(this).apply {
                text = if (rec?.kind != "cancel_shipped")
                    "Для возвратов Kaspi часть заказа — это нормально"
                else "Отмена должна вернуться целиком"
                textSize = 11f; setTextColor(ORANGE)
            })
            strip.addView(col)
            strip.addView(Button(this).apply {
                text = "Готово"
                textSize = 12f; isAllCaps = false
                setTypeface(null, Typeface.BOLD)
                setTextColor(WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ORANGE)
                setOnClickListener { autoComplete() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)))
            llContent.addView(strip)
            llContent.addView(spacer(dp(12)))
        }

        llContent.addView(TextView(this).apply {
            text = "СОСТАВ ВОЗВРАТА"
            textSize = 10f; letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(MUTED)
            setPadding(dp(2), 0, 0, dp(6))
        })
        renderLines(r)

        llBottom.visibility =
            if (r.status == "IN_PROGRESS" && !completing) View.VISIBLE else View.GONE
    }

    /** Решение по отсканированной вещи: КРАСНАЯ «БРАК» слева, ЗЕЛЁНАЯ
     *  «ВЕРНУТЬ НА СКЛАД» справа — сразу понятно, куда жать. */
    private fun renderDecision(line: ReturnLine) {
        val card = card(WHITE, BLUE_L)
        card.addView(TextView(this).apply {
            text = "КУДА ЭТОТ ТОВАР?"
            textSize = 10f; letterSpacing = 0.06f
            setTypeface(null, Typeface.BOLD)
            setTextColor(BLUE)
        })
        card.addView(TextView(this).apply {
            text = line.name ?: line.offer_code ?: "—"
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(TEXT)
            setPadding(0, dp(3), 0, 0)
        })
        if (expected(line) > 1) {
            card.addView(TextView(this).apply {
                text = "Вещь ${fmtN(scanned(line))} из ${fmtN(expected(line))}"
                textSize = 12f; setTextColor(MUTED)
                setPadding(0, dp(2), 0, 0)
            })
        }
        addImagesRow(card, line.images.orEmpty().take(3), 110)

        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }
        // Кнопки всегда активны: от двойного нажатия защищает guard в decide()
        // (render зовётся при busy=true — задизейбленная кнопка тут зависла бы)
        btns.addView(Button(this).apply {
            text = "БРАК"
            textSize = 16f; isAllCaps = true
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(RED)
            setOnClickListener { decide(defect = true) }
        }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(5) })
        btns.addView(Button(this).apply {
            text = "ВЕРНУТЬ\nНА СКЛАД"
            textSize = 14f; isAllCaps = true
            setTypeface(null, Typeface.BOLD)
            setTextColor(WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(GREEN)
            setOnClickListener { decide(defect = false) }
        }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { leftMargin = dp(5) })
        card.addView(btns)
        llContent.addView(card)
        llContent.addView(spacer(dp(12)))
    }

    /** «СЕЙЧАС ПРИНИМАЕМ» — что сканировать следующим. */
    private fun renderCurrent() {
        val cur = nextPending() ?: return
        val card = card(WHITE, BLUE_L)
        card.addView(TextView(this).apply {
            text = "СЕЙЧАС ПРИНИМАЕМ — ОТСКАНИРУЙТЕ ТОВАР"
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
            val df = n(l.qty_defect)
            val lineDone = sc >= exp
            val isCurrent = !completing && pendingLineId == null &&
                next != null && l.line_id == next.line_id
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
                    (if (exp > 1) " · ${fmtN(sc)}/${fmtN(exp)} шт" else "") +
                    (if (df > 0) " · брак ${fmtN(df)}" else "")
                textSize = 11f
                setTextColor(if (df > 0) RED else FAINT)
                setPadding(0, dp(1), 0, 0)
            })
            row.addView(col)
            row.addView(TextView(this).apply {
                text = when {
                    lineDone && df > 0 -> "Брак ${fmtN(df)}"
                    lineDone -> "Принято"
                    sc > 0 -> "${fmtN(sc)}/${fmtN(exp)}"
                    isCurrent -> "← текущий"
                    else -> "Ожидает"
                }
                textSize = 11f; setTypeface(null, Typeface.BOLD)
                setTextColor(when {
                    lineDone && df > 0 -> RED
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

    private fun card(bg: Int, stroke: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBorder2(bg, stroke, 16)
        setPadding(dp(14), dp(11), dp(14), dp(13))
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

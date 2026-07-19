package com.otgruzka.tsd

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.*
import kotlinx.coroutines.launch

class InventoryActivity : AppCompatActivity() {

    private lateinit var api: WmsApi
    private var currentSession: InvSession? = null

    private lateinit var tvInvSession: TextView
    private lateinit var tvScanMode: TextView
    private lateinit var tvStatTotal: TextView
    private lateinit var tvStatMatched: TextView
    private lateinit var tvStatPending: TextView
    private lateinit var tvStatDisc: TextView
    private lateinit var tvInvBuffer: TextView
    private lateinit var panelBuffer: View
    private lateinit var panelScanCount: View
    private lateinit var tvCountBarcode: TextView
    private lateinit var tvCountProduct: TextView
    private lateinit var tvCountStock: TextView
    private lateinit var tvCountQty: TextView
    private lateinit var llInvList: LinearLayout
    private lateinit var svInvList: ScrollView

    private var isScanCountMode = false
    private var countingBarcode: String? = null
    private var countingQty = 0

    private val barcodeBuf = StringBuilder()
    private val recentScans = mutableListOf<InvScanResponse>()
    private val scanMap = HashMap<String, InvScanResponse>() // barcode → latest scan for O(1) lookup

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
        if (!WmsAuth.isLoggedIn(this)) { finish(); return }
        api = WmsApiClient.build(this)
        setContentView(R.layout.activity_inventory)
        bindViews()
        initSession()
    }

    private fun bindViews() {
        tvInvSession   = findViewById(R.id.tvInvSession)
        tvScanMode     = findViewById(R.id.tvScanMode)
        tvStatTotal    = findViewById(R.id.tvStatTotal)
        tvStatMatched  = findViewById(R.id.tvStatMatched)
        tvStatPending  = findViewById(R.id.tvStatPending)
        tvStatDisc     = findViewById(R.id.tvStatDisc)
        tvInvBuffer    = findViewById(R.id.tvInvBuffer)
        panelBuffer    = findViewById(R.id.panelBuffer)
        panelScanCount = findViewById(R.id.panelScanCount)
        tvCountBarcode = findViewById(R.id.tvCountBarcode)
        tvCountProduct = findViewById(R.id.tvCountProduct)
        tvCountStock   = findViewById(R.id.tvCountStock)
        tvCountQty     = findViewById(R.id.tvCountQty)
        llInvList      = findViewById(R.id.llInvList)
        svInvList      = findViewById(R.id.svInvList)

        findViewById<TextView>(R.id.tvHamburger).setOnClickListener { showMenu(it) }
        findViewById<Button>(R.id.btnCountConfirm).setOnClickListener { confirmScanCount() }
        findViewById<Button>(R.id.btnCountCancel).setOnClickListener { resetScanCount() }
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

    // ─── Menu ─────────────────────────────────────────────────────────────────

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            add(0, 1, 0, if (isScanCountMode) "✓ Режим подсчёта пиком" else "Режим подсчёта пиком")
            add(0, 2, 0, "История")
            add(0, 3, 0, "✓ Завершить")
            add(0, 4, 0, "Отменить сессию")
            add(0, 5, 0, "Назад")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> toggleScanCountMode()
                2 -> startActivity(Intent(this, InvHistoryActivity::class.java))
                3 -> confirmComplete()
                4 -> confirmCancel()
                5 -> finish()
            }
            true
        }
        popup.show()
    }

    // ─── Session ─────────────────────────────────────────────────────────────

    private fun initSession() {
        tvInvSession.text = "Подключение…"
        lifecycleScope.launch {
            try {
                val existing = runCatching { api.getActiveInvSession() }.getOrNull()
                currentSession = existing ?: api.createInvSession(CreateSessionBody())
                updateSessionLabel()
                // Load existing scans so list is visible on re-entry and for all TSD devices
                if (existing != null) loadExistingScans(existing.session_id)
                refreshStats()
            } catch (e: Exception) {
                tvInvSession.text = "Ошибка: ${e.message?.take(50)}"
            }
        }
    }

    private suspend fun loadExistingScans(sessionId: String) {
        try {
            val resp = api.getInvSessionItems(sessionId, pageSize = 500)
            recentScans.clear()
            scanMap.clear()
            resp.items.forEach { item ->
                val scan = InvScanResponse(
                    id = item.id,
                    barcode = item.barcode,
                    product_name = item.product_name,
                    ms_stock = item.ms_stock,
                    counter_qty = item.counter_qty,
                    verifier_qty = item.verifier_qty,
                    status = item.status,
                    role = "loaded",
                    message = item.status,
                )
                // scanMap holds ALL scans for duplicate detection
                scanMap[item.barcode] = scan
                // recentScans holds only last 50 for display
                if (recentScans.size < 50) recentScans.add(scan)
            }
            refreshList()
        } catch (_: Exception) {}
    }

    private fun updateSessionLabel() {
        val s = currentSession ?: return
        val wh = WmsAuth.WAREHOUSE_NAMES[s.warehouse_id] ?: "Склад ${s.warehouse_id}"
        tvInvSession.text = "$wh · ${s.total ?: 0} позиций"
    }

    private fun refreshStats() {
        val sid = currentSession?.session_id ?: return
        lifecycleScope.launch {
            try {
                val stats = api.getInvSessionStats(sid)
                val bs = stats.by_status
                tvStatTotal.text   = stats.total.toString()
                tvStatMatched.text = ((bs["MATCHED"] ?: 0) + (bs["VERIFIED"] ?: 0)).toString()
                tvStatPending.text = (bs["PENDING"] ?: 0).toString()
                tvStatDisc.text    = (bs["DISCREPANCY"] ?: 0).toString()
                currentSession = currentSession?.copy(total = stats.total)
                updateSessionLabel()
            } catch (_: Exception) {}
        }
    }

    // ─── Scan-count mode ─────────────────────────────────────────────────────

    private fun toggleScanCountMode() {
        isScanCountMode = !isScanCountMode
        tvScanMode.visibility    = if (isScanCountMode) View.VISIBLE else View.GONE
        panelScanCount.visibility = if (isScanCountMode) View.VISIBLE else View.GONE
        panelBuffer.visibility   = if (isScanCountMode) View.GONE else View.VISIBLE
        if (!isScanCountMode) resetScanCount()
        else toast("Режим подсчёта: каждый пик = +1")
    }

    private fun resetScanCount() {
        countingBarcode = null
        countingQty = 0
        tvCountBarcode.text = "штрихкод"
        tvCountProduct.text = "Сканируйте товар"
        tvCountStock.text = ""
        tvCountQty.text = "0"
    }

    private fun handleScanCountMode(barcode: String) {
        if (countingBarcode == null || countingBarcode == barcode) {
            if (countingBarcode == null) {
                // First scan — start counting, load product name
                countingBarcode = barcode
                tvCountBarcode.text = barcode
                tvCountProduct.text = "загрузка…"
                tvCountStock.text = ""
                lifecycleScope.launch {
                    try {
                        val info = api.getInvProduct(barcode)
                        tvCountProduct.text = info.product_name ?: barcode
                        if (info.ms_stock != null) {
                            tvCountStock.text = "В МС: ${info.ms_stock.toInt()}"
                        }
                    } catch (_: Exception) {
                        tvCountProduct.text = barcode
                    }
                }
            }
            countingQty++
            tvCountQty.text = countingQty.toString()
            beep(880, 60, 1, 0, square = false) // short happy beep per scan
        } else {
            // Different barcode — auto-submit current, start new
            val prevBarcode = countingBarcode!!
            val prevQty = countingQty
            submitScan(currentSession!!, prevBarcode, prevQty)
            // Start fresh for new barcode
            countingBarcode = barcode
            countingQty = 1
            tvCountBarcode.text = barcode
            tvCountProduct.text = "загрузка…"
            tvCountStock.text = ""
            tvCountQty.text = "1"
            lifecycleScope.launch {
                try {
                    val info = api.getInvProduct(barcode)
                    tvCountProduct.text = info.product_name ?: barcode
                    if (info.ms_stock != null) tvCountStock.text = "В МС: ${info.ms_stock.toInt()}"
                } catch (_: Exception) { tvCountProduct.text = barcode }
            }
            beep(880, 60, 1, 0, square = false)
        }
    }

    private fun confirmScanCount() {
        val session = currentSession ?: return
        val barcode = countingBarcode ?: run { toast("Сначала отсканируйте товар"); return }
        if (countingQty == 0) { toast("Количество 0"); return }
        submitScan(session, barcode, countingQty)
        resetScanCount()
    }

    // ─── Barcode handling ────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val ch = event.unicodeChar.toChar()
            when {
                event.keyCode == KeyEvent.KEYCODE_ENTER && barcodeBuf.isNotEmpty() -> {
                    val raw = barcodeBuf.toString().trim()
                    barcodeBuf.clear(); tvInvBuffer.text = ""
                    handleBarcode(raw)
                    return true
                }
                ch > ' ' -> {
                    barcodeBuf.append(ch); tvInvBuffer.text = barcodeBuf.toString()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBarcode(barcode: String) {
        val session = currentSession ?: run { toast("Нет активной сессии"); return }
        if (isScanCountMode) {
            handleScanCountMode(barcode)
        } else {
            checkDuplicateAndShow(barcode, session)
        }
    }

    // ─── Duplicate check & qty dialog ────────────────────────────────────────

    private fun checkDuplicateAndShow(barcode: String, session: InvSession) {
        val existing = scanMap[barcode]
        if (existing == null) {
            loadProductAndShowDialog(barcode, session, prefillQty = null, isVerify = false)
            return
        }

        when (existing.status) {
            "PENDING" -> {
                // Needs verification — different beep, clear prompt
                beep(660, 200, 2, 100, square = false)
                val name = existing.product_name ?: barcode
                AlertDialog.Builder(this)
                    .setTitle("⏳ Требует проверки")
                    .setMessage("$name\n\nПервый сотрудник насчитал: ${existing.counter_qty}\n\nВведите ваше количество для проверки:")
                    .setPositiveButton("Проверить") { _, _ ->
                        loadProductAndShowDialog(barcode, session, prefillQty = null, isVerify = true)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            "MATCHED", "VERIFIED" -> {
                // Already confirmed — double warning beep
                beep(1500, 180, 2, 80, square = true)
                val name = existing.product_name ?: barcode
                val label = if (existing.status == "MATCHED") "совпало с МС" else "подтверждено двумя"
                AlertDialog.Builder(this)
                    .setTitle("✓ Уже подтверждено")
                    .setMessage("$name\nКол-во: ${existing.counter_qty}  ($label)\n\nПересчитать заново?")
                    .setPositiveButton("Пересчитать") { _, _ ->
                        loadProductAndShowDialog(barcode, session, prefillQty = existing.counter_qty, isVerify = false)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            "DISCREPANCY" -> {
                // Has discrepancy — urgent beep
                beep(400, 250, 3, 80, square = true)
                val name = existing.product_name ?: barcode
                AlertDialog.Builder(this)
                    .setTitle("⚠ Расхождение!")
                    .setMessage("$name\n1-й: ${existing.counter_qty}  ·  2-й: ${existing.verifier_qty}\n\nПересчитать заново?")
                    .setPositiveButton("Пересчитать") { _, _ ->
                        loadProductAndShowDialog(barcode, session, prefillQty = null, isVerify = false)
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            else -> loadProductAndShowDialog(barcode, session, prefillQty = null, isVerify = false)
        }
    }

    private fun loadProductAndShowDialog(
        barcode: String, session: InvSession, prefillQty: Int?, isVerify: Boolean
    ) {
        lifecycleScope.launch {
            var productName: String? = null
            var msStock: Double? = null
            try {
                val info = api.getInvProduct(barcode)
                productName = info.product_name
                msStock = info.ms_stock
            } catch (_: Exception) {}
            showQtyDialog(barcode, session, productName, msStock, prefillQty, isVerify)
        }
    }

    private fun showQtyDialog(
        barcode: String,
        session: InvSession,
        productName: String?,
        msStock: Double?,
        prefillQty: Int?,
        isVerify: Boolean = false
    ) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            textSize = 28f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            if (prefillQty != null) setText(prefillQty.toString())
        }

        // Build message: barcode + ms stock info
        val msgParts = mutableListOf(barcode)
        if (msStock != null) msgParts.add("В МС: ${msStock.toInt()} шт")
        if (isVerify) msgParts.add("⏳ режим проверки")
        val message = msgParts.joinToString("  ·  ")

        val title = if (!productName.isNullOrBlank()) productName else barcode

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(input)
            .setPositiveButton("Подтвердить") { dialog, _ ->
                imm.hideSoftInputFromWindow(input.windowToken, 0)
                dialog.dismiss()
                val qty = input.text.toString().trim().toIntOrNull()
                if (qty == null || qty < 0) { toast("Введите корректное количество"); return@setPositiveButton }
                submitScan(session, barcode, qty)
            }
            .setNegativeButton("Отмена") { dialog, _ ->
                imm.hideSoftInputFromWindow(input.windowToken, 0)
                dialog.dismiss()
            }
            .show()

        // Select all pre-filled text so user can immediately overtype
        input.post {
            input.selectAll()
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    // ─── Submit ───────────────────────────────────────────────────────────────

    private fun submitScan(session: InvSession, barcode: String, qty: Int) {
        lifecycleScope.launch {
            try {
                val result = api.invScan(InvScanBody(session.session_id, barcode, qty))
                if (isScanCountMode && tvCountBarcode.text == barcode) {
                    tvCountProduct.text = result.product_name ?: barcode
                }
                scanMap[result.barcode] = result
                val idx = recentScans.indexOfFirst { it.id == result.id }
                if (idx >= 0) {
                    recentScans[idx] = result
                    // Replace just the one changed row (rows are at even indices, spacers at odd)
                    val viewIdx = idx * 2
                    if (viewIdx < llInvList.childCount) {
                        llInvList.removeViewAt(viewIdx)
                        llInvList.addView(buildScanRow(result), viewIdx)
                    }
                } else {
                    recentScans.add(0, result)
                    llInvList.addView(buildScanRow(result), 0)
                    llInvList.addView(spacer(dp(6)), 1)
                    if (recentScans.size > 50) {
                        recentScans.removeAt(50)
                        // Remove last row + spacer from view
                        val last = llInvList.childCount - 1
                        if (last >= 1) { llInvList.removeViewAt(last); llInvList.removeViewAt(last - 1) }
                    }
                }
                svInvList.post { svInvList.fullScroll(View.FOCUS_UP) }
                refreshStats()

                // Audio feedback based on result
                when (result.status) {
                    "MATCHED"     -> beep(880, 200, 1, 0, square = false)
                    "PENDING"     -> beep(660, 180, 2, 80, square = false)
                    "VERIFIED"    -> beep(880, 150, 2, 60, square = false)
                    "DISCREPANCY" -> beep(400, 250, 3, 80, square = true)
                }
                toast(buildResultMessage(result))
            } catch (e: Exception) {
                beep(300, 300, 2, 100, square = true)
                toast("Ошибка: ${e.message?.take(60)}")
            }
        }
    }

    private fun buildResultMessage(r: InvScanResponse): String {
        val name = r.product_name?.take(20) ?: r.barcode
        return when (r.status) {
            "MATCHED"     -> "✓ $name: совпадает (${r.counter_qty})"
            "PENDING"     -> "⏳ $name: ожидает проверки (${r.counter_qty})"
            "VERIFIED"    -> "✓✓ $name: подтверждено"
            "DISCREPANCY" -> "⚠ $name: расхождение! 1й=${r.counter_qty} 2й=${r.verifier_qty}"
            "already_done"-> "Уже подтверждено: ${r.counter_qty}"
            else          -> r.message
        }
    }

    // ─── List ─────────────────────────────────────────────────────────────────

    private fun refreshList() {
        llInvList.removeAllViews()
        recentScans.forEach { scan ->
            llInvList.addView(buildScanRow(scan))
            llInvList.addView(spacer(dp(6)))
        }
    }

    private fun buildScanRow(scan: InvScanResponse): View {
        val (labelText, labelColor) = when (scan.status) {
            "MATCHED"     -> "СОВПАДАЕТ"        to getColor(R.color.success)
            "PENDING"     -> "ОЖИДАЕТ ПРОВЕРКИ" to getColor(R.color.warning)
            "VERIFIED"    -> "ПОДТВЕРЖДЕНО"     to getColor(R.color.success)
            "DISCREPANCY" -> "РАСХОЖДЕНИЕ!"     to getColor(R.color.error)
            else          -> scan.status         to getColor(R.color.secondary)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(getColor(R.color.surface))
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(TextView(this).apply {
            text = labelText; textSize = 10f; letterSpacing = 0.08f
            setTextColor(labelColor); setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(this).apply {
            text = "✕"; textSize = 14f; setPadding(dp(8), 0, 0, 0)
            setTextColor(getColor(R.color.secondary))
            setOnClickListener { confirmDeleteScan(scan) }
        })
        row.addView(topRow)

        // Product name (bold) or barcode if no name
        row.addView(TextView(this).apply {
            text = scan.product_name?.takeIf { it.isNotBlank() } ?: scan.barcode
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
            setPadding(0, dp(3), 0, 0)
        })

        // Barcode shown below name (only if we have a name)
        if (!scan.product_name.isNullOrBlank()) {
            row.addView(TextView(this).apply {
                text = scan.barcode; textSize = 11f
                setTextColor(getColor(R.color.secondary))
                setPadding(0, dp(1), 0, 0)
            })
        }

        val qtyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        qtyRow.addView(chip("Считал: ${scan.counter_qty}", getColor(R.color.primary)))
        if (scan.verifier_qty != null) {
            qtyRow.addView(chip("Проверка: ${scan.verifier_qty}", when (scan.status) {
                "VERIFIED"    -> getColor(R.color.success)
                "DISCREPANCY" -> getColor(R.color.error)
                else          -> getColor(R.color.secondary)
            }))
        }
        if (scan.ms_stock != null) {
            qtyRow.addView(chip("МС: ${scan.ms_stock.toInt()}", getColor(R.color.secondary)))
        }
        row.addView(qtyRow)

        return row
    }

    private fun confirmDeleteScan(scan: InvScanResponse) {
        AlertDialog.Builder(this)
            .setTitle("Удалить запись?")
            .setMessage("${scan.product_name ?: scan.barcode} — кол-во ${scan.counter_qty}")
            .setPositiveButton("Удалить") { _, _ -> deleteScan(scan) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun deleteScan(scan: InvScanResponse) {
        lifecycleScope.launch {
            try {
                api.deleteInvScan(scan.id)
                scanMap.remove(scan.barcode)
                recentScans.removeAll { it.id == scan.id }
                refreshList()
                refreshStats()
                toast("Удалено")
            } catch (e: Exception) {
                toast("Ошибка: ${e.message?.take(50)}")
            }
        }
    }

    // ─── Session close ────────────────────────────────────────────────────────

    private fun confirmComplete() {
        AlertDialog.Builder(this)
            .setTitle("Завершить инвентаризацию?")
            .setMessage("Сессия сохранится в историю.")
            .setPositiveButton("Завершить") { _, _ ->
                currentSession?.let { closeSession(it, "COMPLETED") }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun confirmCancel() {
        AlertDialog.Builder(this)
            .setTitle("Отменить инвентаризацию?")
            .setMessage("Все сканы будут удалены.")
            .setPositiveButton("Отменить сессию") { _, _ ->
                currentSession?.let { closeSession(it, "CANCELLED") }
            }
            .setNegativeButton("Назад", null)
            .show()
    }

    private fun closeSession(session: InvSession, status: String) {
        lifecycleScope.launch {
            try {
                api.updateInvSession(session.session_id, status)
                toast(if (status == "COMPLETED") "Завершено" else "Отменено")
                finish()
            } catch (e: Exception) {
                toast("Ошибка: ${e.message?.take(60)}")
            }
        }
    }

    // ─── Audio ────────────────────────────────────────────────────────────────

    private fun beep(freqHz: Int, durationMs: Int, count: Int, gapMs: Int, square: Boolean) {
        Thread {
            try {
                val sampleRate = 22050
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val fmt = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val track = AudioTrack(attrs, fmt, minBuf.coerceAtLeast(4096), AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE)
                track.setVolume(1.0f); track.play()

                val spm = sampleRate / 1000
                val toneSamples = durationMs * spm
                val gapSamples = gapMs * spm
                val cycle = if (freqHz > 0) sampleRate / freqHz else 1
                val toneBuf = ShortArray(toneSamples) { i ->
                    if (square) (if ((i % cycle) < cycle / 2) 28000 else -28000).toShort()
                    else (28000 * kotlin.math.sin(2.0 * Math.PI * freqHz * i / sampleRate)).toInt().toShort()
                }
                val gapBuf = ShortArray(gapSamples)
                repeat(count) { idx ->
                    track.write(toneBuf, 0, toneSamples)
                    if (idx < count - 1 && gapSamples > 0) track.write(gapBuf, 0, gapSamples)
                }
                track.stop(); track.release()
            } catch (_: Exception) {}
        }.start()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun chip(text: String, color: Int) = TextView(this).apply {
        this.text = text; textSize = 11f; setTextColor(color)
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(6), dp(2), dp(6), dp(2))
        setBackgroundResource(R.drawable.card_bg)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, dp(6), 0) }
    }

    private fun cardDrawable(bg: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(12).toFloat()
        }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

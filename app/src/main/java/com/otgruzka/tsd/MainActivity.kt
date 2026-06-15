package com.otgruzka.tsd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.otgruzka.tsd.api.*
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var api: WmsApi
    private var tsdId: String = "TSD"

    // Views
    private lateinit var tvUser: TextView
    private lateinit var tvSession: TextView
    private lateinit var tvEndShift: TextView
    private lateinit var tabScan: TextView
    private lateinit var tabPickup: TextView
    private lateinit var paneScan: View
    private lateinit var panePickup: View
    private lateinit var tvScanBuffer: TextView
    private lateinit var llScanList: LinearLayout
    private lateinit var svScan: ScrollView
    private lateinit var tvPickupStatus: TextView
    private lateinit var llPickupList: LinearLayout
    private lateinit var svPickup: ScrollView
    private lateinit var btnCreate: Button

    // Scan state
    private val scanItems = LinkedHashMap<String, ScannedItem>()
    private val barcodeBuf = StringBuilder()
    private val toneGen by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME) }

    // After demands created: remove READY items from list
    private val demandsResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val toRemove = scanItems.entries
            .filter { it.value.status == ScanStatus.READY || it.value.status == ScanStatus.KASPI_ONLY }
            .map { it.key }
        toRemove.forEach { scanItems.remove(it) }
        refreshScanList()
        updateCreateButton()
        updateSessionLabel()
    }

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
        if (!WmsAuth.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        api = WmsApiClient.build(this)
        tsdId = WmsAuth.getUser(this)?.username ?: "TSD"

        setContentView(R.layout.activity_main)
        bindViews()
        switchTab(0)
        initSession()
    }

    private fun bindViews() {
        tvUser     = findViewById(R.id.tvUser)
        tvSession  = findViewById(R.id.tvSession)
        tvEndShift = findViewById(R.id.tvEndShift)
        tabScan    = findViewById(R.id.tabScan)
        tabPickup = findViewById(R.id.tabPickup)
        paneScan  = findViewById(R.id.paneScan)
        panePickup = findViewById(R.id.panePickup)
        tvScanBuffer  = findViewById(R.id.tvScanBuffer)
        llScanList    = findViewById(R.id.llScanList)
        svScan        = findViewById(R.id.svScan)
        tvPickupStatus = findViewById(R.id.tvPickupStatus)
        llPickupList   = findViewById(R.id.llPickupList)
        svPickup       = findViewById(R.id.svPickup)
        btnCreate      = findViewById(R.id.btnCreate)

        tvEndShift.setOnClickListener { confirmEndShift() }
        findViewById<TextView>(R.id.tvHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<TextView>(R.id.tvLogout).setOnClickListener { doLogout() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearAll() }
        findViewById<Button>(R.id.btnPickupRefresh).setOnClickListener { loadPickupOrders() }
        btnCreate.setOnClickListener { createDemands() }

        tabScan.setOnClickListener { switchTab(0) }
        tabPickup.setOnClickListener {
            switchTab(1)
            if (!ScanCache.pickupLoaded) loadPickupOrders()
        }
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

    override fun onDestroy() {
        super.onDestroy()
        try { toneGen.release() } catch (_: Exception) {}
    }

    // ─── Tabs ────────────────────────────────────────────────────────────────

    private fun switchTab(idx: Int) {
        val active   = getColor(R.color.primary)
        val inactive = getColor(R.color.secondary)
        val activeBg = 0x1A5956E8.toInt()
        if (idx == 0) {
            paneScan.visibility   = View.VISIBLE
            panePickup.visibility = View.GONE
            tabScan.setTextColor(active);    tabScan.setBackgroundColor(activeBg)
            tabPickup.setTextColor(inactive); tabPickup.setBackgroundColor(0)
        } else {
            paneScan.visibility   = View.GONE
            panePickup.visibility = View.VISIBLE
            tabPickup.setTextColor(active);  tabPickup.setBackgroundColor(activeBg)
            tabScan.setTextColor(inactive);  tabScan.setBackgroundColor(0)
        }
    }

    // ─── Session ─────────────────────────────────────────────────────────────

    private fun initSession() {
        val user = WmsAuth.getUser(this) ?: return
        val wh = WmsAuth.WAREHOUSE_NAMES[user.warehouse_id] ?: "Склад ${user.warehouse_id}"
        tvUser.text = "${user.full_name}  ·  $wh"
        tvSession.text = "Подключение…"
        lifecycleScope.launch {
            try {
                val session = runCatching { api.getActiveSession() }.getOrNull()
                    ?: api.createSession(CreateSessionBody())
                ScanCache.currentSession = session
                updateCreateButton()
                tvSession.text = "Смена · собрано 0"
            } catch (e: Exception) {
                tvSession.text = "Нет связи: ${e.message?.take(40)}"
            }
        }
    }

    private fun updateCreateButton() {
        val readyCount = scanItems.values.count { it.status == ScanStatus.READY }
        btnCreate.isEnabled = readyCount > 0
        btnCreate.text = if (readyCount > 0) "Создать отгрузки  ·  $readyCount" else "Создать отгрузки"
    }

    private fun updateSessionLabel() {
        val ready = scanItems.values.count { it.status == ScanStatus.READY }
        val base  = ScanCache.currentSession?.order_count ?: 0
        tvSession.text = "Смена · собрано ${base + ready}"
    }

    // ─── Barcode input ───────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (paneScan.visibility != View.VISIBLE) return super.dispatchKeyEvent(event)
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

    private fun handleBarcode(raw: String) {
        val code = raw.replace(Regex("-\\d+$"), "")
        if (paneScan.visibility != View.VISIBLE) return
        if (scanItems.containsKey(code)) {
            lifecycleScope.launch {
                try {
                    toneGen.startTone(ToneGenerator.TONE_DTMF_5, 100)
                    kotlinx.coroutines.delay(150)
                    toneGen.startTone(ToneGenerator.TONE_DTMF_5, 100)
                } catch (_: Exception) {}
            }
            toast("Уже в списке: $code")
            return
        }

        val batchId = ScanCache.currentSession?.batch_id ?: ""
        scanItems[code] = ScannedItem(code, ScanStatus.CHECKING)
        refreshScanList()
        svScan.post { svScan.fullScroll(View.FOCUS_DOWN) }

        lifecycleScope.launch {
            try {
                val res = api.scanLock(ScanLockRequest(code, batchId))
                val status = when {
                    res.result == "ALREADY_SHIPPED"  -> ScanStatus.SHIPPED
                    res.result == "CANCELLING"        -> ScanStatus.CANCELLING
                    res.result == "NOT_FOUND"         -> ScanStatus.NOT_FOUND
                    res.result == "ALREADY_LOCKED"    -> ScanStatus.LOCKED_BY_OTHER
                    res.result == "SUCCESS" && res.order?.source == "kaspi" -> ScanStatus.KASPI_ONLY
                    res.result == "SUCCESS"           -> ScanStatus.READY
                    else                              -> ScanStatus.NOT_FOUND
                }
                scanItems[code] = ScannedItem(
                    code        = code,
                    status      = status,
                    customerName = res.order?.customer_name,
                    totalPrice  = res.order?.total_price ?: 0.0,
                    assembled   = res.order?.assembled ?: false,
                    express     = res.order?.express ?: false,
                    lockHolder  = res.lock_holder,
                    source      = res.order?.source
                )
                saveScanRecord(
                    orderCode    = code,
                    customerName = res.order?.customer_name,
                    totalPrice   = res.order?.total_price ?: 0.0,
                    result       = res.result,
                    lockHolder   = res.lock_holder,
                    assembled    = res.order?.assembled ?: false,
                    express      = res.order?.express ?: false,
                    isPickup     = false,
                    batchId      = batchId
                )
                playTone(status)
            } catch (e: Exception) {
                scanItems.remove(code)
                toast("Ошибка: ${e.message?.take(60)}")
            }
            refreshScanList()
            updateCreateButton()
            updateSessionLabel()
            svScan.post { svScan.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ─── Scan list ───────────────────────────────────────────────────────────

    private fun refreshScanList() {
        llScanList.removeAllViews()
        scanItems.values.toList().reversed().forEach { item ->
            llScanList.addView(buildScanRow(item))
            llScanList.addView(spacer(dp(6)))
        }
    }

    private fun buildScanRow(item: ScannedItem): View {
        val (labelText, labelColor) = when (item.status) {
            ScanStatus.READY           -> "ГОТОВО К ОТГРУЗКЕ"   to getColor(R.color.success)
            ScanStatus.SHIPPED         -> "УЖЕ ОТГРУЖЕНА"        to getColor(R.color.warning)
            ScanStatus.KASPI_ONLY      -> "МС НЕ ИМПОРТИРОВАЛ"   to getColor(R.color.warning)
            ScanStatus.CANCELLING      -> "ОТМЕНА"               to getColor(R.color.error)
            ScanStatus.NOT_FOUND       -> "НЕ НАЙДЕН"            to getColor(R.color.secondary)
            ScanStatus.LOCKED_BY_OTHER -> "УЖЕ БЕРЁТСЯ"          to getColor(R.color.warning)
            ScanStatus.CHECKING        -> "ПРОВЕРКА…"            to getColor(R.color.secondary)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(getColor(R.color.surface))
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(12), dp(14), dp(12))
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
        if (item.status != ScanStatus.CHECKING) {
            topRow.addView(TextView(this).apply {
                text = "✕"; textSize = 14f; setPadding(dp(8), 0, 0, 0)
                setTextColor(getColor(R.color.secondary))
                setOnClickListener { removeItem(item.code) }
            })
        }
        row.addView(topRow)

        row.addView(TextView(this).apply {
            text = item.code; textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
            setPadding(0, dp(4), 0, 0)
        })

        if (item.status == ScanStatus.LOCKED_BY_OTHER && !item.lockHolder.isNullOrBlank()) {
            val parts = item.lockHolder.split(":", limit = 2)
            val holderLabel = if (parts.size == 2) "${parts[0]}  ${parts[1]}" else item.lockHolder
            row.addView(small("Взят: $holderLabel"))
        }

        if (!item.customerName.isNullOrBlank()) row.addView(small(item.customerName))
        if (item.status == ScanStatus.READY || item.status == ScanStatus.SHIPPED || item.status == ScanStatus.KASPI_ONLY) {
            if (item.totalPrice > 0) row.addView(small("₸ ${fmtPrice(item.totalPrice)}"))
            val chips = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0)
            }
            if (item.assembled) chips.addView(chip("Собран",    getColor(R.color.success)))
            if (item.express)   chips.addView(chip("Экспресс",  getColor(R.color.warning)))
            if (item.status == ScanStatus.KASPI_ONLY)
                chips.addView(chip("Нет в МС", getColor(R.color.secondary)))
            if (chips.childCount > 0) row.addView(chips)
        }
        return row
    }

    private fun removeItem(code: String) {
        val item = scanItems.remove(code) ?: return
        // Release lock only if we hold it (status where lock_acquired was true)
        if (item.status == ScanStatus.READY || item.status == ScanStatus.KASPI_ONLY) {
            lifecycleScope.launch {
                try { api.releaseLock(code) } catch (_: Exception) {}
            }
        }
        refreshScanList()
        updateCreateButton()
        updateSessionLabel()
    }

    private fun clearAll() {
        val toRelease = scanItems.values
            .filter { it.status == ScanStatus.READY || it.status == ScanStatus.KASPI_ONLY }
            .map { it.code }
        scanItems.clear()
        refreshScanList()
        updateCreateButton()
        updateSessionLabel()
        if (toRelease.isNotEmpty()) {
            lifecycleScope.launch {
                toRelease.forEach { code ->
                    try { api.releaseLock(code) } catch (_: Exception) {}
                }
            }
        }
    }

    // ─── Create demands ───────────────────────────────────────────────────────

    private fun createDemands() {
        val readyCodes = scanItems.values
            .filter { it.status == ScanStatus.READY || it.status == ScanStatus.KASPI_ONLY }
            .map { it.code }
        if (readyCodes.isEmpty()) return

        val intent = Intent(this, OrderResultActivity::class.java)
        intent.putStringArrayListExtra("codes", ArrayList(readyCodes))
        intent.putExtra("session_batch_id", ScanCache.currentSession?.batch_id)
        demandsResultLauncher.launch(intent)
    }

    // ─── Самовывоз ───────────────────────────────────────────────────────────

    private fun loadPickupOrders() {
        tvPickupStatus.text = "Загрузка…"
        lifecycleScope.launch {
            try {
                val orders = api.getOrders(state = "PICKUP", pageSize = 200)
                ScanCache.pickupOrders = orders
                ScanCache.pickupLoaded = true
                tvPickupStatus.text = "Самовывозы: ${orders.size}"
                refreshPickupList()
            } catch (e: Exception) {
                tvPickupStatus.text = "Ошибка: ${e.message?.take(40)}"
            }
        }
    }

    private fun refreshPickupList() {
        llPickupList.removeAllViews()
        ScanCache.pickupOrders.forEach { order ->
            llPickupList.addView(buildPickupRow(order))
            llPickupList.addView(spacer(dp(6)))
        }
    }

    private fun buildPickupRow(order: KaspiOrder): View {
        val confirmed = ScanCache.confirmedPickups.contains(order.order_code)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardDrawable(getColor(R.color.surface))
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = if (confirmed) "ВЫДАН" else "САМОВЫВОЗ"
            textSize = 10f; letterSpacing = 0.08f
            setTextColor(if (confirmed) getColor(R.color.success) else getColor(R.color.secondary))
            setTypeface(null, Typeface.BOLD)
        })
        info.addView(TextView(this).apply {
            text = order.order_code; textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
            setPadding(0, dp(4), 0, 0)
        })
        if (!order.customer_name.isNullOrBlank()) info.addView(small(order.customer_name))
        if (order.total_price > 0) info.addView(small("₸ ${fmtPrice(order.total_price)}"))
        if (order.is_cancelling) info.addView(small("Отменяется"))
        row.addView(info)

        if (!confirmed) {
            row.addView(Button(this).apply {
                text = "Выдать"; textSize = 13f; isAllCaps = false
                setTextColor(getColor(R.color.on_primary))
                backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
                setOnClickListener { handlePickupGive(order) }
            })
        }
        return row
    }

    private fun handlePickupGive(order: KaspiOrder) {
        ScanCache.confirmedPickups.add(order.order_code)
        refreshPickupList()
        updateSessionLabel()
        toast("Выдан: ${order.order_code}")
        saveScanRecord(
            orderCode    = order.order_code,
            customerName = order.customer_name,
            totalPrice   = order.total_price,
            result       = "SUCCESS",
            lockHolder   = null,
            assembled    = order.assembled,
            express      = order.express,
            isPickup     = true,
            batchId      = ScanCache.currentSession?.batch_id ?: ""
        )
    }

    // ─── History ──────────────────────────────────────────────────────────────

    private fun saveScanRecord(
        orderCode: String, customerName: String?, totalPrice: Double,
        result: String, lockHolder: String?,
        assembled: Boolean, express: Boolean,
        isPickup: Boolean, batchId: String
    ) {
        val record = ScanRecord(
            orderCode    = orderCode,
            customerName = customerName,
            totalPrice   = totalPrice,
            result       = result,
            lockHolder   = lockHolder,
            assembled    = assembled,
            express      = express,
            isPickup     = isPickup,
            batchId      = batchId,
            tsdId        = tsdId
        )
        val prefs = getSharedPreferences("wms_history", MODE_PRIVATE)
        val existing = try {
            Gson().fromJson(
                prefs.getString("records", "[]"),
                Array<ScanRecord>::class.java
            ).toMutableList()
        } catch (_: Exception) { mutableListOf() }
        existing.add(0, record)
        if (existing.size > 500) existing.subList(500, existing.size).clear()
        prefs.edit().putString("records", Gson().toJson(existing)).apply()
    }

    // ─── Audio feedback ───────────────────────────────────────────────────────

    private fun playTone(status: ScanStatus) {
        lifecycleScope.launch {
            try {
                when (status) {
                    ScanStatus.READY      -> toneGen.startTone(ToneGenerator.TONE_DTMF_9, 200)
                    ScanStatus.SHIPPED,
                    ScanStatus.LOCKED_BY_OTHER -> {
                        toneGen.startTone(ToneGenerator.TONE_DTMF_5, 150)
                        kotlinx.coroutines.delay(230)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_1, 150)
                    }
                    ScanStatus.NOT_FOUND,
                    ScanStatus.CANCELLING -> repeat(3) {
                        toneGen.startTone(ToneGenerator.TONE_SUP_BUSY, 100)
                        kotlinx.coroutines.delay(180)
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }

    // ─── End shift ───────────────────────────────────────────────────────────

    private fun confirmEndShift() {
        val batchId = ScanCache.currentSession?.batch_id ?: run {
            toast("Нет активной смены"); return
        }
        val count = ScanCache.currentSession?.order_count ?: 0
        android.app.AlertDialog.Builder(this)
            .setTitle("Завершить смену")
            .setMessage("Смена будет закрыта ($count заказов) и появится в истории.")
            .setPositiveButton("Завершить") { _, _ -> endShift(batchId) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun endShift(batchId: String) {
        lifecycleScope.launch {
            try {
                api.updateSession(batchId, "COMPLETED")
                ScanCache.currentSession = null
                scanItems.clear()
                refreshScanList()
                updateCreateButton()
                ScanCache.confirmedPickups.clear()
                toast("Смена завершена и сохранена в истории")
                // Start a fresh session
                try {
                    val newSession = api.createSession(CreateSessionBody())
                    ScanCache.currentSession = newSession
                    tvSession.text = "Новая смена · собрано 0"
                } catch (_: Exception) {
                    tvSession.text = "Нет активной смены"
                }
            } catch (e: Exception) {
                toast("Ошибка завершения: ${e.message?.take(60)}")
            }
        }
    }

    // ─── Logout ──────────────────────────────────────────────────────────────

    private fun doLogout() {
        val lockedCodes = scanItems.values
            .filter { it.status == ScanStatus.READY || it.status == ScanStatus.KASPI_ONLY }
            .map { it.code }

        if (lockedCodes.isNotEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Выход из аккаунта")
                .setMessage("В списке ${lockedCodes.size} заказов с активными замками. Освободить их?")
                .setPositiveButton("Освободить и выйти") { _, _ ->
                    lifecycleScope.launch {
                        lockedCodes.forEach { code ->
                            try { api.releaseLock(code) } catch (_: Exception) {}
                        }
                        performLogout()
                    }
                }
                .setNegativeButton("Выйти, оставить замки") { _, _ -> performLogout() }
                .show()
        } else {
            performLogout()
        }
    }

    private fun performLogout() {
        WmsAuth.logout(this); WmsApiClient.reset()
        ScanCache.currentSession = null
        ScanCache.pickupLoaded = false
        ScanCache.confirmedPickups.clear()
        startActivity(Intent(this, LoginActivity::class.java)); finish()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun small(s: String) = TextView(this).apply {
        text = s; textSize = 12f
        setTextColor(getColor(R.color.secondary))
        setPadding(0, dp(2), 0, 0)
    }

    private fun chip(text: String, color: Int) = TextView(this).apply {
        this.text = text; textSize = 10f; setTextColor(color)
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

    private fun fmtPrice(v: Double) = "%,.0f".format(v).replace(',', ' ')
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

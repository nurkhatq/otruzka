package com.otgruzka.tsd

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.otgruzka.tsd.api.*
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var api: WmsApi
    private var tsdId: String = "TSD-01"
    private val scanItems = LinkedHashMap<String, ScanCard>()
    private lateinit var tvUser: TextView
    private lateinit var tvSession: TextView
    private lateinit var tabScan: TextView
    private lateinit var tabPickup: TextView
    private lateinit var paneScan: LinearLayout
    private lateinit var panePickup: LinearLayout
    private lateinit var scanList: LinearLayout
    private lateinit var pickupList: LinearLayout
    private lateinit var tvPickupStatus: TextView
    private val barcodeBuf = StringBuilder()

    data class ScanCard(
        val code: String,
        val result: String,
        val customerName: String?,
        val price: Double,
        val assembled: Boolean,
        val express: Boolean,
        val lockHolder: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!WmsAuth.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java)); finish(); return
        }
        api = WmsApiClient.build(this)
        tsdId = WmsAuth.getUser(this)?.username ?: "TSD"
        buildUI()
        switchTab(0)
        loadSession()
    }

    // ─── UI Construction ────────────────────────────────────────────────────────

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F3F4F6"))
        }
        root.addView(buildTopBar())
        root.addView(buildTabBar())

        val paneContainer = FrameLayout(this)

        paneScan = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        paneScan.addView(buildScanHeader())
        val scanScroll = ScrollView(this)
        scanList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(80))
        }
        scanScroll.addView(scanList)
        paneScan.addView(scanScroll, lp(weight = 1f))
        paneContainer.addView(paneScan)

        panePickup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        panePickup.addView(buildPickupHeader())
        val pickupScroll = ScrollView(this)
        pickupList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(80))
        }
        pickupScroll.addView(pickupList)
        panePickup.addView(pickupScroll, lp(weight = 1f))
        paneContainer.addView(panePickup)

        root.addView(paneContainer, lp(weight = 1f))
        setContentView(root)
    }

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(2).toFloat()
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        tvUser = TextView(this).apply {
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        }
        tvSession = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
        }
        left.addView(tvUser)
        left.addView(tvSession)
        bar.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconBtn("📋") { startActivity(Intent(this, HistoryActivity::class.java)) })
        bar.addView(iconBtn("⏻") { doLogout() })
        return bar
    }

    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(8), dp(0))
        }
        tabScan = tabBtn("📷  Сканирование")
        tabPickup = tabBtn("🏪  Самовывоз")
        tabScan.setOnClickListener { switchTab(0) }
        tabPickup.setOnClickListener {
            switchTab(1)
            if (!ScanCache.pickupLoaded) loadPickupOrders()
        }
        bar.addView(tabScan, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(tabPickup, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return bar
    }

    private fun buildScanHeader(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(1).toFloat()
        }
        bar.addView(TextView(this).apply {
            text = "◉  Поднеси штрихкод к сканеру"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#F0F9FF"))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), Color.parseColor("#BAE6FD"))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(8), 1) })
        bar.addView(Button(this).apply {
            text = "Стереть"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.parseColor("#DC2626"))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#FEF2F2"))
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { clearAll() }
        })
        return bar
    }

    private fun buildPickupHeader(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(1).toFloat()
        }
        tvPickupStatus = TextView(this).apply {
            text = "Загрузка самовывозов…"
            textSize = 13f
            setTextColor(Color.parseColor("#6B7280"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(tvPickupStatus)
        bar.addView(iconBtn("↻") { loadPickupOrders() })
        return bar
    }

    // ─── Tab switching ───────────────────────────────────────────────────────────

    private fun switchTab(idx: Int) {
        val active = Color.parseColor("#2563EB"); val inactive = Color.parseColor("#6B7280")
        val activeBg = Color.parseColor("#DBEAFE")
        if (idx == 0) {
            paneScan.visibility = View.VISIBLE; panePickup.visibility = View.GONE
            tabScan.setTextColor(active); tabScan.setBackgroundColor(activeBg)
            tabPickup.setTextColor(inactive); tabPickup.setBackgroundColor(Color.TRANSPARENT)
        } else {
            paneScan.visibility = View.GONE; panePickup.visibility = View.VISIBLE
            tabPickup.setTextColor(active); tabPickup.setBackgroundColor(activeBg)
            tabScan.setTextColor(inactive); tabScan.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    // ─── Session ─────────────────────────────────────────────────────────────────

    private fun loadSession() {
        val user = WmsAuth.getUser(this) ?: return
        val wh = WmsAuth.WAREHOUSE_NAMES[user.warehouse_id] ?: "Склад ${user.warehouse_id}"
        tvUser.text = "${user.full_name}  ·  $wh"
        lifecycleScope.launch {
            try {
                var session = api.getActiveSession()
                if (session == null) session = api.createSession(CreateSessionBody())
                ScanCache.currentSession = session
                refreshSessionLabel()
            } catch (e: Exception) {
                tvSession.text = "Ошибка подключения"
            }
        }
    }

    private fun refreshSessionLabel() {
        val done = scanItems.count { it.value.result == "SUCCESS" }
        val base = ScanCache.currentSession?.order_count ?: 0
        tvSession.text = "Смена · принято ${base + done}"
    }

    // ─── Barcode input ───────────────────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (paneScan.visibility != View.VISIBLE) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            val ch = event.unicodeChar.toChar()
            when {
                event.keyCode == KeyEvent.KEYCODE_ENTER && barcodeBuf.isNotEmpty() -> {
                    val code = barcodeBuf.toString().trim()
                    barcodeBuf.clear()
                    processScan(code)
                    return true
                }
                ch.isLetterOrDigit() || ch == '-' || ch == '_' -> {
                    barcodeBuf.append(ch)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun processScan(code: String) {
        val session = ScanCache.currentSession ?: return toast("Нет активной смены")
        if (scanItems.containsKey(code)) return toast("Уже в списке: $code")

        scanItems[code] = ScanCard(code, "CHECKING", null, 0.0, false, false, null)
        refreshScanList()

        lifecycleScope.launch {
            try {
                val res = api.scanLock(ScanRequest(code, session.batch_id))
                scanItems[code] = ScanCard(
                    code, res.result,
                    res.order?.customer_name,
                    res.order?.total_price ?: 0.0,
                    res.order?.assembled ?: false,
                    res.order?.express ?: false,
                    res.lock_holder
                )
                saveScanRecord(res, isPickup = false)
            } catch (e: Exception) {
                scanItems.remove(code)
                toast("Ошибка: ${e.message}")
            }
            refreshScanList()
            refreshSessionLabel()
        }
    }

    private fun refreshScanList() {
        scanList.removeAllViews()
        scanItems.values.toList().reversed().forEach { card ->
            scanList.addView(buildScanRow(card))
            scanList.addView(spacer(dp(6)))
        }
    }

    private fun buildScanRow(card: ScanCard): View {
        val (bg, border, icon) = when (card.result) {
            "SUCCESS"        -> Triple("#F0FDF4", "#86EFAC", "✓")
            "ALREADY_LOCKED" -> Triple("#FEFCE8", "#FDE047", "⚠")
            "CANCELLING"     -> Triple("#FEF2F2", "#FCA5A5", "✗")
            "NOT_FOUND"      -> Triple("#F9FAFB", "#D1D5DB", "?")
            else             -> Triple("#F9FAFB", "#D1D5DB", "…")
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(bg, border)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        row.addView(TextView(this).apply {
            text = icon; textSize = 20f; setPadding(0, 0, dp(10), 0)
        })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = card.code; textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        })
        when (card.result) {
            "SUCCESS" -> {
                if (!card.customerName.isNullOrBlank()) info.addView(small(card.customerName))
                info.addView(small("₸ ${fmtPrice(card.price)}"))
                val badges = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, 0)
                }
                if (card.assembled) badges.addView(badge("Собран", "#D1FAE5", "#059669"))
                if (card.express) badges.addView(badge("Экспресс", "#FEF3C7", "#D97706"))
                if (badges.childCount > 0) info.addView(badges)
            }
            "ALREADY_LOCKED" -> info.addView(small("У кого: ${card.lockHolder?.substringAfter(":") ?: ""}"))
            "CANCELLING"     -> info.addView(small("Заказ отменяется — не трогать"))
            "NOT_FOUND"      -> info.addView(small("Не найден в системе"))
            "CHECKING"       -> info.addView(small("Проверяю…"))
        }
        row.addView(info)
        if (card.result != "CHECKING") {
            row.addView(iconBtn("✕") { deleteOneScan(card.code, card.result == "SUCCESS") })
        }
        return row
    }

    private fun deleteOneScan(code: String, release: Boolean) {
        if (release) lifecycleScope.launch {
            try { api.releaseLock(code) } catch (_: Exception) {}
        }
        scanItems.remove(code)
        refreshScanList()
        refreshSessionLabel()
    }

    private fun clearAll() {
        val toRelease = scanItems.filter { it.value.result == "SUCCESS" }.keys.toList()
        if (toRelease.isNotEmpty()) lifecycleScope.launch {
            toRelease.forEach { try { api.releaseLock(it) } catch (_: Exception) {} }
        }
        scanItems.clear()
        refreshScanList()
        refreshSessionLabel()
    }

    // ─── Самовывоз ───────────────────────────────────────────────────────────────

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
                tvPickupStatus.text = "Ошибка загрузки"
            }
        }
    }

    private fun refreshPickupList() {
        pickupList.removeAllViews()
        ScanCache.pickupOrders.forEach { order ->
            pickupList.addView(buildPickupRow(order))
            pickupList.addView(spacer(dp(6)))
        }
    }

    private fun buildPickupRow(order: KaspiOrder): View {
        val confirmed = ScanCache.confirmedPickups.contains(order.order_code)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg(
                if (confirmed) "#F0FDF4" else "#FFFFFF",
                if (confirmed) "#86EFAC" else "#E5E7EB"
            )
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = order.order_code; textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
        })
        if (!order.customer_name.isNullOrBlank()) info.addView(small(order.customer_name))
        info.addView(small("₸ ${fmtPrice(order.total_price)}"))
        val badges = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(3), 0, 0)
        }
        if (order.assembled)    badges.addView(badge("Собран",   "#D1FAE5", "#059669"))
        if (order.express)      badges.addView(badge("Экспресс", "#FEF3C7", "#D97706"))
        if (order.is_cancelling) badges.addView(badge("Отмена",  "#FEE2E2", "#DC2626"))
        if (badges.childCount > 0) info.addView(badges)
        row.addView(info)

        if (confirmed) {
            row.addView(TextView(this).apply {
                text = "✓"; textSize = 24f
                setTextColor(Color.parseColor("#059669"))
                setPadding(dp(8), 0, 0, 0)
            })
        } else {
            row.addView(Button(this).apply {
                text = "Принять"; textSize = 12f; isAllCaps = false
                setTextColor(Color.WHITE)
                background = roundedBg("#2563EB", "#2563EB", dp(8))
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { confirmPickup(order) }
            })
        }
        return row
    }

    private fun confirmPickup(order: KaspiOrder) {
        val session = ScanCache.currentSession ?: return toast("Нет активной смены")
        lifecycleScope.launch {
            try {
                val res = api.scanLock(ScanRequest(order.order_code, session.batch_id))
                when (res.result) {
                    "SUCCESS" -> {
                        ScanCache.confirmedPickups.add(order.order_code)
                        saveScanRecord(res, isPickup = true)
                        refreshPickupList()
                        refreshSessionLabel()
                        toast("✓ Выдано: ${order.order_code}")
                    }
                    "ALREADY_LOCKED" -> toast("Уже выдан: ${res.lock_holder?.substringAfter(":") ?: ""}")
                    "CANCELLING"     -> toast("⚠ Заказ отменяется!")
                    else             -> toast("Не найден: ${order.order_code}")
                }
            } catch (e: Exception) {
                toast("Ошибка: ${e.message}")
            }
        }
    }

    // ─── Persistence ─────────────────────────────────────────────────────────────

    private fun saveScanRecord(res: ScanResult, isPickup: Boolean) {
        val session = ScanCache.currentSession ?: return
        val record = ScanRecord(
            orderCode = res.order_code,
            customerName = res.order?.customer_name,
            totalPrice = res.order?.total_price ?: 0.0,
            result = res.result,
            lockHolder = res.lock_holder,
            assembled = res.order?.assembled ?: false,
            express = res.order?.express ?: false,
            isPickup = isPickup,
            batchId = session.batch_id,
            tsdId = tsdId
        )
        val prefs = getSharedPreferences("wms_history", MODE_PRIVATE)
        val list = try {
            Gson().fromJson(
                prefs.getString("records", "[]"), Array<ScanRecord>::class.java
            ).toMutableList()
        } catch (_: Exception) { mutableListOf() }
        list.add(0, record)
        if (list.size > 500) list.dropLast(list.size - 500)
        prefs.edit().putString("records", Gson().toJson(list)).apply()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private fun doLogout() {
        WmsAuth.logout(this)
        WmsApiClient.reset()
        ScanCache.currentSession = null
        ScanCache.pickupLoaded = false
        ScanCache.confirmedPickups.clear()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(weight: Float = 0f) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        if (weight > 0) 0 else LinearLayout.LayoutParams.WRAP_CONTENT,
        weight
    )

    private fun tabBtn(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(10))
    }

    private fun iconBtn(icon: String, click: () -> Unit) = TextView(this).apply {
        text = icon; textSize = 18f
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { click() }
    }

    private fun small(s: String) = TextView(this).apply {
        text = s; textSize = 12f; setTextColor(Color.parseColor("#6B7280"))
    }

    private fun badge(text: String, bg: String, fg: String) = TextView(this).apply {
        this.text = text; textSize = 10f
        setTextColor(Color.parseColor(fg))
        background = roundedBg(bg, bg, dp(4))
        setPadding(dp(5), dp(2), dp(5), dp(2))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, dp(4), 0) }
    }

    private fun roundedBg(bg: String, stroke: String, radius: Int = dp(10)) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor(bg))
            cornerRadius = radius.toFloat()
            if (bg != stroke) setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun fmtPrice(v: Double) = "%,.0f".format(v).replace(',', ' ')

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

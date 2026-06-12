package com.otgruzka.tsd

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.otgruzka.tsd.api.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var tvScanBuffer: TextView
    private lateinit var tvCacheStatus: TextView
    private lateinit var llScannedList: LinearLayout
    private lateinit var svScanned: ScrollView
    private lateinit var btnClear: Button
    private lateinit var btnCreate: Button

    private val scanBuffer = StringBuilder()
    private var clearOnResume = false

    private data class ScanCard(
        val tvLabel: TextView,
        val tvOrder: TextView,
        var status: ScanStatus = ScanStatus.CHECKING
    )
    private val scanCards = LinkedHashMap<String, ScanCard>()

    private val toneGen by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME) }

    private val prefs by lazy { getSharedPreferences("scan_cache", Context.MODE_PRIVATE) }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val barcode = intent.getStringExtra("scandata")?.trim()
            if (!barcode.isNullOrBlank()) handleScan(barcode)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tvScanBuffer = findViewById(R.id.tvScanBuffer)
        tvCacheStatus = findViewById(R.id.tvCacheStatus)
        llScannedList = findViewById(R.id.llScannedList)
        svScanned = findViewById(R.id.svScanned)
        btnClear = findViewById(R.id.btnClear)
        btnCreate = findViewById(R.id.btnCreate)

        btnClear.setOnClickListener {
            if (scanCards.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Сброс")
                .setMessage("Очистить список из ${scanCards.size} заказов?")
                .setPositiveButton("Очистить") { _, _ ->
                    scanCards.clear()
                    llScannedList.removeAllViews()
                    updateCreateButton()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        btnCreate.setOnClickListener {
            val readyOrders = scanCards.filter { it.value.status == ScanStatus.READY }.keys.toList()
            if (readyOrders.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle("Подтверждение")
                .setMessage("Создать отгрузки для ${readyOrders.size} заказов?")
                .setPositiveButton("Создать") { _, _ ->
                    ScanCache.pendingOrders = readyOrders
                    clearOnResume = true
                    startActivity(Intent(this, OrderActivity::class.java))
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        if (!ScanCache.isLoaded) {
            loadCache()
        } else {
            tvCacheStatus.text = "Кэш: ${ScanCache.orderNameStatus.size} заказов"
        }
    }

    private fun saveCacheToDisk() {
        val json = Gson().toJson(ScanCache.orderNameStatus)
        prefs.edit()
            .putString("orders_json", json)
            .putString("saved_date", LocalDate.now().toString())
            .apply()
    }

    private fun loadCacheFromDisk(): Boolean {
        val savedDate = prefs.getString("saved_date", null) ?: return false
        if (savedDate != LocalDate.now().toString()) return false
        val json = prefs.getString("orders_json", null) ?: return false
        return try {
            val type = object : TypeToken<Map<String, Boolean>>() {}.type
            val map: Map<String, Boolean> = Gson().fromJson(json, type)
            ScanCache.orderNameStatus.clear()
            ScanCache.orderNameStatus.putAll(map)
            ScanCache.isLoaded = true
            true
        } catch (_: Exception) { false }
    }

    private fun loadCache() {
        if (loadCacheFromDisk()) {
            tvCacheStatus.text = "Кэш: ${ScanCache.orderNameStatus.size} заказов"
            return
        }

        lifecycleScope.launch {
            ScanCache.isLoading = true
            try {
                tvCacheStatus.text = "Загрузка кэша..."
                val dateFrom = LocalDate.now().minusDays(5)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val filter = "moment>$dateFrom 00:00:00"
                val pageSize = 1000
                var offset = 0
                var totalLoaded = 0

                ScanCache.orderNameStatus.clear()

                while (true) {
                    val result = ApiClient.api.getOrders(filter, pageSize, offset)
                    result.rows.forEach { order ->
                        ScanCache.orderNameStatus[order.name] = !order.demands.isNullOrEmpty()
                    }
                    totalLoaded += result.rows.size
                    val total = result.meta?.size ?: break
                    if (totalLoaded >= total || result.rows.isEmpty()) break
                    offset += pageSize
                    tvCacheStatus.text = "Загрузка: $totalLoaded / $total"
                }

                ScanCache.isLoaded = true
                saveCacheToDisk()
                tvCacheStatus.text = "Кэш: $totalLoaded заказов"
            } catch (_: Exception) {
                ScanCache.isLoaded = false
                tvCacheStatus.text = "Кэш недоступен — проверка при сканировании"
            } finally {
                ScanCache.isLoading = false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        scanBuffer.clear()
        tvScanBuffer.text = ""
        if (clearOnResume) {
            clearOnResume = false
            scanCards.clear()
            llScannedList.removeAllViews()
            updateCreateButton()
        }

        val filter = IntentFilter("com.android.scanner.broadcast")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER -> {
                val barcode = scanBuffer.toString().trim()
                scanBuffer.clear(); tvScanBuffer.text = ""
                if (barcode.isNotEmpty()) handleScan(barcode)
            }
            KeyEvent.KEYCODE_DEL -> {
                if (scanBuffer.isNotEmpty()) {
                    scanBuffer.deleteCharAt(scanBuffer.length - 1)
                    tvScanBuffer.text = scanBuffer.toString()
                }
            }
            else -> {
                val char = event.unicodeChar.toChar()
                if (char > ' ') { scanBuffer.append(char); tvScanBuffer.text = scanBuffer.toString() }
            }
        }
        return true
    }

    private fun handleScan(barcode: String) {
        if (ScanCache.isLoading) {
            scanBuffer.clear()
            tvScanBuffer.text = "Подождите — кэш загружается"
            return
        }

        val clean = barcode.replace(Regex("-\\d+$"), "")
        tvScanBuffer.text = ""
        if (scanCards.containsKey(clean)) return

        val card = addScanRow(clean)
        scanCards[clean] = card
        updateCreateButton()
        svScanned.post { svScanned.fullScroll(View.FOCUS_DOWN) }

        if (ScanCache.isLoaded && ScanCache.orderNameStatus.containsKey(clean)) {
            val hasDemand = ScanCache.orderNameStatus[clean]!!
            if (hasDemand) {
                card.status = ScanStatus.SHIPPED
                card.tvLabel.text = "УЖЕ ОТГРУЖЕНА"
                card.tvLabel.setTextColor(getColor(R.color.warning))
            } else {
                card.status = ScanStatus.READY
                card.tvLabel.text = "ГОТОВО К ОТГРУЗКЕ"
                card.tvLabel.setTextColor(getColor(R.color.success))
            }
            playTone(card.status)
            updateCreateButton()
        } else {
            lifecycleScope.launch {
                try {
                    val result = ApiClient.api.searchOrders("name=$clean")
                    if (result.rows.isEmpty()) {
                        card.status = ScanStatus.NOT_FOUND
                        card.tvLabel.text = "НЕ НАЙДЕН"
                        card.tvLabel.setTextColor(getColor(R.color.error))
                        playTone(ScanStatus.NOT_FOUND)
                    } else {
                        val order = result.rows[0]
                        if (!order.demands.isNullOrEmpty()) {
                            card.status = ScanStatus.SHIPPED
                            card.tvLabel.text = "УЖЕ ОТГРУЖЕНА"
                            card.tvLabel.setTextColor(getColor(R.color.warning))
                            ScanCache.orderNameStatus[clean] = true
                            playTone(ScanStatus.SHIPPED)
                        } else {
                            card.status = ScanStatus.READY
                            card.tvLabel.text = "ГОТОВО К ОТГРУЗКЕ"
                            card.tvLabel.setTextColor(getColor(R.color.success))
                            ScanCache.orderNameStatus[clean] = false
                            playTone(ScanStatus.READY)
                        }
                    }
                } catch (_: Exception) {
                    card.status = ScanStatus.NOT_FOUND
                    card.tvLabel.text = "ОШИБКА СЕТИ"
                    card.tvLabel.setTextColor(getColor(R.color.error))
                    playTone(ScanStatus.NOT_FOUND)
                }
                updateCreateButton()
            }
        }
    }

    private fun addScanRow(orderName: String): ScanCard {
        val cardView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            elevation = dp(2).toFloat()
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, 0, 0, dp(8)) }
        }
        val tvLabel = TextView(this).apply {
            text = "ПРОВЕРКА..."
            textSize = 11f
            setTextColor(getColor(R.color.secondary))
            letterSpacing = 0.08f
        }
        val tvOrder = TextView(this).apply {
            text = orderName
            textSize = 19f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(getColor(R.color.on_background))
            setPadding(0, dp(4), 0, 0)
        }
        cardView.addView(tvLabel)
        cardView.addView(tvOrder)
        llScannedList.addView(cardView)
        return ScanCard(tvLabel, tvOrder)
    }

    private fun playTone(status: ScanStatus) {
        lifecycleScope.launch {
            try {
                delay(350)
                when (status) {
                    ScanStatus.READY -> {
                        toneGen.startTone(ToneGenerator.TONE_DTMF_9, 180)
                    }
                    ScanStatus.SHIPPED -> {
                        toneGen.startTone(ToneGenerator.TONE_DTMF_5, 180)
                        delay(230)
                        toneGen.startTone(ToneGenerator.TONE_DTMF_1, 180)
                    }
                    ScanStatus.NOT_FOUND -> {
                        repeat(3) {
                            toneGen.startTone(ToneGenerator.TONE_SUP_BUSY, 100)
                            delay(180)
                        }
                    }
                    else -> {}
                }
            } catch (_: Exception) {}
        }
    }

    private fun updateCreateButton() {
        val readyCount = scanCards.values.count { it.status == ScanStatus.READY }
        btnCreate.isEnabled = readyCount > 0
        btnCreate.text = if (readyCount > 0) "Создать отгрузки  ·  $readyCount"
                         else "Создать отгрузки"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

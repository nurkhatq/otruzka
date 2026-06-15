package com.otgruzka.tsd

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson

class HistoryActivity : AppCompatActivity() {

    private lateinit var tabAll: TextView
    private lateinit var tabPickup: TextView
    private lateinit var recordList: LinearLayout
    private var allRecords: List<ScanRecord> = emptyList()
    private var showPickupOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F3F4F6"))
        }

        // Top bar
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = dp(2).toFloat()

            addView(TextView(this@HistoryActivity).apply {
                text = "←"
                textSize = 20f
                setPadding(0, 0, dp(16), 0)
                setOnClickListener { finish() }
            })
            addView(TextView(this@HistoryActivity).apply {
                text = "История сессий"
                textSize = 17f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#111827"))
            })
        })

        // Tab bar
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(8), dp(0))
        }
        tabAll = tabBtn("Все сканы")
        tabPickup = tabBtn("Самовывоз")
        tabAll.setOnClickListener { showPickupOnly = false; switchTab(); refreshList() }
        tabPickup.setOnClickListener { showPickupOnly = true; switchTab(); refreshList() }
        tabBar.addView(tabAll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(tabPickup, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabBar)

        // List
        val scroll = ScrollView(this)
        recordList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(40))
        }
        scroll.addView(recordList)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        switchTab()
        loadRecords()
    }

    private fun loadRecords() {
        val prefs = getSharedPreferences("wms_history", MODE_PRIVATE)
        allRecords = try {
            Gson().fromJson(
                prefs.getString("records", "[]"), Array<ScanRecord>::class.java
            ).toList()
        } catch (_: Exception) { emptyList() }
        refreshList()
    }

    private fun refreshList() {
        recordList.removeAllViews()
        val filtered = if (showPickupOnly) allRecords.filter { it.isPickup } else allRecords

        if (filtered.isEmpty()) {
            recordList.addView(TextView(this).apply {
                text = if (showPickupOnly) "Нет записей самовывоза" else "История пуста"
                textSize = 14f
                setTextColor(Color.parseColor("#9CA3AF"))
                gravity = Gravity.CENTER
                setPadding(0, dp(40), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
            return
        }

        // Group by batchId
        val sessions = filtered.groupBy { it.batchId }
        sessions.forEach { (batchId, records) ->
            recordList.addView(buildSessionCard(batchId, records))
            recordList.addView(spacer(dp(8)))
        }
    }

    private fun buildSessionCard(batchId: String, records: List<ScanRecord>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#E5E7EB"))
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }

        val success = records.count { it.result == "SUCCESS" }
        val locked  = records.count { it.result == "ALREADY_LOCKED" }
        val cancel  = records.count { it.result == "CANCELLING" }
        val pickups = records.count { it.isPickup }

        // Session header
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ts = records.firstOrNull()?.timestamp?.let {
            java.text.SimpleDateFormat("dd.MM  HH:mm", java.util.Locale("ru")).format(java.util.Date(it))
        } ?: "—"
        header.addView(TextView(this).apply {
            text = ts; textSize = 13f; setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#111827"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = records.firstOrNull()?.tsdId ?: ""
            textSize = 11f; setTextColor(Color.parseColor("#9CA3AF"))
        })
        card.addView(header)

        // Stats row
        card.addView(spacer(dp(6)))
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        stats.addView(statChip("✓ $success", "#D1FAE5", "#059669"))
        if (locked > 0) stats.addView(statChip("⚠ $locked", "#FEF3C7", "#D97706"))
        if (cancel > 0) stats.addView(statChip("✗ $cancel", "#FEE2E2", "#DC2626"))
        if (pickups > 0) stats.addView(statChip("🏪 $pickups", "#EFF6FF", "#2563EB"))
        card.addView(stats)

        // Record rows (collapsible — show top 5)
        card.addView(spacer(dp(8)))
        val showAll = records.size <= 5
        records.take(if (showAll) records.size else 5).forEach { r ->
            card.addView(buildRecordRow(r))
            card.addView(spacer(dp(4)))
        }
        if (!showAll) {
            card.addView(TextView(this).apply {
                text = "Ещё ${records.size - 5}…"
                textSize = 12f
                setTextColor(Color.parseColor("#6B7280"))
                setPadding(0, dp(4), 0, 0)
            })
        }
        return card
    }

    private fun buildRecordRow(r: ScanRecord): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        val (icon, color) = when (r.result) {
            "SUCCESS"        -> "✓" to "#059669"
            "ALREADY_LOCKED" -> "⚠" to "#D97706"
            "CANCELLING"     -> "✗" to "#DC2626"
            else             -> "?" to "#9CA3AF"
        }
        row.addView(TextView(this).apply {
            text = icon; textSize = 14f
            setTextColor(Color.parseColor(color))
            setPadding(0, 0, dp(8), 0)
        })
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        info.addView(TextView(this).apply {
            text = r.orderCode; textSize = 13f
            setTextColor(Color.parseColor("#374151"))
        })
        if (!r.customerName.isNullOrBlank()) {
            info.addView(TextView(this).apply {
                text = r.customerName; textSize = 11f
                setTextColor(Color.parseColor("#9CA3AF"))
            })
        }
        row.addView(info)
        if (r.isPickup) row.addView(TextView(this).apply {
            text = "🏪"; textSize = 12f; setPadding(dp(4), 0, 0, 0)
        })
        return row
    }

    private fun switchTab() {
        val active = Color.parseColor("#2563EB"); val inactive = Color.parseColor("#6B7280")
        if (!showPickupOnly) {
            tabAll.setTextColor(active); tabAll.setBackgroundColor(Color.parseColor("#DBEAFE"))
            tabPickup.setTextColor(inactive); tabPickup.setBackgroundColor(Color.TRANSPARENT)
        } else {
            tabPickup.setTextColor(active); tabPickup.setBackgroundColor(Color.parseColor("#DBEAFE"))
            tabAll.setTextColor(inactive); tabAll.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tabBtn(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f
        setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(10))
    }

    private fun statChip(text: String, bg: String, fg: String) = TextView(this).apply {
        this.text = text; textSize = 12f
        setTextColor(Color.parseColor(fg))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor(bg)); cornerRadius = dp(6).toFloat()
        }
        setPadding(dp(8), dp(3), dp(8), dp(3))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, dp(6), 0) }
    }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }
}

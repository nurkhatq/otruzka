package com.otgruzka.tsd

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.*
import kotlinx.coroutines.launch

class InvHistoryActivity : AppCompatActivity() {

    private lateinit var api: WmsApi
    private lateinit var tvTitle: TextView
    private lateinit var llList: LinearLayout
    private lateinit var svList: ScrollView
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!WmsAuth.isLoggedIn(this)) { finish(); return }
        api = WmsApiClient.build(this)
        setContentView(R.layout.activity_inv_history)
        tvTitle = findViewById(R.id.tvInvHistTitle)
        llList  = findViewById(R.id.llInvHistList)
        svList  = findViewById(R.id.svInvHistList)
        tvEmpty = findViewById(R.id.tvInvHistEmpty)
        findViewById<TextView>(R.id.tvInvHistBack).setOnClickListener { finish() }
        loadSessions()
    }

    private fun loadSessions() {
        tvTitle.text = "История инвентаризаций"
        lifecycleScope.launch {
            try {
                val resp = api.getInvSessions()
                llList.removeAllViews()
                if (resp.items.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                } else {
                    tvEmpty.visibility = View.GONE
                    resp.items.forEach { session ->
                        llList.addView(buildSessionRow(session))
                        llList.addView(spacer(dp(8)))
                    }
                }
            } catch (e: Exception) {
                tvEmpty.text = "Ошибка: ${e.message?.take(60)}"
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun buildSessionRow(session: InvSession): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(getColor(R.color.surface))
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(14), dp(18), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { showSessionDetail(session) }
        }

        val statusColor = when (session.status) {
            "COMPLETED" -> getColor(R.color.success)
            "CANCELLED" -> getColor(R.color.error)
            else        -> getColor(R.color.secondary)
        }
        val statusLabel = when (session.status) {
            "COMPLETED" -> "ЗАВЕРШЕНА"
            "CANCELLED" -> "ОТМЕНЕНА"
            else        -> session.status
        }

        row.addView(TextView(this).apply {
            text = statusLabel; textSize = 10f; letterSpacing = 0.08f
            setTextColor(statusColor); setTypeface(null, Typeface.BOLD)
        })

        val wh = WmsAuth.WAREHOUSE_NAMES[session.warehouse_id] ?: "Склад ${session.warehouse_id}"
        row.addView(TextView(this).apply {
            text = wh; textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
            setPadding(0, dp(4), 0, 0)
        })

        val date = session.started_at.take(10)
        val creator = session.created_by_name ?: ""
        row.addView(small("$date · $creator"))

        val total = session.total
        row.addView(small("Позиций: $total"))

        return row
    }

    private fun showSessionDetail(session: InvSession) {
        lifecycleScope.launch {
            try {
                val stats = api.getInvSessionStats(session.session_id)
                val bs = stats.by_status
                val matched = (bs["MATCHED"] ?: 0) + (bs["VERIFIED"] ?: 0)
                val pending = bs["PENDING"] ?: 0
                val disc = bs["DISCREPANCY"] ?: 0

                val msg = buildString {
                    append("Всего: ${stats.total}\n")
                    append("✓ Ок: $matched\n")
                    if (pending > 0) append("⏳ Проверка: $pending\n")
                    if (disc > 0) append("⚠ Расхождения: $disc\n")
                    val dur = stats.duration_sec
                    if (dur != null) {
                        val m = dur / 60; val s = dur % 60
                        append("Время: ${m}м ${s}с")
                    }
                }

                AlertDialog.Builder(this@InvHistoryActivity)
                    .setTitle("${session.started_at.take(10)} · ${WmsAuth.WAREHOUSE_NAMES[session.warehouse_id] ?: "Склад"}")
                    .setMessage(msg)
                    .setPositiveButton("Показать позиции") { _, _ -> showItems(session) }
                    .setNegativeButton("Закрыть", null)
                    .show()
            } catch (e: Exception) {
                toast("Ошибка: ${e.message?.take(50)}")
            }
        }
    }

    private fun showItems(session: InvSession) {
        lifecycleScope.launch {
            try {
                val resp = api.getInvSessionItems(session.session_id, pageSize = 100)
                if (resp.items.isEmpty()) { toast("Нет позиций"); return@launch }

                val lines = resp.items.joinToString("\n") { item ->
                    val status = when (item.status) {
                        "MATCHED"     -> "✓"
                        "VERIFIED"    -> "✓✓"
                        "PENDING"     -> "⏳"
                        "DISCREPANCY" -> "⚠"
                        else          -> "?"
                    }
                    val name = item.product_name?.take(28) ?: item.barcode
                    val qty = if (item.verifier_qty != null)
                        "${item.counter_qty}/${item.verifier_qty}"
                    else "${item.counter_qty}"
                    "$status $name — $qty (МС: ${item.ms_stock?.toInt() ?: "?"})"
                }

                AlertDialog.Builder(this@InvHistoryActivity)
                    .setTitle("Позиции (${resp.total})")
                    .setMessage(lines)
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                toast("Ошибка: ${e.message?.take(50)}")
            }
        }
    }

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

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

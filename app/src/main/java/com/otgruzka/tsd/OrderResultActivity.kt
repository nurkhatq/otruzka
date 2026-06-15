package com.otgruzka.tsd

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CreateDemandsRequest
import com.otgruzka.tsd.api.WmsApiClient
import kotlinx.coroutines.launch

class OrderResultActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var llResults: LinearLayout
    private lateinit var btnBack: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val codes = intent.getStringArrayListExtra("codes") ?: arrayListOf()
        val sessionBatchId = intent.getStringExtra("session_batch_id")

        // Build UI (same style as original OrderActivity)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
            setPadding(dp(20), dp(0), dp(20), dp(20))
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.let { }
        }

        tvTitle = TextView(this).apply {
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
            letterSpacing = 0.02f
            setPadding(0, dp(12), 0, dp(16))
        }
        tvTitle.text = "ОТГРУЗКИ  ·  ${codes.size}"
        root.addView(tvTitle)

        root.addView(TextView(this).apply {
            text = "01 · СПИСОК"
            textSize = 10f
            setTextColor(getColor(R.color.secondary))
            letterSpacing = 0.1f
            setPadding(0, 0, 0, dp(10))
        })

        val scroll = ScrollView(this).apply { android.view.ViewTreeObserver.OnGlobalLayoutListener { } }
        llResults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(llResults)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        btnBack = Button(this).apply {
            text = "← Назад"
            textSize = 15f; isAllCaps = false
            setTextColor(getColor(R.color.secondary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(0)
            elevation = 0f
            visibility = View.GONE
            setOnClickListener { finish() }
        }
        root.addView(btnBack, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)))

        setContentView(root)

        // Add placeholder rows
        codes.forEach { code -> addRow("ПОИСК…", getColor(R.color.secondary), code) }

        // Start creating demands
        lifecycleScope.launch {
            val api = WmsApiClient.build(this@OrderResultActivity)
            try {
                val resp = api.createDemands(CreateDemandsRequest(codes, sessionBatchId))
                llResults.removeAllViews()

                var created = 0; var skipped = 0; var failed = 0

                resp.results.forEach { r ->
                    when (r.status) {
                        "CREATED" -> {
                            addRow("СОЗДАНА", getColor(R.color.success),
                                "${r.code}  /  ${r.demand_name ?: "—"}")
                            created++
                        }
                        "ALREADY_SHIPPED" -> {
                            addRow("УЖЕ ОТГРУЖЕНА", getColor(R.color.warning), r.code)
                            skipped++
                        }
                        "NOT_IN_MS" -> {
                            addRow("НЕТ В МС", getColor(R.color.warning), r.code)
                            skipped++
                        }
                        else -> {
                            addRow("ОШИБКА  ${r.status}", getColor(R.color.error),
                                "${r.code}  —  ${r.detail ?: ""}")
                            failed++
                        }
                    }
                }

                // Summary
                llResults.addView(View(this@OrderResultActivity).apply {
                    setBackgroundColor(getColor(R.color.divider))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { setMargins(0, dp(8), 0, dp(8)) }
                })
                addRow("02 · ИТОГО", getColor(R.color.secondary),
                    "Создано $created  ·  Пропущено $skipped  ·  Ошибок $failed")

            } catch (e: Exception) {
                llResults.removeAllViews()
                addRow("ОШИБКА СОЕДИНЕНИЯ", getColor(R.color.error),
                    e.message?.take(80) ?: "Неизвестная ошибка")
            }
            btnBack.visibility = View.VISIBLE
        }
    }

    private fun addRow(label: String, labelColor: Int, detail: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            elevation = dp(2).toFloat()
            setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(8)) }
        }
        card.addView(TextView(this).apply {
            text = label; textSize = 10f; letterSpacing = 0.08f
            setTextColor(labelColor); setTypeface(null, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = detail; textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
            setPadding(0, dp(4), 0, 0)
        })
        llResults.addView(card)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

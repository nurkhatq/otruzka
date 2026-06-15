package com.otgruzka.tsd

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CreateDemandsRequest
import com.otgruzka.tsd.api.DemandResult
import com.otgruzka.tsd.api.WmsApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OrderResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val codes = intent.getStringArrayListExtra("codes") ?: arrayListOf()
        val sessionBatchId = intent.getStringExtra("session_batch_id")
        val api = WmsApiClient.build(this)

        // ── Root ────────────────────────────────────────────────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F0EDE8"))
        }

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvTitle = TextView(this).apply {
            text = "Создание отгрузок"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(tvTitle)
        root.addView(topBar, lp(matchW, wrapH))

        // ── Progress card ───────────────────────────────────────────────────
        val progressCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutParams = lp(matchW, wrapH).apply { topMargin = dp(14); leftMargin = dp(14); rightMargin = dp(14) }
            background = roundedBg(Color.WHITE, dp(14))
            elevation = dp(2).toFloat()
        }

        val tvStatus = TextView(this).apply {
            text = "Запуск…"
            textSize = 14f
            setTextColor(Color.parseColor("#9896A8"))
            gravity = Gravity.CENTER
        }

        val tvCount = TextView(this).apply {
            text = "0 из ${codes.size}"
            textSize = 32f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = codes.size.coerceAtLeast(1)
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#5956E8"))
        }

        val tvSubStatus = TextView(this).apply {
            text = "Отгрузки создаются в фоне"
            textSize = 12f
            setTextColor(Color.parseColor("#BCBAC8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }

        progressCard.addView(tvStatus)
        progressCard.addView(tvCount)
        progressCard.addView(progressBar, lp(matchW, wrapH))
        progressCard.addView(tvSubStatus)

        // Кнопка "Назад в сканирование"
        val btnBack = Button(this).apply {
            text = "Назад в сканирование"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.parseColor("#5956E8"))
            setBackgroundColor(Color.TRANSPARENT)
            visibility = View.GONE
            setOnClickListener { finish() }
        }
        progressCard.addView(btnBack, lp(matchW, wrapH).apply { topMargin = dp(16) })

        root.addView(progressCard)

        // ── Results section ─────────────────────────────────────────────────
        val resultsHeader = TextView(this).apply {
            text = "РЕЗУЛЬТАТЫ"
            textSize = 10f
            letterSpacing = 0.1f
            setTextColor(Color.parseColor("#9896A8"))
            setPadding(dp(28), dp(20), dp(14), dp(8))
            visibility = View.GONE
        }
        root.addView(resultsHeader, lp(matchW, wrapH))

        val scroll = ScrollView(this)
        val llResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(40))
        }
        scroll.addView(llResults)
        root.addView(scroll, lp(matchW, 0, 1f))

        setContentView(root)

        // ── Summary card builder ────────────────────────────────────────────
        fun showSummary(results: List<DemandResult>) {
            var created = 0; var shipped = 0; var notInMs = 0; var errors = 0

            llResults.removeAllViews()
            results.forEach { r ->
                when (r.status) {
                    "CREATED"        -> { created++; addResultRow(llResults, "СОЗДАНА",       "#1A6B36", r.code, r.demand_name) }
                    "ALREADY_SHIPPED"-> { shipped++; addResultRow(llResults, "УЖЕ ОТГРУЖЕНА", "#5956E8", r.code, null) }
                    "NOT_IN_MS"      -> { notInMs++; addResultRow(llResults, "НЕТ В МС",      "#9896A8", r.code, null) }
                    else             -> { errors++;  addResultRow(llResults, "ОШИБКА",         "#C42828", r.code, r.detail) }
                }
            }

            // Summary chip row
            val summaryRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            if (created > 0)  summaryRow.addView(summaryChip("Создано $created",  "#1A6B36", "#E6F4EC"))
            if (shipped > 0)  summaryRow.addView(summaryChip("Отгружено $shipped","#5956E8", "#EEEDFB"))
            if (notInMs > 0)  summaryRow.addView(summaryChip("Нет в МС $notInMs","#9896A8", "#F3F3F5"))
            if (errors > 0)   summaryRow.addView(summaryChip("Ошибок $errors",   "#C42828", "#FDEAEA"))
            llResults.addView(summaryRow)
        }

        // ── Polling loop ────────────────────────────────────────────────────
        lifecycleScope.launch {
            try {
                val jobResp = api.createDemands(CreateDemandsRequest(codes, sessionBatchId))
                val jobId = jobResp.job_id

                if (jobId == null) {
                    // No token or instant error
                    tvStatus.text = "Ошибка конфигурации"
                    tvCount.text = "—"
                    btnBack.visibility = View.VISIBLE
                    return@launch
                }

                tvStatus.text = "Создаётся…"

                // Poll until DONE or ERROR
                while (isActive) {
                    delay(2000)
                    try {
                        val job = api.getDemandJob(jobId)
                        val done = job.done
                        val total = job.total.coerceAtLeast(1)

                        progressBar.max = total
                        progressBar.progress = done
                        tvCount.text = "$done из $total"

                        when (job.status) {
                            "DONE" -> {
                                tvStatus.text = "Готово"
                                tvSubStatus.text = ""
                                tvCount.text = "$total из $total"
                                progressBar.progress = total
                                btnBack.visibility = View.VISIBLE
                                resultsHeader.visibility = View.VISIBLE
                                job.results?.let { showSummary(it) }
                                break
                            }
                            "ERROR" -> {
                                tvStatus.text = "Завершено с ошибками"
                                tvSubStatus.text = ""
                                btnBack.visibility = View.VISIBLE
                                resultsHeader.visibility = View.VISIBLE
                                job.results?.let { showSummary(it) }
                                break
                            }
                            "NOT_FOUND" -> {
                                tvStatus.text = "Задание не найдено"
                                btnBack.visibility = View.VISIBLE
                                break
                            }
                            // PROCESSING — continue polling
                        }
                    } catch (_: Exception) {
                        // Network blip — keep polling
                    }
                }

            } catch (e: Exception) {
                tvStatus.text = "Ошибка запуска"
                tvSubStatus.text = e.message?.take(80) ?: "Нет соединения"
                btnBack.visibility = View.VISIBLE
            }
        }
    }

    private fun addResultRow(parent: LinearLayout, label: String, labelColor: String, code: String, detail: String?) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(Color.WHITE, dp(12))
            elevation = dp(1).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = lp(matchW, wrapH).apply { bottomMargin = dp(8) }
        }
        card.addView(TextView(this).apply {
            text = label
            textSize = 10f
            letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor(labelColor))
        })
        card.addView(TextView(this).apply {
            text = code
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            setPadding(0, dp(3), 0, 0)
        })
        if (!detail.isNullOrBlank()) {
            card.addView(TextView(this).apply {
                text = detail
                textSize = 12f
                setTextColor(Color.parseColor("#9896A8"))
                setPadding(0, dp(2), 0, 0)
            })
        }
        parent.addView(card)
    }

    private fun summaryChip(text: String, fg: String, bg: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.parseColor(fg))
        setPadding(dp(12), dp(5), dp(12), dp(5))
        background = roundedBg(Color.parseColor(bg), dp(8))
        layoutParams = lp(wrapH, wrapH).apply { rightMargin = dp(6) }
    }

    private fun roundedBg(color: Int, radius: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private val matchW = LinearLayout.LayoutParams.MATCH_PARENT
    private val wrapH  = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

package com.otgruzka.tsd

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CoreApi
import com.otgruzka.tsd.api.CoreApiClient
import com.otgruzka.tsd.api.InvCount
import kotlinx.coroutines.launch

/**
 * Инвентаризация: выбор пересчёта и вход в работу.
 *
 * Если ячейка уже открыта на этом ТСД (перезаход, приложение убили) — сразу
 * бросаем в неё: серверное состояние главнее локального.
 */
class InventoryCountsActivity : AppCompatActivity() {

    private val BG = PickerTasksActivity.BG
    private val WHITE = Color.WHITE
    private val BORDER = PickerTasksActivity.BORDER
    private val TEXT = PickerTasksActivity.TEXT
    private val MUTED = PickerTasksActivity.MUTED
    private val BLUE = PickerTasksActivity.BLUE

    private lateinit var api: CoreApi
    private lateinit var llContent: LinearLayout
    private lateinit var tvHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CoreAuth.isLoggedIn(this)) { finish(); return }
        api = CoreApiClient.build(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            fitsSystemWindows = true
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WHITE)
            elevation = dp(2).toFloat()
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        head.addView(TextView(this).apply {
            text = "Инвентаризация"
            textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
        })
        head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(TextView(this).apply {
            text = "← Назад"
            textSize = 13f; setTextColor(BLUE)
            setPadding(dp(8), dp(6), dp(4), dp(6))
            setOnClickListener { finish() }
        })
        root.addView(head)

        tvHint = TextView(this).apply {
            textSize = 12f; setTextColor(MUTED)
            setPadding(dp(16), dp(10), dp(16), 0)
        }
        root.addView(tvHint)

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
        return root
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                // Незакрытая ячейка важнее списка: продолжаем прерванный подсчёт
                val active = api.invActive().my_cell
                if (active != null) {
                    openCell(active.count_cell_id)
                    return@launch
                }
                render(api.invCounts("OPEN"))
            } catch (e: Exception) {
                toast("Ошибка: ${e.message?.take(60)}")
            }
        }
    }

    private fun render(counts: List<InvCount>) {
        llContent.removeAllViews()
        tvHint.text =
            if (counts.isEmpty()) "Открытых пересчётов нет — заведите его на сайте, в разделе «Склад»."
            else "Выберите пересчёт, потом сканируйте штрихкод ячейки."
        for (c in counts) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedBorder(WHITE, BORDER, 14)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setOnClickListener {
                    startActivity(
                        Intent(this@InventoryCountsActivity, InventoryCellActivity::class.java)
                            .putExtra("count_id", c.id)
                    )
                }
            }
            card.addView(TextView(this).apply {
                text = c.number
                textSize = 16f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT)
            })
            val p = c.progress
            val zone = when (c.zone) {
                "picking" -> "МХ (полки)"
                "reserve" -> "ДХ (хранение)"
                "defect" -> "Брак"
                null -> "все зоны"
                else -> c.zone
            }
            card.addView(TextView(this).apply {
                text = buildString {
                    append(zone)
                    if (c.blind) append(" · слепой")
                    if (p != null) {
                        append(" · посчитано ${p.posted + p.counted} из ${p.cells_total}")
                        if (p.in_progress > 0) append(" · считают ${p.in_progress}")
                    }
                }
                textSize = 12f; setTextColor(MUTED)
                setPadding(0, dp(4), 0, 0)
            })
            if (!c.comment.isNullOrBlank()) {
                card.addView(TextView(this).apply {
                    text = c.comment
                    textSize = 12f; setTextColor(MUTED)
                    setPadding(0, dp(2), 0, 0)
                })
            }
            llContent.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) })
        }
    }

    private fun openCell(countCellId: Int) {
        startActivity(
            Intent(this, InventoryCellActivity::class.java)
                .putExtra("count_cell_id", countCellId)
        )
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundedBorder(bg: Int, stroke: Int, r: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(r).toFloat(); setStroke(dp(1), stroke)
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

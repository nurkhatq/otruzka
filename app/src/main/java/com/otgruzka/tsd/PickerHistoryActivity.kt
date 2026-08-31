package com.otgruzka.tsd

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CoreApi
import com.otgruzka.tsd.api.CoreApiClient
import com.otgruzka.tsd.api.PickerTask
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * История сборок текущего сборщика (как в waybills): поиск, за смену / вся,
 * повторная печать накладной, если PDF уже собирался.
 */
class PickerHistoryActivity : AppCompatActivity() {

    private lateinit var api: CoreApi
    private lateinit var llList: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnScopeSession: TextView
    private lateinit var btnScopeAll: TextView

    private var sessionId: Int = 0
    private var sessionOnly = true
    private var tasks: List<PickerTask> = emptyList()
    private var reprintingId: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        api = CoreApiClient.build(this)
        sessionId = intent.getIntExtra("session_id", 0)
        sessionOnly = sessionId != 0
        setContentView(buildUi())
        load()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
            fitsSystemWindows = true
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            elevation = dp(3).toFloat()
            setPadding(dp(8), dp(10), dp(16), dp(10))
        }
        top.addView(TextView(this).apply {
            text = "←"; textSize = 22f
            setTextColor(getColor(R.color.primary))
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener { finish() }
        })
        top.addView(TextView(this).apply {
            text = "История сборок"
            textSize = 16f; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        root.addView(top)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), 0)
        }

        if (sessionId != 0) {
            val scopeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            btnScopeSession = scopeButton("За смену") { setScope(true) }
            btnScopeAll = scopeButton("Вся история") { setScope(false) }
            scopeRow.addView(btnScopeSession, LinearLayout.LayoutParams(0, dp(36), 1f))
            scopeRow.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
            scopeRow.addView(btnScopeAll, LinearLayout.LayoutParams(0, dp(36), 1f))
            controls.addView(scopeRow)
        }

        etSearch = EditText(this).apply {
            hint = "Поиск: название или номер заказа"
            textSize = 14f
            setTextColor(getColor(R.color.on_background))
            setHintTextColor(getColor(R.color.secondary))
            setBackgroundResource(R.drawable.input_bg)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { renderList() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        controls.addView(etSearch, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        tvStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(getColor(R.color.secondary))
            setPadding(dp(4), dp(8), 0, dp(4))
        }
        controls.addView(tvStatus)
        root.addView(controls)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0)
                .apply { weight = 1f }
        }
        llList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(20))
        }
        scroll.addView(llList)
        root.addView(scroll)
        return root
    }

    private fun scopeButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; setTypeface(null, Typeface.BOLD)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun setScope(session: Boolean) {
        sessionOnly = session
        load()
    }

    private fun styleScope() {
        if (sessionId == 0) return
        fun style(btn: TextView, active: Boolean) {
            btn.setTextColor(getColor(if (active) R.color.on_primary else R.color.secondary))
            btn.background = cardDrawable(if (active) getColor(R.color.primary) else 0x14000000)
        }
        style(btnScopeSession, sessionOnly)
        style(btnScopeAll, !sessionOnly)
    }

    private fun load() {
        styleScope()
        tvStatus.text = "Загрузка…"
        lifecycleScope.launch {
            try {
                val resp = api.history(
                    limit = 100,
                    sessionId = if (sessionOnly && sessionId != 0) sessionId else null,
                )
                tasks = resp.tasks ?: emptyList()
                renderList()
            } catch (e: Exception) {
                tvStatus.text = "Ошибка: ${e.message?.take(60)}"
            }
        }
    }

    private fun renderList() {
        val q = etSearch.text.toString().trim().lowercase()
        val filtered = if (q.isEmpty()) tasks else tasks.filter { t ->
            (t.product_name ?: "").lowercase().contains(q) ||
                t.orders.orEmpty().any { it.order_code.contains(q, ignoreCase = true) }
        }
        tvStatus.text = when {
            tasks.isEmpty() -> "Завершённых заданий пока нет"
            filtered.isEmpty() -> "Ничего не найдено"
            else -> "Заданий: ${filtered.size}"
        }
        llList.removeAllViews()
        filtered.forEach { t ->
            llList.addView(historyRow(t))
            llList.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8))
            })
        }
    }

    private fun historyRow(t: PickerTask): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable(getColor(R.color.surface))
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(11), dp(14), dp(11))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(this).apply {
            text = t.product_name ?: "—"
            textSize = 13f; maxLines = 2
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
        })
        val codes = t.orders.orEmpty().map { it.order_code }.distinct()
        val codesLabel = codes.take(2).joinToString(", ") +
            if (codes.size > 2) " +${codes.size - 2}" else ""
        col.addView(TextView(this).apply {
            text = codesLabel
            textSize = 11f
            setTextColor(getColor(R.color.secondary))
            setPadding(0, dp(2), 0, 0)
        })
        col.addView(TextView(this).apply {
            text = "#${t.id} · ${PickerTasksActivity.catLabel(t.task_type)} · " +
                "${t.scanned_qty}/${t.total_orders}" +
                (fmtTime(t.completed_at)?.let { " · $it" } ?: "")
            textSize = 11f
            setTextColor(getColor(R.color.secondary))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)

        if (t.pdf_filename != null) {
            row.addView(Button(this).apply {
                text = if (reprintingId == t.id) "…" else "Печать"
                textSize = 12f; isAllCaps = false
                isEnabled = reprintingId != t.id
                setTextColor(getColor(R.color.on_primary))
                backgroundTintList =
                    android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
                setOnClickListener { reprint(t.id) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)
            ).apply { leftMargin = dp(8) })
        }
        return row
    }

    private fun reprint(taskId: Int) {
        if (reprintingId != null) return
        reprintingId = taskId
        renderList()
        lifecycleScope.launch {
            try {
                api.reprint(taskId)
                toast("Накладная отправлена на принт-станцию")
            } catch (e: Exception) {
                toast("Ошибка печати: ${e.message?.take(60)}")
            } finally {
                reprintingId = null
                renderList()
            }
        }
    }

    /** UTC ISO → время Алматы (как в waybills). */
    private fun fmtTime(iso: String?): String? {
        if (iso.isNullOrBlank()) return null
        return try {
            val clean = iso.substringBefore(".").replace("Z", "").replace("T", " ")
            val inFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val outFmt = SimpleDateFormat("dd.MM HH:mm", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Almaty")
            }
            outFmt.format(inFmt.parse(clean)!!)
        } catch (_: Exception) { null }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardDrawable(bg: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(12).toFloat()
        }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

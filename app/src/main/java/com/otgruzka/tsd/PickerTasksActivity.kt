package com.otgruzka.tsd

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CoreApi
import com.otgruzka.tsd.api.CoreApiClient
import com.otgruzka.tsd.api.PickerSessionMe
import com.otgruzka.tsd.api.PickerTask
import kotlinx.coroutines.launch

/** Смена сборщика: начать/завершить + список назначенных заданий (round-robin с сервера). */
class PickerTasksActivity : AppCompatActivity() {

    companion object {
        val CAT_LABELS = mapOf(
            "mass_single" to "Массовые",
            "single" to "Одиночные",
            "multi_qty" to "Мультикол-во",
            "kit" to "Наборы",
            "multi" to "Мультипозиция",
            "CANCEL" to "Отмена",
        )

        fun catLabel(type: String?): String = CAT_LABELS[type] ?: (type ?: "—")
    }

    private lateinit var api: CoreApi
    private lateinit var tvUser: TextView
    private lateinit var tvStoreCity: TextView
    private lateinit var tvSessionState: TextView
    private lateinit var btnSession: Button
    private lateinit var llTasks: LinearLayout
    private lateinit var tvTasksTitle: TextView

    private var me: PickerSessionMe? = null
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CoreAuth.isLoggedIn(this)) {
            startActivity(Intent(this, PickerLoginActivity::class.java)); finish(); return
        }
        api = CoreApiClient.build(this)
        setContentView(buildUi())
        renderHeader()
    }

    override fun onResume() {
        super.onResume()
        if (CoreAuth.isLoggedIn(this)) load()
    }

    // ─── UI ──────────────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
            fitsSystemWindows = true
        }

        // Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(getColor(R.color.surface))
            elevation = dp(3).toFloat()
            setPadding(dp(20), dp(14), dp(12), dp(14))
        }
        val userCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvUser = TextView(this).apply {
            textSize = 15f; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
        }
        tvStoreCity = TextView(this).apply {
            textSize = 11f; setTextColor(getColor(R.color.primary))
            setPadding(0, dp(2), 0, 0)
            setOnClickListener { changeStoreOrCity() }
        }
        userCol.addView(tvUser); userCol.addView(tvStoreCity)
        top.addView(userCol)
        top.addView(headerLink("История", R.color.primary) {
            val i = Intent(this, PickerHistoryActivity::class.java)
            me?.session?.id?.let { sid -> i.putExtra("session_id", sid) }
            startActivity(i)
        })
        top.addView(headerLink("Выйти", R.color.error) { doLogout() })
        root.addView(top)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
            isFillViewport = true
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(20))
        }

        // Session card
        val sessionCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable(getColor(R.color.surface))
            elevation = dp(2).toFloat()
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        tvSessionState = TextView(this).apply {
            textSize = 13f; setTextColor(getColor(R.color.secondary))
        }
        btnSession = Button(this).apply {
            textSize = 15f; isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_primary))
            setOnClickListener { toggleSession() }
        }
        sessionCard.addView(tvSessionState)
        sessionCard.addView(btnSession, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(10) })
        body.addView(sessionCard)

        tvTasksTitle = TextView(this).apply {
            text = "МОИ ЗАДАНИЯ"
            textSize = 11f; letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.secondary))
            setPadding(dp(4), dp(18), 0, dp(8))
        }
        body.addView(tvTasksTitle)

        llTasks = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(llTasks)

        scroll.addView(body)
        root.addView(scroll)
        return root
    }

    private fun renderHeader() {
        tvUser.text = CoreAuth.fullName(this) ?: CoreAuth.username(this) ?: ""
        val store = CoreAuth.storeName(this) ?: "магазин?"
        val city = CoreAuth.cityLabel(CoreAuth.city(this))
        tvStoreCity.text = "$store · $city ▾"
    }

    // ─── Data ────────────────────────────────────────────────────────────────

    private fun load() {
        if (loading) return
        val storeId = CoreAuth.storeId(this)
        val city = CoreAuth.city(this)
        if (storeId == 0 || city.isNullOrBlank()) {
            tvSessionState.text = "Выберите магазин и город (нажмите сверху)"
            btnSession.visibility = View.GONE
            return
        }
        loading = true
        tvSessionState.text = "Загрузка…"
        lifecycleScope.launch {
            try {
                me = api.sessionMe(storeId, city)
                renderSession()
                renderTasks()
            } catch (e: Exception) {
                tvSessionState.text = "Нет связи: ${e.message?.take(50)}"
            } finally {
                loading = false
            }
        }
    }

    private fun renderSession() {
        val m = me ?: return
        btnSession.visibility = View.VISIBLE
        if (m.in_session) {
            val cnt = m.active_sessions_count ?: 1
            tvSessionState.text = "Смена активна · сборщиков на смене: $cnt"
            btnSession.text = "Завершить смену"
            btnSession.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.error))
        } else {
            tvSessionState.text = "Смена не начата — задания раздаются только на смене"
            btnSession.text = "Начать смену"
            btnSession.backgroundTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.primary))
        }
    }

    private fun renderTasks() {
        val m = me ?: return
        llTasks.removeAllViews()
        val tasks = m.tasks ?: emptyList()
        tvTasksTitle.text = "МОИ ЗАДАНИЯ (${tasks.size})"
        if (!m.in_session) {
            llTasks.addView(emptyNote("Начните смену, чтобы получить задания"))
            return
        }
        if (tasks.isEmpty()) {
            llTasks.addView(emptyNote("Заданий пока нет. Обновится само при открытии экрана."))
            return
        }
        tasks.forEach { t ->
            llTasks.addView(taskRow(t))
            llTasks.addView(spacer(dp(8)))
        }
    }

    private fun taskRow(t: PickerTask): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardDrawable(getColor(R.color.surface))
            elevation = dp(2).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener {
                startActivity(
                    Intent(this@PickerTasksActivity, PickerTaskActivity::class.java)
                        .putExtra("task_id", t.id)
                )
            }
        }
        val inProgress = t.scanned_qty > 0
        row.addView(TextView(this).apply {
            text = catLabel(t.task_type)
            textSize = 10f; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(if (t.task_type == "kit") R.color.warning else R.color.primary))
            background = cardDrawable(0x145956E8)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(10) }
        }
        col.addView(TextView(this).apply {
            text = t.product_name ?: "—"
            textSize = 14f; maxLines = 2
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_background))
        })
        col.addView(TextView(this).apply {
            text = "#${t.id}" + if (inProgress) " · в работе" else ""
            textSize = 11f
            setTextColor(getColor(if (inProgress) R.color.warning else R.color.secondary))
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col)
        row.addView(TextView(this).apply {
            text = "${t.scanned_qty}/${t.total_orders}"
            textSize = 17f; setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(if (t.scanned_qty >= t.total_orders) R.color.success else R.color.primary))
        })
        return row
    }

    // ─── Actions ─────────────────────────────────────────────────────────────

    private fun toggleSession() {
        val m = me
        if (m?.in_session == true) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Завершить смену")
                .setMessage("Незапущенные задания вернутся в очередь и уйдут другим сборщикам.")
                .setPositiveButton("Завершить") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            api.endSession()
                            toast("Смена завершена")
                            load()
                        } catch (e: Exception) { toast("Ошибка: ${e.message?.take(50)}") }
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        } else {
            val storeId = CoreAuth.storeId(this)
            val city = CoreAuth.city(this) ?: return
            btnSession.isEnabled = false
            lifecycleScope.launch {
                try {
                    val r = api.startSession(storeId, city)
                    toast("Смена начата · назначено заданий: ${r.assigned ?: 0}")
                    load()
                } catch (e: Exception) {
                    toast("Ошибка: ${e.message?.take(50)}")
                } finally {
                    btnSession.isEnabled = true
                }
            }
        }
    }

    private fun changeStoreOrCity() {
        lifecycleScope.launch {
            val stores = try { api.stores() } catch (_: Exception) { emptyList() }
            val names = stores.map { it.name ?: it.code ?: "Магазин ${it.id}" }
            val cityLocked = CoreAuth.cityLocked(this@PickerTasksActivity)
            val cityKeys = CoreAuth.CITY_NAMES.keys.toList()

            val storePick: (() -> Unit) -> Unit = { next ->
                if (stores.size > 1) {
                    android.app.AlertDialog.Builder(this@PickerTasksActivity)
                        .setTitle("Магазин")
                        .setItems(names.toTypedArray()) { _, i ->
                            CoreAuth.setStore(this@PickerTasksActivity, stores[i].id, names[i])
                            next()
                        }
                        .show()
                } else next()
            }
            storePick {
                if (!cityLocked) {
                    android.app.AlertDialog.Builder(this@PickerTasksActivity)
                        .setTitle("Город сборки")
                        .setItems(CoreAuth.CITY_NAMES.values.toTypedArray()) { _, i ->
                            CoreAuth.setCity(this@PickerTasksActivity, cityKeys[i])
                            renderHeader(); load()
                        }
                        .show()
                } else {
                    renderHeader(); load()
                }
            }
        }
    }

    private fun doLogout() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Выход из сборки")
            .setMessage("Выйти из аккаунта сборщика? Активная смена останется открытой.")
            .setPositiveButton("Выйти") { _, _ ->
                CoreAuth.logout(this); CoreApiClient.reset()
                finish()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun headerLink(text: String, colorRes: Int, onClick: () -> Unit) =
        TextView(this).apply {
            this.text = text; textSize = 13f
            setTextColor(getColor(colorRes))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { onClick() }
        }

    private fun emptyNote(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f; gravity = Gravity.CENTER
        setTextColor(getColor(R.color.secondary))
        setPadding(dp(10), dp(30), dp(10), dp(30))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun cardDrawable(bg: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(12).toFloat()
        }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}

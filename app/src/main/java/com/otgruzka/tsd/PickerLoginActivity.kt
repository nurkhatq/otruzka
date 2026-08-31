package com.otgruzka.tsd

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.CoreApiClient
import com.otgruzka.tsd.api.CoreStore
import kotlinx.coroutines.launch

/**
 * Вход сборщика в ядро novamanya (qoimams.asia). Аккаунты — те же, что у
 * веб-сборщика; после входа подтягиваем профиль (город) и список магазинов.
 */
class PickerLoginActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (CoreAuth.isLoggedIn(this)) { startTasks(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(getColor(R.color.background))
            setPadding(dp(28), 0, dp(28), 0)
        }

        root.addView(TextView(this).apply {
            text = "СБОРКА"
            textSize = 34f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.primary))
            letterSpacing = 0.02f
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Сборщик заказов · qoimams.asia"
            textSize = 13f
            setTextColor(getColor(R.color.secondary))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(36))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            elevation = dp(4).toFloat()
            setPadding(dp(22), dp(26), dp(22), dp(26))
        }

        card.addView(fieldLabel("ЛОГИН"))
        etUsername = editField("Логин сборщика", false)
        card.addView(etUsername, wrapLp(0, dp(14)))

        card.addView(fieldLabel("ПАРОЛЬ"))
        etPassword = editField("Пароль", true)
        card.addView(etPassword, wrapLp(0, dp(10)))

        tvError = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.error))
            visibility = View.GONE
        }
        card.addView(tvError, wrapLp(0, dp(18)))

        btnLogin = Button(this).apply {
            text = "Войти"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(getColor(R.color.on_primary))
            backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.on_background))
            isAllCaps = false
            setOnClickListener { doLogin() }
        }
        card.addView(btnLogin, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
        ))

        root.addView(card)

        root.addView(TextView(this).apply {
            text = "← Назад к отгрузке"
            textSize = 13f
            setTextColor(getColor(R.color.secondary))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0)
            setOnClickListener { finish() }
        })

        setContentView(root)
    }

    private fun doLogin() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) { showError("Введите логин и пароль"); return }
        btnLogin.isEnabled = false; btnLogin.text = "Вход…"
        tvError.visibility = View.GONE

        val api = CoreApiClient.build(this)
        lifecycleScope.launch {
            try {
                val resp = api.login(username, password)
                CoreAuth.save(
                    this@PickerLoginActivity, resp.access_token,
                    username, password, null, "operator", null
                )
                CoreApiClient.reset()
                val me = CoreApiClient.build(this@PickerLoginActivity).me()
                CoreAuth.save(
                    this@PickerLoginActivity, resp.access_token,
                    username, password, me.full_name, me.role, me.city
                )
                val stores = try {
                    CoreApiClient.build(this@PickerLoginActivity).stores()
                } catch (_: Exception) { emptyList() }
                afterLogin(stores, me.city)
            } catch (e: retrofit2.HttpException) {
                val msg = try {
                    val body = e.response()?.errorBody()?.string() ?: ""
                    org.json.JSONObject(body).optString("detail", "Ошибка входа")
                } catch (_: Exception) { "Ошибка входа" }
                showError(msg)
                btnLogin.isEnabled = true; btnLogin.text = "Войти"
            } catch (e: Exception) {
                showError(e.message ?: "Нет соединения")
                btnLogin.isEnabled = true; btnLogin.text = "Войти"
            }
        }
    }

    /** Магазин: один — берём сразу, несколько — спрашиваем. Потом город (если не заперт). */
    private fun afterLogin(stores: List<CoreStore>, userCity: String?) {
        when {
            stores.isEmpty() -> chooseCityIfNeeded(userCity)
            stores.size == 1 -> {
                CoreAuth.setStore(this, stores[0].id, stores[0].name ?: stores[0].code)
                chooseCityIfNeeded(userCity)
            }
            else -> {
                val names = stores.map { it.name ?: it.code ?: "Магазин ${it.id}" }.toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle("Магазин")
                    .setCancelable(false)
                    .setItems(names) { _, i ->
                        CoreAuth.setStore(this, stores[i].id, names[i])
                        chooseCityIfNeeded(userCity)
                    }
                    .show()
            }
        }
    }

    private fun chooseCityIfNeeded(userCity: String?) {
        if (!userCity.isNullOrBlank() || CoreAuth.cityLocked(this)) {
            if (CoreAuth.city(this).isNullOrBlank() && !userCity.isNullOrBlank()) {
                CoreAuth.setCity(this, userCity)
            }
            startTasks(); return
        }
        val keys = CoreAuth.CITY_NAMES.keys.toList()
        val names = CoreAuth.CITY_NAMES.values.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Город сборки")
            .setCancelable(false)
            .setItems(names) { _, i ->
                CoreAuth.setCity(this, keys[i])
                startTasks()
            }
            .show()
    }

    private fun showError(msg: String) {
        tvError.text = msg; tvError.visibility = View.VISIBLE
    }

    private fun startTasks() {
        startActivity(Intent(this, PickerTasksActivity::class.java)); finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text; textSize = 10f; letterSpacing = 0.1f
        setTextColor(getColor(R.color.secondary))
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    private fun editField(hint: String, password: Boolean) = EditText(this).apply {
        this.hint = hint; textSize = 16f
        setTextColor(getColor(R.color.on_background))
        setHintTextColor(getColor(R.color.secondary))
        setBackgroundResource(R.drawable.input_bg)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = if (password)
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        else
            android.text.InputType.TYPE_CLASS_TEXT
    }

    private fun wrapLp(marginTop: Int, marginBottom: Int) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, marginTop, 0, marginBottom) }
}

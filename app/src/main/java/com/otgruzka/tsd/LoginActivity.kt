package com.otgruzka.tsd

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.otgruzka.tsd.api.WmsApiClient
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnLogin: Button
    private lateinit var tvError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (WmsAuth.isLoggedIn(this)) {
            startMain(); return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F3F4F6"))
            setPadding(dp(32), dp(0), dp(32), dp(0))
        }

        // Logo / Title
        root.addView(TextView(this).apply {
            text = "WMS"
            textSize = 40f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#2563EB"))
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "Система управления складом"
            textSize = 14f
            setTextColor(Color.parseColor("#6B7280"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(32))
        })

        // Card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            background = cardBg()
            elevation = dp(4).toFloat()
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        card.addView(label("Логин"))
        etUsername = EditText(this).apply {
            hint = "Введите логин"
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = inputBg()
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        card.addView(etUsername, lp(margin = dp(0), marginBottom = dp(12)))

        card.addView(label("Пароль"))
        etPassword = EditText(this).apply {
            hint = "Введите пароль"
            textSize = 16f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = inputBg()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        card.addView(etPassword, lp(margin = 0, marginBottom = dp(8)))

        tvError = TextView(this).apply {
            setTextColor(Color.parseColor("#DC2626"))
            textSize = 13f
            visibility = android.view.View.GONE
        }
        card.addView(tvError, lp(margin = 0, marginBottom = dp(16)))

        btnLogin = Button(this).apply {
            text = "Войти"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = btnBg(Color.parseColor("#2563EB"))
            isAllCaps = false
            setPadding(dp(0), dp(14), dp(0), dp(14))
            setOnClickListener { doLogin() }
        }
        card.addView(btnLogin, lp(margin = 0))

        root.addView(card)
        setContentView(root)
    }

    private fun doLogin() {
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            showError("Введите логин и пароль")
            return
        }
        btnLogin.isEnabled = false
        btnLogin.text = "Вход..."
        tvError.visibility = android.view.View.GONE

        val api = WmsApiClient.build(this)
        lifecycleScope.launch {
            try {
                val resp = api.login(username, password)
                WmsAuth.save(this@LoginActivity, resp.access_token, resp.user)
                WmsApiClient.reset()
                startMain()
            } catch (e: Exception) {
                showError("Неверный логин или пароль")
                btnLogin.isEnabled = true
                btnLogin.text = "Войти"
            }
        }
    }

    private fun showError(msg: String) {
        tvError.text = msg
        tvError.visibility = android.view.View.VISIBLE
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.parseColor("#374151"))
        setTypeface(null, Typeface.BOLD)
        setPadding(0, 0, 0, dp(4))
    }

    private fun lp(margin: Int = 0, marginBottom: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(margin, margin, margin, marginBottom)
        }

    private fun cardBg(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(12).toFloat()
        }

    private fun inputBg(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.parseColor("#F9FAFB"))
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Color.parseColor("#D1D5DB"))
        }

    private fun btnBg(color: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(8).toFloat()
        }
}

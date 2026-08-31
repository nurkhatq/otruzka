package com.otgruzka.tsd

import android.content.Context

/**
 * Токен и профиль сборщика в ядре novamanya (qoimams.asia).
 * Хранится отдельно от wms_auth — логины отгрузки и сборки независимы.
 */
object CoreAuth {

    private const val PREFS = "core_auth"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(
        context: Context, token: String, username: String, password: String,
        fullName: String?, role: String, city: String?
    ) {
        prefs(context).edit()
            .putString("token", token)
            .putString("username", username)
            .putString("password", password)
            .putString("full_name", fullName)
            .putString("role", role)
            .putString("city", city)
            .apply()
    }

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString("token", token).apply()
    }

    fun setStore(context: Context, storeId: Int, storeName: String?) {
        prefs(context).edit()
            .putInt("store_id", storeId)
            .putString("store_name", storeName)
            .apply()
    }

    fun setCity(context: Context, city: String) {
        prefs(context).edit().putString("city", city).apply()
    }

    fun token(context: Context): String? = prefs(context).getString("token", null)
    fun username(context: Context): String? = prefs(context).getString("username", null)
    fun password(context: Context): String? = prefs(context).getString("password", null)
    fun fullName(context: Context): String? = prefs(context).getString("full_name", null)
    fun role(context: Context): String = prefs(context).getString("role", "operator") ?: "operator"
    fun city(context: Context): String? = prefs(context).getString("city", null)
    fun storeId(context: Context): Int = prefs(context).getInt("store_id", 0)
    fun storeName(context: Context): String? = prefs(context).getString("store_name", null)

    fun isLoggedIn(context: Context) = token(context) != null

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** Оператор заперт в городе своего склада; admin/manager выбирают город. */
    fun cityLocked(context: Context) = role(context) !in listOf("admin", "manager")

    val CITY_NAMES = linkedMapOf(
        "almaty" to "Алматы",
        "astana" to "Астана",
        "shymkent" to "Шымкент",
    )

    fun cityLabel(key: String?): String = CITY_NAMES[key] ?: (key ?: "—")
}

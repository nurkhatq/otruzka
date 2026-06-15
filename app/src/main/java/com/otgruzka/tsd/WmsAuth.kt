package com.otgruzka.tsd

import android.content.Context
import com.otgruzka.tsd.api.WmsUser

object WmsAuth {

    private const val PREFS = "wms_auth"

    fun save(context: Context, token: String, user: WmsUser) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", token)
            .putString("username", user.username)
            .putString("full_name", user.full_name)
            .putInt("warehouse_id", user.warehouse_id)
            .putString("role", user.role)
            .apply()
    }

    fun getToken(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null)

    fun getUser(context: Context): WmsUser? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = p.getString("token", null) ?: return null
        return WmsUser(
            id = 0,
            username = p.getString("username", "") ?: "",
            full_name = p.getString("full_name", "") ?: "",
            warehouse_id = p.getInt("warehouse_id", 0),
            role = p.getString("role", "worker") ?: "worker"
        )
    }

    fun isLoggedIn(context: Context) = getToken(context) != null

    fun logout(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    val WAREHOUSE_NAMES = mapOf(1 to "PP1 Шымкент", 2 to "PP2 Алматы", 5 to "PP5 Астана")
}

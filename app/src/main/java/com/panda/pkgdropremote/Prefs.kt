package com.panda.pkgdropremote

import android.content.Context
import android.content.SharedPreferences

/**
 * Ajustes persistentes: PS4 elegida (IP descubierta o manual),
 * puerto RPI y modo de entrega por defecto.
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("pkgdropremote", Context.MODE_PRIVATE)

    var ps4Ip: String
        get() = sp.getString(KEY_IP, "") ?: ""
        set(v) = sp.edit().putString(KEY_IP, v.trim()).apply()

    var ps4Name: String
        get() = sp.getString(KEY_NAME, "") ?: ""
        set(v) = sp.edit().putString(KEY_NAME, v).apply()

    var rpiPort: Int
        get() = sp.getInt(KEY_PORT, RpiClient.DEFAULT_PORT)
        set(v) = sp.edit().putInt(KEY_PORT, v.coerceIn(1, 65535)).apply()

    /** true = delegar a PkgDrop (URLs); false no aplica a URLs (siempre delegan). */
    var defaultServeLocal: Boolean
        get() = sp.getBoolean(KEY_SERVE, false)
        set(v) = sp.edit().putBoolean(KEY_SERVE, v).apply()

    fun hasPs4(): Boolean = ps4Ip.isNotBlank()

    fun forgetPs4() {
        sp.edit().remove(KEY_IP).remove(KEY_NAME).apply()
    }

    companion object {
        private const val KEY_IP = "ps4_ip"
        private const val KEY_NAME = "ps4_name"
        private const val KEY_PORT = "rpi_port"
        private const val KEY_SERVE = "default_serve_local"
    }
}

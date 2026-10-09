package com.tanuj.applock

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

/** Stores locked apps and a salted SHA-256 hash of the PIN (never the PIN itself). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("applock", Context.MODE_PRIVATE)

    var lockedApps: Set<String>
        get() = sp.getStringSet("locked", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("locked", v).apply()

    val hasPin: Boolean get() = sp.contains("pinHash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pinHash", hash(pin, salt))
            .apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = Base64.decode(sp.getString("salt", "") ?: "", Base64.NO_WRAP)
        val expected = sp.getString("pinHash", null) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), hash(pin, salt).toByteArray())
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return Base64.encodeToString(md.digest(pin.toByteArray()), Base64.NO_WRAP)
    }
}

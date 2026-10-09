package com.tanuj.applock

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Stores settings and a salted SHA-256 hash of the PIN (never the PIN itself). */
object Method {
    const val BOTH = 0
    const val PIN = 1
    const val FINGER = 2
    val labels = arrayOf("PIN + Fingerprint", "PIN only", "Fingerprint only")
}

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("applock", Context.MODE_PRIVATE)

    var lockedApps: Set<String>
        get() = sp.getStringSet("locked", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("locked", v).apply()

    /** How locked apps are opened: Method.BOTH / PIN / FINGER */
    var unlockMethod: Int
        get() = sp.getInt("method", if (sp.getBoolean("useBio", true)) Method.BOTH else Method.PIN)
        set(v) = sp.edit().putInt("method", v).apply()

    /** How long an app stays unlocked after you leave it (ms). 0 = lock immediately. */
    var relockDelay: Long
        get() = sp.getLong("relock", 0)
        set(v) = sp.edit().putLong("relock", v).apply()

    /** Wrong-attempt protection: 5 wrong PINs → 30 s cooldown. */
    var failCount: Int
        get() = sp.getInt("fails", 0)
        set(v) = sp.edit().putInt("fails", v).apply()
    var lockUntil: Long
        get() = sp.getLong("lockUntil", 0)
        set(v) = sp.edit().putLong("lockUntil", v).apply()

    /** 0 = unknown (PIN created by v1 of the app). */
    val pinLength: Int get() = sp.getInt("pinLen", 0)
    val hasPin: Boolean get() = sp.contains("pinHash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pinHash", hash(pin, salt))
            .putInt("pinLen", pin.length)
            .putInt("fails", 0)
            .putLong("lockUntil", 0)
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

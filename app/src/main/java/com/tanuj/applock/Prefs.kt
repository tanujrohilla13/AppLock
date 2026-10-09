package com.tanuj.applock

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

object Method {
    const val BOTH = 0
    const val PIN = 1
    const val FINGER = 2
    val labels = arrayOf("Passcode + Fingerprint", "Passcode only", "Fingerprint only")
}

/** Feature flags – each toggled from the Features screen. */
object F {
    const val LOG = "f_log"
    const val SHUFFLE = "f_shuffle"
    const val PATTERN = "f_pattern"
    const val NEW_APP = "f_newapp"
    const val FAKE_CRASH = "f_fakecrash"
    const val TILE = "f_tile"
    const val SCHEDULE = "f_schedule"
    const val PROFILES = "f_profiles"
    const val STATS = "f_stats"
    const val TRUSTED_WIFI = "f_wifi"
}

/** Stores settings and a salted SHA-256 hash of the secret (never the secret itself). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("applock", Context.MODE_PRIVATE)

    // ----- which apps are protected -----
    var lockedApps: Set<String>
        get() = sp.getStringSet("locked", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("locked", v).apply()

    var unlockMethod: Int
        get() = sp.getInt("method", if (sp.getBoolean("useBio", true)) Method.BOTH else Method.PIN)
        set(v) = sp.edit().putInt("method", v).apply()

    /** How long an app stays unlocked after you leave it (ms). 0 = immediately. */
    var relockDelay: Long
        get() = sp.getLong("relock", 0)
        set(v) = sp.edit().putLong("relock", v).apply()

    /** Master switch to pause/resume all locking. */
    var protectionPaused: Boolean
        get() = sp.getBoolean("paused", false)
        set(v) = sp.edit().putBoolean("paused", v).apply()

    // ----- generic feature on/off -----
    fun isOn(key: String, default: Boolean = false) = sp.getBoolean(key, default)
    fun setOn(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()

    // ----- wrong-attempt protection -----
    var failCount: Int
        get() = sp.getInt("fails", 0)
        set(v) = sp.edit().putInt("fails", v).apply()
    var lockUntil: Long
        get() = sp.getLong("lockUntil", 0)
        set(v) = sp.edit().putLong("lockUntil", v).apply()

    // ----- secret (PIN or pattern) -----
    val secretLength: Int get() = sp.getInt("pinLen", 0)
    val hasPin: Boolean get() = sp.contains("pinHash")
    /** true = the saved secret is a pattern, false = numeric PIN. */
    var isPattern: Boolean
        get() = sp.getBoolean("isPattern", false)
        set(v) = sp.edit().putBoolean("isPattern", v).apply()

    fun setSecret(secret: String, pattern: Boolean) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pinHash", hash(secret, salt))
            .putInt("pinLen", secret.length)
            .putBoolean("isPattern", pattern)
            .putInt("fails", 0)
            .putLong("lockUntil", 0)
            .apply()
    }

    fun checkSecret(secret: String): Boolean {
        val salt = Base64.decode(sp.getString("salt", "") ?: "", Base64.NO_WRAP)
        val expected = sp.getString("pinHash", null) ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), hash(secret, salt).toByteArray())
    }

    private fun hash(secret: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return Base64.encodeToString(md.digest(secret.toByteArray()), Base64.NO_WRAP)
    }

    // ----- schedule: lock only between start/end minutes-of-day; -1 = always -----
    var scheduleStart: Int get() = sp.getInt("schStart", -1); set(v) = sp.edit().putInt("schStart", v).apply()
    var scheduleEnd: Int get() = sp.getInt("schEnd", -1); set(v) = sp.edit().putInt("schEnd", v).apply()

    // ----- trusted wifi SSID: locking is skipped when connected to it -----
    var trustedWifi: String
        get() = sp.getString("wifi", "") ?: ""
        set(v) = sp.edit().putString("wifi", v).apply()

    // ----- unlock log (newest first, capped) -----
    fun addLog(entry: String) {
        if (!isOn(F.LOG, true)) return
        val cur = sp.getString("log", "") ?: ""
        val line = "${System.currentTimeMillis()}|$entry"
        val all = (listOf(line) + cur.split("\n").filter { it.isNotBlank() }).take(100)
        sp.edit().putString("log", all.joinToString("\n")).apply()
    }
    fun readLog(): List<Pair<Long, String>> =
        (sp.getString("log", "") ?: "").split("\n").filter { it.isNotBlank() }.mapNotNull {
            val i = it.indexOf('|'); if (i < 0) null else it.substring(0, i).toLongOrNull()?.let { t -> t to it.substring(i + 1) }
        }
    fun clearLog() = sp.edit().remove("log").apply()

    // ----- lock profiles: name -> set of packages -----
    fun profiles(): Map<String, Set<String>> {
        val raw = sp.getString("profiles", "") ?: ""
        return raw.split("\n").filter { it.isNotBlank() }.mapNotNull {
            val i = it.indexOf('='); if (i < 0) null else it.substring(0, i) to
                it.substring(i + 1).split(",").filter { p -> p.isNotBlank() }.toSet()
        }.toMap()
    }
    fun saveProfile(name: String, pkgs: Set<String>) {
        val m = profiles().toMutableMap(); m[name] = pkgs
        sp.edit().putString("profiles", m.entries.joinToString("\n") { "${it.key}=${it.value.joinToString(",")}" }).apply()
    }
    fun deleteProfile(name: String) {
        val m = profiles().toMutableMap(); m.remove(name)
        sp.edit().putString("profiles", m.entries.joinToString("\n") { "${it.key}=${it.value.joinToString(",")}" }).apply()
    }
}

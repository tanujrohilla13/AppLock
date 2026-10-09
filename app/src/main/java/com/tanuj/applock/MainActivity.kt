package com.tanuj.applock

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private var onMain = false
    private var openingExternal = false
    private var screen = ""   // "" = none, else name to restore with back

    private data class AppItem(val label: String, val pkg: String, val icon: Drawable)
    private var allApps: List<AppItem> = emptyList()
    private var shown: List<AppItem> = emptyList()

    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var statusDot: View
    private lateinit var countText: TextView
    private lateinit var adapter: BaseAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!prefs.hasPin) showCreate() else showAuth()
    }

    override fun onRestart() {
        super.onRestart()
        if (!openingExternal && prefs.hasPin) showAuth()
        openingExternal = false
    }

    override fun onResume() { super.onResume(); if (onMain) refreshStatus() }

    // ---------- auth ----------
    private fun showCreate() {
        onMain = false; screen = ""
        setContentView(AuthView(this, prefs, AuthView.Mode.CREATE,
            "Welcome to App Lock", "Create a 4-digit PIN", null, onSuccess = { showMain() }))
    }

    private fun showAuth() {
        onMain = false; screen = ""
        showAuthScreen(prefs, "App Lock", "Enter your code", null) { showMain() }
    }

    private fun showChangeCode(pattern: Boolean) {
        onMain = false; screen = "change"
        setContentView(AuthView(this, prefs, AuthView.Mode.CREATE,
            if (pattern) "Set a pattern" else "Change PIN",
            if (pattern) "Draw a new pattern" else "Create a 4-digit PIN", null,
            onSuccess = { toast("Code updated"); showMain() }, createPattern = pattern))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when (screen) {
            "features", "settings", "change" -> showMain()
            "log", "intruders", "schedule", "wifi", "profiles", "stats" -> showFeatures()
            else -> super.onBackPressed()
        }
    }

    // ---------- main ----------
    private fun showMain() {
        onMain = true; screen = ""
        val root = col().apply { setPadding(dp(20), dp(28), dp(20), 0) }
        root.addView(TextView(this).apply {
            text = "App Lock"; textSize = 30f; setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        countText = TextView(this).apply { textSize = 14f; setTextColor(C.SUB); setPadding(0, dp(2), 0, dp(18)) }
        root.addView(countText)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = ripple(rounded(C.SURFACE, 20)); setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener { openingExternal = true; startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        statusDot = View(this)
        card.addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(14) })
        val t = col()
        statusTitle = TextView(this).apply { textSize = 16f; setTextColor(C.TEXT); typeface = Typeface.DEFAULT_BOLD }
        statusSub = TextView(this).apply { textSize = 13f; setTextColor(C.SUB) }
        t.addView(statusTitle); t.addView(statusSub)
        card.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(TextView(this).apply { text = "›"; textSize = 26f; setTextColor(C.SUB) })
        root.addView(card)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill("⚙️  Settings") { showSettings() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(pill("✨  Features") { showFeatures() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        val search = EditText(this).apply {
            hint = "🔍  Search apps"; setHintTextColor(C.SUB); setTextColor(C.TEXT); textSize = 15f
            background = rounded(C.SURFACE, 14); setPadding(dp(16), 0, dp(16), 0); isSingleLine = true
            addTextChangedListener(afterChanged { filter(it) })
        }
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(20); bottomMargin = dp(8) })

        val list = ListView(this).apply { divider = null; isVerticalScrollBarEnabled = false }
        adapter = AppAdapter(); list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> toggleApp(shown[pos].pkg) }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
        refreshStatus()
        if (allApps.isEmpty()) loadApps() else filter("")
    }

    private fun refreshStatus() {
        val on = serviceEnabled() && !prefs.protectionPaused
        statusDot.background = oval(if (on) C.OK else C.DANGER)
        statusTitle.text = when {
            !serviceEnabled() -> "Protection is OFF"
            prefs.protectionPaused -> "Paused"
            else -> "Protection is ON"
        }
        statusSub.text = if (!serviceEnabled()) "Tap to enable in Accessibility"
            else if (prefs.protectionPaused) "Resume it in Features" else "Locked apps need your code"
        val n = prefs.lockedApps.size
        countText.text = if (n == 0) "No apps locked yet" else "$n app${if (n > 1) "s" else ""} locked"
    }

    // ---------- settings ----------
    private val relockVals = longArrayOf(0, 30_000, 60_000, 300_000, 900_000)
    private val relockLabels = arrayOf("Immediately", "After 30 seconds", "After 1 minute", "After 5 minutes", "After 15 minutes")

    private fun showSettings() {
        onMain = false; screen = "settings"
        val root = col().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        root.addView(backHeader("Settings") { showMain() })
        val card = card()
        val bio = Bio.available(this)
        card.addView(row("Unlock method", if (bio) Method.labels[prefs.unlockMethod] else "Passcode only (no fingerprint)") {
            if (!bio) toast("Add a fingerprint in phone Settings first")
            else AlertDialog.Builder(this).setTitle("Unlock method")
                .setSingleChoiceItems(Method.labels, prefs.unlockMethod) { d, w -> prefs.unlockMethod = w; d.dismiss(); showSettings() }.show()
        })
        card.addView(divider())
        card.addView(row("Re-lock apps", relockLabels[relockVals.indexOf(prefs.relockDelay).coerceAtLeast(0)]) {
            AlertDialog.Builder(this).setTitle("Re-lock after leaving an app")
                .setSingleChoiceItems(relockLabels, relockVals.indexOf(prefs.relockDelay).coerceAtLeast(0)) { d, w -> prefs.relockDelay = relockVals[w]; d.dismiss(); showSettings() }.show()
        })
        card.addView(divider())
        val sLocked = prefs.lockedApps.contains("com.android.settings")
        card.addView(row("Protect phone Settings", if (sLocked) "On" else "Off") {
            prefs.lockedApps = if (sLocked) prefs.lockedApps - "com.android.settings" else prefs.lockedApps + "com.android.settings"
            showSettings()
        })
        card.addView(divider())
        card.addView(row("Change PIN", "4-digit passcode") { showChangeCode(false) })
        card.addView(divider())
        card.addView(row("Set / change pattern", if (prefs.isPattern) "Pattern is active" else "Use a draw pattern") { showChangeCode(true) })
        root.addView(card)
        setContentView(root)
    }

    // ---------- features (toggles) ----------
    private fun showFeatures() {
        onMain = false; screen = "features"
        val outer = ScrollView(this).apply { setBackgroundColor(C.BG) }
        val root = col().apply { setPadding(dp(20), dp(20), dp(20), dp(28)) }
        root.addView(backHeader("Features") { showMain() })

        root.addView(sectionLabel("Protection"))
        val c1 = card()
        c1.addView(switchRow("Pause all locking", "Master off switch (also in Quick Settings)", prefs.protectionPaused) {
            prefs.protectionPaused = it; if (it) LockService.lockAll()
        })
        c1.addView(divider())
        c1.addView(toggle("Intruder selfie", "Front-camera photo after 3 wrong tries", "f_intruder") { on ->
            if (on && checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 7)
        })
        c1.addView(divider())
        c1.addView(row("Captured photos", "View intruder snaps") { showIntruders() })
        c1.addView(divider())
        c1.addView(row("Uninstall protection", if (isAdmin()) "On – App Lock can't be removed" else "Off") { toggleAdmin() })
        root.addView(c1)

        root.addView(sectionLabel("Unlock screen"))
        val c2 = card()
        c2.addView(toggle("Scramble keypad", "Shuffle number positions each time", F.SHUFFLE))
        c2.addView(divider())
        c2.addView(toggle("Fake crash screen", "Locked apps show a fake error; long-press to open", F.FAKE_CRASH))
        root.addView(c2)

        root.addView(sectionLabel("Automation"))
        val c3 = card()
        c3.addView(row("Lock schedule", scheduleText()) { showSchedule() })
        c3.addView(divider())
        c3.addView(row("Trusted Wi-Fi", if (prefs.trustedWifi.isEmpty()) "Off" else "Unlocked on ${prefs.trustedWifi}") { showWifi() })
        c3.addView(divider())
        c3.addView(row("Lock profiles", "${prefs.profiles().size} saved") { showProfiles() })
        c3.addView(divider())
        c3.addView(toggle("Ask to lock new apps", "Notify when you install something", F.NEW_APP))
        root.addView(c3)

        root.addView(sectionLabel("Activity"))
        val c4 = card()
        c4.addView(toggle("Keep unlock log", "Record when apps are opened", F.LOG, default = true))
        c4.addView(divider())
        c4.addView(row("View unlock log", "Recent unlocks & wrong attempts") { showLog() })
        c4.addView(divider())
        c4.addView(row("App usage stats", "How often you open locked apps") { showStats() })
        root.addView(c4)

        outer.addView(root)
        setContentView(outer)
    }

    private fun toggle(title: String, sub: String, key: String, default: Boolean = false, onChange: ((Boolean) -> Unit)? = null) =
        switchRow(title, sub, prefs.isOn(key, default)) { prefs.setOn(key, it); onChange?.invoke(it) }

    // ---------- sub-screens ----------
    private fun scheduleText(): String {
        val s = prefs.scheduleStart; val e = prefs.scheduleEnd
        return if (s < 0 || e < 0) "Always on" else "Locked ${hhmm(s)}–${hhmm(e)}"
    }

    private fun showSchedule() {
        onMain = false; screen = "schedule"
        val root = col().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        root.addView(backHeader("Lock schedule") { showFeatures() })
        val card = card()
        card.addView(row("Start locking at", if (prefs.scheduleStart < 0) "—" else hhmm(prefs.scheduleStart)) {
            pickTime(prefs.scheduleStart) { prefs.scheduleStart = it; showSchedule() }
        })
        card.addView(divider())
        card.addView(row("Stop locking at", if (prefs.scheduleEnd < 0) "—" else hhmm(prefs.scheduleEnd)) {
            pickTime(prefs.scheduleEnd) { prefs.scheduleEnd = it; showSchedule() }
        })
        card.addView(divider())
        card.addView(row("Clear schedule", "Lock apps all day") { prefs.scheduleStart = -1; prefs.scheduleEnd = -1; showSchedule() })
        root.addView(card)
        root.addView(TextView(this).apply {
            text = "Outside these hours your apps open without a code."; textSize = 13f; setTextColor(C.SUB); setPadding(dp(6), dp(16), dp(6), 0)
        })
        setContentView(root)
    }

    private fun pickTime(cur: Int, onSet: (Int) -> Unit) {
        val h = if (cur < 0) 9 else cur / 60; val m = if (cur < 0) 0 else cur % 60
        TimePickerDialog(this, { _, hh, mm -> onSet(hh * 60 + mm) }, h, m, DateFormat.is24HourFormat(this)).show()
    }

    private fun showWifi() {
        onMain = false; screen = "wifi"
        val root = col().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        root.addView(backHeader("Trusted Wi-Fi") { showFeatures() })
        val input = EditText(this).apply {
            setText(prefs.trustedWifi); hint = "Wi-Fi name (SSID)"; setHintTextColor(C.SUB); setTextColor(C.TEXT)
            background = rounded(C.SURFACE, 14); setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(input, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(pill("Save") {
            prefs.trustedWifi = input.text.toString().trim(); toast("Saved"); showFeatures()
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(16) })
        root.addView(TextView(this).apply {
            text = "Apps stay unlocked while you're on this network. Leave empty to turn off.\n\nTip: Android needs Location permission to read the Wi-Fi name."
            textSize = 13f; setTextColor(C.SUB); setPadding(dp(6), dp(16), dp(6), 0)
        })
        setContentView(root)
    }

    private fun showProfiles() {
        onMain = false; screen = "profiles"
        val root = col().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        root.addView(backHeader("Lock profiles") { showFeatures() })
        root.addView(pill("＋  Save current apps as a profile") {
            val in2 = EditText(this).apply { hint = "Profile name" }
            AlertDialog.Builder(this).setTitle("New profile").setView(in2)
                .setPositiveButton("Save") { _, _ ->
                    val name = in2.text.toString().trim()
                    if (name.isNotEmpty()) { prefs.saveProfile(name, prefs.lockedApps); showProfiles() }
                }.setNegativeButton("Cancel", null).show()
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(8); bottomMargin = dp(12) })

        val card = card()
        val profs = prefs.profiles()
        if (profs.isEmpty()) card.addView(TextView(this).apply {
            text = "No profiles yet. Save one to switch which apps are locked in a tap."
            setTextColor(C.SUB); textSize = 14f; setPadding(dp(18), dp(18), dp(18), dp(18))
        })
        profs.entries.forEachIndexed { i, entry ->
            if (i > 0) card.addView(divider())
            val r = row(entry.key, "${entry.value.size} apps · tap to apply") {
                prefs.lockedApps = entry.value; toast("Applied ${entry.key}"); showMain()
            }
            r.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle(entry.key).setMessage("Delete this profile?")
                    .setPositiveButton("Delete") { _, _ -> prefs.deleteProfile(entry.key); showProfiles() }
                    .setNegativeButton("Cancel", null).show(); true
            }
            card.addView(r)
        }
        root.addView(card)
        setContentView(root)
    }

    private fun showLog() {
        onMain = false; screen = "log"
        val root = col().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        root.addView(backHeader("Unlock log") { showFeatures() })
        root.addView(pill("Clear log") { prefs.clearLog(); showLog() },
            LinearLayout.LayoutParams(WRAP_CONTENT, dp(42)).apply { bottomMargin = dp(10) })
        val entries = prefs.readLog()
        val sv = ScrollView(this); val c = col()
        if (entries.isEmpty()) c.addView(TextView(this).apply { text = "Nothing logged yet."; setTextColor(C.SUB); textSize = 14f })
        entries.forEach { e ->
            c.addView(TextView(this).apply {
                text = "${whenStr(e.first)}   ${e.second}"; setTextColor(C.TEXT); textSize = 14f; setPadding(0, dp(8), 0, dp(8))
            })
        }
        sv.addView(c); root.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun showIntruders() {
        onMain = false; screen = "intruders"
        val root = col().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        root.addView(backHeader("Intruder photos") { showFeatures() })
        val files = Intruder.dir(this).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        val sv = ScrollView(this); val c = col()
        if (files.isEmpty()) c.addView(TextView(this).apply { text = "No intruder photos."; setTextColor(C.SUB); textSize = 14f })
        files.forEach { f ->
            c.addView(TextView(this).apply { text = whenStr(f.lastModified()); setTextColor(C.SUB); textSize = 12f; setPadding(0, dp(12), 0, dp(4)) })
            c.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(f.absolutePath)); adjustViewBounds = true
                setOnLongClickListener { f.delete(); showIntruders(); true }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        sv.addView(c); root.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun showStats() {
        onMain = false; screen = "stats"
        val root = col().apply { setPadding(dp(20), dp(20), dp(20), dp(20)) }
        root.addView(backHeader("Usage stats") { showFeatures() })
        if (!hasUsageAccess()) {
            root.addView(TextView(this).apply { text = "App Lock needs Usage Access to show this."; setTextColor(C.SUB); textSize = 14f; setPadding(0, dp(8), 0, dp(16)) })
            root.addView(pill("Grant Usage Access") { openingExternal = true; startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
            setContentView(root); return
        }
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        val stats = usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_WEEKLY, now - 7L * 86400_000, now)
            .filter { it.packageName in prefs.lockedApps && it.totalTimeInForeground > 0 }
            .sortedByDescending { it.totalTimeInForeground }
        val sv = ScrollView(this); val c = col()
        if (stats.isEmpty()) c.addView(TextView(this).apply { text = "No usage recorded this week."; setTextColor(C.SUB); textSize = 14f })
        stats.forEach { u ->
            val name = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(u.packageName, 0)).toString() } catch (e: Exception) { u.packageName }
            val mins = u.totalTimeInForeground / 60000
            c.addView(TextView(this).apply {
                text = "$name — ${if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"} this week"
                setTextColor(C.TEXT); textSize = 15f; setPadding(0, dp(10), 0, dp(10))
            })
        }
        sv.addView(c); root.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    // ---------- device admin (uninstall protection) ----------
    private fun adminComp() = ComponentName(this, AdminReceiver::class.java)
    private fun isAdmin() = (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(adminComp())
    private fun toggleAdmin() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        openingExternal = true
        if (isAdmin()) { dpm.removeActiveAdmin(adminComp()); toast("Uninstall protection off"); openingExternal = false; showFeatures() }
        else startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp())
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Stops App Lock being uninstalled until you turn this off.")
        })
    }

    private fun hasUsageAccess(): Boolean {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        val now = System.currentTimeMillis()
        return usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 86400_000, now).isNotEmpty()
    }

    // ---------- apps list ----------
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.applicationInfo }
                .filter { it.packageName != packageName }.distinctBy { it.packageName }
                .map { AppItem(pm.getApplicationLabel(it).toString(), it.packageName, pm.getApplicationIcon(it)) }
            val locked = prefs.lockedApps
            allApps = apps.sortedWith(compareBy({ it.pkg !in locked }, { it.label.lowercase() }))
            runOnUiThread { if (onMain) filter("") }
        }.start()
    }

    private fun filter(q: String) {
        shown = if (q.isBlank()) allApps else allApps.filter { it.label.contains(q.trim(), true) }
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    private fun toggleApp(pkg: String) {
        prefs.lockedApps = if (pkg in prefs.lockedApps) prefs.lockedApps - pkg else prefs.lockedApps + pkg
        adapter.notifyDataSetChanged(); refreshStatus()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
            val app = shown[p]
            val row = (cv as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(TextView(context).apply { textSize = 16f; setTextColor(C.TEXT); setPadding(dp(16), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(TextView(context).apply { gravity = Gravity.CENTER; textSize = 12f; setPadding(dp(12), dp(6), dp(12), dp(6)) })
            }
            val locked = app.pkg in prefs.lockedApps
            (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
            (row.getChildAt(1) as TextView).text = app.label
            (row.getChildAt(2) as TextView).apply {
                text = if (locked) "🔒 Locked" else "Lock"
                setTextColor(if (locked) C.TEXT else C.SUB)
                background = if (locked) rounded(C.ACCENT, 20) else rounded(C.BG, 20, C.SURFACE2)
            }
            return row
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perms, res)
        if (req == 7 && (res.isEmpty() || res[0] != PackageManager.PERMISSION_GRANTED)) {
            prefs.setOn("f_intruder", false); toast("Camera permission needed"); if (screen == "features") showFeatures()
        }
    }

    // ---------- tiny view helpers ----------
    private fun col() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(C.SURFACE, 20) }
    private fun serviceEnabled(): Boolean {
        val e = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return e.contains("$packageName/")
    }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)
    private fun whenStr(t: Long) = DateFormat.format("dd MMM, h:mm a", t).toString()

    private fun backHeader(title: String, onBack: () -> Unit) = TextView(this).apply {
        text = "‹  $title"; textSize = 26f; setTextColor(C.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, dp(8), 0, dp(18)); setOnClickListener { onBack() }
    }
    private fun sectionLabel(s: String) = TextView(this).apply {
        text = s.uppercase(); textSize = 12f; setTextColor(C.SUB); setPadding(dp(6), dp(20), 0, dp(8))
    }
    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(C.TEXT); gravity = Gravity.CENTER
        background = ripple(rounded(C.SURFACE2, 24)); setOnClickListener { onClick() }
    }
    private fun row(title: String, value: String, onClick: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16)); background = ripple(rounded(0, 20)); setOnClickListener { onClick() }
        val t = col().apply { setBackgroundColor(0) }
        t.addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(C.TEXT) })
        t.addView(TextView(context).apply { text = value; textSize = 13f; setTextColor(C.ACCENT); setPadding(0, dp(2), 0, 0) })
        addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(context).apply { text = "›"; textSize = 24f; setTextColor(C.SUB) })
    }
    private fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        val t = col().apply { setBackgroundColor(0) }
        t.addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(C.TEXT) })
        t.addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(C.SUB); setPadding(0, dp(2), 0, 0) })
        addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(Switch(context).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) } })
    }
    private fun divider() = View(this).apply {
        setBackgroundColor(C.SURFACE2)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { marginStart = dp(18); marginEnd = dp(18) }
    }
    private fun afterChanged(cb: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { cb(s.toString()) }
    }
}

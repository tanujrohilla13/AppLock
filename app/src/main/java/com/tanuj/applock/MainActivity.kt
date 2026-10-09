package com.tanuj.applock

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

/** Home screen – protected by the same PIN so nobody can untick your apps. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private var onMain = false
    private var openingSettings = false

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
        if (!prefs.hasPin) showCreatePin() else showAuth()
    }

    override fun onRestart() {
        super.onRestart()
        // Coming back from background → ask again (but not after visiting Accessibility settings)
        if (!openingSettings && prefs.hasPin) showAuth()
        openingSettings = false
    }

    override fun onResume() {
        super.onResume()
        if (onMain) refreshStatus()
    }

    // ---------- auth screens ----------
    private fun showCreatePin() {
        onMain = false
        setContentView(AuthView(this, prefs, AuthView.Mode.CREATE,
            title = "Welcome to App Lock", subtitle = "Create a 4-digit PIN", icon = null,
            onSuccess = { showMain() }))
    }

    private fun showAuth() {
        onMain = false
        showAuthScreen(prefs, "App Lock", "Enter your PIN", null) { showMain() }
    }

    private fun showChangePin() {
        onMain = false
        setContentView(AuthView(this, prefs, AuthView.Mode.CREATE,
            title = "Change PIN", subtitle = "Create a 4-digit PIN", icon = null,
            onSuccess = {
                Toast.makeText(this, "PIN updated", Toast.LENGTH_SHORT).show()
                showMain()
            }))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!onMain && prefs.hasPin && ::adapter.isInitialized && (isChangingPin || inSettings)) showMain()
        else super.onBackPressed()
    }
    private var isChangingPin = false
    private var inSettings = false

    // ---------- main screen ----------
    private fun showMain() {
        onMain = true
        isChangingPin = false
        inSettings = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            setPadding(dp(20), dp(28), dp(20), 0)
        }

        // Header
        root.addView(TextView(this).apply {
            text = "App Lock"; textSize = 30f; setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        countText = TextView(this).apply { textSize = 14f; setTextColor(C.SUB); setPadding(0, dp(2), 0, dp(18)) }
        root.addView(countText)

        // Status card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ripple(rounded(C.SURFACE, 20))
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setOnClickListener {
                openingSettings = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        statusDot = View(this)
        card.addView(statusDot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTitle = TextView(this).apply { textSize = 16f; setTextColor(C.TEXT); typeface = Typeface.DEFAULT_BOLD }
        statusSub = TextView(this).apply { textSize = 13f; setTextColor(C.SUB) }
        texts.addView(statusTitle); texts.addView(statusSub)
        card.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        card.addView(TextView(this).apply { text = "›"; textSize = 26f; setTextColor(C.SUB) })
        root.addView(card)

        // Quick actions
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val changePin = pill("🔑  Change PIN") { isChangingPin = true; showChangePin() }
        val settingsBtn = pill("⚙️  Settings") { showSettings() }
        actions.addView(changePin, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        actions.addView(settingsBtn, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        root.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(12) })

        // Search
        val search = EditText(this).apply {
            hint = "🔍  Search apps"
            setHintTextColor(C.SUB); setTextColor(C.TEXT); textSize = 15f
            background = rounded(C.SURFACE, 14)
            setPadding(dp(16), 0, dp(16), 0)
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { filter(s.toString()) }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)).apply { topMargin = dp(20); bottomMargin = dp(8) })

        // App list
        val list = ListView(this).apply {
            divider = null
            selector = rounded(0x00000000)
            isVerticalScrollBarEnabled = false
        }
        adapter = AppAdapter()
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> toggle(shown[pos].pkg) }
        root.addView(list, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        setContentView(root)
        refreshStatus()
        if (allApps.isEmpty()) loadApps() else filter("")
    }

    // ---------- settings screen ----------
    private val relockValues = longArrayOf(0, 30_000, 60_000, 5 * 60_000, 15 * 60_000)
    private val relockLabels = arrayOf("Immediately", "After 30 seconds", "After 1 minute", "After 5 minutes", "After 15 minutes")
    private val settingsPkgs = listOf("com.android.settings")

    private fun showSettings() {
        onMain = false
        inSettings = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "‹  Settings"; textSize = 26f; setTextColor(C.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(8), 0, dp(20))
            setOnClickListener { showMain() }
        })

        val bioAvailable = Bio.available(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.SURFACE, 20)
        }

        card.addView(settingRow("Unlock method",
            if (bioAvailable) Method.labels[prefs.unlockMethod] else "PIN only (no fingerprint enrolled)") {
            if (!bioAvailable) {
                Toast.makeText(this, "Add a fingerprint in phone Settings first", Toast.LENGTH_SHORT).show()
            } else android.app.AlertDialog.Builder(this)
                .setTitle("Unlock method")
                .setSingleChoiceItems(Method.labels, prefs.unlockMethod) { d, which ->
                    prefs.unlockMethod = which; d.dismiss(); showSettings()
                }.show()
        })
        card.addView(divider())
        card.addView(settingRow("Re-lock apps",
            relockLabels[relockValues.indexOf(prefs.relockDelay).coerceAtLeast(0)]) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Re-lock after leaving an app")
                .setSingleChoiceItems(relockLabels, relockValues.indexOf(prefs.relockDelay).coerceAtLeast(0)) { d, which ->
                    prefs.relockDelay = relockValues[which]; d.dismiss(); showSettings()
                }.show()
        })
        card.addView(divider())
        val settingsLocked = prefs.lockedApps.containsAll(settingsPkgs)
        card.addView(settingRow("Protect phone Settings",
            if (settingsLocked) "On · stops others turning off App Lock" else "Off") {
            val set = prefs.lockedApps.toMutableSet()
            if (settingsLocked) set.removeAll(settingsPkgs.toSet()) else set.addAll(settingsPkgs)
            prefs.lockedApps = set
            showSettings()
        })
        card.addView(divider())
        card.addView(settingRow("Change PIN", "Used for PIN unlock & as backup") {
            isChangingPin = true; showChangePin()
        })
        root.addView(card)

        root.addView(TextView(this).apply {
            text = "Tip: in “Fingerprint only” mode you can also use your phone's screen lock if your finger isn't read.\n\n" +
                    "Apps always lock again when the screen turns off."
            textSize = 13f; setTextColor(C.SUB)
            setPadding(dp(6), dp(20), dp(6), 0)
        })
        setContentView(root)
    }

    private fun settingRow(title: String, value: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(16))
        background = ripple(rounded(0x00000000, 20))
        setOnClickListener { onClick() }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(context).apply { text = title; textSize = 16f; setTextColor(C.TEXT) })
        texts.addView(TextView(context).apply { text = value; textSize = 13f; setTextColor(C.ACCENT); setPadding(0, dp(2), 0, 0) })
        addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(TextView(context).apply { text = "›"; textSize = 24f; setTextColor(C.SUB) })
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(C.SURFACE2)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply { marginStart = dp(18); marginEnd = dp(18) }
    }

    private fun pill(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(C.TEXT); gravity = Gravity.CENTER
        background = ripple(rounded(C.SURFACE2, 24))
        setOnClickListener { onClick() }
    }

    private fun refreshStatus() {
        val on = isServiceEnabled()
        statusDot.background = oval(if (on) C.OK else C.DANGER)
        statusTitle.text = if (on) "Protection is ON" else "Protection is OFF"
        statusSub.text = if (on) "Locked apps need ${Method.labels[if (Bio.available(this)) prefs.unlockMethod else Method.PIN].lowercase()}" else "Tap to enable App Lock in Accessibility"
        val n = prefs.lockedApps.size
        countText.text = if (n == 0) "No apps locked yet" else "$n app${if (n > 1) "s" else ""} locked"
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.contains("$packageName/")
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.applicationInfo }
                .filter { it.packageName != packageName }
                .distinctBy { it.packageName }
                .map { AppItem(pm.getApplicationLabel(it).toString(), it.packageName, pm.getApplicationIcon(it)) }
            val locked = prefs.lockedApps
            allApps = apps.sortedWith(compareBy({ it.pkg !in locked }, { it.label.lowercase() }))
            runOnUiThread { filter("") }
        }.start()
    }

    private fun filter(q: String) {
        shown = if (q.isBlank()) allApps else allApps.filter { it.label.contains(q.trim(), ignoreCase = true) }
        adapter.notifyDataSetChanged()
    }

    private fun toggle(pkg: String) {
        val set = prefs.lockedApps.toMutableSet()
        if (!set.add(pkg)) set.remove(pkg)
        prefs.lockedApps = set
        adapter.notifyDataSetChanged()
        refreshStatus()
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(p: Int) = shown[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
            val app = shown[p]
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(10), dp(6), dp(10))
                addView(ImageView(context), LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(TextView(context).apply { textSize = 16f; setTextColor(C.TEXT); setPadding(dp(16), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    gravity = Gravity.CENTER; textSize = 12f
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                })
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
}

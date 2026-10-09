package com.tanuj.applock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Settings screen: set PIN, enable the service, choose which apps to lock. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView

    data class AppItem(val label: String, val pkg: String, val icon: android.graphics.drawable.Drawable)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        status = TextView(this).apply { textSize = 15f; setPadding(32, 24, 32, 8) }

        val enableBtn = Button(this).apply {
            text = "1. Turn on App Lock service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val pinBtn = Button(this).apply {
            text = "2. Set / change PIN"
            setOnClickListener { askNewPin() }
        }

        val list = ListView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(enableBtn)
            addView(pinBtn)
            addView(TextView(context).apply {
                text = "3. Tick the apps to lock:"
                textSize = 16f; setPadding(32, 24, 32, 8)
            })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        if (!prefs.hasPin) askNewPin()
        loadApps(list)
    }

    override fun onResume() {
        super.onResume()
        val on = isServiceEnabled()
        status.text = (if (on) "✅ Service is ON" else "⚠️ Service is OFF – tap button 1") +
                (if (prefs.hasPin) "   ✅ PIN set" else "   ⚠️ No PIN")
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains("$packageName/")
    }

    private fun askNewPin() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4–8 digits"; gravity = Gravity.CENTER
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (prefs.hasPin) "New PIN" else "Create a PIN")
            .setView(input)
            .setPositiveButton("Save", null)
            .setCancelable(prefs.hasPin)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = input.text.toString()
                if (pin.length in 4..8) {
                    prefs.setPin(pin); dialog.dismiss(); onResume()
                    Toast.makeText(this, "PIN saved", Toast.LENGTH_SHORT).show()
                } else input.error = "Use 4–8 digits"
            }
        }
        // Changing an existing PIN requires the current one
        if (prefs.hasPin) verifyCurrentPin { dialog.show() } else dialog.show()
    }

    private fun verifyCurrentPin(onOk: () -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }
        AlertDialog.Builder(this).setTitle("Current PIN").setView(input)
            .setPositiveButton("OK") { _, _ ->
                if (prefs.checkPin(input.text.toString())) onOk()
                else Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun loadApps(list: ListView) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
            .map { AppItem(pm.getApplicationLabel(it).toString(), it.packageName, pm.getApplicationIcon(it)) }
            .sortedBy { it.label.lowercase() }

        list.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(p: Int) = apps[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, convertView: View?, parent: ViewGroup?): View {
                val app = apps[p]
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(32, 16, 32, 16)
                    addView(ImageView(context), LinearLayout.LayoutParams(96, 96))
                    addView(TextView(context).apply { textSize = 16f; setPadding(32, 0, 0, 0) },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(CheckBox(context).apply { isClickable = false; isFocusable = false })
                }
                (row.getChildAt(0) as ImageView).setImageDrawable(app.icon)
                (row.getChildAt(1) as TextView).text = app.label
                (row.getChildAt(2) as CheckBox).isChecked = app.pkg in prefs.lockedApps
                return row
            }
        }
        list.setOnItemClickListener { _, view, p, _ ->
            val pkg = apps[p].pkg
            val set = prefs.lockedApps.toMutableSet()
            if (!set.add(pkg)) set.remove(pkg)
            prefs.lockedApps = set
            ((view as LinearLayout).getChildAt(2) as CheckBox).isChecked = pkg in set
        }
    }
}

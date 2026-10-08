package com.hermansantoso.psimonitor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var status: TextView
    private lateinit var locationNote: TextView
    private lateinit var locationButton: Button

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Prefs.nightOverride(base)?.let { applyOverrideConfiguration(Configuration().apply { uiMode = it }) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        locationNote = findViewById(R.id.location_note)
        locationButton = findViewById(R.id.location_button)
        findViewById<Button>(R.id.refresh).setOnClickListener { refresh() }

        val modes = listOf(Prefs.MODE_AUTO) + Region.entries.map { it.key }
        val modeLabels = listOf("Follow my location") + Region.entries.map { it.label }
        setUpSpinner(R.id.mode, modes, modeLabels, Prefs.mode(this)) {
            Prefs.setMode(this, it)
            render()
            PsiWidget.renderAll(this)
        }
        val themes = listOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)
        setUpSpinner(R.id.theme, themes, listOf("Follow system", "Light", "Dark"), Prefs.theme(this)) {
            Prefs.setTheme(this, it)
            PsiWidget.renderAll(this)
            // A fresh instance, not recreate(): a relaunched activity keeps resources resolved for the old theme.
            finish()
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(0, 0)
        }
        render()
    }

    private fun setUpSpinner(
        id: Int, values: List<String>, labels: List<String>, current: String, onChange: (String) -> Unit,
    ) {
        val spinner = findViewById<Spinner>(id)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        var selected = values.indexOf(current).coerceAtLeast(0)
        spinner.setSelection(selected)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, itemId: Long) {
                if (position == selected) return
                selected = position
                onChange(values[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        status.text = "Updating…"
        io.execute {
            val error = try {
                AirRepo.refresh(applicationContext)
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
            runOnUiThread {
                render()
                if (error != null) status.text = "Couldn't update: $error"
            }
        }
    }

    private fun render() {
        renderLocation()
        val snap = Prefs.snapshot(this) ?: return
        val s = shown(this, snap)
        val psiAt = formatTime(snap.psiTime)
        val pm25At = formatTime(snap.pm25Time)
        status.text = if (psiAt == pm25At) "NEA readings as of $psiAt" else "PSI as of $psiAt · PM2.5 as of $pm25At"

        findViewById<TextView>(R.id.here_title).text = s.title
        findViewById<TextView>(R.id.here_note).text = s.note
        bind(R.id.here_psi, R.id.here_psi_band, s.psi, psiBand(s.psi), this::findViewById)
        bind(R.id.here_pm25, R.id.here_pm25_band, s.pm25, pm25Band(s.pm25), this::findViewById)
        findViewById<TextView>(R.id.here_pm25_band).append(" · " + getString(R.string.pm25_unit))

        val rows = findViewById<LinearLayout>(R.id.rows)
        rows.removeAllViews()
        for (region in Region.entries) {
            val row = layoutInflater.inflate(R.layout.row_region, rows, false)
            val name = row.findViewById<TextView>(R.id.row_name)
            name.text = region.label
            if (region == s.region) name.setTypeface(name.typeface, Typeface.BOLD)
            val psi = snap.psi.getValue(region)
            val pm25 = snap.pm25.getValue(region)
            bind(R.id.row_psi, R.id.row_psi_band, psi, psiBand(psi), row::findViewById)
            bind(R.id.row_pm25, R.id.row_pm25_band, pm25, pm25Band(pm25), row::findViewById)
            rows.addView(row)
        }
    }

    private fun bind(valueId: Int, bandId: Int, value: Int, band: Band, find: (Int) -> TextView) {
        find(valueId).apply {
            text = value.toString()
            setTextColor(getColor(band.colorRes))
        }
        find(bandId).text = band.label
    }

    /** Explains what location access the app has and offers the next permission step, if any. */
    private fun renderLocation() {
        val auto = Prefs.mode(this) == Prefs.MODE_AUTO
        when {
            !auto -> {
                locationNote.text = "The widget always shows the region chosen above, so location is not used. " +
                    "Choose \"Follow my location\" to use it again."
                locationButton.visibility = View.GONE
            }
            !Locator.granted(this) -> {
                locationNote.text = "Allow location so the widget can show the region you are in. " +
                    "Only an approximate location is used, and it never leaves your phone."
                locationButton.text = "Allow location"
                locationButton.visibility = View.VISIBLE
                locationButton.setOnClickListener { ask(Manifest.permission.ACCESS_COARSE_LOCATION) }
            }
            !Locator.backgroundGranted(this) -> {
                locationNote.text = "The widget's region updates only when you open this app. " +
                    "Choose \"Allow all the time\" to let it follow you in the background."
                locationButton.text = "Allow all the time"
                locationButton.visibility = View.VISIBLE
                locationButton.setOnClickListener { ask(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
            }
            else -> {
                locationNote.text = "Location is allowed all the time, so the widget follows you in the background."
                locationButton.text = "Change in settings"
                locationButton.visibility = View.VISIBLE
                locationButton.setOnClickListener { openAppSettings() }
            }
        }
    }

    private fun ask(permission: String) = requestPermissions(arrayOf(permission), 0)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        val permission = permissions.firstOrNull() ?: return
        // Android stops showing the dialog after repeated denials; send the user to settings instead.
        if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !shouldShowRequestPermissionRationale(permission)
        ) {
            openAppSettings()
        }
        // onResume runs next and refreshes with the new permission state.
    }

    private fun openAppSettings() = startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    )
}

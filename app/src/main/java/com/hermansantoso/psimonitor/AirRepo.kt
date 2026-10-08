package com.hermansantoso.psimonitor

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Fetches NEA readings from data.gov.sg. All functions block, so call them off the main thread. */
object AirRepo {
    private const val PSI_URL = "https://api-open.data.gov.sg/v2/real-time/api/psi"
    private const val PM25_URL = "https://api-open.data.gov.sg/v2/real-time/api/pm25"

    /** Re-locates the device, fetches the latest readings, caches them and redraws the widgets. */
    fun refresh(ctx: Context): Snapshot {
        try {
            Locator.updateAutoRegion(ctx)
            val (psi, psiTime) = latest(PSI_URL, "psi_twenty_four_hourly")
            val (pm25, pm25Time) = latest(PM25_URL, "pm25_one_hourly")
            return Snapshot(psi, pm25, psiTime, pm25Time).also { Prefs.setSnapshot(ctx, it) }
        } finally {
            PsiWidget.renderAll(ctx)
        }
    }

    private fun latest(url: String, reading: String): Pair<Map<Region, Int>, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode != 200) throw IOException("data.gov.sg returned HTTP ${conn.responseCode}")
            val body = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (body.getInt("code") != 0) throw IOException(body.optString("errorMsg", "data.gov.sg error"))
            val item = body.getJSONObject("data").getJSONArray("items").getJSONObject(0)
            return Snapshot.readings(item.getJSONObject("readings").getJSONObject(reading)) to
                item.getString("timestamp")
        } finally {
            conn.disconnect()
        }
    }
}

object Locator {
    private const val MAX_AGE_MS = 2 * 60 * 60 * 1000L

    fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun backgroundGranted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Stores the region for the current position. Keeps the previous one when no fix is available. */
    fun updateAutoRegion(ctx: Context) {
        val loc = current(ctx) ?: return
        Prefs.setAutoRegion(ctx, Region.nearest(loc.latitude, loc.longitude)?.key ?: Prefs.OUTSIDE)
    }

    @SuppressLint("MissingPermission")
    private fun current(ctx: Context): Location? {
        if (!granted(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java)
        val providers = lm.getProviders(true)
        var fix: Location? = null
        val provider = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in providers }
        if (provider != null) {
            val latch = CountDownLatch(1)
            val cancel = CancellationSignal()
            try {
                lm.getCurrentLocation(provider, cancel, ctx.mainExecutor) {
                    fix = it
                    latch.countDown()
                }
                if (!latch.await(10, TimeUnit.SECONDS)) cancel.cancel()
            } catch (_: SecurityException) {
            }
        }
        return fix ?: providers
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: SecurityException) { null } }
            .filter { System.currentTimeMillis() - it.time < MAX_AGE_MS }
            .maxByOrNull { it.time }
    }
}

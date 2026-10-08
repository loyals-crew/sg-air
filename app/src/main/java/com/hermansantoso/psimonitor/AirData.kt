package com.hermansantoso.psimonitor

import android.content.Context
import android.content.res.Configuration
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.hypot

/** The five regions NEA reports for. Coordinates are the label points from the API's regionMetadata. */
enum class Region(val key: String, val label: String, val lat: Double, val lon: Double) {
    NORTH("north", "North", 1.41803, 103.82),
    SOUTH("south", "South", 1.29587, 103.82),
    EAST("east", "East", 1.35735, 103.94),
    WEST("west", "West", 1.35735, 103.70),
    CENTRAL("central", "Central", 1.35735, 103.82);

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }

        /** Nearest region to a point, or null when the point is outside Singapore (rough bounding box). */
        fun nearest(lat: Double, lon: Double): Region? {
            if (lat !in 1.15..1.475 || lon !in 103.59..104.10) return null
            return entries.minBy { hypot(it.lat - lat, it.lon - lon) }
        }
    }
}

class Band(val label: String, val colorRes: Int)

/** NEA descriptors for 24-hr PSI. */
fun psiBand(v: Int) = when {
    v <= 50 -> Band("Good", R.color.band_good)
    v <= 100 -> Band("Moderate", R.color.band_moderate)
    v <= 200 -> Band("Unhealthy", R.color.band_unhealthy)
    v <= 300 -> Band("Very unhealthy", R.color.band_very_unhealthy)
    else -> Band("Hazardous", R.color.band_hazardous)
}

/** NEA bands for 1-hr PM2.5 in µg/m³. */
fun pm25Band(v: Int) = when {
    v <= 55 -> Band("Normal", R.color.band_good)
    v <= 150 -> Band("Elevated", R.color.band_unhealthy)
    v <= 250 -> Band("High", R.color.band_very_unhealthy)
    else -> Band("Very high", R.color.band_hazardous)
}

class Snapshot(
    val psi: Map<Region, Int>,
    val pm25: Map<Region, Int>,
    val psiTime: String,
    val pm25Time: String,
) {
    fun toJson(): String = JSONObject()
        .put("psi", JSONObject(psi.mapKeys { it.key.key }))
        .put("pm25", JSONObject(pm25.mapKeys { it.key.key }))
        .put("psiTime", psiTime)
        .put("pm25Time", pm25Time)
        .toString()

    companion object {
        fun fromJson(json: String): Snapshot {
            val o = JSONObject(json)
            return Snapshot(
                readings(o.getJSONObject("psi")),
                readings(o.getJSONObject("pm25")),
                o.getString("psiTime"),
                o.getString("pm25Time"),
            )
        }

        fun readings(o: JSONObject): Map<Region, Int> = Region.entries.associateWith { o.getInt(it.key) }
    }
}

object Prefs {
    const val MODE_AUTO = "auto"
    const val OUTSIDE = "outside"
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("air", Context.MODE_PRIVATE)

    /** [MODE_AUTO] to follow the device location, otherwise a [Region.key] chosen by hand. */
    fun mode(ctx: Context): String = prefs(ctx).getString("mode", MODE_AUTO)!!
    fun setMode(ctx: Context, mode: String) = prefs(ctx).edit().putString("mode", mode).apply()

    /** Last located region: a [Region.key], [OUTSIDE], or null if the device has never been located. */
    fun autoRegion(ctx: Context): String? = prefs(ctx).getString("autoRegion", null)
    fun setAutoRegion(ctx: Context, value: String) = prefs(ctx).edit().putString("autoRegion", value).apply()

    /** [THEME_SYSTEM] to follow the phone's dark theme setting, or [THEME_LIGHT] / [THEME_DARK] to force one. */
    fun theme(ctx: Context): String = prefs(ctx).getString("theme", THEME_SYSTEM)!!
    fun setTheme(ctx: Context, theme: String) = prefs(ctx).edit().putString("theme", theme).apply()

    /** The Configuration.uiMode night bits for a forced theme, or null when following the system. */
    fun nightOverride(ctx: Context): Int? = when (theme(ctx)) {
        THEME_LIGHT -> Configuration.UI_MODE_NIGHT_NO
        THEME_DARK -> Configuration.UI_MODE_NIGHT_YES
        else -> null
    }

    fun snapshot(ctx: Context): Snapshot? =
        prefs(ctx).getString("snapshot", null)?.let { runCatching { Snapshot.fromJson(it) }.getOrNull() }

    fun setSnapshot(ctx: Context, snap: Snapshot) = prefs(ctx).edit().putString("snapshot", snap.toJson()).apply()
}

/** The headline reading: the user's region, or the highest Singapore reading when the region is unknown. */
class Shown(val region: Region?, val title: String, val note: String, val psi: Int, val pm25: Int)

fun shown(ctx: Context, snap: Snapshot): Shown {
    val mode = Prefs.mode(ctx)
    val auto = Prefs.autoRegion(ctx)
    val region = Region.fromKey(if (mode == Prefs.MODE_AUTO) auto else mode)
    if (region != null) {
        val note = if (mode == Prefs.MODE_AUTO) "Based on your location" else "Chosen region"
        return Shown(region, region.label, note, snap.psi.getValue(region), snap.pm25.getValue(region))
    }
    val note = if (auto == Prefs.OUTSIDE) "You appear to be outside Singapore" else "Location not available yet"
    return Shown(null, "Singapore (highest)", note, snap.psi.values.max(), snap.pm25.values.max())
}

private val SGT = ZoneId.of("Asia/Singapore")

/** "2:00 PM", with the date added when the reading is not from today. */
fun formatTime(iso: String): String {
    val t = runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(SGT) }.getOrNull() ?: return iso
    val pattern = if (t.toLocalDate() == LocalDate.now(SGT)) "h:mm a" else "d MMM, h:mm a"
    return t.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
}

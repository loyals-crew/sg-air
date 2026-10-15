package com.hermansantoso.psimonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class PsiWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        renderAll(ctx)
        RefreshWorker.schedule(ctx)
        RefreshReceiver.scheduleNext(ctx)
    }

    override fun onDisabled(ctx: Context) {
        RefreshWorker.cancel(ctx)
        RefreshReceiver.cancel(ctx)
    }

    companion object {
        /** Redraws every placed widget from the cached snapshot, optionally with "Updating…" in place of the time. */
        fun renderAll(ctx: Context, updating: Boolean = false) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, PsiWidget::class.java))
            if (ids.isEmpty()) return

            val views = RemoteViews(ctx.packageName, R.layout.widget)
            val background = when (Prefs.theme(ctx)) {
                Prefs.THEME_LIGHT -> R.drawable.widget_bg_light
                Prefs.THEME_DARK -> R.drawable.widget_bg_dark
                else -> R.drawable.card_bg
            }
            views.setInt(R.id.widget_root, "setBackgroundResource", background)
            val values = intArrayOf(R.id.widget_region, R.id.widget_psi, R.id.widget_pm25)
            val captions = intArrayOf(
                R.id.widget_time, R.id.widget_refresh, R.id.widget_psi_label, R.id.widget_psi_band,
                R.id.widget_pm25_label, R.id.widget_pm25_band,
            )
            for (id in values) setColor(ctx, views, id, R.color.text)
            for (id in captions) setColor(ctx, views, id, R.color.text_muted)
            val snap = Prefs.snapshot(ctx)
            if (snap == null) {
                views.setTextViewText(R.id.widget_region, "SG Air")
                views.setTextViewText(R.id.widget_time, "Loading…")
                for (id in intArrayOf(R.id.widget_psi, R.id.widget_pm25)) views.setTextViewText(id, "–")
                for (id in intArrayOf(R.id.widget_psi_band, R.id.widget_pm25_band)) views.setTextViewText(id, "")
            } else {
                val s = shown(ctx, snap)
                val psi = psiBand(s.psi)
                val pm25 = pm25Band(s.pm25)
                views.setTextViewText(R.id.widget_region, s.title)
                views.setTextViewText(R.id.widget_time, formatTime(snap.psiTime))
                views.setTextViewText(R.id.widget_psi, s.psi.toString())
                setColor(ctx, views, R.id.widget_psi, psi.colorRes)
                views.setTextViewText(R.id.widget_psi_band, psi.label)
                views.setTextViewText(R.id.widget_pm25, s.pm25.toString())
                setColor(ctx, views, R.id.widget_pm25, pm25.colorRes)
                views.setTextViewText(R.id.widget_pm25_band, pm25.label)
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (updating) views.setTextViewText(R.id.widget_time, "Updating…")
            views.setOnClickPendingIntent(R.id.widget_root, open)
            views.setOnClickPendingIntent(R.id.widget_refresh, RefreshReceiver.intent(ctx, RefreshReceiver.ACTION_TAP))
            mgr.updateAppWidget(ids, views)
        }

        private fun setColor(ctx: Context, views: RemoteViews, viewId: Int, colorRes: Int) {
            val night = Prefs.nightOverride(ctx)
            if (night != null) {
                val forced = ctx.createConfigurationContext(Configuration().apply { uiMode = night })
                views.setTextColor(viewId, forced.getColor(colorRes))
            } else if (Build.VERSION.SDK_INT >= 31) {
                // Passing the resource lets the launcher re-resolve it when the system theme changes.
                views.setColor(viewId, "setTextColor", colorRes)
            } else {
                views.setTextColor(viewId, ctx.getColor(colorRes))
            }
        }
    }
}

/** Keeps the widget current. NEA publishes hourly; polling every 30 min picks a new reading up promptly. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        // Alarms are lost on reboot and force-stop; this backup job re-arms the hourly one.
        RefreshReceiver.scheduleNext(applicationContext)
        AirRepo.refresh(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val NAME = "refresh"
        private const val NOW = "refresh-now"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequest.Builder(RefreshWorker::class.java, 30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Refreshes as soon as possible, retrying with backoff on failure. Repeated taps don't queue extra fetches. */
        fun runNow(ctx: Context) {
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequest.from(RefreshWorker::class.java))
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
        }
    }
}

/**
 * Refreshes when the widget's refresh button is tapped, and shortly after each hour when NEA publishes.
 * The hourly alarm may fire during Doze, unlike the periodic job, so the widget is current when the phone wakes.
 */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        when (intent.action) {
            ACTION_TAP -> {
                // A tap's broadcast must finish within 10 s, so the fetch runs as a job instead.
                PsiWidget.renderAll(app, updating = true)
                RefreshWorker.runNow(app)
            }
            ACTION_HOURLY -> {
                scheduleNext(app)
                // Fetch right here: during Doze the alarm grants network access only briefly, and a job could wait
                // for the next maintenance window. Background broadcasts may run for 60 s; stop waiting well before.
                val pending = goAsync()
                Thread {
                    val fetch = Thread { runCatching { AirRepo.refresh(app) } }.apply { start() }
                    fetch.join(45_000)
                    pending.finish()
                }.start()
            }
        }
    }

    companion object {
        const val ACTION_TAP = "com.hermansantoso.psimonitor.REFRESH"
        const val ACTION_HOURLY = "com.hermansantoso.psimonitor.HOURLY"
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val AFTER_HOUR_MS = 5 * 60 * 1000L

        fun intent(ctx: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, RefreshReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Arms the alarm for five minutes past the next hour, replacing any earlier one. */
        fun scheduleNext(ctx: Context) {
            val now = System.currentTimeMillis()
            var at = now / HOUR_MS * HOUR_MS + AFTER_HOUR_MS
            if (at <= now) at += HOUR_MS
            val alarms = ctx.getSystemService(AlarmManager::class.java)
            val op = intent(ctx, ACTION_HOURLY)
            // Exact timing needs the "Alarms & reminders" permission, which Android 14+ does not grant by default.
            // Without it the alarm is inexact: Android may deliver it up to ~40 min late to batch it with others.
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, op)
            }
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx, ACTION_HOURLY))
        }
    }
}

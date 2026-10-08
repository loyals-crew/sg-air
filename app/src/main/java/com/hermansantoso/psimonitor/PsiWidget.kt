package com.hermansantoso.psimonitor

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class PsiWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        renderAll(ctx)
        RefreshWorker.schedule(ctx)
    }

    override fun onDisabled(ctx: Context) {
        RefreshWorker.cancel(ctx)
    }

    companion object {
        /** Redraws every placed widget from the cached snapshot. */
        fun renderAll(ctx: Context) {
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
                R.id.widget_time, R.id.widget_psi_label, R.id.widget_psi_band,
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
            views.setOnClickPendingIntent(R.id.widget_root, open)
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
        AirRepo.refresh(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private const val NAME = "refresh"

        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequest.Builder(RefreshWorker::class.java, 30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
        }
    }
}

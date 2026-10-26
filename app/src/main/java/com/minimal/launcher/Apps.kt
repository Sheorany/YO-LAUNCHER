package com.minimal.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.widget.Toast

data class AppInfo(val label: String, val pkg: String, val activity: String) {
    val key: String get() = "$pkg/$activity"
}

object Apps {
    fun load(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map {
                AppInfo(
                    it.loadLabel(pm).toString(),
                    it.activityInfo.packageName,
                    it.activityInfo.name
                )
            }
            .distinctBy { it.key }
            .sortedBy { it.label.lowercase() }
    }

    fun launch(ctx: Context, app: AppInfo) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(app.pkg, app.activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Can't open ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    fun icon(ctx: Context, app: AppInfo): Drawable? = try {
        ctx.packageManager.getActivityIcon(ComponentName(app.pkg, app.activity))
    } catch (e: Exception) {
        null
    }
}

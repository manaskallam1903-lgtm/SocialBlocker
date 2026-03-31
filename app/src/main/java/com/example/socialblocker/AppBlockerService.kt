package com.example.socialblocker

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class AppBlockerService : AccessibilityService() {

    private var lastBlockTime = 0L
    private val blockCooldownMs = 2000L

    // Black Screen Overlay Variables
    private var overlayView: View? = null
    private var shortsOverlayView: View? = null
    private var windowManager: WindowManager? = null

    // Audio State Tracking
    private var preMuteVolume = -1

    // App Timer Tracking
    private var currentForegroundApp: String? = null
    private val timerHandler = Handler(Looper.getMainLooper())

    companion object {
        var isCallBypassActive = false
        var isManualBypassActive = false
        var isPostCallBypassActive = false
        var postCallBypassEndTime = 0L
    }

    // --- SCREEN OFF DETECTOR ---
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                // Instantly kill the 15-min bypass the first time they lock their phone
                if (isPostCallBypassActive) {
                    isPostCallBypassActive = false
                    Log.d("SocialBlocker", "Screen turned off. Post-call bypass terminated.")
                }
            }
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (isPostCallBypassActive && System.currentTimeMillis() > postCallBypassEndTime) {
                isPostCallBypassActive = false
                showToast("Post-call bypass expired. Bedtime reactivated.")
            }

            if (isBedtime() && !isCallBypassActive && !isManualBypassActive && !isPostCallBypassActive) {
                showBedtimeOverlay()
            } else {
                hideBedtimeOverlay()
            }

            if (overlayView == null && !isManualBypassActive && !isPostCallBypassActive) {
                checkAppTimers()
            }

            timerHandler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d("SocialBlocker", "Service Connected!")
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        timerHandler.post(timerRunnable)

        // Register Screen Off Receiver
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentForegroundApp = event.packageName?.toString()
        }

        val packageName = event.packageName?.toString() ?: currentForegroundApp

        val prefs = getSharedPreferences("BedtimePrefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("hardcore_mode", false)) {
            if (packageName == "com.android.settings" ||
                packageName == "com.google.android.apps.wellbeing" ||
                packageName == "com.google.android.packageinstaller") {

                if (isAppMentionedOnScreen(rootInActiveWindow)) {
                    triggerHomeBlock("Hardcore Mode: You cannot modify Social Blocker!", force = true)
                    return
                }
            }
        }

        if (packageName == null || isManualBypassActive) return

        // --- POST-CALL PENALTY LOGIC ---
        if (isPostCallBypassActive) {
            if (packageName == "com.google.android.youtube" || packageName == "com.instagram.android") {
                isPostCallBypassActive = false
                applyPenalty()
                triggerHomeBlock("PENALTY! 1 bypass lost for opening social media!", force = true)
                return
            }
        }

        if (packageName == "com.google.android.youtube") {
            val root = rootInActiveWindow ?: return
            if (isYouTubeShorts(root)) blockShortContent("Shorts blocked")
        } else if (packageName == "com.instagram.android") {
            val root = rootInActiveWindow ?: return
            if (isInstagramReels(root)) blockShortContent("Reels blocked")
        }
    }

    private fun isAppMentionedOnScreen(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (text.contains("social blocker") || desc.contains("social blocker")) return true
        for (i in 0 until node.childCount) {
            if (isAppMentionedOnScreen(node.getChild(i))) return true
        }
        return false
    }

    private fun blockShortContent(message: String) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBlockTime > blockCooldownMs) {
            lastBlockTime = currentTime
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (preMuteVolume == -1) preMuteVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } catch (e: Exception) {}

            showShortsOverlay(message)

            Handler(Looper.getMainLooper()).postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 300)
            Handler(Looper.getMainLooper()).postDelayed({
                val root = rootInActiveWindow
                if (root != null && (isYouTubeShorts(root) || isInstagramReels(root))) performGlobalAction(GLOBAL_ACTION_BACK)
            }, 800)

            Handler(Looper.getMainLooper()).postDelayed({
                hideShortsOverlay()
                try {
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (preMuteVolume != -1) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, preMuteVolume, 0)
                        preMuteVolume = -1
                    }
                } catch (e: Exception) {}
            }, 1500)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showShortsOverlay(message: String) {
        if (shortsOverlayView != null) return
        try {
            val layout = FrameLayout(this)
            layout.setBackgroundColor(Color.BLACK)
            layout.isClickable = true
            layout.isFocusable = false

            val text = TextView(this).apply {
                this.text = message
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
            }
            layout.addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            )
            windowManager?.addView(layout, params)
            shortsOverlayView = layout
        } catch (e: Exception) {}
    }

    private fun hideShortsOverlay() {
        if (shortsOverlayView != null) {
            try { windowManager?.removeView(shortsOverlayView) } catch (e: Exception) {}
            shortsOverlayView = null
        }
    }

    private fun checkAppTimers() {
        val activePkg = rootInActiveWindow?.packageName?.toString() ?: currentForegroundApp ?: return
        val prefs = getSharedPreferences("AppTimerPrefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        if (activePkg == "com.instagram.android") handleAppTimer("ig", "Instagram", prefs, now)
        else if (activePkg == "com.google.android.youtube") handleAppTimer("yt", "YouTube", prefs, now)
    }

    private fun handleAppTimer(prefix: String, appName: String, prefs: SharedPreferences, now: Long) {
        val cooldownStart = prefs.getLong("${prefix}_cooldown_start", 0L)
        val storedCooldownHours = prefs.getInt("${prefix}_cooldown_hours", 1)
        val actualCooldownHours = if (storedCooldownHours < 1) 1 else storedCooldownHours
        val cooldownDurationMs = actualCooldownHours * 3600000L
        val limitSeconds = prefs.getInt("${prefix}_limit_mins", 10) * 60

        if (cooldownStart > 0) {
            if (now < cooldownStart + cooldownDurationMs) {
                triggerHomeBlock("$appName is on Cooldown limit!", force = true)
            } else {
                prefs.edit().putLong("${prefix}_cooldown_start", 0L).putInt("${prefix}_usage_seconds", 0).apply()
            }
        } else {
            var usage = prefs.getInt("${prefix}_usage_seconds", 0)
            usage++
            prefs.edit().putInt("${prefix}_usage_seconds", usage).apply()

            if (usage >= limitSeconds) {
                prefs.edit().putLong("${prefix}_cooldown_start", now).apply()
                triggerHomeBlock("Daily limit reached! $appName Cooldown started.", force = true)
            }
        }
    }

    private fun getRemainingBypasses(): Int {
        val prefs = getSharedPreferences("BypassPrefs", Context.MODE_PRIVATE)
        val currentWeek = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)
        val lastBypassWeek = prefs.getInt("last_bypass_week", -1)
        val totalAllowed = prefs.getInt("weekly_limit", 1)
        if (currentWeek != lastBypassWeek) {
            prefs.edit().putInt("used_this_week", 0).putInt("last_bypass_week", currentWeek).apply()
            return totalAllowed
        }
        val used = prefs.getInt("used_this_week", 0)
        return Math.max(0, totalAllowed - used)
    }

    // --- FULL SCREEN BEDTIME OVERLAY ---
    @SuppressLint("ClickableViewAccessibility")
    private fun showBedtimeOverlay() {
        if (overlayView != null) return

        try {
            val layout = FrameLayout(this)
            layout.setBackgroundColor(Color.BLACK)
            layout.isClickable = true
            layout.isFocusable = true

            val remainingBypasses = getRemainingBypasses()
            val isExpended = remainingBypasses <= 0

            val bypassText = TextView(this).apply {
                text = if (isExpended) "Bypasses expended for the week" else "Hold here for 3s to Bypass"
                setTextColor(Color.parseColor("#444444"))
                textSize = 14f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#111111"))
                setPadding(0, 80, 0, 80)
            }

            var isHolding = false
            val bypassRunnable = Runnable {
                if (isHolding) {
                    if (isExpended) {
                        showToast("Go sleep!")
                    } else {
                        handleBypassTrigger()
                    }
                }
            }

            bypassText.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        isHolding = true
                        timerHandler.postDelayed(bypassRunnable, 3000)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        isHolding = false
                        timerHandler.removeCallbacks(bypassRunnable)
                    }
                }
                true
            }

            val btnParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            btnParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            layout.addView(bypassText, btnParams)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            windowManager?.addView(layout, params)
            overlayView = layout
        } catch (e: Exception) {}
    }

    private fun hideBedtimeOverlay() {
        if (overlayView != null) {
            try { windowManager?.removeView(overlayView) } catch (e: Exception) {}
            overlayView = null
        }
    }

    private fun handleBypassTrigger() {
        val prefs = getSharedPreferences("BypassPrefs", Context.MODE_PRIVATE)
        val currentWeek = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)
        val lastBypassWeek = prefs.getInt("last_bypass_week", -1)
        val totalAllowed = prefs.getInt("weekly_limit", 1)

        val used = if (currentWeek == lastBypassWeek) prefs.getInt("used_this_week", 0) else 0

        if (totalAllowed - used > 0) {
            isManualBypassActive = true
            prefs.edit().putInt("used_this_week", used + 1).putInt("last_bypass_week", currentWeek).apply()
            showToast("Emergency Bypass Activated (1 Hour)")
            hideBedtimeOverlay()
            timerHandler.postDelayed({
                isManualBypassActive = false
                showToast("Bypass Expired")
            }, 3600000)
        } else {
            showToast("Go sleep!")
        }
    }

    // --- PENALTY EXECUTION (-1) ---
    private fun applyPenalty() {
        val prefs = getSharedPreferences("BypassPrefs", Context.MODE_PRIVATE)
        val currentWeek = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)
        val lastBypassWeek = prefs.getInt("last_bypass_week", -1)

        var used = if (currentWeek == lastBypassWeek) prefs.getInt("used_this_week", 0) else 0

        prefs.edit()
            .putInt("used_this_week", used + 1) // Minus 1 Bypass (by adding 1 to 'used')
            .putInt("last_bypass_week", currentWeek)
            .apply()
    }

    private fun triggerHomeBlock(message: String, force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        if (force || currentTime - lastBlockTime > blockCooldownMs) {
            lastBlockTime = currentTime
            showToast(message)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun isYouTubeShorts(node: AccessibilityNodeInfo): Boolean {
        val ids = listOf("com.google.android.youtube:id/reel_watch_fragment_root", "com.google.android.youtube:id/reel_recycler")
        return ids.any { id -> node.findAccessibilityNodeInfosByViewId(id)?.any { it.isVisibleToUser } == true }
    }

    private fun isInstagramReels(node: AccessibilityNodeInfo): Boolean {
        val ids = listOf("com.instagram.android:id/clips_viewer_view_pager", "com.instagram.android:id/root_clips_layout")
        return ids.any { id -> node.findAccessibilityNodeInfosByViewId(id)?.any { it.isVisibleToUser } == true }
    }

    private fun isBedtime(): Boolean {
        val prefs = getSharedPreferences("BedtimePrefs", Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean("bedtime_enabled", true)
        if (!isEnabled) return false
        val startH = prefs.getInt("start_hour", 23)
        val startM = prefs.getInt("start_minute", 0)
        val endH = prefs.getInt("end_hour", 6)
        val endM = prefs.getInt("end_minute", 0)
        val cal = Calendar.getInstance()
        val curM = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val sM = startH * 60 + startM
        val eM = endH * 60 + endM
        if (sM == eM) return false
        return if (sM < eM) curM in sM until eM else curM >= sM || curM < eM
    }

    override fun onInterrupt() {}
}
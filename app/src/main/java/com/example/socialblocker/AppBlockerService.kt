package com.example.socialblocker

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    private var tickCount = 0

    // Anti-Loophole: Penalty timer for trying to access settings
    private var hardcoreBanEndTime = 0L

    // --- FEATURE OVERLAYS ---
    private var floatingTimerView: View? = null
    private var tvFloatingTimer: TextView? = null

    private var cooldownOverlayView: View? = null
    private var radialTimerView: RadialTimerView? = null
    private var tvCooldownDigital: TextView? = null
    private var tvCooldownEnd: TextView? = null

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
                if (isPostCallBypassActive) {
                    isPostCallBypassActive = false
                    Log.d("SocialBlocker", "Screen turned off. Post-call bypass terminated.")
                }
            }
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            tickCount++
            val shouldIncrementUsage = (tickCount % 10 == 0) // 10 ticks of 100ms = 1 full second

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
                checkAppTimers(shouldIncrementUsage)
            }

            // ANTI-LOOPHOLE ACTIVE POLLING (Running at 100ms)
            if (isHardcoreLocked() && (currentForegroundApp == "com.android.settings" || currentForegroundApp == "com.google.android.packageinstaller")) {
                if (System.currentTimeMillis() < hardcoreBanEndTime) {
                    triggerHomeBlock("Hardcore Lockout: Wait before trying again!", force = true)
                }
                else if (isAppMentionedOnScreen(rootInActiveWindow)) {
                    hardcoreBanEndTime = System.currentTimeMillis() + 5000L
                    triggerHomeBlock("Hardcore Mode: You cannot modify Social Blocker!", force = true)
                }
            }

            timerHandler.postDelayed(this, 100)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        timerHandler.post(timerRunnable)

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenReceiver, filter)
    }

    // --- BUG FIX: Kill Hardcore Mode if accessibility is revoked ---
    override fun onUnbind(intent: Intent?): Boolean {
        val prefs = getSharedPreferences("BedtimePrefs", Context.MODE_PRIVATE)
        // CRITICAL FIX: Use .commit() to enforce a blocking, instant write to disk
        // before the Android OS can kill the process completely.
        prefs.edit()
            .putBoolean("hardcore_mode", false)
            .putLong("hardcore_unlock_request_time", 0L)
            .commit()
        Log.d("SocialBlocker", "Accessibility Disabled! Hardcore Mode terminated.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)

        // FAILSAFE: Clear hardcore mode here as well using synchronous commit()
        val prefs = getSharedPreferences("BedtimePrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("hardcore_mode", false)
            .putLong("hardcore_unlock_request_time", 0L)
            .commit()
    }

    private fun isHardcoreLocked(): Boolean {
        val prefs = getSharedPreferences("BedtimePrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("hardcore_mode", false)) return false

        val requestTime = prefs.getLong("hardcore_unlock_request_time", 0L)
        if (requestTime == 0L) return true

        var waitHours = prefs.getInt("hardcore_wait_hours", 3)
        if (waitHours < 3) waitHours = 3

        val unlockTime = requestTime + (waitHours * 3600000L)
        return System.currentTimeMillis() < unlockTime
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentForegroundApp = event.packageName?.toString()
        }

        val packageName = event.packageName?.toString() ?: currentForegroundApp

        if (isHardcoreLocked()) {
            if (packageName == "com.android.settings" ||
                packageName == "com.google.android.apps.wellbeing" ||
                packageName == "com.google.android.packageinstaller") {

                if (System.currentTimeMillis() < hardcoreBanEndTime) {
                    triggerHomeBlock("Hardcore Lockout: Wait before trying again!", force = true)
                    return
                }

                val eventText = event.text.toString().lowercase().replace(" ", "")
                val contentDesc = event.contentDescription?.toString()?.lowercase()?.replace(" ", "") ?: ""

                if (eventText.contains("socialblocker") || contentDesc.contains("socialblocker") ||
                    eventText.contains("com.example.socialblocker") || isAppMentionedOnScreen(rootInActiveWindow)) {

                    hardcoreBanEndTime = System.currentTimeMillis() + 5000L
                    triggerHomeBlock("Hardcore Mode: You cannot modify Social Blocker!", force = true)
                    return
                }
            }
        }

        if (packageName == null || isManualBypassActive) return

        if (isPostCallBypassActive) {
            // Apply penalty if they open IG or YT specifically
            if (packageName == "com.google.android.youtube" || packageName == "com.instagram.android") {
                isPostCallBypassActive = false
                applyPenalty()
                triggerHomeBlock("PENALTY! 1 bypass lost for opening social media!", force = true)
                return
            }
        }

        // Keep Shorts/Reels block hardcoded since they rely on specific UI node IDs
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

        val text = node.text?.toString()?.lowercase()?.replace(" ", "") ?: ""
        val desc = node.contentDescription?.toString()?.lowercase()?.replace(" ", "") ?: ""

        if (text.contains("socialblocker") || desc.contains("socialblocker") ||
            text.contains("com.example.socialblocker") || desc.contains("com.example.socialblocker")) return true

        for (i in 0 until node.childCount) {
            if (isAppMentionedOnScreen(node.getChild(i))) return true
        }
        return false
    }

    // --- FEATURE 1: FLOATING TOP-RIGHT TIMER ---
    private fun showFloatingTimer(remainingSeconds: Int) {
        if (floatingTimerView == null) {
            tvFloatingTimer = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(30, 15, 30, 15)
                background = GradientDrawable().apply {
                    cornerRadius = 40f
                    setColor(Color.parseColor("#99000000")) // Semi-transparent black
                }
            }
            val layout = FrameLayout(this).apply {
                addView(tvFloatingTimer, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    setMargins(0, 120, 50, 0) // Keep away from status bar and edges
                })
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            windowManager?.addView(layout, params)
            floatingTimerView = layout
        }

        val m = remainingSeconds / 60
        val s = remainingSeconds % 60
        tvFloatingTimer?.text = String.format("%02d:%02d", m, s)

        if (remainingSeconds < 60) {
            tvFloatingTimer?.setTextColor(Color.parseColor("#FF453A")) // Red warning
        } else {
            tvFloatingTimer?.setTextColor(Color.WHITE)
        }
    }

    private fun hideFloatingTimer() {
        if (floatingTimerView != null) {
            try { windowManager?.removeView(floatingTimerView) } catch (e: Exception) {}
            floatingTimerView = null
            tvFloatingTimer = null
        }
    }

    // --- FEATURE 2: SAMSUNG STYLE RADIAL TIMER ---
    inner class RadialTimerView(context: Context) : View(context) {
        var progress = 1f
        private val bgPaint = Paint().apply {
            color = Color.parseColor("#1C1C1E")
            style = Paint.Style.STROKE
            strokeWidth = 40f // Made thicker to match Samsung style
            isAntiAlias = true
        }
        private val progressPaint = Paint().apply {
            color = Color.parseColor("#A3B5FF") // Light Blue matching Samsung
            style = Paint.Style.STROKE
            strokeWidth = 40f // Made thicker
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            // Adjust radius to account for the stroke width so it doesn't clip
            val radius = Math.min(cx, cy) - (bgPaint.strokeWidth / 2f) - 5f

            canvas.drawCircle(cx, cy, radius, bgPaint)
            val sweepAngle = progress * 360f
            val rectF = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(rectF, -90f, sweepAngle, false, progressPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showCooldownOverlay(appName: String, remainingMs: Long, totalMs: Long, endTimeMs: Long) {
        if (cooldownOverlayView == null) {
            val layout = FrameLayout(this)
            layout.setBackgroundColor(Color.BLACK)
            layout.isClickable = true
            layout.isFocusable = true

            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (preMuteVolume == -1) preMuteVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            } catch (e: Exception) {}

            val centerContainer = FrameLayout(this)

            val screenWidth = resources.displayMetrics.widthPixels
            val ringSize = (screenWidth * 0.85).toInt()

            radialTimerView = RadialTimerView(this)
            centerContainer.addView(radialTimerView, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))

            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER)
            }

            textContainer.addView(TextView(this).apply {
                text = "$appName Blocked"
                setTextColor(Color.parseColor("#888888"))
                textSize = 16f
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, 30)
            })

            tvCooldownDigital = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 72f
                setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
                gravity = Gravity.CENTER_HORIZONTAL
            }
            textContainer.addView(tvCooldownDigital)

            tvCooldownEnd = TextView(this).apply {
                setTextColor(Color.parseColor("#AAAAAA"))
                textSize = 16f
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, 30, 0, 0)
            }
            textContainer.addView(tvCooldownEnd)

            centerContainer.addView(textContainer)

            layout.addView(centerContainer)

            val btnHome = Button(this).apply {
                text = "Go Home"
                setTextColor(Color.WHITE)
                isAllCaps = false
                textSize = 16f
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#E04040"))
                    cornerRadius = 100f
                }
                setPadding(120, 50, 120, 50)
                setOnClickListener { performGlobalAction(GLOBAL_ACTION_HOME) }
            }

            layout.addView(btnHome, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply { setMargins(0, 0, 0, 200) })

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
            )
            windowManager?.addView(layout, params)
            cooldownOverlayView = layout
        }

        radialTimerView?.progress = (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
        radialTimerView?.invalidate()

        val totalSec = remainingMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        if (h > 0) {
            tvCooldownDigital?.text = String.format("%d:%02d:%02d", h, m, s)
        } else {
            tvCooldownDigital?.text = String.format("%02d:%02d", m, s)
        }

        val cal = Calendar.getInstance().apply { timeInMillis = endTimeMs }
        val endH = cal.get(Calendar.HOUR)
        val dispH = if (endH == 0) 12 else endH
        val endM = cal.get(Calendar.MINUTE)
        val amPm = if (cal.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM"
        tvCooldownEnd?.text = String.format("🔔 %d:%02d %s", dispH, endM, amPm)
    }

    private fun hideCooldownOverlay() {
        if (cooldownOverlayView != null) {
            try { windowManager?.removeView(cooldownOverlayView) } catch (e: Exception) {}
            cooldownOverlayView = null
            radialTimerView = null
            tvCooldownDigital = null
            tvCooldownEnd = null

            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (preMuteVolume != -1) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, preMuteVolume, 0)
                    preMuteVolume = -1
                }
            } catch (e: Exception) {}
        }
    }

    // --- HELPER: Fetch App Name from Package ---
    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    // --- FEATURE 3: DYNAMIC APP CHECKER ---
    private fun checkAppTimers(shouldIncrementUsage: Boolean) {
        val activePkg = rootInActiveWindow?.packageName?.toString() ?: currentForegroundApp
        val prefs = getSharedPreferences("AppTimerPrefs", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // Fetch dynamic list of tracked packages (defaults to IG and YT to keep existing functionality)
        val trackedApps = prefs.getStringSet("tracked_packages", setOf("com.instagram.android", "com.google.android.youtube")) ?: setOf()

        var isTrackedAppForeground = false

        if (activePkg != null && trackedApps.contains(activePkg)) {
            isTrackedAppForeground = true
            val appName = getAppName(activePkg)

            // Maintain backward compatibility for keys "ig" and "yt", otherwise use package name as the prefix key
            val prefix = when (activePkg) {
                "com.instagram.android" -> "ig"
                "com.google.android.youtube" -> "yt"
                else -> activePkg.replace(".", "_")
            }

            handleAppTimer(prefix, appName, prefs, now, shouldIncrementUsage)
        }

        if (!isTrackedAppForeground) {
            hideFloatingTimer()
            hideCooldownOverlay()
        }
    }

    private fun handleAppTimer(prefix: String, appName: String, prefs: SharedPreferences, now: Long, shouldIncrementUsage: Boolean) {
        val cooldownStart = prefs.getLong("${prefix}_cooldown_start", 0L)
        val storedCooldownHours = prefs.getInt("${prefix}_cooldown_hours", 1)
        val actualCooldownHours = if (storedCooldownHours < 1) 1 else storedCooldownHours
        val cooldownDurationMs = actualCooldownHours * 3600000L
        val limitSeconds = prefs.getInt("${prefix}_limit_mins", 10) * 60

        if (cooldownStart > 0) {
            hideFloatingTimer()
            val endTimeMs = cooldownStart + cooldownDurationMs
            val remainingMs = endTimeMs - now

            if (remainingMs > 0) {
                showCooldownOverlay(appName, remainingMs, cooldownDurationMs, endTimeMs)
            } else {
                prefs.edit().putLong("${prefix}_cooldown_start", 0L).putInt("${prefix}_usage_seconds", 0).apply()
                hideCooldownOverlay()
            }
        } else {
            hideCooldownOverlay()

            var usage = prefs.getInt("${prefix}_usage_seconds", 0)
            if (shouldIncrementUsage) {
                usage++
                prefs.edit().putInt("${prefix}_usage_seconds", usage).apply()
            }

            val remainingSeconds = limitSeconds - usage
            if (remainingSeconds <= 0) {
                prefs.edit().putLong("${prefix}_cooldown_start", now).apply()
                hideFloatingTimer()
                showCooldownOverlay(appName, cooldownDurationMs, cooldownDurationMs, now + cooldownDurationMs)
            } else {
                showFloatingTimer(remainingSeconds)
            }
        }
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

    private fun applyPenalty() {
        val prefs = getSharedPreferences("BypassPrefs", Context.MODE_PRIVATE)
        val currentWeek = Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)
        val lastBypassWeek = prefs.getInt("last_bypass_week", -1)

        var used = if (currentWeek == lastBypassWeek) prefs.getInt("used_this_week", 0) else 0

        prefs.edit()
            .putInt("used_this_week", used + 1)
            .putInt("last_bypass_week", currentWeek)
            .apply()
    }

    private fun triggerHomeBlock(message: String, force: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        if (force || currentTime - lastBlockTime > blockCooldownMs) {
            lastBlockTime = currentTime

            showShortsOverlay(message)
            showToast(message)
            performGlobalAction(GLOBAL_ACTION_HOME)

            Handler(Looper.getMainLooper()).postDelayed({
                hideShortsOverlay()
            }, 1500)
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

    override fun onInterrupt() {
        // Required method by AccessibilityService. We do not need to perform any action when interrupted.
    }
}
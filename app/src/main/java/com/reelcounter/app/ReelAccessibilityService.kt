package com.reelcounter.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Detects reel/short swipes in target apps by watching for TYPE_VIEW_SCROLLED
 * events on those apps' full-screen video feeds, debounced so one swipe = one count.
 *
 * This is a heuristic, not an official API â€” there is no public signal for
 * "a new reel started playing." Expect occasional over/undercounts, and
 * re-tune debounceMs or targetPackages if an app update changes behavior.
 */
class ReelAccessibilityService : AccessibilityService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var params: WindowManager.LayoutParams

    private val targetPackages = setOf(
        "com.instagram.android",
        "com.google.android.youtube",
        "com.zhiliaoapp.musically",
        "com.facebook.katana",
        "com.snapchat.android"
    )

    private var lastCountTime = 0L
    private val debounceMs = 300L

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("reel_counter_prefs", Context.MODE_PRIVATE)
        resetIfNewDay()

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }

        setupOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            setOverlayVisible(pkg in targetPackages)
        }

        if (pkg !in targetPackages) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val now = System.currentTimeMillis()
        if (now - lastCountTime >= debounceMs) {
            lastCountTime = now
            incrementCount(pkg)
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        if (::overlayView.isInitialized) {
            overlayView.post {
                overlayView.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    override fun onInterrupt() {}

    private fun resetIfNewDay() {
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (prefs.getString("count_day", "") != todayKey) {
            prefs.edit()
                .putString("count_day", todayKey)
                .putInt("count_total", 0)
                .apply()
        }
    }

    private fun incrementCount(pkg: String) {
        resetIfNewDay()
        val total = prefs.getInt("count_total", 0) + 1
        val perApp = prefs.getInt("count_$pkg", 0) + 1
        prefs.edit()
            .putInt("count_total", total)
            .putInt("count_$pkg", perApp)
            .apply()
        if (::overlayView.isInitialized) {
            overlayView.post { overlayView.text = total.toString() }
        }
    }

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        overlayView = TextView(this).apply {
            text = prefs.getInt("count_total", 0).toString()
            textSize = 18f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC1A1A1A.toInt())
            setPadding(28, 20, 28, 20)
            visibility = android.view.View.GONE
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) isDragging = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }

        overlayView.setOnLongClickListener {
            resetIfNewDay()
            prefs.edit().putInt("count_total", 0).apply()
            overlayView.text = "0"
            true
        }

        windowManager.addView(overlayView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::windowManager.isInitialized && ::overlayView.isInitialized) {
            try {
                windowManager.removeView(overlayView)
            } catch (e: Exception) {
                // view already detached
            }
        }
    }
}

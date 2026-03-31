package com.example.socialblocker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)

            if (incomingNumber == null) return

            val prefs = context.getSharedPreferences("BypassPrefs", Context.MODE_PRIVATE)
            val savedNumbers = prefs.getString("emergency_numbers", "") ?: ""

            // Clean incoming number to just digits
            val cleanIncoming = incomingNumber.replace(Regex("[^0-9+]"), "")

            // 1. Check if it's a saved emergency contact
            var isEmergencyContact = savedNumbers.split(",").any {
                it.isNotEmpty() && (cleanIncoming.endsWith(it) || it.endsWith(cleanIncoming))
            }

            // 2. Check for Repeat Caller (within 10 minutes)
            val lastCaller = prefs.getString("last_caller_number", "")
            val lastCallTime = prefs.getLong("last_caller_time", 0L)
            val currentTime = System.currentTimeMillis()

            if (cleanIncoming == lastCaller && (currentTime - lastCallTime) <= (10 * 60 * 1000)) {
                Log.d("SocialBlocker", "Repeat Caller Detected! Letting it through.")
                isEmergencyContact = true
            }

            when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    // Save this caller to memory for the 10-minute rule
                    prefs.edit()
                        .putString("last_caller_number", cleanIncoming)
                        .putLong("last_caller_time", currentTime)
                        .apply()

                    if (isEmergencyContact) {
                        Log.d("SocialBlocker", "Emergency/Repeat Contact Calling: $cleanIncoming")
                        AppBlockerService.isCallBypassActive = true
                        AppBlockerService.isPostCallBypassActive = false
                    }
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    Log.d("SocialBlocker", "Call ended.")
                    if (AppBlockerService.isCallBypassActive) {
                        AppBlockerService.isCallBypassActive = false
                        AppBlockerService.isPostCallBypassActive = true
                        AppBlockerService.postCallBypassEndTime = System.currentTimeMillis() + (15 * 60 * 1000) // 15 mins
                    }
                }
            }
        }
    }
}
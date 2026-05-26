package com.example.telecom

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import com.example.MainActivity

class PhoneCallService : InCallService() {
    private val TAG = "PhoneX_CallService"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "InCallService created")
        CallManager.inCallService = this
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "InCallService destroyed")
        CallManager.inCallService = null
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.d(TAG, "onCallAdded: $call")
        CallManager.onCallAdded(call)

        // Resolve caller information using phone numbers on background threads
        val handleUri = call.details?.handle
        val number = handleUri?.schemeSpecificPart ?: ""
        CallManager.resolveCallerDetails(this, number)
        CallManager.detectActiveSimAndCarriers(this)

        // Bring MainActivity to the foreground to display our AMOLED calling screen
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("SHOW_CALL_SCREEN", true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity for active call: ${e.message}")
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: $call")
        CallManager.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        Log.d(TAG, "onCallAudioStateChanged: $audioState")
        CallManager.updateAudioState(audioState)
    }
}

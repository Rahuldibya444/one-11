package com.example.telecom

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaRecorder
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

object CallManager {
    private const val TAG = "PhoneX_CallManager"
    private const val CHANNEL_ID = "rahul_call_channel"
    private const val NOTIFICATION_ID = 2605
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var timerJob: Job? = null
    private var recordingTimerJob: Job? = null

    // Preferences for Call Recording Options (Auto / Manual)
    private val _isAutoRecording = MutableStateFlow(false)
    val isAutoRecording: StateFlow<Boolean> = _isAutoRecording.asStateFlow()

    // Call state flows
    private val _currentCall = MutableStateFlow<Call?>(null)
    val currentCall: StateFlow<Call?> = _currentCall.asStateFlow()

    private val _callStateValue = MutableStateFlow(Call.STATE_DISCONNECTED)
    val callStateValue: StateFlow<Int> = _callStateValue.asStateFlow()

    private val _callerNumber = MutableStateFlow("")
    val callerNumber: StateFlow<String> = _callerNumber.asStateFlow()

    private val _callerName = MutableStateFlow("")
    val callerName: StateFlow<String> = _callerName.asStateFlow()

    private val _callerPhotoUri = MutableStateFlow<String?>(null)
    val callerPhotoUri: StateFlow<String?> = _callerPhotoUri.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val _isHeld = MutableStateFlow(false)
    val isHeld: StateFlow<Boolean> = _isHeld.asStateFlow()

    private val _callDurationSec = MutableStateFlow(0)
    val callDurationSec: StateFlow<Int> = _callDurationSec.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingDurationSec = MutableStateFlow(0)
    val recordingDurationSec: StateFlow<Int> = _recordingDurationSec.asStateFlow()

    private val _dtmfKeysPlayed = MutableStateFlow("")
    val dtmfKeysPlayed: StateFlow<String> = _dtmfKeysPlayed.asStateFlow()

    private val _activeSimCarrier = MutableStateFlow("Primary SIM")
    val activeSimCarrier: StateFlow<String> = _activeSimCarrier.asStateFlow()

    private val _recordingsList = MutableStateFlow<List<CallRecording>>(
        listOf(
            CallRecording(
                id = "REC-P4",
                callerNameOrNumber = "Caelum Vane",
                timestamp = System.currentTimeMillis() - 7200000,
                durationSec = 142,
                audioPathSimulated = "rec_caelum.wav"
            ),
            CallRecording(
                id = "REC-N9",
                callerNameOrNumber = "Aurora Vance",
                timestamp = System.currentTimeMillis() - 18000000,
                durationSec = 67,
                audioPathSimulated = "rec_aurora.wav"
            )
        )
    )
    val recordingsList: StateFlow<List<CallRecording>> = _recordingsList.asStateFlow()

    // Reference to the service to pass controls back
    var inCallService: PhoneCallService? = null

    // Register active Call callbacks
    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.d(TAG, "Call state changed to $state")
            updateCallState(call)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            Log.d(TAG, "Call details changed")
            updateCallState(call)
        }
    }

    fun onCallAdded(call: Call) {
        _currentCall.value = call
        call.registerCallback(callCallback)
        updateCallState(call)
    }

    fun onCallRemoved(call: Call) {
        call.unregisterCallback(callCallback)
        if (_currentCall.value == call) {
            _currentCall.value = null
            _callStateValue.value = Call.STATE_DISCONNECTED
            stopTimer()
            val recActive = _isRecording.value
            val duration = _recordingDurationSec.value
            stopRecordingTimer()
            _isRecording.value = false
            _dtmfKeysPlayed.value = ""

            inCallService?.let { dismissCallNotification(it) }

            if (recActive && duration > 0) {
                val recordId = "REC-" + java.util.UUID.randomUUID().toString().take(4).uppercase()
                val numberOrName = if (_callerName.value.isNotEmpty() && _callerName.value != "Unknown Caller") {
                    _callerName.value
                } else if (_callerNumber.value.isNotEmpty()) {
                    _callerNumber.value
                } else {
                    "Simulated Entry"
                }
                val newRecording = CallRecording(
                    id = recordId,
                    callerNameOrNumber = numberOrName,
                    timestamp = System.currentTimeMillis(),
                    durationSec = duration,
                    audioPathSimulated = "rec_$recordId.wav"
                )
                _recordingsList.value = listOf(newRecording) + _recordingsList.value
            }
        }
    }

    fun updateCallState(call: Call) {
        val state = call.state
        _callStateValue.value = state
        _isHeld.value = state == Call.STATE_HOLDING

        // Extract handle / phone number
        val handleUri = call.details?.handle
        val number = handleUri?.schemeSpecificPart ?: ""
        _callerNumber.value = number

        val stateText = when (state) {
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_RINGING -> "RINGING"
            else -> "CONNECTED"
        }

        inCallService?.let { context ->
            showCallNotification(context, stateText)
        }

        if (state == Call.STATE_ACTIVE) {
            startTimer()
            inCallService?.let { context ->
                if (_isAutoRecording.value && !_isRecording.value) {
                    toggleRecording(context)
                }
            }
        } else if (state == Call.STATE_DISCONNECTED) {
            stopTimer()
            stopRecordingTimer()
            _isRecording.value = false
            inCallService?.let { dismissCallNotification(it) }
        }
    }

    fun setAutoRecording(context: Context, enabled: Boolean) {
        _isAutoRecording.value = enabled
        val prefs = context.getSharedPreferences("rahul_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("auto_recording", enabled).apply()
    }

    fun loadSettings(context: Context) {
        val prefs = context.getSharedPreferences("rahul_settings", Context.MODE_PRIVATE)
        _isAutoRecording.value = prefs.getBoolean("auto_recording", false)
    }

    fun showCallNotification(context: Context, stateText: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Active Calls & Recording",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Displays status of active telephone channels"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("SHOW_CALL_SCREEN", true)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val nameOrNum = _callerName.value.ifEmpty { _callerNumber.value.ifEmpty { "Active Channel" } }
            val text = "Channel: $nameOrNum ($stateText)"
            val subText = if (_isRecording.value) "🔴 Secure Recording: ${_recordingDurationSec.value}s" else "Connected"

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("RahuL - Active Session")
                .setContentText(text)
                .setSubText(subText)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error posting notification: ${e.message}")
        }
    }

    fun dismissCallNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification: ${e.message}")
        }
    }

    fun setMute(muted: Boolean) {
        inCallService?.let { service ->
            service.setMuted(muted)
            _isMuted.value = muted
        }
    }

    fun setSpeakerOn(speakerOn: Boolean) {
        inCallService?.let { service ->
            val route = if (speakerOn) {
                CallAudioState.ROUTE_SPEAKER
            } else {
                CallAudioState.ROUTE_EARPIECE
            }
            service.setAudioRoute(route)
            _isSpeakerOn.value = speakerOn
        }
    }

    fun updateAudioState(audioState: CallAudioState) {
        _isMuted.value = audioState.isMuted
        _isSpeakerOn.value = (audioState.route and CallAudioState.ROUTE_SPEAKER) != 0
    }

    // Call Control APIs
    fun answerCall() {
        _currentCall.value?.let { call ->
            call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
            Log.d(TAG, "Call answered successfully")
        }
    }

    fun disconnectCall() {
        _currentCall.value?.let { call ->
            call.disconnect()
            Log.d(TAG, "Call disconnected successfully")
        }
    }

    fun toggleHold() {
        _currentCall.value?.let { call ->
            if (_isHeld.value) {
                call.unhold()
                _isHeld.value = false
            } else {
                call.hold()
                _isHeld.value = true
            }
        }
    }

    fun playDtmf(key: Char) {
        _currentCall.value?.let { call ->
            call.playDtmfTone(key)
            _dtmfKeysPlayed.value = _dtmfKeysPlayed.value + key
            scope.launch {
                delay(200)
                call.stopDtmfTone()
            }
        }
    }

    fun clearDtmf() {
        _dtmfKeysPlayed.value = ""
    }

    // Audio Call Recording Simulation
    fun toggleRecording(context: Context) {
        if (_isRecording.value) {
            val duration = _recordingDurationSec.value
            stopRecordingTimer()
            _isRecording.value = false
            Log.d(TAG, "Call recording stopped")
            if (duration > 0) {
                val recordId = "REC-" + java.util.UUID.randomUUID().toString().take(4).uppercase()
                val numberOrName = if (_callerName.value.isNotEmpty() && _callerName.value != "Unknown Caller") {
                    _callerName.value
                } else if (_callerNumber.value.isNotEmpty()) {
                    _callerNumber.value
                } else {
                    "Simulated Entry"
                }
                val newRecording = CallRecording(
                    id = recordId,
                    callerNameOrNumber = numberOrName,
                    timestamp = System.currentTimeMillis(),
                    durationSec = duration,
                    audioPathSimulated = "rec_$recordId.wav"
                )
                _recordingsList.value = listOf(newRecording) + _recordingsList.value
            }
        } else {
            _isRecording.value = true
            _recordingDurationSec.value = 0
            startRecordingTimer()
            Log.d(TAG, "Call recording started")
        }
    }

    fun deleteRecording(id: String) {
        _recordingsList.value = _recordingsList.value.filter { it.id != id }
    }

    private fun startTimer() {
        if (timerJob == null || timerJob?.isCompleted == true) {
            _callDurationSec.value = 0
            timerJob = scope.launch(Dispatchers.Main) {
                while (true) {
                    delay(1000)
                    _callDurationSec.value = _callDurationSec.value + 1
                    inCallService?.let { context ->
                        val stateText = if (_isHeld.value) "HOLDING" else "ACTIVE - ${_callDurationSec.value}s"
                        showCallNotification(context, stateText)
                    }
                }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun startRecordingTimer() {
        if (recordingTimerJob == null || recordingTimerJob?.isCompleted == true) {
            recordingTimerJob = scope.launch(Dispatchers.Main) {
                while (true) {
                    delay(1000)
                    _recordingDurationSec.value = _recordingDurationSec.value + 1
                    inCallService?.let { context ->
                        val stateText = if (_isHeld.value) "HOLDING" else "ACTIVE - ${_callDurationSec.value}s"
                        showCallNotification(context, stateText)
                    }
                }
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
    }

    // Resolve caller name and profile contact image
    fun resolveCallerDetails(context: Context, number: String) {
        if (number.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            var resolvedName = "Unknown Caller"
            var resolvedPhotoUri: String? = null
            try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
                )
                val projection = arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI
                )
                val cursor = context.contentResolver.query(uri, projection, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                        val photoIndex = it.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI)
                        if (nameIndex >= 0) {
                            resolvedName = it.getString(nameIndex) ?: resolvedName
                        }
                        if (photoIndex >= 0) {
                            resolvedPhotoUri = it.getString(photoIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error resolving caller: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                _callerName.value = resolvedName
                _callerPhotoUri.value = resolvedPhotoUri
            }
        }
    }

    fun detectActiveSimAndCarriers(context: Context) {
        scope.launch(Dispatchers.IO) {
            var simCarrier = "Primary SIM"
            try {
                val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                subscriptionManager?.let { sm ->
                    val activeList = sm.activeSubscriptionInfoList
                    if (!activeList.isNullOrEmpty()) {
                        val first = activeList.firstOrNull()
                        val carrierName = first?.displayName?.toString() ?: "Carrier"
                        val simSlot = (first?.simSlotIndex?.plus(1)) ?: 1
                        simCarrier = "SIM $simSlot ($carrierName)"
                    }
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission missing for SIM queries: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error retrieving active SIMs: ${e.message}")
            }
            withContext(Dispatchers.Main) {
                _activeSimCarrier.value = simCarrier
            }
        }
    }
}

data class CallRecording(
    val id: String,
    val callerNameOrNumber: String,
    val timestamp: Long,
    val durationSec: Int,
    val audioPathSimulated: String
)

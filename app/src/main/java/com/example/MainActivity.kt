package com.example

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.telecom.*
import com.example.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val TAG = "PhoneX_MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                PhoneXApp()
            }
        }
    }
}

@Composable
fun PhoneXApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Screen selection
    var currentTab by remember { mutableStateOf(0) } // 0 = Dialer, 1 = History, 2 = Contacts

    // State observations
    val activeCall by CallManager.currentCall.collectAsStateWithLifecycle()
    val callState by CallManager.callStateValue.collectAsStateWithLifecycle()
    val callerNumber by CallManager.callerNumber.collectAsStateWithLifecycle()
    val callerName by CallManager.callerName.collectAsStateWithLifecycle()
    val callerPhotoUri by CallManager.callerPhotoUri.collectAsStateWithLifecycle()
    val isMuted by CallManager.isMuted.collectAsStateWithLifecycle()
    val isSpeakerOn by CallManager.isSpeakerOn.collectAsStateWithLifecycle()
    val isHeld by CallManager.isHeld.collectAsStateWithLifecycle()
    val callDuration by CallManager.callDurationSec.collectAsStateWithLifecycle()
    val isRecording by CallManager.isRecording.collectAsStateWithLifecycle()
    val recordDuration by CallManager.recordingDurationSec.collectAsStateWithLifecycle()
    val dtmfKeys by CallManager.dtmfKeysPlayed.collectAsStateWithLifecycle()
    val simCarrier by CallManager.activeSimCarrier.collectAsStateWithLifecycle()
    val recordingsList by CallManager.recordingsList.collectAsStateWithLifecycle()
    val isAutoRecording by CallManager.isAutoRecording.collectAsStateWithLifecycle()

    // Dialer Number State
    var dialNumber by remember { mutableStateOf("") }
    var searchMatchedContactName by remember { mutableStateOf("") }

    // System Status
    var isDefaultDialer by remember { mutableStateOf(false) }
    var contactsList by remember { mutableStateOf<List<PhoneXContact>>(emptyList()) }
    var callLogsList by remember { mutableStateOf<List<PhoneXCallLog>>(emptyList()) }

    // Permissions
    val permissionsToRequest = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.SEND_SMS,
        "android.permission.POST_NOTIFICATIONS"
    )

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.forEach { (permission, granted) ->
            if (!granted) {
                Log.w("PhoneX", "Permission denied: $permission")
                allGranted = false
            }
        }
        // Load data upon permission response
        contactsList = ContactRepository.fetchAllContacts(context)
        callLogsList = CallLogRepository.fetchCallLogs(context)
    }

    // Role request launcher
    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        isDefaultDialer = checkIsDefaultDialer(context)
        if (isDefaultDialer) {
            Toast.makeText(context, "PhoneX registered as Default Dialer", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Default Dialer role is needed for real call management", Toast.LENGTH_LONG).show()
        }
    }

    // Initial setups and resumes
    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
        isDefaultDialer = checkIsDefaultDialer(context)
        CallManager.loadSettings(context)
        CallManager.detectActiveSimAndCarriers(context)
        contactsList = ContactRepository.fetchAllContacts(context)
        callLogsList = CallLogRepository.fetchCallLogs(context)
    }

    // Refresh lists dynamically on tab switches
    LaunchedEffect(currentTab) {
        contactsList = ContactRepository.fetchAllContacts(context)
        callLogsList = CallLogRepository.fetchCallLogs(context)
    }

    // Dynamic contact matching as the user dials
    LaunchedEffect(dialNumber) {
        if (dialNumber.isNotEmpty()) {
            val normalizedDial = dialNumber.replace("[\\s\\-\\(\\)]".toRegex(), "")
            val matched = contactsList.firstOrNull {
                it.phoneNumber.replace("[\\s\\-\\(\\)]".toRegex(), "").contains(normalizedDial) ||
                it.phoneNumber.contains(dialNumber)
            }
            searchMatchedContactName = matched?.name ?: ""
        } else {
            searchMatchedContactName = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CyberBlack)
    ) {
        // Glowing Space Dust Wallpaper (Canvas)
        SpaceDustBackground()

        Scaffold(
            containerColor = Color.Transparent,
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PHONEX",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = CyberCyan,
                            modifier = Modifier.testTag("app_title_header")
                        )

                        // Carrier indicator tag with subtle glass styling
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(GlassTint)
                                .border(1.dp, GlassBorder, RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = simCarrier,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = GlassTranslucentText
                            )
                        }
                    }

                    // Default Dialer Role status banner
                    if (!isDefaultDialer) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x22F50057))
                                .border(1.dp, Color(0x66F50057), RoundedCornerShape(12.dp))
                                .clickable {
                                    requestDefaultDialerRole(context, roleLauncher)
                                }
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "System Authorization Required",
                                        color = CyberMagenta,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Tap to make PhoneX your Default Dialer so carrier network calls operate correctly.",
                                        color = Color.White.copy(alpha = 0.8f),
                                        fontSize = 11.sp
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(CyberMagenta)
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "GRANT",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                // Glassmorphic Cyber Bottom Navigation
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(GlassOverlay)
                        .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val items = listOf(
                            NavigationTabItem("DIALER", 0),
                            NavigationTabItem("HISTORY", 1),
                            NavigationTabItem("CONTACTS", 2),
                            NavigationTabItem("SETTINGS", 3)
                        )
                        items.forEach { tab ->
                            val active = currentTab == tab.index
                            val colorAnimation by animateColorAsState(
                                targetValue = if (active) CyberCyan else Color.White.copy(alpha = 0.5f),
                                label = "nav_color"
                            )
                            Box(
                                modifier = Modifier
                                    .testTag("nav_tab_${tab.title.lowercase()}")
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { currentTab = tab.index }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = tab.title,
                                        color = colorAnimation,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Black else FontWeight.Normal,
                                        fontFamily = FontFamily.Monospace,
                                        letterSpacing = 1.sp
                                    )
                                    if (active) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp, 2.dp)
                                                .background(CyberCyan)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentTab) {
                    0 -> DialPadScreen(
                        dialNumber = dialNumber,
                        matchedName = searchMatchedContactName,
                        onDigitClick = { dialNumber += it },
                        onBackspace = { if (dialNumber.isNotEmpty()) dialNumber = dialNumber.dropLast(1) },
                        onClearAll = { dialNumber = "" },
                        onCallClick = {
                            if (dialNumber.isNotEmpty()) {
                                makeRealPhoneCall(context, dialNumber)
                            }
                        }
                    )
                    1 -> CallHistoryScreen(
                        logs = callLogsList,
                        recordings = recordingsList,
                        onCallClick = { makeRealPhoneCall(context, it) },
                        onDeleteLog = { id ->
                            CallLogRepository.deleteCallLog(context, id)
                            callLogsList = CallLogRepository.fetchCallLogs(context)
                        },
                        onClearAll = {
                            CallLogRepository.clearAllLogs(context)
                            callLogsList = CallLogRepository.fetchCallLogs(context)
                        },
                        onDeleteRecording = { id ->
                            CallManager.deleteRecording(id)
                        }
                    )
                    2 -> ContactsScreen(
                        contacts = contactsList,
                        onCallClick = { makeRealPhoneCall(context, it) },
                        onAddContact = { name, phone ->
                            val success = ContactRepository.addNewContact(context, name, phone)
                            if (success) {
                                contactsList = ContactRepository.fetchAllContacts(context)
                                Toast.makeText(context, "$name Added!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Failed to save. Write permission required.", Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                    3 -> SettingsScreen(
                        isAutoRecording = isAutoRecording,
                        onAutoRecordingChange = { enabled ->
                            CallManager.setAutoRecording(context, enabled)
                        }
                    )
                }
            }
        }

        // Active Fullscreen Calling HUD
        if (activeCall != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CyberBlack)
                    .testTag("active_call_overlay")
            ) {
                // Real-time contact background wallpaper during connection
                if (!callerPhotoUri.isNullOrEmpty()) {
                    AsyncImage(
                        model = callerPhotoUri,
                        contentDescription = "Caller wallpaper",
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.18f)
                            .blur(20.dp)
                    )
                }

                ActiveCallHudScreen(
                    number = callerNumber,
                    name = callerName,
                    state = callState,
                    isMuted = isMuted,
                    isSpeakerOn = isSpeakerOn,
                    isHeld = isHeld,
                    duration = callDuration,
                    isRecording = isRecording,
                    recordDuration = recordDuration,
                    dtmfKeys = dtmfKeys,
                    carrier = simCarrier,
                    onAccept = { CallManager.answerCall() },
                    onDecline = { CallManager.disconnectCall() },
                    onMuteToggle = { CallManager.setMute(!isMuted) },
                    onSpeakerToggle = { CallManager.setSpeakerOn(!isSpeakerOn) },
                    onHoldToggle = { CallManager.toggleHold() },
                    onRecordToggle = { CallManager.toggleRecording(context) },
                    onDtmfKey = { CallManager.playDtmf(it) },
                    onClearDtmf = { CallManager.clearDtmf() }
                )
            }
        }
    }
}

data class NavigationTabItem(val title: String, val index: Int)

@Composable
fun SpaceDustBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "dust")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Cosmic dark center radial gradient
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF0F1B2A), CyberBlack),
                center = center,
                radius = width * 0.9f
            )
        )

        // Geometric grid overlay representing futuristic networks
        val gridStep = 60.dp.toPx()
        for (x in 0..(width / gridStep).toInt()) {
            drawLine(
                color = CyberCyan.copy(alpha = 0.04f),
                start = androidx.compose.ui.geometry.Offset(x * gridStep, 0f),
                end = androidx.compose.ui.geometry.Offset(x * gridStep, height),
                strokeWidth = 1f
            )
        }
        for (y in 0..(height / gridStep).toInt()) {
            drawLine(
                color = CyberCyan.copy(alpha = 0.04f),
                start = androidx.compose.ui.geometry.Offset(0f, y * gridStep),
                end = androidx.compose.ui.geometry.Offset(width, y * gridStep),
                strokeWidth = 1f
            )
        }
    }
}

@Composable
fun DialPadScreen(
    dialNumber: String,
    matchedName: String,
    onDigitClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onClearAll: () -> Unit,
    onCallClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Dialer Display Card with glassmorphism styling
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(GlassTint)
                .border(1.dp, GlassBorder, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Matched Caller name indicator (instantly matching Contacts database during typing)
                AnimatedVisibility(
                    visible = matchedName.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        text = "MATCH: $matchedName",
                        color = CyberCyan,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Styled Number Text Area
                    Text(
                        text = dialNumber.ifEmpty { "ENTER NUMBER" },
                        fontSize = if (dialNumber.length > 12) 24.sp else 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (dialNumber.isEmpty()) Color.White.copy(alpha = 0.25f) else Color.White,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dialer_display_text")
                    )

                    if (dialNumber.isNotEmpty()) {
                        IconButton(
                            onClick = onBackspace,
                            modifier = Modifier.testTag("backspace_key")
                        ) {
                            Text(
                                "⌫",
                                color = CyberCyan,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Standard Dialer Keys 1-9, *, 0, #
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("*", "0", "#")
        )

        Column(
            modifier = Modifier.wrapContentHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            keys.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    row.forEach { key ->
                        DialKey(
                            digit = key,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dialkey_$key"),
                            onClick = {
                                onDigitClick(key)
                                CallManager.playDtmf(key[0])
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Large glowing cyan central Dial Out Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dialNumber.isNotEmpty()) {
                IconButton(
                    onClick = onClearAll,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(GlassOverlay)
                        .border(1.dp, GlassBorder, CircleShape)
                        .testTag("clear_all_dial_key")
                ) {
                    Text("🗑", color = CyberMagenta, fontSize = 20.sp)
                }
                Spacer(modifier = Modifier.width(32.dp))
            }

            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(CyberGreen)
                    .border(2.dp, CyberCyan, CircleShape)
                    .clickable { onCallClick() }
                    .testTag("call_action_button"),
                contentAlignment = Alignment.Center
            ) {
                // Line graphics inside button
                Canvas(modifier = Modifier.size(28.dp)) {
                    // Receiver glyph drawing
                    val path = Path().apply {
                        moveTo(6.dp.toPx(), 4.dp.toPx())
                        quadraticTo(3.dp.toPx(), 10.dp.toPx(), 10.dp.toPx(), 18.dp.toPx())
                        quadraticTo(18.dp.toPx(), 25.dp.toPx(), 24.dp.toPx(), 22.dp.toPx())
                        lineTo(22.dp.toPx(), 16.dp.toPx())
                        lineTo(17.dp.toPx(), 18.dp.toPx())
                        quadraticTo(12.dp.toPx(), 12.dp.toPx(), 10.dp.toPx(), 11.dp.toPx())
                        lineTo(12.dp.toPx(), 6.dp.toPx())
                        close()
                    }
                    drawPath(
                        path = path,
                        color = Color.Black,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }
        }
    }
}

@Suppress("DEPRECATION")
@Composable
fun DialKey(
    digit: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val subtitle = when (digit) {
        "2" -> "A B C"
        "3" -> "D E F"
        "4" -> "G H I"
        "5" -> "J K L"
        "6" -> "M N O"
        "7" -> "P R S"
        "8" -> "T U V"
        "9" -> "W X Y"
        "0" -> "+"
        else -> ""
    }

    Box(
        modifier = modifier
            .aspectRatio(1.5f)
            .clip(RoundedCornerShape(16.dp))
            .background(GlassTint)
            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = digit,
                fontSize = 24.sp,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    color = GlassTranslucentText,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
fun CallHistoryScreen(
    logs: List<PhoneXCallLog>,
    recordings: List<CallRecording>,
    onCallClick: (String) -> Unit,
    onDeleteLog: (String) -> Unit,
    onClearAll: () -> Unit,
    onDeleteRecording: (String) -> Unit
) {
    var selectedSubTab by remember { mutableStateOf(0) } // 0 = Recent Calls, 1 = Audio Recordings
    var currentlyPlayingId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        // Dual Sub-Tabs Slider (Recent Calls | Secure Recordings)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(GlassOverlay)
                .border(1.dp, GlassBorder, RoundedCornerShape(14.dp)),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            val tabs = listOf("RECIENTS", "CALL RECORDINGS")
            tabs.forEachIndexed { index, title ->
                val active = selectedSubTab == index
                val colorAnimation by animateColorAsState(
                    targetValue = if (active) CyberCyan else Color.White.copy(alpha = 0.5f),
                    label = "sub_tab_color"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) GlassTint else Color.Transparent)
                        .clickable { selectedSubTab = index }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        color = colorAnimation,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Black else FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (selectedSubTab == 0) {
            // Recent Calls Block
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CARRIER CHANNEL LOGS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = CyberCyan
                )

                if (logs.isNotEmpty()) {
                    Text(
                        text = "CLEAR ALL",
                        fontSize = 11.sp,
                        color = CyberMagenta,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable { onClearAll() }
                            .padding(8.dp)
                            .testTag("clear_all_logs_btn")
                    )
                }
            }

            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No call records. Connection logs cleared.",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(logs) { log ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(GlassTint)
                                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                                .padding(14.dp)
                                .testTag("log_item_${log.id}")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val typeText = when (log.type) {
                                            1 -> "↙ INCOMING"
                                            2 -> "↗ OUTGOING"
                                            3 -> "❌ MISSED"
                                            else -> "↙ REJECTED"
                                        }
                                        val typeColor = when (log.type) {
                                            1 -> CyberGreen
                                            2 -> CyberCyan
                                            3 -> CyberMagenta
                                            else -> CyberYellow
                                        }
                                        Text(
                                            text = typeText,
                                            fontSize = 10.sp,
                                            color = typeColor,
                                            fontWeight = FontWeight.Black,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = log.name ?: log.number,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    if (log.name != null) {
                                        Text(
                                            text = log.number,
                                            fontSize = 12.sp,
                                            color = GlassTranslucentText
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val format = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                                    Text(
                                        text = "${format.format(Date(log.date))}  •  ${log.duration}s",
                                        fontSize = 11.sp,
                                        color = Color.White.copy(alpha = 0.4f)
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { onCallClick(log.number) },
                                        modifier = Modifier.testTag("dial_log_call_${log.id}")
                                    ) {
                                        Text("📞", color = CyberGreen, fontSize = 20.sp)
                                    }
                                    IconButton(
                                        onClick = { onDeleteLog(log.id) }
                                    ) {
                                        Text("✕", color = CyberMagenta.copy(alpha = 0.6f), fontSize = 16.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Secure Audio Recordings Block
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SECURE BIOPHONIC RAW FILES",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = CyberMagenta
                )
            }

            if (recordings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No audio recorded. Toggle RECORD during a call.",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(recordings) { rec ->
                        val isPlaying = currentlyPlayingId == rec.id
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(GlassTint)
                                .border(
                                    width = 1.dp,
                                    color = if (isPlaying) CyberMagenta.copy(alpha = 0.6f) else GlassBorder,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable {
                                    currentlyPlayingId = if (isPlaying) null else rec.id
                                }
                                .padding(14.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = rec.callerNameOrNumber,
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        val form = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())
                                        Text(
                                            text = "${form.format(Date(rec.timestamp))}  •  ${rec.durationSec}s",
                                            fontSize = 11.sp,
                                            color = Color.White.copy(alpha = 0.4f)
                                        )
                                    }

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // Floating Action Core Indicators (Play state indicator icon)
                                        IconButton(onClick = {
                                            currentlyPlayingId = if (isPlaying) null else rec.id
                                        }) {
                                            Text(
                                                text = if (isPlaying) "⏸" else "▶",
                                                color = if (isPlaying) CyberMagenta else CyberCyan,
                                                fontSize = 20.sp
                                            )
                                        }
                                        IconButton(onClick = {
                                            if (isPlaying) currentlyPlayingId = null
                                            onDeleteRecording(rec.id)
                                        }) {
                                            Text("✕", color = Color.White.copy(alpha = 0.3f), fontSize = 16.sp)
                                        }
                                    }
                                }

                                // Interactive animated audio waveform!
                                AnimatedVisibility(visible = isPlaying) {
                                    Column(modifier = Modifier.padding(top = 10.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(Color.Black.copy(alpha = 0.5f))
                                                .border(1.dp, CyberMagenta.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            InteractivePlaybackWaveform()
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "ATMOSPHERIC DECRYPTION...",
                                                color = CyberMagenta.copy(alpha = 0.8f),
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Black
                                            )
                                            Text(
                                                text = rec.audioPathSimulated,
                                                color = Color.White.copy(alpha = 0.3f),
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InteractivePlaybackWaveform() {
    val infiniteTransition = rememberInfiniteTransition(label = "playback_wave_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase_val"
    )

    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val points = 45
        val step = width / points

        for (layer in 0 until 3) {
            val path = Path()
            path.moveTo(0f, centerY)
            val color = when (layer) {
                0 -> CyberCyan
                1 -> CyberMagenta.copy(alpha = 0.7f)
                else -> CyberYellow.copy(alpha = 0.4f)
            }
            val amp = 12.dp.toPx() - (layer * 3.dp.toPx())
            val freq = 0.08f + (layer * 0.03f)

            for (i in 0..points) {
                val x = i * step
                val sinVal = kotlin.math.sin(i * freq - phase + (layer * 1.5f))
                val y = centerY + sinVal * amp
                path.lineTo(x, y)
            }

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 1.6.dp.toPx())
            )
        }
    }
}

@Composable
fun ContactsScreen(
    contacts: List<PhoneXContact>,
    onCallClick: (String) -> Unit,
    onAddContact: (String, String) -> Unit
) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredContacts = remember(contacts, searchQuery) {
        contacts.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.phoneNumber.contains(searchQuery)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SECURE REGISTER",
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = CyberCyan
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyberCyan)
                    .clickable { showAddDialog = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("add_contact_action_btn")
            ) {
                Text(
                    text = "+ ADD CONTACT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.Black
                )
            }
        }

        // Futuristic Glassmorphism Search Field
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("SEARCH BIOMETRIC INDEX...", color = Color.White.copy(alpha = 0.3f), fontSize = 13.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(GlassTint)
                .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                .testTag("contact_search_bar"),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (filteredContacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No contact match found.",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 14.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredContacts) { contact ->
                    var isExpanded by remember { mutableStateOf(false) }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(GlassTint)
                            .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                            .clickable { isExpanded = !isExpanded }
                            .padding(14.dp)
                            .testTag("contact_chip_${contact.id}")
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Photo thumbnail or Glow placeholder
                                    if (contact.photoUri != null) {
                                        AsyncImage(
                                            model = contact.photoUri,
                                            contentDescription = "Contact photo",
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(CircleShape)
                                                .background(CyberBlue.copy(alpha = 0.2f))
                                                .border(1.dp, CyberBlue, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = contact.name.take(1).uppercase(),
                                                color = CyberCyan,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(14.dp))
                                    Column {
                                        Text(
                                            text = contact.name,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Text(
                                            text = contact.phoneNumber,
                                            fontSize = 13.sp,
                                            color = GlassTranslucentText
                                        )
                                    }
                                }

                                Text(
                                    text = if (isExpanded) "▲" else "▼",
                                    color = CyberCyan.copy(alpha = 0.6f),
                                    fontSize = 12.sp
                                )
                            }

                            AnimatedVisibility(visible = isExpanded) {
                                Column {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 12.dp),
                                        color = GlassBorder
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceAround
                                    ) {
                                        // Mapped button actions with ripples
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(GlassTint)
                                                .clickable { onCallClick(contact.phoneNumber) }
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                                .testTag("call_contact_${contact.id}")
                                        ) {
                                            Text("CALL 📞", color = CyberGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(GlassTint)
                                                .clickable {
                                                    // Quick SMS Dispatch
                                                    val smsUri = Uri.parse("smsto:${contact.phoneNumber}")
                                                    val intent = Intent(Intent.ACTION_SENDTO, smsUri)
                                                    context.startActivity(intent)
                                                }
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            Text("SEND SMS 💬", color = CyberCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var newName by remember { mutableStateOf("") }
        var newPhone by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = CyberGrayDark,
            tonalElevation = 6.dp,
            title = {
                Text(
                    "CREATE BIOMETRIC CONTACT",
                    color = CyberCyan,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Display Name", color = CyberCyan) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = CyberGrayLight,
                            unfocusedContainerColor = CyberGrayLight,
                        ),
                        modifier = Modifier.testTag("add_name_field")
                    )

                    TextField(
                        value = newPhone,
                        onValueChange = { newPhone = it },
                        label = { Text("Phone Number", color = CyberCyan) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = CyberGrayLight,
                            unfocusedContainerColor = CyberGrayLight,
                        ),
                        modifier = Modifier.testTag("add_phone_field")
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotEmpty() && newPhone.isNotEmpty()) {
                            onAddContact(newName, newPhone)
                            showAddDialog = false
                        }
                    },
                    modifier = Modifier.testTag("save_contact_btn")
                ) {
                    Text("SAVE TO CHIP", color = CyberGreen, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("ABORT", color = CyberMagenta)
                }
            }
        )
    }
}

@Composable
fun ActiveCallHudScreen(
    number: String,
    name: String,
    state: Int,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    isHeld: Boolean,
    duration: Int,
    isRecording: Boolean,
    recordDuration: Int,
    dtmfKeys: String,
    carrier: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onMuteToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onHoldToggle: () -> Unit,
    onRecordToggle: () -> Unit,
    onDtmfKey: (Char) -> Unit,
    onClearDtmf: () -> Unit
) {
    var showKeypadInCall by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Upper Column: Connected State details and Dynamic Wave pulsing
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(160.dp)
            ) {
                // Interactive cellular waves
                PulsingWaveAnimation(isActive = state == Call.STATE_ACTIVE)

                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(GlassOverlay)
                        .border(2.dp, CyberCyan, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = name.take(1).uppercase().ifEmpty { "P" },
                        color = CyberCyan,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = name.ifEmpty { "Unknown Caller" },
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = number,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = GlassTranslucentText,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Pulse connection banner (glowing status tag)
            val connectionStateText = when (state) {
                Call.STATE_RINGING -> "INCOMING CALL..."
                Call.STATE_DIALING -> "DIALING CARRIER MATRIX..."
                Call.STATE_CONNECTING -> "ESTABLISHING CHANNELS..."
                Call.STATE_ACTIVE -> "ACTIVE CONNECTION"
                Call.STATE_HOLDING -> "ON HOLD"
                Call.STATE_DISCONNECTED -> "CALL ENDED"
                else -> "HOLDING CHANNEL..."
            }
            val statusColor = when (state) {
                Call.STATE_ACTIVE -> CyberGreen
                Call.STATE_RINGING -> CyberYellow
                Call.STATE_HOLDING -> CyberYellow
                else -> CyberCyan
            }

            Text(
                text = connectionStateText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                color = statusColor,
                letterSpacing = 1.5.sp
            )

            // Dynamic ticking timer
            if (state == Call.STATE_ACTIVE) {
                Spacer(modifier = Modifier.height(8.dp))
                val minutesString = (duration / 60).toString().padStart(2, '0')
                val secondsString = (duration % 60).toString().padStart(2, '0')
                Text(
                    text = "$minutesString:$secondsString",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }
        }

        // Mid-area: DTMF retractable keypad block
        AnimatedVisibility(visible = showKeypadInCall) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(GlassOverlay)
                    .border(1.dp, GlassBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "DTMF KEYS: $dtmfKeys",
                            fontSize = 12.sp,
                            color = CyberCyan,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "CLEAR",
                            fontSize = 10.sp,
                            color = CyberMagenta,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable { onClearDtmf() }
                                .padding(4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    val keys = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("*", "0", "#")
                    )
                    keys.forEach { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            row.forEach { char ->
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(GlassTint)
                                        .border(1.dp, GlassBorder, CircleShape)
                                        .clickable { onDtmfKey(char[0]) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = char, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Recording notification strip (displays live record ticks)
        AnimatedVisibility(visible = isRecording) {
            Row(
                modifier = Modifier
                    .padding(bottom = 14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x33F50057))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(CyberMagenta)
                )
                Spacer(modifier = Modifier.width(6.dp))
                val recMinutes = (recordDuration / 60).toString().padStart(2, '0')
                val recSeconds = (recordDuration % 60).toString().padStart(2, '0')
                Text(
                    text = "RECORDING BIOPHONIC WAVE: $recMinutes:$recSeconds",
                    color = CyberMagenta,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // Action Hub Controllers (Mute, Speaker, Hold, simulated call record, Keypad)
        Column(
            modifier = Modifier.wrapContentHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (state == Call.STATE_ACTIVE || state == Call.STATE_HOLDING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    InCallControllerButton(
                        label = "MUTE",
                        isActive = isMuted,
                        activeColor = CyberCyan,
                        symbol = "🎤",
                        onClick = onMuteToggle,
                        modifier = Modifier.testTag("in_call_mute_toggle")
                    )

                    InCallControllerButton(
                        label = "SPEAKER",
                        isActive = isSpeakerOn,
                        activeColor = CyberCyan,
                        symbol = "🔊",
                        onClick = onSpeakerToggle,
                        modifier = Modifier.testTag("in_call_speaker_toggle")
                    )

                    InCallControllerButton(
                        label = "HOLD",
                        isActive = isHeld,
                        activeColor = CyberYellow,
                        symbol = "⏸",
                        onClick = onHoldToggle,
                        modifier = Modifier.testTag("in_call_hold_toggle")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    InCallControllerButton(
                        label = "KEYPAD",
                        isActive = showKeypadInCall,
                        activeColor = CyberCyan,
                        symbol = "⌨",
                        onClick = { showKeypadInCall = !showKeypadInCall }
                    )

                    InCallControllerButton(
                        label = "RECORD",
                        isActive = isRecording,
                        activeColor = CyberMagenta,
                        symbol = "⏺",
                        onClick = onRecordToggle
                    )

                    InCallControllerButton(
                        label = "MERGE",
                        isActive = false,
                        activeColor = CyberCyan,
                        symbol = "🔗",
                        onClick = { }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Reject / End Call or Incoming Ring Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state == Call.STATE_RINGING) {
                    // Incoming Calls: Accept or Decline Keys
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(CyberGreen)
                            .border(1.dp, Color.White, CircleShape)
                            .clickable { onAccept() }
                            .testTag("incoming_accept_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📞", color = Color.Black, fontSize = 26.sp)
                    }

                    Spacer(modifier = Modifier.width(64.dp))

                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(CyberMagenta)
                            .border(1.dp, Color.White, CircleShape)
                            .clickable { onDecline() }
                            .testTag("incoming_decline_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("✖", color = Color.White, fontSize = 26.sp)
                    }
                } else {
                    // Single Outgoing Hanging Button
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(CyberMagenta)
                            .border(2.dp, CyberMagenta.copy(alpha = 0.5f), CircleShape)
                            .clickable { onDecline() }
                            .testTag("hangup_action_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(28.dp)) {
                            val path = Path().apply {
                                moveTo(2.dp.toPx(), 14.dp.toPx())
                                quadraticTo(14.dp.toPx(), 4.dp.toPx(), 26.dp.toPx(), 14.dp.toPx())
                                lineTo(22.dp.toPx(), 20.dp.toPx())
                                lineTo(17.dp.toPx(), 18.dp.toPx())
                                quadraticTo(14.dp.toPx(), 15.dp.toPx(), 14.dp.toPx(), 14.dp.toPx())
                                lineTo(12.dp.toPx(), 17.dp.toPx())
                                close()
                            }
                            drawPath(
                                path = path,
                                color = Color.White,
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PulsingWaveAnimation(isActive: Boolean) {
    if (!isActive) return

    val infiniteTransition = rememberInfiniteTransition(label = "pulse_weave")
    
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )
    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )
    val scaleFactor by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val baseRadius = size.minDimension / 2.3f * scaleFactor

        for (waveIdx in 0 until 4) {
            val path = Path()
            val pointsCount = 120
            val angleStep = (2 * Math.PI / pointsCount).toFloat()
            val color = when (waveIdx) {
                0 -> CyberGreen.copy(alpha = 0.8f)      // Primary voice peak
                1 -> CyberCyan.copy(alpha = 0.6f)       // Technical overtone
                2 -> CyberMagenta.copy(alpha = 0.4f)    // Sub-harmonic
                else -> CyberYellow.copy(alpha = 0.3f)   // Dynamic resonance
            }

            val waveFreq = 4 + waveIdx * 2
            val waveAmp = (8.dp.toPx() + waveIdx * 4.dp.toPx()) * (if (waveIdx % 2 == 0) 1 else -1)
            val currentPhase = if (waveIdx % 2 == 0) phase1 else phase2

            for (i in 0..pointsCount) {
                val angle = i * angleStep
                val voiceFluctuation = kotlin.math.sin(angle * waveFreq + currentPhase) * waveAmp
                val radius = baseRadius + voiceFluctuation
                val x = centerX + radius * kotlin.math.cos(angle)
                val y = centerY + radius * kotlin.math.sin(angle)

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }
            path.close()

            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

@Composable
fun InCallControllerButton(
    label: String,
    isActive: Boolean,
    activeColor: Color,
    symbol: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val fillTint by animateColorAsState(
        targetValue = if (isActive) activeColor.copy(alpha = 0.15f) else GlassTint,
        label = "fill_color"
    )
    val boundaryColor by animateColorAsState(
        targetValue = if (isActive) activeColor else GlassBorder,
        label = "border_color"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(fillTint)
                .border(1.dp, boundaryColor, CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = symbol,
                fontSize = 24.sp,
                color = if (isActive) activeColor else Color.White
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 9.sp,
            color = if (isActive) activeColor else GlassTranslucentText,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
    }
}

// Check if PhoneX holds the default dialer role on Android system currently
fun checkIsDefaultDialer(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) ?: false
    } else {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val defaultPackageName = telecomManager?.defaultDialerPackage
        defaultPackageName == context.packageName
    }
}

// Request ROLE_DIALER using RoleManager Q+ or standard telecom actions
fun requestDefaultDialerRole(context: Context, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            launcher.launch(intent)
        } else {
            fallbackDefaultDialerRequest(context)
        }
    } else {
        fallbackDefaultDialerRequest(context)
    }
}

fun fallbackDefaultDialerRequest(context: Context) {
    try {
        val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.e("PhoneX", "Failed fallback request: ${e.message}")
    }
}

// Trigger real cellular phone dial out
fun makeRealPhoneCall(context: Context, dialNumber: String) {
    if (dialNumber.isEmpty()) return

    val formattedUri = Uri.fromParts("tel", dialNumber, null)
    val hasCallPermission = context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    if (!hasCallPermission) {
        Toast.makeText(context, "Direct dial permission blocked. Attempting system dial redirection...", Toast.LENGTH_LONG).show()
        // Graceful redirecting fallback to standard DIAL intent
        val dialIntent = Intent(Intent.ACTION_DIAL, formattedUri)
        context.startActivity(dialIntent)
        return
    }

    try {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager != null && checkIsDefaultDialer(context)) {
            // Using modern placeCall direct API as our default role is active!
            val bundle = Bundle()
            telecomManager.placeCall(formattedUri, bundle)
            Log.d("PhoneX", "Standard placeCall executed for number: $dialNumber")
        } else {
            // Directly dials outgoing call immediately via system transceiver
            val callIntent = Intent(Intent.ACTION_CALL, formattedUri)
            context.startActivity(callIntent)
            Log.d("PhoneX", "ACTION_CALL intent triggered for number: $dialNumber")
        }

        // Standard caller log persistence
        CallLogRepository.addCallLog(context, dialNumber, 2, 0)
    } catch (e: SecurityException) {
        Log.e("PhoneX", "Direct placing call failed: Security exception ${e.message}")
        val dialIntent = Intent(Intent.ACTION_DIAL, formattedUri)
        context.startActivity(dialIntent)
    } catch (e: Exception) {
        Log.e("PhoneX", "Error initiating telecom carrier call: ${e.message}")
        Toast.makeText(context, "Cellular error: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun SettingsScreen(
    isAutoRecording: Boolean,
    onAutoRecordingChange: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Identity Header
        Spacer(modifier = Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(GlassTint)
                .border(1.5.dp, CyberGreen, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "R🪶",
                color = CyberGreen,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "RahuL Dialer Settings",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            text = "Transparent Glassmorphic Engine v2.0",
            fontSize = 11.sp,
            color = GlassTranslucentText.copy(alpha = 0.6f),
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(30.dp))

        // Glass Card Settings Wrapper
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(GlassOverlay)
                .border(1.dp, GlassBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🎙️",
                        fontSize = 22.sp,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Automatic Call Recording",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = if (isAutoRecording) "Currently set to AUTOMATIC" else "Currently set to MANUAL",
                            fontSize = 12.sp,
                            color = if (isAutoRecording) CyberGreen else GlassTranslucentText
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Mode Selector Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassTint)
                        .border(1.dp, GlassBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                    horizontalArrangement = Arrangement.SpaceAround
                ) {
                    val modes = listOf("MANUAL", "AUTOMATIC")
                    modes.forEachIndexed { idx, mode ->
                        val active = (idx == 0 && !isAutoRecording) || (idx == 1 && isAutoRecording)
                        val colorAni by animateColorAsState(
                            targetValue = if (active) CyberGreen else Color.White.copy(alpha = 0.5f),
                            label = "setting_active"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) Color.White.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable {
                                    onAutoRecordingChange(idx == 1)
                                }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = mode,
                                color = colorAni,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Info status panel
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(GlassTint)
                .border(1.dp, GlassBorder.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Text(
                text = "When Automatic Call Recording is active, the system starts raw biophonic capture immediately upon call connection. Otherwise, recording can be manually controlled using the RECORD toggle in the call HUD.",
                fontSize = 11.sp,
                color = GlassTranslucentText.copy(alpha = 0.8f),
                lineHeight = 16.sp
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Bottom Brand
        Text(
            text = "DECRYPTED TELEPHONY SYSTEM BY RAHUL",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = CyberGreen.copy(alpha = 0.6f),
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

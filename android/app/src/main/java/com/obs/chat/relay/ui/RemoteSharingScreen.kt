package com.obs.chat.relay.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obs.chat.relay.OBSChatRelayApp
import com.obs.chat.relay.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun RemoteSharingScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { OBSChatRelayApp.instance.controller }
    val transmitter = controller.remoteTransmitter
    val prefs = controller.prefs

    val isRemoteConnected by transmitter.isRemoteConnected.collectAsState()
    val connectionStatus by transmitter.connectionStatus.collectAsState()

    var remoteEnabled by remember { mutableStateOf(prefs.remoteForwardingEnabled) }
    var targetHost by remember { mutableStateOf(prefs.remoteTargetHost) }
    var roomCode by remember { mutableStateOf(prefs.cloudRoomCode) }

    var testTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }

    val effectiveRoom = roomCode.ifBlank { "steushio_live" }
    val obsDockUrl = "https://steushio.github.io/OBS-chat-dock-relay/?room=$effectiveRoom"

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header
        item {
            Text(
                "Remote Network Sharing",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Stream chat & alerts over 5G cellular data to your PC OBS overlay online. No need to be on the same Wi-Fi!",
                fontSize = 12.sp,
                color = TextMuted
            )
        }

        // 2. PRIMARY CARD: Free Cloud Room (Zero PC apps required)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "🌐 Streamer Cloud Room",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = KickGreen
                        )
                        Surface(
                            color = Color(0x2253FC18),
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, KickGreen)
                        ) {
                            Text(
                                "5G / Internet",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = KickGreen,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Zero PC apps needed. OBS receives chat directly via the internet from your phone.",
                        fontSize = 11.sp,
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Room Code with integrated trailing Randomize button
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = {
                            roomCode = it
                            prefs.cloudRoomCode = it
                        },
                        label = { Text("Streamer Cloud Room Code") },
                        placeholder = { Text("e.g. live_892104 or my_channel", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = KickGreen,
                            unfocusedBorderColor = BorderDark
                        ),
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    val newCode = "live_" + (100000..999999).random()
                                    roomCode = newCode
                                    prefs.cloudRoomCode = newCode
                                    Toast.makeText(context, "Generated new Room: $newCode", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text("🎲 New", color = KickGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // OBS Dock URL Surface
                    Surface(
                        color = BgDark,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Your OBS Custom Browser Dock URL:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = obsDockUrl,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = KickGreen
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Perfectly aligned action buttons row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                try {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("OBS Dock URL", obsDockUrl)
                                    clipboard?.setPrimaryClip(clip)
                                    Toast.makeText(context, "📋 Copied OBS Dock Link to Clipboard!", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Copy OBS Link", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    transmitter.forwardEvent("chat", mapOf(
                                        "platform" to "youtube",
                                        "username" to "System Test",
                                        "message" to "🎉 Cloud Room Connected! Streaming over 5G data."
                                    ))
                                    Toast.makeText(context, "Sent test to Cloud Room: $effectiveRoom", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = KickGreen, contentColor = Color.Black),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Send Test Chat", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        // 3. SECONDARY CARD: Direct PC WebSocket / Local Tunnel (Optional)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "⚙️ Direct PC WebSocket (Optional)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMain
                        )

                        Switch(
                            checked = remoteEnabled,
                            onCheckedChange = {
                                remoteEnabled = it
                                prefs.remoteForwardingEnabled = it
                                if (it) {
                                    transmitter.start()
                                } else {
                                    transmitter.stop()
                                }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        if (remoteEnabled)
                            "Connecting directly to your PC IP or localtunnel server."
                        else
                            "Disabled. (You are using the Free Cloud Room above).",
                        fontSize = 11.sp,
                        color = TextMuted
                    )

                    if (remoteEnabled) {
                        Spacer(modifier = Modifier.height(10.dp))

                        // Status Row
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isRemoteConnected) KickGreen else Color.Yellow)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = connectionStatus.uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = if (isRemoteConnected) KickGreen else Color.Yellow,
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Host Field with Paste Trailing Icon
                        OutlinedTextField(
                            value = targetHost,
                            onValueChange = {
                                targetHost = it
                                prefs.remoteTargetHost = it
                            },
                            label = { Text("PC Host / Tunnel URL") },
                            placeholder = { Text("e.g. 192.168.1.10:3000", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = BorderDark
                            ),
                            trailingIcon = {
                                TextButton(
                                    onClick = {
                                        try {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                            val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                                            if (!clip.isNullOrBlank()) {
                                                targetHost = clip
                                                prefs.remoteTargetHost = clip
                                                prefs.remoteForwardingEnabled = true
                                                remoteEnabled = true
                                                transmitter.start()
                                                Toast.makeText(context, "Pasted: $clip", Toast.LENGTH_SHORT).show()
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Clipboard error: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp)
                                ) {
                                    Text("📋 Paste", fontSize = 11.sp, color = AccentBlue)
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    com.obs.chat.relay.util.QrCodeScannerHelper.scanQrCode(
                                        context = context,
                                        onSuccess = { targetUrl, _ ->
                                            targetHost = targetUrl
                                            prefs.remoteTargetHost = targetUrl
                                            prefs.remoteForwardingEnabled = true
                                            remoteEnabled = true
                                            transmitter.start()
                                            Toast.makeText(context, "Connected to: $targetUrl", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { err ->
                                            Toast.makeText(context, "Scan error: $err", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Scan QR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = {
                                    testTesting = true
                                    testResult = null
                                    scope.launch {
                                        val (success, msg) = transmitter.testConnection(targetHost)
                                        testTesting = false
                                        testSuccess = success
                                        testResult = msg
                                        if (success) {
                                            transmitter.start()
                                        }
                                    }
                                },
                                enabled = !testTesting && targetHost.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue, contentColor = Color.Black),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                if (testTesting) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.Black, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Test Ping", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        if (testResult != null) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                color = if (testSuccess) Color(0x2253FC18) else Color(0x22EF4444),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (testSuccess) KickGreen else AccentRed),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = testResult ?: "",
                                    color = if (testSuccess) KickGreen else AccentRed,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header Card
        item {
            Text(
                "Remote Network Sharing",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextMain
            )
            Text(
                "Transfer live stream chat & alerts over Mobile Data / Cellular to your PC OBS overlay online. No need to be on the same Wi-Fi!",
                fontSize = 12.sp,
                color = TextMuted
            )
        }

        // 2. Status Banner Card
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(if (isRemoteConnected) KickGreen else if (remoteEnabled) Color.Yellow else AccentRed)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = connectionStatus.uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = if (isRemoteConnected) KickGreen else if (remoteEnabled) Color.Yellow else AccentRed,
                                fontSize = 13.sp
                            )
                        }

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

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isRemoteConnected)
                            "Active: Mobile chat & alerts are being streamed online directly to your PC overlay."
                        else if (remoteEnabled)
                            "Connecting to your PC IP / Host. Check your target host and network."
                        else
                            "Remote transmitter is disabled. Enable switch to stream over mobile cellular data.",
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            }
        }

        // 3. Remote PC Target Configuration Card
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
                            "Target PC Overlay IP / Host",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMain
                        )

                        // 1-Tap QR Code Scan Button
                        Button(
                            onClick = {
                                com.obs.chat.relay.util.QrCodeScannerHelper.scanQrCode(
                                    context = context,
                                    onSuccess = { targetUrl, raw ->
                                        targetHost = targetUrl
                                        prefs.remoteTargetHost = targetUrl
                                        prefs.remoteForwardingEnabled = true
                                        remoteEnabled = true
                                        transmitter.start()
                                        Toast.makeText(context, "🎉 Scanned QR! Connected to: $targetUrl", Toast.LENGTH_LONG).show()
                                    },
                                    onError = { err ->
                                        Toast.makeText(context, "Scan error: $err", Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Scan QR", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        "Scan the QR code displayed in your OBS Custom Dock or paste the Cloud Tunnel URL (e.g. *.loca.lt). No router setup or Tailscale needed!",
                        fontSize = 11.sp,
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = targetHost,
                            onValueChange = {
                                targetHost = it
                                prefs.remoteTargetHost = it
                            },
                            placeholder = { Text("e.g. *.loca.lt or 192.168.1.10:3000", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentBlue,
                                unfocusedBorderColor = BorderDark
                            )
                        )

                        Button(
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
                                        Toast.makeText(context, "Pasted & Connecting: $clip", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Clipboard error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentPurple),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                        ) {
                            Text("📋 Paste", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        "Cloud Room (No PC relay app needed)",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = KickGreen
                    )
                    Text(
                        "OBS connects directly to your cloud room over the internet. Any streamer can use their own room.",
                        fontSize = 11.sp,
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = roomCode,
                            onValueChange = {
                                roomCode = it
                                prefs.cloudRoomCode = it
                            },
                            label = { Text("Streamer Cloud Room Code") },
                            placeholder = { Text("e.g. live_892104 or my_channel", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = KickGreen,
                                unfocusedBorderColor = BorderDark
                            )
                        )

                        Button(
                            onClick = {
                                val newCode = "live_" + (100000..999999).random()
                                roomCode = newCode
                                prefs.cloudRoomCode = newCode
                                Toast.makeText(context, "Generated new Room: $newCode", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BgDark),
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp)
                        ) {
                            Text("🎲 New", fontSize = 12.sp, color = TextMain, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val effectiveRoom = roomCode.ifBlank { "steushio_live" }
                    val obsDockUrl = "https://steushio.github.io/OBS-chat-dock-relay/?room=$effectiveRoom"

                    Surface(
                        color = BgDark,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, BorderDark, RoundedCornerShape(8.dp))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                "Your OBS Browser Dock URL:",
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

                    Spacer(modifier = Modifier.height(8.dp))

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
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("📋 Copy OBS Dock Link", fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("🚀 Send Test Chat", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Test Connection Button
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (testTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.Black, strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Testing Connection...")
                        } else {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test PC Overlay Connection", fontWeight = FontWeight.Bold)
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
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // 4. Quick Setup Guide Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderDark, RoundedCornerShape(16.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("💡 How Remote Sharing Works", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextMain)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "1. ZERO CONFIGURATION: Open OBS Studio and check the OBS Mobile Relay Plugin or open Custom Browser Dock 'http://127.0.0.1:3000/tunnel-dock.html'.\n\n" +
                                "2. NO ROUTER SETUP & NO TAILSCALE: The PC automatically creates a secure cloud tunnel address (e.g. *.loca.lt) with an instant QR code.\n\n" +
                                "3. Simply click 'Copy' on your PC or paste the address into this screen, and tap 'Test Connection'.\n\n" +
                                "4. Your OBS docks will instantly receive every chat message and alert in real-time, even while you are streaming outside on 4G/5G mobile cellular data!",
                        fontSize = 11.sp,
                        color = TextMuted,
                        lineHeight = 16.sp
                    )
                }
            }
        }
    }
}

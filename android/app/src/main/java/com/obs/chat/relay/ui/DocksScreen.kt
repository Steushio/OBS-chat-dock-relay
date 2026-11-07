package com.obs.chat.relay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.obs.chat.relay.OBSChatRelayApp
import com.obs.chat.relay.ui.components.DockWebView
import com.obs.chat.relay.ui.theme.*

@Composable
fun DocksScreen() {
    val controller = remember { OBSChatRelayApp.instance.controller }
    val port = controller.prefs.httpPort

    var selectedSubTab by remember { mutableIntStateOf(0) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val urls = listOf(
        "http://127.0.0.1:$port/chatdock.html",
        "http://127.0.0.1:$port/alertdock.html",
        "http://127.0.0.1:$port/eventdock.html"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabRow(
                selectedTabIndex = selectedSubTab,
                containerColor = BgDark,
                contentColor = TextMain,
                modifier = Modifier.weight(1f)
            ) {
                Tab(
                    selected = selectedSubTab == 0,
                    onClick = { selectedSubTab = 0 },
                    text = {
                        Text(
                            "💬 Chat",
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selectedSubTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedSubTab == 1,
                    onClick = { selectedSubTab = 1 },
                    text = {
                        Text(
                            "🔔 Alerts",
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selectedSubTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedSubTab == 2,
                    onClick = { selectedSubTab = 2 },
                    text = {
                        Text(
                            "📋 Events",
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selectedSubTab == 2) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }

            IconButton(
                onClick = { refreshKey++ },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Refresh Dock",
                    tint = AccentBlue,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        key(refreshKey, selectedSubTab) {
            DockWebView(
                url = urls[selectedSubTab],
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )
        }
    }
}

package com.example.obsqura.ui.scenario

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.obsqura.BLEConnectionManager

@Composable
fun ScenarioModeScreen(
    ble: BLEConnectionManager,
    onBack: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var secure by remember { mutableStateOf(true) }
    var mitmOn by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(onClick = onBack) { Text("← 뒤로") }
            Text("🎭 시나리오 모드", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(1.dp))
        }
        Spacer(Modifier.height(12.dp))

        Button(onClick = { ble.sendKyberRequestPacketized() }) { Text("🔐 공개키 요청") }

        Spacer(Modifier.height(16.dp))
        Row {
            FilterChip(selected = !secure, onClick = { secure = false }, label = { Text("Legacy") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = secure, onClick = { secure = true }, label = { Text("Secure") })
            Spacer(Modifier.width(12.dp))
            AssistChip(onClick = { mitmOn = !mitmOn }, label = { Text(if (mitmOn) "‼🔴 MITM ON" else "🔵 MITM OFF") })
        }

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("HELLO") })

        Spacer(Modifier.height(12.dp))
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            if (text.isBlank()) return@Button
            if (secure) ble.sendEncryptedTextMessage(text, mitmOn) else ble.sendPlainTextMessage(text, mitmOn)
            text = ""
        }) { Text("▶ 전송") }
    }
}

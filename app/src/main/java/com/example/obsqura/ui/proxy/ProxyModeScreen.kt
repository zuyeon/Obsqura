package com.example.obsqura.ui.proxy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.obsqura.BLEConnectionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyModeScreen(
    ble: BLEConnectionManager,
    onBack: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val canSend = text.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Proxy Mode") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // 상태 표시만 간단히
            Text(
                text = if (ble.proxyMode) "프록시 경유 모드 " else "프록시 모드 꺼짐",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("보낼 메세지") },
                singleLine = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { ble.sendPlainTextMessage(text) },
                    enabled = canSend,
                    modifier = Modifier.weight(1f)
                ) { Text("🆓 평문 전송") }

                Button(
                    onClick = { ble.sendEncryptedTextMessage(text) },
                    enabled = canSend, // 키 없으면 BLEConnectionManager가 토스트로 안내함
                    modifier = Modifier.weight(1f)
                ) { Text("🔒 암호 전송") }
            }


        }
    }
}

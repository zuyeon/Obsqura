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

    // 🔑 현재 프록시 세션(혹은 BLE 주소) 기준으로 암호 가능 여부
    val canEncrypt = ble.canEncryptNow()

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
            // 상태 표시
            Text(
                text = buildString {
                    append(if (ble.proxyMode) "프록시 경유 모드" else "프록시 모드 꺼짐")
                    append(" • 키: "); append(if (canEncrypt) "존재" else "없음")
                },
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
                    // ⬇️ 키 없으면 버튼 자체 비활성화
                    enabled = canSend && canEncrypt,
                    modifier = Modifier.weight(1f)
                ) { Text("🔒 암호 전송") }
            }

            // (선택) 안내 문구
            if (!canEncrypt) {
                Text(
                    "암호 전송은 공유키 합의 후 가능해요. (테스트/시나리오 모드에서 공개키 요청 실행 → 프록시로 전환)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

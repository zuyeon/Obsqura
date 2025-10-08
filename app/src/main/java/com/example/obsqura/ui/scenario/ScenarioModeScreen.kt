package com.example.obsqura.ui.scenario

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.obsqura.BLEConnectionManager
import com.example.obsqura.ui.theme.*   // PrimaryButton, AppDimens 등
import androidx.compose.ui.Alignment


@Composable
fun ScenarioModeScreen(
    ble: BLEConnectionManager,
    onBack: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    var secure by remember { mutableStateOf(true) }
    var mitmOn by remember { mutableStateOf(false) }

    Column(modifier = Modifier
        .fillMaxSize()
        .padding(AppDimens.ScreenPadding)
    ) {
        // 상단바: 뒤로가기 버튼 + 타이틀
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) {
                Text("←", fontSize = 20.sp)
            }

            Spacer(modifier = Modifier.width(160.dp))

            Text(
                "🎭 ATTACK",
                style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
        }


        Spacer(Modifier.height(12.dp))

        // 공개키 요청 버튼
        PrimaryButton(
            onClick = { ble.sendKyberRequestPacketized() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🔐 공개키 요청", fontSize = 18.sp)
        }

        Spacer(Modifier.height(AppDimens.GapLg))

        // 모드 선택 Chips
        Row {
            FilterChip(selected = !secure, onClick = { secure = false }, label = { Text("Legacy") })
            Spacer(Modifier.width(AppDimens.GapMd))
            FilterChip(selected = secure, onClick = { secure = true }, label = { Text("Secure") })
            Spacer(Modifier.width(AppDimens.GapLg))
            AssistChip(onClick = { mitmOn = !mitmOn }, label = { Text(if (mitmOn) "🔴 MITM ON(앱쪽변조)" else "🔵 MITM OFF") })
        }

        Spacer(Modifier.height(AppDimens.GapLg))

        // 입력 필드
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().height(200.dp),
            singleLine = true,
            placeholder = { Text("HELLO, WORLD!") }
        )

        Spacer(Modifier.height(AppDimens.GapMd))

        // 전송 버튼
        PrimaryButton(
            onClick = {
                if (text.isBlank()) return@PrimaryButton
                if (secure) {
                    ble.sendEncryptedTextMessage(text, mitmOn)
                } else {
                    ble.sendPlainTextMessage(text, mitmOn)
                }
                text = ""
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("▶ 전송", fontSize = 18.sp)
        }
    }
}



// app/src/debug/java/com/example/obsqura/ui/test/TestModeStaticPreview.kt
package com.example.obsqura.ui.test

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.obsqura.ui.theme.BLECommunicatorTheme

// ✅ BLE/블루투스/시스템 서비스 일절 사용 안 함 (순수 UI 미리보기용)

private val sampleDevices = listOf(
    "RPi-LED • D8:3A:DD:1E:53:AF (RSSI -48)",
    "ObsQura Demo #1 • 00:11:22:33:44:55 (RSSI -67)",
    "HM-10 • 00:1A:7D:DA:71:13 (RSSI -80)",
)

private val sampleLogs = listOf(
    "[INFO] 앱 시작",
    "[SCAN] 스캔 준비 완료",
    "[FOUND] RPi-LED (RSSI:-48)",
    "[GATT] 서비스 탐색 완료"
)

@Preview(
    name = "TestMode – Static (Phone Light)",
    device = Devices.PIXEL_6,
    showSystemUi = true, showBackground = true
)
@Preview(
    name = "TestMode – Static (Phone Dark)",
    device = Devices.PIXEL_6,
    showSystemUi = true, showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Preview(
    name = "TestMode – Static (Tablet)",
    device = Devices.PIXEL_C,
    showSystemUi = true, showBackground = true
)
@Composable
fun Preview_TestMode_Static() {
    BLECommunicatorTheme {
        var messageText by remember { mutableStateOf("") }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp)) {

                // 상단바(뒤로 + 타이틀)
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { /* no-op */ }) { Text("←", fontSize = 20.sp) }
                    Spacer(Modifier.width(90.dp))
                    Text(
                        "🔧 TEST",
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 22.sp
                    )
                    Spacer(Modifier.width(1.dp))
                }

                Spacer(Modifier.height(6.dp))

                // 섹션 타이틀
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "BLE 스캐너",
                        style = MaterialTheme.typography.headlineMedium,
                        fontSize = 24.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 스캔 버튼 (모양 확인용)
                Button(
                    onClick = { /* no-op */ },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = MaterialTheme.shapes.large
                ) { Text("🔍 BLE 디바이스 스캔", fontSize = 18.sp) }

                Spacer(Modifier.height(16.dp))

                // 디바이스 카드 리스트 (더미)
                LazyColumn(Modifier.weight(1f)) {
                    items(sampleDevices) { line ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            ),
                            elevation = CardDefaults.cardElevation(8.dp),
                            shape = MaterialTheme.shapes.extraLarge
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    text = line.substringBefore(" •"),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = line.substringAfter(" •"),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                Spacer(Modifier.height(10.dp))
                                Row {
                                    Button(
                                        onClick = { /* no-op */ },
                                        modifier = Modifier.weight(1f),
                                        shape = MaterialTheme.shapes.large
                                    ) { Text("🔌 연결") }

                                    Spacer(Modifier.width(8.dp))

                                    Button(
                                        onClick = { /* no-op */ },
                                        modifier = Modifier.weight(1f),
                                        shape = MaterialTheme.shapes.large
                                    ) { Text("🔌 해제") }

                                }

                                Spacer(Modifier.height(10.dp))

                                Button(
                                    onClick = { /* no-op */ },
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("🔐 공개키 요청") }

                                Spacer(Modifier.height(10.dp))

                                Button(
                                    onClick = { /* no-op */ },
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("📶 Ping 테스트") }

                                Spacer(Modifier.height(16.dp))
                                Text("✉️ 텍스트 전송", color = MaterialTheme.colorScheme.onSurface)

                                OutlinedTextField(
                                    value = messageText,
                                    onValueChange = { if (it.length <= 50) messageText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("보낼 메시지를 입력하세요") },
                                    singleLine = true
                                )

                                Text(
                                    text = "${messageText.length} / 50",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (messageText.length >= 50)
                                        MaterialTheme.colorScheme.error
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                )

                                Row {
                                    Button(
                                        onClick = { /* no-op */ },
                                        modifier = Modifier.weight(1f),
                                        shape = MaterialTheme.shapes.large
                                    ) { Text("📨 평문 전송") }

                                    Spacer(Modifier.width(8.dp))

                                    Button(
                                        onClick = { /* no-op */ },
                                        modifier = Modifier.weight(1f),
                                        shape = MaterialTheme.shapes.large
                                    ) { Text("🔒 암호 전송") }
                                }

                                Spacer(Modifier.height(12.dp))
                                Text("📄 공개키 (Base64):", color = MaterialTheme.colorScheme.onSurface)
                                Text(
                                    "BASE64_PUBLIC_KEY_SAMPLE==",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                                )

                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "📜 BLE 로그:",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                LazyColumn(Modifier.fillMaxWidth().height(120.dp)) {
                                    items(sampleLogs) { log ->
                                        Text(
                                            log,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 상단 수신 진행률 오버레이/전송 다이얼로그는
                // 실제 TestModeScreen에서 확인하면 되고, 여기선 UI만 본다.
            }
        }
    }
}

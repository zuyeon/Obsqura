package com.example.obsqura.ui.test

import android.bluetooth.BluetoothAdapter
import android.widget.Toast
import android.content.Intent
import android.annotation.SuppressLint
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import com.example.obsqura.BLEConnectionManager
import com.example.obsqura.CustomBluetoothDevice
import java.text.SimpleDateFormat
import java.util.*

@Composable
@SuppressLint("MissingPermission")
fun TestModeScreen(
    ble: BLEConnectionManager,
    bluetoothAdapter: BluetoothAdapter,
    hasScanPermission: () -> Boolean,
    isLocationEnabled: () -> Boolean,
    onRequestPermissions: () -> Unit,
    startBleScan: (onFound: (CustomBluetoothDevice) -> Unit) -> Unit,

    publicKeyBase64: String?,
    logMessages: List<String>,
    progressSent: Int,
    progressTotal: Int,
    showProgress: Boolean,

    recvProgressSent: Int,
    recvProgressTotal: Int,
    showRecvProgress: Boolean,

    onBack: () -> Unit
) {
    val context = LocalContext.current
    val pink = Color(0xFFE91E63)
    val green = Color(0xFF4CAF50)
    val lightGreenBg = Color(0xFFE8F5E9)

    var scannedDevices by remember { mutableStateOf<List<CustomBluetoothDevice>>(emptyList()) }
    var connectedDevice by remember { mutableStateOf<android.bluetooth.BluetoothDevice?>(null) }
    var connectedTime by remember { mutableStateOf<String?>(null) }
    var ledOn by remember { mutableStateOf(false) }
    var messageText by remember { mutableStateOf("") }

    // 🔹 오버레이를 위해 Box로 감싼다
    Box(modifier = Modifier.fillMaxSize()) {

        // ====== ⬇️⬇️⬇️ 여기부터 MainActivity의 UI 블록(Surface~Column~…~AlertDialog) 그대로 붙여넣기 ======
        //      단, 다음 3가지만 바꿔주세요:
        //      1) this@MainActivity → context
        //      2) bleConnectionManager → ble
        //      3) requestPermissionsIfNeeded() 호출 → onRequestPermissions()
        //      그 외 코드는 동일하게 둡니다.
        // --------------------------------------------------------------------------------------

        Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
            Column(modifier = Modifier.padding(16.dp)) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(onClick = onBack) { Text("← 뒤로") }
                    Text("🔧 일반 테스트 모드", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(1.dp))
                }
                Spacer(Modifier.height(12.dp))

                Text("BLE 스캐너", style = MaterialTheme.typography.headlineMedium.copy(color = pink))
                Spacer(modifier = Modifier.height(8.dp))

                connectedDevice?.let { device ->
                    Text("✅ Connected Device:", color = Color.Black)
                    Text("• Name: ${device.name ?: "Unknown"}", color = pink)
                    Text("• Address: ${device.address}", color = pink)
                    connectedTime?.let { Text("• Connected at: $it", color = pink) }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Button(
                    onClick = {
                        if (!hasScanPermission()) {
                            onRequestPermissions()
                            Toast.makeText(context, "스캔 권한이 없습니다.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!isLocationEnabled()) {
                            Toast.makeText(context, "휴대폰 위치 서비스를 켜주세요.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!bluetoothAdapter.isEnabled) {
                            context.startActivity(
                                android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            )
                            return@Button
                        }
                        scannedDevices = emptyList()
                        startBleScan { customDevice ->
                            scannedDevices = (scannedDevices + customDevice)
                                .distinctBy { it.device.address }
                                .sortedByDescending {
                                    it.displayName == "RPi-LED" ||
                                            it.device.address.uppercase() == "D8:3A:DD:1E:53:AF" ||
                                            it.device.address.uppercase() == "00:1A:7D:DA:71:13"
                                }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = pink)
                ) { Text("🔍 BLE 디바이스 스캔") }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(modifier = Modifier.fillMaxHeight().weight(1f)) {
                    items(scannedDevices) { customDevice ->
                        val isRPi = customDevice.displayName == "RPi-LED" ||
                                customDevice.device.address.uppercase() == "D8:3A:DD:1E:53:AF" ||
                                customDevice.device.address.uppercase() == "00:1A:7D:DA:71:13"

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isRPi) lightGreenBg else Color.White
                            ),
                            elevation = CardDefaults.cardElevation(6.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    "이름: ${customDevice.displayName} ${if (isRPi) "🌿 RPi" else ""}",
                                    color = if (isRPi) green else pink
                                )
                                Text(
                                    "주소: ${customDevice.device.address}",
                                    color = if (isRPi) green else Color.Black
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                Row {
                                    Button(
                                        onClick = {
                                            ble.connect(customDevice.device)
                                            connectedDevice = customDevice.device
                                            connectedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                        },
                                        modifier = Modifier.padding(end = 8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = pink)
                                    ) { Text("🔌 연결") }

                                    Button(
                                        onClick = {
                                            ble.disconnect()
                                            connectedDevice = null
                                            connectedTime = null
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = pink)
                                    ) { Text("🔌 해제") }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row {
                                    Button(
                                        onClick = { ble.sendRawLedCommand("LED_ON") },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = pink)
                                    ) { Text("🧪 수동 LED ON") }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Button(
                                        onClick = { ble.sendRawLedCommand("LED_OFF") },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = pink)
                                    ) { Text("🧪 수동 LED OFF") }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Row {
                                    Button(
                                        onClick = {
                                            ble.sendEncryptedLedCommand(if (ledOn) "LED_OFF" else "LED_ON")
                                            ble.logSharedKey()
                                            ledOn = !ledOn
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = pink)
                                    ) { Text(if (ledOn) "🌙 암호 LED OFF" else "💡 암호 LED ON") }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = { ble.sendKyberRequestPacketized() },
                                    colors = ButtonDefaults.buttonColors(containerColor = pink),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("🔐 공개키 요청") }

                                Spacer(modifier = Modifier.height(8.dp))

                                Button(
                                    onClick = {
                                        val serviceUUID = java.util.UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
                                        val charUUID = java.util.UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
                                        ble.sendData(serviceUUID, charUUID, byteArrayOf(0x04, 0x00, 0x00, 0x01))
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = pink),
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("📶 Ping 테스트") }

                                Spacer(modifier = Modifier.height(16.dp))
                                Text("✉️ 텍스트 전송", color = Color.Black)
                                OutlinedTextField(
                                    value = messageText,
                                    onValueChange = { if (it.length <= 50) messageText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("보낼 메시지를 입력하세요", color = Color.Gray) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                    keyboardActions = KeyboardActions(
                                        onSend = {
                                            if (messageText.isNotBlank()) {
                                                ble.sendPlainTextMessage(messageText)
                                                messageText = ""
                                            }
                                        }
                                    ),
                                    textStyle = LocalTextStyle.current.copy(color = Color.Black),
                                    colors = TextFieldDefaults.colors(
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black,
                                        disabledTextColor = Color.Black,
                                        cursorColor = Color.Black,
                                        focusedContainerColor = Color.White,
                                        unfocusedContainerColor = Color.White,
                                        disabledContainerColor = Color.White,
                                        focusedIndicatorColor = Color.Black,
                                        unfocusedIndicatorColor = Color.Gray,
                                        focusedPlaceholderColor = Color.DarkGray,
                                        unfocusedPlaceholderColor = Color.Gray
                                    )
                                )

                                Text(
                                    text = "${messageText.length} / 50",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (messageText.length >= 50) Color.Red else Color.Gray,
                                    modifier = Modifier.padding(top = 4.dp)
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                Row {
                                    Button(
                                        onClick = {
                                            if (messageText.isBlank()) {
                                                Toast.makeText(context, "메시지를 입력하세요.", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            ble.sendPlainTextMessage(messageText)
                                            messageText = ""
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = pink)
                                    ) { Text("📨 평문 전송") }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Button(
                                        onClick = {
                                            if (messageText.isBlank()) {
                                                Toast.makeText(context, "메시지를 입력하세요.", Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            ble.sendEncryptedTextMessage(messageText)
                                            ble.logSharedKey()
                                            messageText = ""
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = green)
                                    ) { Text("🔒 암호 전송") }
                                }

                                publicKeyBase64?.let {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("📄 공개키 (Base64):", color = Color.Black)
                                    Text(it, color = Color.Black, modifier = Modifier.fillMaxWidth().padding(8.dp))
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text("📜 BLE 로그:", style = MaterialTheme.typography.titleSmall, color = Color.Black)
                                LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                                    items(logMessages) { log -> Text(log, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ✅ 수신 진행률 오버레이
        if (showRecvProgress) {
            Surface(
                color = Color(0xFF222222),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    val pct = if (recvProgressTotal == 0) 0f else recvProgressSent.toFloat() / recvProgressTotal
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.width(160.dp).height(6.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(text = "수신 중 ${recvProgressSent}/${recvProgressTotal}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ✅ 전송 진행 다이얼로그
        if (showProgress) {
            AlertDialog(
                onDismissRequest = { /* 전송중에는 닫지 않음 */ },
                confirmButton = {},
                title = { Text("전송 중…") },
                text = {
                    val pct = if (progressTotal == 0) 0f else progressSent.toFloat() / progressTotal
                    Column {
                        LinearProgressIndicator(
                            progress = { pct },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("${progressSent} / ${progressTotal} 패킷 전송")
                    }
                }
            )
        }
        // ====== ⬆️⬆️⬆️ 붙여넣기 끝 ======
    }
}

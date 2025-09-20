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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import com.example.obsqura.BLEConnectionManager
import com.example.obsqura.CustomBluetoothDevice
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.material3.OutlinedButton


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

    var scannedDevices by remember { mutableStateOf<List<CustomBluetoothDevice>>(emptyList()) }
    var connectedDevice by remember { mutableStateOf<android.bluetooth.BluetoothDevice?>(null) }
    var connectedTime by remember { mutableStateOf<String?>(null) }
    var ledOn by remember { mutableStateOf(false) }
    var messageText by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.padding(16.dp)) {

                // ⬆️ 상단바: 뒤로 / 큰 타이틀
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = onBack) { Text("←", fontSize = 20.sp) }

                    Spacer(modifier = Modifier.width(110.dp))

                    Text(
                        "🔧 TEST",
                        style = MaterialTheme.typography.titleLarge,
                        fontSize = 20.sp

                    )
                    Spacer(Modifier.width(1.dp))
                }

                Spacer(Modifier.height(6.dp))

                // "BLE 스캐너" 중앙 정렬 & 크게
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "BLE 스캐너",
                        style = MaterialTheme.typography.headlineMedium,
                        fontSize = 24.sp

                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 연결 정보
                connectedDevice?.let { device ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        tonalElevation = 2.dp,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("✅ Connected Device:", color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text("• Name: ${device.name ?: "Unknown"}", color = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text("• Address: ${device.address}", color = MaterialTheme.colorScheme.onSecondaryContainer)
                            connectedTime?.let {
                                Text("• Connected at: $it", color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // 스캔 버튼(크게)
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
                            context.startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = MaterialTheme.shapes.large
                ) { Text("🔍 BLE 디바이스 스캔", fontSize = 18.sp) }

                Spacer(modifier = Modifier.height(16.dp))

                // ✅ 디바이스 리스트 (weight 문제 해결: Box로 감싸기)
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(scannedDevices) { customDevice ->
                            val isRPi = customDevice.displayName == "RPi-LED" ||
                                    customDevice.device.address.uppercase() == "D8:3A:DD:1E:53:AF" ||
                                    customDevice.device.address.uppercase() == "00:1A:7D:DA:71:13"

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isRPi)
                                        MaterialTheme.colorScheme.secondaryContainer
                                    else
                                        MaterialTheme.colorScheme.surface
                                ),
                                elevation = CardDefaults.cardElevation(8.dp),
                                shape = MaterialTheme.shapes.extraLarge
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        "이름: ${customDevice.displayName} ${if (isRPi) "🌿 RPi" else ""}",
                                        color = if (isRPi)
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        "주소: ${customDevice.device.address}",
                                        color = if (isRPi)
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    Row {
                                        Button(
                                            onClick = {
                                                ble.connect(customDevice.device)
                                                connectedDevice = customDevice.device
                                                connectedTime = SimpleDateFormat(
                                                    "HH:mm:ss",
                                                    Locale.getDefault()
                                                ).format(Date())
                                            },
                                            modifier = Modifier.padding(end = 8.dp),
                                            shape = MaterialTheme.shapes.large
                                        ) { Text("🔌 연결") }

                                        Button(
                                            onClick = {
                                                ble.disconnect()
                                                connectedDevice = null
                                                connectedTime = null
                                            },
                                            shape = MaterialTheme.shapes.large,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondary,
                                                contentColor = MaterialTheme.colorScheme.onSecondary
                                            )
                                        ) { Text("🔌 해제") }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Button(
                                        onClick = { ble.sendKyberRequestPacketized() },
                                        shape = MaterialTheme.shapes.large,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("🔐 공개키 요청") }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    Button(
                                        onClick = {
                                            val serviceUUID = java.util.UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
                                            val charUUID = java.util.UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
                                            ble.sendData(serviceUUID, charUUID, byteArrayOf(0x04, 0x00, 0x00, 0x01))
                                        },
                                        shape = MaterialTheme.shapes.large,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiary,
                                            contentColor = MaterialTheme.colorScheme.onTertiary
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("📶 Ping 테스트") }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("✉️ 텍스트 전송", color = MaterialTheme.colorScheme.onSurface)
                                    OutlinedTextField(
                                        value = messageText,
                                        onValueChange = { if (it.length <= 50) messageText = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        placeholder = { Text("보낼 메시지를 입력하세요") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                        keyboardActions = KeyboardActions(
                                            onSend = {
                                                if (messageText.isNotBlank()) {
                                                    ble.sendPlainTextMessage(messageText)
                                                    messageText = ""
                                                }
                                            }
                                        )
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
                                            onClick = {
                                                if (messageText.isBlank()) {
                                                    Toast.makeText(context, "메시지를 입력하세요.", Toast.LENGTH_SHORT).show()
                                                    return@Button
                                                }
                                                ble.sendPlainTextMessage(messageText)
                                                messageText = ""
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = MaterialTheme.shapes.large
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
                                            shape = MaterialTheme.shapes.large,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.secondary,
                                                contentColor = MaterialTheme.colorScheme.onSecondary
                                            )
                                        ) { Text("🔒 암호 전송") }
                                    }

                                    publicKeyBase64?.let {
                                        Spacer(Modifier.height(12.dp))
                                        Text("📄 공개키 (Base64):", color = MaterialTheme.colorScheme.onSurface)
                                        Text(
                                            it,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "📜 BLE 로그:",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                                        items(logMessages) { log ->
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
                }
            }
        }

        // ✅ 수신 진행률 오버레이
        if (showRecvProgress) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val pct = if (recvProgressTotal == 0) 0f else recvProgressSent.toFloat() / recvProgressTotal
                    LinearProgressIndicator(
                        progress = { pct },
                        modifier = Modifier.width(160.dp).height(6.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "수신 중 ${recvProgressSent}/${recvProgressTotal}",
                        style = MaterialTheme.typography.bodySmall
                    )
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
    }
}

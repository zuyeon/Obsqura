package com.example.obsqura

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.obsqura.ui.theme.BLECommunicatorTheme
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleConnectionManager: BLEConnectionManager
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    // 🔹 스캔 상태/핸들러/콜백을 "전역 1개"만 유지
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var onDeviceFound: ((CustomBluetoothDevice) -> Unit)? = null

    // 🔹 ScanCallback은 재사용 (매번 새로 만들면 APPLICATION_REGISTRATION_FAILED(2) 잘 뜸)
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rawName: String? = device.name ?: result.scanRecord?.deviceName
            val deviceName = rawName ?: "이름 없음"
            Log.d("BLE_SCAN", "📡 발견: $deviceName (${device.address}), rssi=${result.rssi}")
            onDeviceFound?.invoke(CustomBluetoothDevice(device, deviceName))
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e("BLE_SCAN", "❌ 스캔 실패: $errorCode")
        }
    }

    // -------- 권한 처리 --------
    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            perms.forEach { Log.d("Permissions", "${it.key}=${it.value}") }
        }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    // 일부 기기에서 여전히 필요
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionsLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        } else {
            // 🔹 안드9(API 28)에서는 위치 권한 필수
            requestPermissionsLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            ))
        }
    }

    // 🔹 안드9에서는 위치 서비스(고정/네트워크) OFF면 스캔 실패
    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    // -------- 생명주기 --------
    @SuppressLint("MissingPermission", "ServiceCast")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        var publicKeyBase64 by mutableStateOf<String?>(null)
        var logMessages by mutableStateOf<List<String>>(emptyList())

        fun addLog(msg: String) {
            logMessages = (logMessages + msg).takeLast(100)
            if (msg.contains("LED 명령 전체 전송 완료")) {
                Toast.makeText(this@MainActivity, "LED 명령 전송이 완료되었습니다!", Toast.LENGTH_SHORT).show()
            }
        }

        bleConnectionManager = BLEConnectionManager(
            this,
            onPublicKeyReceived = { base64 ->
                publicKeyBase64 = base64
                addLog("📥 공개키 수신 완료")
            },
            logCallback = { msg -> addLog(msg) }
        )

        requestPermissionsIfNeeded()

        setContent {
            BLECommunicatorTheme {
                val pink = Color(0xFFE91E63)
                val green = Color(0xFF4CAF50)
                val lightGreenBg = Color(0xFFE8F5E9)

                var scannedDevices by remember { mutableStateOf<List<CustomBluetoothDevice>>(emptyList()) }
                var connectedDevice by remember { mutableStateOf<BluetoothDevice?>(null) }
                var connectedTime by remember { mutableStateOf<String?>(null) }
                var ledOn by remember { mutableStateOf(false) }

                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("BLE 스캐너", style = MaterialTheme.typography.headlineMedium.copy(color = pink))
                        Spacer(modifier = Modifier.height(8.dp))

                        connectedDevice?.let { device ->
                            Text("✅ Connected Device:", color = Color.DarkGray)
                            Text("• Name: ${device.name ?: "Unknown"}", color = pink)
                            Text("• Address: ${device.address}", color = pink)
                            connectedTime?.let { Text("• Connected at: $it", color = pink) }
                            Spacer(modifier = Modifier.height(12.dp))
                        }

                        // ----- 스캔 버튼 -----
                        Button(
                            onClick = {
                                if (!hasScanPermission()) {
                                    requestPermissionsIfNeeded()
                                    Toast.makeText(this@MainActivity, "스캔 권한이 없습니다.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (!isLocationEnabled()) {
                                    Toast.makeText(this@MainActivity, "휴대폰 위치 서비스를 켜주세요.", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (!bluetoothAdapter.isEnabled) {
                                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                    return@Button
                                }

                                scannedDevices = emptyList()
                                startBleScan { customDevice ->
                                    scannedDevices = (scannedDevices + customDevice)
                                        .distinctBy { it.device.address }
                                        .sortedByDescending {
                                            it.displayName == "RPi-LED" ||
                                                    it.device.address.uppercase() == "04:7F:0E:78:E8:B0"
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
                                        customDevice.device.address.uppercase() == "04:7F:0E:78:E8:B0"

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
                                            color = if (isRPi) green else Color.DarkGray
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row {
                                            Button(
                                                onClick = {
                                                    bleConnectionManager.connect(customDevice.device)
                                                    connectedDevice = customDevice.device
                                                    connectedTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                                                },
                                                modifier = Modifier.padding(end = 8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = pink)
                                            ) { Text("🔌 연결") }

                                            Button(
                                                onClick = {
                                                    bleConnectionManager.disconnect()
                                                    connectedDevice = null
                                                    connectedTime = null
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)
                                            ) { Text("🔌 해제") }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row {
                                            Button(
                                                onClick = { bleConnectionManager.sendRawLedCommand("LED_ON") },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = pink)
                                            ) { Text("🧪 수동 LED ON") }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            Button(
                                                onClick = { bleConnectionManager.sendRawLedCommand("LED_OFF") },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = pink)
                                            ) { Text("🧪 수동 LED OFF") }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Row {
                                            Button(
                                                onClick = {
                                                    bleConnectionManager.sendEncryptedLedCommand(if (ledOn) "LED_OFF" else "LED_ON")
                                                    bleConnectionManager.logSharedKey()
                                                    ledOn = !ledOn
                                                },
                                                modifier = Modifier.weight(1f),
                                                colors = ButtonDefaults.buttonColors(containerColor = pink)
                                            ) { Text(if (ledOn) "🌙 암호 LED OFF" else "💡 암호 LED ON") }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Button(
                                            onClick = {
                                                val serviceUUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
                                                val charUUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
                                                bleConnectionManager.sendData(serviceUUID, charUUID, "KYBER_REQ".toByteArray())
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = pink),
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("🔐 공개키 요청") }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Button(
                                            onClick = {
                                                val serviceUUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
                                                val charUUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
                                                bleConnectionManager.sendData(serviceUUID, charUUID, byteArrayOf(0x04, 0x00, 0x00, 0x01))
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = pink),
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("📶 Ping 테스트") }

                                        publicKeyBase64?.let {
                                            Spacer(modifier = Modifier.height(12.dp))
                                            Text("📄 공개키 (Base64):", color = Color.Gray)
                                            Text(it, color = Color.DarkGray, modifier = Modifier.fillMaxWidth().padding(8.dp))
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        Text("📜 BLE 로그:", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                                        LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
                                            items(logMessages) { log -> Text(log, style = MaterialTheme.typography.bodySmall) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // -------- 스캔 제어 --------
    @SuppressLint("MissingPermission")
    private fun startBleScan(onFound: (CustomBluetoothDevice) -> Unit) {
        onDeviceFound = onFound

        if (isScanning) return

        if (!hasScanPermission()) {
            requestPermissionsIfNeeded()
            Toast.makeText(this, "스캔 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isLocationEnabled()) {
            Toast.makeText(this, "휴대폰 위치 서비스를 켜주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        val scanner = bluetoothLeScanner ?: run {
            Log.e("BLE_SCAN", "scanner is null")
            return
        }

        // 안전을 위해 시작 전에 stop 한번
        runCatching { scanner.stopScan(scanCallback) }

        scanner.startScan(scanCallback)
        isScanning = true
        handler.postDelayed({ stopBleScan() }, 10_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        bluetoothLeScanner?.let { runCatching { it.stopScan(scanCallback) } }
        isScanning = false
        // 바로 재시작 시 2 에러 방지용 쿨다운
        handler.postDelayed({ /* ready */ }, 300)
    }
}

data class CustomBluetoothDevice(val device: BluetoothDevice, val displayName: String)

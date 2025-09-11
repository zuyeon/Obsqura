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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.core.app.ActivityCompat
import androidx.compose.ui.Alignment

import com.example.obsqura.ui.theme.BLECommunicatorTheme
import com.example.obsqura.ui.test.TestModeScreen
import com.example.obsqura.ui.scenario.ScenarioModeScreen
import java.text.SimpleDateFormat
import java.util.*

enum class AppMode { NONE, TEST, SCENARIO }

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
            val rawName: String? = if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                device.name
            } else {
                result.scanRecord?.deviceName
            }
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

    @SuppressLint("MissingPermission", "ServiceCast")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        var publicKeyBase64 by mutableStateOf<String?>(null)
        var logMessages by mutableStateOf<List<String>>(emptyList())

        var progressSent by mutableStateOf(0)
        var progressTotal by mutableStateOf(0)
        var showProgress by mutableStateOf(false)

        var recvProgressSent by mutableStateOf(0)
        var recvProgressTotal by mutableStateOf(0)
        var showRecvProgress by mutableStateOf(false)

        fun addLog(msg: String) {
            logMessages = (logMessages + msg).takeLast(100)
            if (msg.contains("LED 명령 전체 전송 완료") || msg.contains("전체 패킷 전송 완료")) {
                Toast.makeText(this@MainActivity, "전송이 완료되었습니다!", Toast.LENGTH_SHORT).show()
            }
        }

        // 1) 매니저 초기화
        bleConnectionManager = BLEConnectionManager(
            this,
            onPublicKeyReceived = { base64 ->
                publicKeyBase64 = base64
                addLog("📥 공개키 수신 완료")
            },
            logCallback = { msg -> addLog(msg) },
            progressCallback = { sent, total ->
                progressSent = sent
                progressTotal = total
                showProgress = total > 0 && sent < total
                if (total > 0 && sent >= total) showProgress = false
            },
            receiveProgressCallback = { received, total ->
                recvProgressSent = received
                recvProgressTotal = total
                showRecvProgress = total > 0 && received < total
                if (total > 0 && received >= total) showRecvProgress = false
            }
        )

        // 2) 세션 키 일괄 삭제 (초기화 후)
        bleConnectionManager.deleteSharedKeysOnLaunch()

        // 3) 권한/UI 설정은 onCreate 안에서 계속 진행
        requestPermissionsIfNeeded()

        setContent {
            BLECommunicatorTheme {
                var appMode by remember { mutableStateOf(AppMode.NONE) }

                BackHandler(enabled = true) {
                    if (appMode == AppMode.NONE) {
                        finish()
                    } else {
                        // 모드 선택 화면 등으로 돌아가기 처리
                        appMode = AppMode.NONE
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
                    when (appMode) {
                        AppMode.NONE -> ModeSelectionScreen(
                            onSelectTest = { appMode = AppMode.TEST },
                            onSelectScenario = { appMode = AppMode.SCENARIO }
                        )

                        AppMode.TEST -> TestModeScreen(
                            ble = bleConnectionManager,
                            bluetoothAdapter = bluetoothAdapter,
                            hasScanPermission = { hasScanPermission() },
                            isLocationEnabled = { isLocationEnabled() },
                            onRequestPermissions = { requestPermissionsIfNeeded() },
                            startBleScan = { onFound -> startBleScan(onFound) },

                            publicKeyBase64 = publicKeyBase64,
                            logMessages = logMessages,
                            progressSent = progressSent,
                            progressTotal = progressTotal,
                            showProgress = showProgress,

                            recvProgressSent = recvProgressSent,
                            recvProgressTotal = recvProgressTotal,
                            showRecvProgress = showRecvProgress,

                            onBack = { appMode = AppMode.NONE }
                        )

                        AppMode.SCENARIO -> ScenarioModeScreen(
                            ble = bleConnectionManager,
                            onBack = { appMode = AppMode.NONE }
                        )
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

    @Composable
    private fun ModeSelectionScreen(
        onSelectTest: () -> Unit,
        onSelectScenario: () -> Unit
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("모드 선택", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSelectTest,
                modifier = Modifier.fillMaxWidth()
            ) { Text("🔧 일반 테스트 모드") }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onSelectScenario,
                modifier = Modifier.fillMaxWidth()
            ) { Text("🎭 시나리오 모드") }

        }
    }
}

data class CustomBluetoothDevice(val device: BluetoothDevice, val displayName: String)

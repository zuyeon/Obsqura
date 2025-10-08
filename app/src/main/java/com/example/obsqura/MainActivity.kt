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
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import com.example.obsqura.ui.theme.BLECommunicatorTheme
import com.example.obsqura.ui.theme.AppDimens
import com.example.obsqura.ui.theme.PrimaryButton
import com.example.obsqura.ui.theme.SecondaryButton
import com.example.obsqura.ui.test.TestModeScreen
import com.example.obsqura.ui.scenario.ScenarioModeScreen
import com.example.obsqura.ui.proxy.ProxyModeScreen
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import com.example.obsqura.WsClient

enum class AppMode { NONE, TEST, SCENARIO, PROXY }

class MainActivity : ComponentActivity() {

    private var proxyClient: ProxyClient? = null

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bleConnectionManager: BLEConnectionManager
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    // 🔹 스캔 상태/핸들러/콜백을 "전역 1개"만 유지
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var onDeviceFound: ((CustomBluetoothDevice) -> Unit)? = null

    // 🔹 ScanCallback 재사용 (등록 실패 방지)
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val rawName: String? =
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
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
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        } else {
            requestPermissionsLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
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
            },
            wsClient = WsClient
        )


        // 3) 권한/UI 설정은 onCreate 안에서 계속 진행
        requestPermissionsIfNeeded()

        setContent {
            BLECommunicatorTheme {  // 테마(색/타이포/쉐이프) 적용  :contentReference[oaicite:4]{index=4}
                var appMode by remember { mutableStateOf(AppMode.NONE) }

                BackHandler(enabled = true) {
                    if (appMode == AppMode.NONE) finish() else appMode = AppMode.NONE
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (appMode) {
                        AppMode.NONE -> ModeSelectionScreen(
                            onSelectTest = { appMode = AppMode.TEST },
                            onSelectScenario = { appMode = AppMode.SCENARIO },
                            onSelectProxy = { appMode = AppMode.PROXY }
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
                        ).also {
                            // (선택) TEST 들어올 땐 프록시모드 꺼두기
                            bleConnectionManager.proxyMode = false
                            bleConnectionManager.setProxyClient(null)
                            bleConnectionManager.setAutoReconnectEnabled(true)
                        }.let {
                            LaunchedEffect(Unit) {
                                val canScan = hasScanPermission() && isLocationEnabled() && bluetoothAdapter.isEnabled
                                if (canScan) {
                                    bleConnectionManager.connectByScanOnce(
                                        targetName = "RPi-LED",
                                        // targetServiceUuid = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
                                        scanWindowMs = 8_000
                                    )
                                } else {
                                    // 필요한 경우 사용자 안내 로그
                                    Log.d("AUTO_CONNECT", "Skip auto-scan: permission/location/bt not ready")
                                }
                            }
                        }

                        AppMode.SCENARIO -> ScenarioModeScreen(
                            ble = bleConnectionManager,
                            onBack = { appMode = AppMode.NONE }
                        )

                        AppMode.PROXY -> {
                            LaunchedEffect(Unit) {
                                if (proxyClient == null) {
                                    proxyClient = ProxyClient("ws://100.76.136.25:8080/ws")
                                    proxyClient?.start()
                                }

                                // 🔹 1) 현재 연결 주소 확보 (끊기기 전에)
                                val srcAddr = bleConnectionManager.getConnectedDevice()?.address

                                // 🔹 2) 다음 disconnect에서 키 삭제하지 않도록 1회 보존
                                bleConnectionManager.setKeepSharedKeyOnNextDisconnect(true)

                                // 🔹 3) BLE 자동재연결 OFF + 연결 끊기
                                bleConnectionManager.setAutoReconnectEnabled(false)
                                bleConnectionManager.disconnect()

                                // 🔹 4) 프록시 세션ID 지정(임의로 고정하거나 필요 시 동적으로)
                                bleConnectionManager.setProxySessionId("proxy-session")

                                // 🔹 5) 기존(shared_key_<addr>.bin) → shared_key_proxy-session.bin 복사
                                srcAddr?.let { bleConnectionManager.copySharedKeyFromAddressToProxySession(it) }

                                // 🔹 6) 프록시 클라이언트 주입 + 프록시 모드 ON
                                bleConnectionManager.setProxyClient(proxyClient)
                                bleConnectionManager.proxyMode = true
                            }

                            ProxyModeScreen(
                                ble = bleConnectionManager,
                                onBack = {
                                    bleConnectionManager.proxyMode = false
                                    bleConnectionManager.setProxyClient(null)
                                    bleConnectionManager.setAutoReconnectEnabled(true)
                                    proxyClient?.stop()
                                    proxyClient = null
                                    appMode = AppMode.NONE
                                }
                            )
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
        handler.postDelayed({ /* cooldown */ }, 300)
    }

    // ===== 메인 첫 화면: 모드 선택 (UI만 리스킨) =====
    @Composable
    private fun ModeSelectionScreen(
        onSelectTest: () -> Unit,
        onSelectScenario: () -> Unit,
        onSelectProxy: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = AppDimens.ScreenPadding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Choose your mode",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(Modifier.height(70.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SquareOutlineButton(
                    label = "TEST",
                    icon = "🔧",
                    borderColor = MaterialTheme.colorScheme.primary,
                    onClick = onSelectTest,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .sizeIn(maxWidth = 100.dp, maxHeight = 100.dp)
                )

                SquareOutlineButton(
                    label = "ATTACK",
                    icon = "🎭",
                    borderColor = MaterialTheme.colorScheme.secondary,
                    onClick = onSelectScenario,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .sizeIn(maxWidth = 100.dp, maxHeight = 100.dp)
                )

                SquareOutlineButton(
                    label = "PROXY",
                    icon = "🛰️",
                    borderColor = MaterialTheme.colorScheme.tertiary,
                    onClick = onSelectProxy,
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .sizeIn(maxWidth = 100.dp, maxHeight = 100.dp)
                )
            }
        }
    }

    @Composable
    private fun SquareOutlineButton(
        label: String,
        icon: String,
        borderColor: Color,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.aspectRatio(1f),   // 정사각형
            shape = MaterialTheme.shapes.large,
            border = BorderStroke(2.dp, borderColor),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Transparent, // 투명 배경
                contentColor = borderColor          // 테두리 색 = 텍스트/아이콘 색
            )
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(icon, fontSize = 50.sp)       // 아이콘 (이모티콘)
                Spacer(Modifier.height(6.dp))
                Text(label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WsClient.stop()
        bleConnectionManager.proxyMode = false
        bleConnectionManager.setProxyClient(null)
        proxyClient?.stop()
        bleConnectionManager.disconnect()   // ← 추가: BLE도 정리
    }

    override fun onStop() {
        super.onStop()
        // 화면 떠날 때 스캔 중이면 안전 종료
        runCatching { stopBleScan() }
    }

}
data class CustomBluetoothDevice(val device: BluetoothDevice, val displayName: String)

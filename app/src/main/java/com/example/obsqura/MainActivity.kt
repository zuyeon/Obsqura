package com.example.obsqura

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.obsqura.ui.proxy.ProxyModeScreen
import com.example.obsqura.ui.scenario.ScenarioModeScreen
import com.example.obsqura.ui.test.TestModeScreen
import com.example.obsqura.ui.theme.AppDimens
import com.example.obsqura.ui.theme.BLECommunicatorTheme

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

        var progressSent by mutableIntStateOf(0)
        var progressTotal by mutableIntStateOf(0)
        var showProgress by mutableStateOf(false)

        var recvProgressSent by mutableIntStateOf(0)
        var recvProgressTotal by mutableIntStateOf(0)
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

        // 🔴 앱이 켜질 때 완전 초기화
        bleConnectionManager.coldBootReset()

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

                        AppMode.TEST -> {
                            // (A) 진입 시 모드 관련 스위치
                            LaunchedEffect(Unit) {
                                // ✅ 이전 모드에서 남아있을 수 있는 지연 write/재시도 콜백 전부 끊기
                                bleConnectionManager.abortAllSendsAndTimers()

                                runCatching { stopBleScan() }
                                bleConnectionManager.proxyMode = false
                                bleConnectionManager.setProxyClient(null)
                                bleConnectionManager.setAutoReconnectEnabled(true)
                            }

                            // (B) 화면 그리기
                            TestModeScreen(
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

                            // (C) 자동 스캔/연결 트리거
                            LaunchedEffect(Unit) {
                                val canScan = hasScanPermission() && isLocationEnabled() && bluetoothAdapter.isEnabled
                                if (canScan) {
                                    bleConnectionManager.connectByScanOnce(
                                        targetName = "RPi-LED",
                                        scanWindowMs = 8_000
                                    )
                                } else {
                                    Log.d("AUTO_CONNECT", "Skip auto-scan: permission/location/bt not ready")
                                }
                            }
                        }


                        AppMode.SCENARIO -> {
                            // ✅ 모드 진입 시 콜백/타이머 전부 중단
                            LaunchedEffect(Unit) {
                                bleConnectionManager.abortAllSendsAndTimers()
                                // 시나리오 모드는 BLE 직결이니까 프록시 꺼두는 게 안전
                                bleConnectionManager.proxyMode = false
                                bleConnectionManager.setProxyClient(null)
                                bleConnectionManager.setAutoReconnectEnabled(true)
                            }

                            ScenarioModeScreen(
                                ble = bleConnectionManager,
                                onBack = { appMode = AppMode.NONE }
                            )
                        }

                        AppMode.PROXY -> {
                            LaunchedEffect(Unit) {
                                // 0) 혹시 남은 지연 write/재시도 콜백 싹 끊기
                                bleConnectionManager.abortAllSendsAndTimers()

                                // 1) 현재 BLE가 연결돼 있다면, 그 주소 기반 키가 있는지 확인
                                val srcAddr = bleConnectionManager.getConnectedDevice()?.address
                                val hasAddrKey = bleConnectionManager.hasSharedKeyFor(srcAddr)

                                // 2) 주소키도 없고 proxy-session 키도 없으면 프록시 진입 차단
                                if (!hasAddrKey && !bleConnectionManager.hasSharedKeyForProxy()) {
                                    Log.w("PROXY_MODE", "세션키 없음 → Proxy 진입 차단, TEST로 전환")
                                    Toast.makeText(
                                        this@MainActivity,
                                        "프록시용 공유키가 없습니다. 먼저 TEST/SCENARIO에서 공개키 요청으로 키 합의를 완료하세요.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    appMode = AppMode.TEST
                                    return@LaunchedEffect
                                }

                                // 3) proxy 세션 ID 고정
                                bleConnectionManager.setProxySessionId("proxy-session")

                                // 4) proxy-session 키가 아직 없고, 주소키가 있으면 복사해온다(프록시 전환 전!)
                                if (!bleConnectionManager.hasSharedKeyForProxy() && hasAddrKey && srcAddr != null) {
                                    val copied = bleConnectionManager.copySharedKeyFromAddressToProxySession(srcAddr)
                                    if (!copied) {
                                        Log.w("PROXY_MODE", "키 복사 실패 → Proxy 진입 차단, TEST로 전환")
                                        Toast.makeText(
                                            this@MainActivity,
                                            "프록시용 공유키 복사에 실패했습니다. 다시 키 합의 후 시도하세요.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        appMode = AppMode.TEST
                                        return@LaunchedEffect
                                    }
                                }

                                // 5) 여기까지 왔으면 shared_key_proxy-session.bin 확보 완료
                                if (proxyClient == null) {
                                    proxyClient = ProxyClient("ws://100.76.136.25:8080/ws")
                                }

                                // ★★★ 핵심: 리스너(브릿지) 먼저 붙이고 → start()를 나중에 ★★★
                                bleConnectionManager.setProxyClient(proxyClient)   // 리스너/브릿지 등록 (proxyConnected 토글 가능)
                                proxyClient?.start()                               // 이제 실제 연결 시작

                                // 6) disconnect 시 키 보존(프록시로 전환할 때만 1회)
                                bleConnectionManager.setKeepSharedKeyOnNextDisconnect(true)

                                // 7) BLE 자동재연결 OFF + 연결 끊기
                                bleConnectionManager.setAutoReconnectEnabled(false)
                                bleConnectionManager.disconnect()

                                // 8) 프록시 모드 ON
                                bleConnectionManager.proxyMode = true
                            }


                            ProxyModeScreen(
                                ble = bleConnectionManager,
                                onBack = {
                                    // ✅ 프록시 화면에서 나갈 때도 혹시 남아있을 콜백/타이머 끊기
                                    bleConnectionManager.abortAllSendsAndTimers()

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
        // ✅ 남은 콜백과 진행바/버퍼 모두 종료·초기화
        bleConnectionManager.abortAllSendsAndTimers()

        bleConnectionManager.proxyMode = false
        bleConnectionManager.setProxyClient(null)
        proxyClient?.stop()
        bleConnectionManager.disconnect()
    }

    override fun onStop() {
        super.onStop()
        bleConnectionManager.abortAllSendsAndTimers()
        // 화면 떠날 때 스캔 중이면 안전 종료
        runCatching { stopBleScan() }
    }

}
data class CustomBluetoothDevice(val device: BluetoothDevice, val displayName: String)

package com.example.Obsqura

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

@Composable
fun BLEDeviceListScreen(
    devices: List<BluetoothDevice>,
    onScanClick: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit = {}
) {
    val context = LocalContext.current
    val hasBluetoothConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = {
                Log.d("BLE", "🟢 스캔 버튼이 눌렸습니다")
                onScanClick()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("BLE 스캔 시작")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 로그로 디바이스 개수 출력 (디버깅용)
        Log.d("BLE", "🔍 스캔된 기기 수: ${devices.size}")

        LazyColumn {
            items(devices) { device ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .clickable { onDeviceClick(device) }
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        if (hasBluetoothConnectPermission) {
                            Text(text = device.name ?: "이름 없음")
                            Text(text = device.address)
                        } else {
                            Text("권한 없음 (BLUETOOTH_CONNECT)")
                        }
                    }
                }
            }
        }
    }
}

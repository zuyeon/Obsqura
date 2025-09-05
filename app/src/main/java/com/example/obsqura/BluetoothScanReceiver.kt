package com.example.obsqura

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

class BluetoothScanReceiver(
    private val onDeviceFound: (BluetoothDevice) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (BluetoothDevice.ACTION_FOUND == intent.action) {

            // 1) BLUETOOTH_CONNECT 권한 체크 (Android 12+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val hasConnectPerm = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                if (hasConnectPerm != PackageManager.PERMISSION_GRANTED) {
                    Log.e("ScanReceiver", "권한 없음: BLUETOOTH_CONNECT")
                    return
                }
            }

            // 2) try-catch로 SecurityException 처리
            try {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    Log.d("ScanReceiver", "기기 발견: ${it.name} - ${it.address}")
                    onDeviceFound(it)
                }
            } catch (e: SecurityException) {
                Log.e("ScanReceiver", "SecurityException 발생: $e")
            }
        }
    }
}

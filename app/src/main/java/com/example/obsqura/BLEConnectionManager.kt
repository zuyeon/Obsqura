// BLEConnectionManager.kt (with retry logic and index tracking fix)

@file:Suppress("DEPRECATION")

package com.example.obsqura

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import kotlin.math.ceil
import java.io.File
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BLEConnectionManager(
    private val context: Context,
    private val onPublicKeyReceived: (String) -> Unit,
    private val logCallback: ((String) -> Unit)? = null
) {
    private var bluetoothGatt: BluetoothGatt? = null
    private val packetBuffer = mutableMapOf<Int, ByteArray>()
    private val receivedIndices = mutableSetOf<Int>()
    private var currentMsgId: Byte? = null
    private var currentTotalPackets: Int = -1
    private var connectedDevice: BluetoothDevice? = null
    private var packetList: List<ByteArray> = emptyList()
    private var currentSendingIndex = 0
    private var sendingMsgId: Byte = 0
    private var sendingType: Byte = 0
    private val packetRetryMap = mutableMapOf<Int, Int>()  // index -> retryCount

    fun getConnectedDevice(): BluetoothDevice? = connectedDevice

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BLE_COMM"
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasPermission()) {
            Log.e(TAG, "연결 권한 없음")
            return
        }
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            connectedDevice = device
        } catch (e: SecurityException) {
            Log.e(TAG, "connectGatt 권한 오류", e)
        }
    }

    fun disconnect() {
        if (!hasPermission()) {
            Log.e(TAG, "disconnect 권한 없음")
            return
        }
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            connectedDevice = null
            Log.d(TAG, "🔌 GATT 연결 해제")
        } catch (e: SecurityException) {
            Log.e(TAG, "disconnect 권한 오류", e)
        }
    }

    private var writeInProgress = false

    @SuppressLint("MissingPermission")
    private fun sendDataWithRetry(data: ByteArray) {
        if (!hasPermission()) return
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

        if (writeInProgress) {
            Log.w(TAG, "✋ 이전 write 작업 대기 중 - writeCharacteristic() 생략")
            Handler(Looper.getMainLooper()).postDelayed({
                sendDataWithRetry(data)
            }, 50) // 약간의 딜레이 후 재시도
            return
        }

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = data

        writeInProgress = true
        val success = gatt.writeCharacteristic(characteristic)
        if (!success) {
            Log.e(TAG, "❌ writeCharacteristic() 실패 - index=$currentSendingIndex")
            writeInProgress = false
            Handler(Looper.getMainLooper()).postDelayed({
                sendPacketAt(currentSendingIndex) // 재시도는 같은 index만
            }, 100)
            return
        }
    }


    fun sendLargeMessage(rawData: ByteArray, type: Byte, msgId: Byte) {
        val payloadSize = 16
        val totalPackets = ceil(rawData.size / payloadSize.toDouble()).toInt()
        sendingType = type
        sendingMsgId = msgId
        currentSendingIndex = 0
        packetList = (0 until totalPackets).map { i ->
            val start = i * payloadSize
            val end = minOf(start + payloadSize, rawData.size)
            val chunk = rawData.sliceArray(start until end)

            val payload = if (chunk.size == payloadSize) {
                chunk
            } else {
                chunk // 마지막 패킷은 패딩 없이 그대로 사용
            }

            val header = byteArrayOf(type, msgId, i.toByte(), totalPackets.toByte())
            header + payload
        }
        Log.d(TAG, "📦 전송할 전체 패킷 개수: ${packetList.size}")
        packetList.forEachIndexed { idx, pkt ->
            Log.d(TAG, "📤 Packet[$idx]: ${pkt.joinToString(" ") { "%02X".format(it) }}")
        }

        packetRetryMap.clear()
        logCallback?.invoke("📦 총 ${packetList.size}개 패킷 전송 시작 (msgId=$msgId)")
        sendPacketAt(currentSendingIndex)
    }

    private fun sendPacketAt(index: Int) {
        if (index >= packetList.size) return
        val packet = packetList[index]
        val retryCount = packetRetryMap.getOrDefault(index, 0)

        if (retryCount >= 3) {
            Log.e(TAG, "❌ 패킷 $index 전송 3회 실패 - 전송 중단")
            logCallback?.invoke("❌ 패킷 $index 전송 3회 실패 - 전송 중단")

            // 누적 실패가 너무 많을 경우 전체 취소 권고
            val totalFailed = packetRetryMap.values.count { it >= 3 }
            if (totalFailed >= 3) {
                logCallback?.invoke("❌ 다수 패킷 전송 실패 감지 (${totalFailed}개) - 전체 중단 또는 재전송 권장")
            }

            return
        }

        packetRetryMap[index] = retryCount + 1
        logCallback?.invoke("📤 전송중: idx=$index (재시도 ${retryCount + 1}/3)")
        sendDataWithRetry(packet)
    }

    fun enableNotification(serviceUUID: UUID = SERVICE_UUID, charUUID: UUID = CHARACTERISTIC_UUID) {
        if (!hasPermission()) {
            Log.e(TAG, "Notify 권한 없음")
            return
        }
        val gatt = bluetoothGatt ?: return
        try {
            val service = gatt.getService(serviceUUID) ?: return
            val characteristic = service.getCharacteristic(charUUID) ?: return

            val success = gatt.setCharacteristicNotification(characteristic, true)
            if (!success) {
                Log.e(TAG, "Notify 설정 실패")
                return
            }

            val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            val descriptor = characteristic.getDescriptor(cccdUuid)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
                Log.d(TAG, "🔔 Notify 설정 완료")
            } ?: run {
                Log.e(TAG, "Descriptor($cccdUuid) 없음")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "enableNotification 권한 오류", e)
        }
    }

    @Suppress("DEPRECATION")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "✅ GATT 연결 성공 (status=$status)")
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "BLE 연결됨", Toast.LENGTH_SHORT).show()
                    }
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "discoverServices 권한 오류", e)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "⚠️ GATT 연결 끊김 (status=$status)")
                    connectedDevice = null
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "BLE 연결 끊김", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> {
                    Log.d(TAG, "ℹ️ GATT 상태 변경: newState=$newState, status=$status")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "✅ 서비스 검색 성공")
                enableNotification()
                gatt.services.forEach { service ->
                    Log.d(TAG, "🔧 Service UUID: ${service.uuid}")
                    service.characteristics.forEach { characteristic ->
                        Log.d(TAG, "   └ Char UUID: ${characteristic.uuid}")
                    }
                }
            } else {
                Log.e(TAG, "서비스 검색 실패: status=$status")
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeInProgress = false // 🔓 다음 write 허용

            if (status == BluetoothGatt.GATT_SUCCESS) {
                logCallback?.invoke("✅ 패킷 $currentSendingIndex 전송 성공 (${currentSendingIndex + 1}/${packetList.size})")
                currentSendingIndex++
                if (currentSendingIndex < packetList.size) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendPacketAt(currentSendingIndex)
                    }, 60) // 적절한 pacing
                } else {
                    logCallback?.invoke("✅ 전체 패킷 전송 완료 (msgId=$sendingMsgId)")

                    if (sendingType == 0x03.toByte()) {
                        logCallback?.invoke("✅ LED 명령 전체 전송 완료 (${packetList.size}개 패킷)")
                    }

                    val failed = packetRetryMap.count { it.value >= 2 }
                    val retried = packetRetryMap.count { it.value > 1 }
                    val total = packetList.size
                    logCallback?.invoke("📊 전송률 통계: 전체 ${total} 개 중 ${total - failed} 개 성공 / ${failed} 개 실패 / ${retried} 개 재시도 이상")
                }
            } else {
                logCallback?.invoke("⚠️ 패킷 $currentSendingIndex 전송 실패 - 재시도 예정")
                Handler(Looper.getMainLooper()).postDelayed({
                    sendPacketAt(currentSendingIndex)
                }, 200) // 실패 시 딜레이 길게
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val packet = characteristic.value
            Log.d(TAG, "📥 패킷 수신(20B)")

            val index = packet[2].toInt() and 0xFF
            val total = packet[3].toInt() and 0xFF
            val msgId = packet[1]

            if (currentMsgId == null || msgId != currentMsgId) {
                Log.w(TAG, "새 메시지 수신 시작 또는 메시지 ID 변경 감지. 버퍼 초기화")
                packetBuffer.clear()
                receivedIndices.clear()
                currentMsgId = msgId
                currentTotalPackets = total
            }

            if (!receivedIndices.contains(index)) {
                packetBuffer[index] = packet
                receivedIndices.add(index)
            } else {
                Log.w(TAG, "📛 중복 수신된 패킷 index=$index")
            }

            if (receivedIndices.size == total) {
                Log.d(TAG, "📦 모든 패킷($total) 수신 완료. 재조립 시작")
                val rawData = reassemblePackets(packetBuffer)
                packetBuffer.clear()
                receivedIndices.clear()
                currentMsgId = null

                val base64Key = Base64.encodeToString(rawData, Base64.NO_WRAP)
                Log.d(TAG, "🧩 복원된 공개키 (Base64): $base64Key")
                logCallback?.invoke("📩 공개키 수신 완료 (Base64): $base64Key")

                val decodedPublicKey: ByteArray = try {
                    Base64.decode(base64Key, Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Base64 디코딩 실패", e)
                    return
                }

                if (decodedPublicKey.size < 100) {
                    Log.e(TAG, "❌ 디코딩된 공개키 길이 비정상: ${decodedPublicKey.size}B")
                    logCallback?.invoke("❌ 디코딩된 공개키 길이 비정상 (${decodedPublicKey.size}B)")
                    return
                }

                if (!isLikelyKyberKey(decodedPublicKey)) {
                    Log.e(TAG, "❌ 수신된 데이터가 Kyber 공개키로 보이지 않습니다.")
                    logCallback?.invoke("❌ 유효하지 않은 공개키 (길이: ${decodedPublicKey.size}B)")
                    return
                }

                try {
                    val result = KyberJNI.encapsulate(decodedPublicKey)
                    val ciphertext = result.ciphertext
                    val sharedKey = result.sharedKey
                    logCallback?.invoke("✅ Encapsulation 완료\n- Ciphertext: ${ciphertext.size}B\n- SharedKey: ${sharedKey.size}B")
                    Log.d(TAG, "✅ Encapsulation 완료 - Ciphertext(${ciphertext.size}B), SharedKey(${sharedKey.size}B)")

                    File(context.filesDir, "shared_key.bin").writeBytes(sharedKey)
                    Log.d(TAG, "💾 Shared Key 저장 완료")

                    val newMsgId = (0..255).random().toByte()
                    sendLargeMessage(ciphertext, type = 0x02, msgId = newMsgId)

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Encapsulation 수행 실패", e)
                }

                try {
                    File(context.filesDir, "received_publickey_base64.txt").writeText(base64Key)
                    File(context.filesDir, "received_publickey_raw.bin").writeBytes(decodedPublicKey)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 공개키 저장 실패", e)
                }
            }
        }

        private fun reassemblePackets(packets: Map<Int, ByteArray>): ByteArray {
            val sorted = packets.toSortedMap()
            val result = mutableListOf<Byte>()
            for ((_, packet) in sorted) {
                result.addAll(packet.drop(4)) // remove 4-byte header
            }
            return result.toByteArray()
        }
        }

    private fun loadSharedKey(): ByteArray? {
        return try {
            val file = File(context.filesDir, "shared_key.bin")
            val bytes = file.readBytes()

            if (bytes.size < 32) {
                Log.e(TAG, "❌ 공유키 길이 비정상: ${bytes.size}B")
                logCallback?.invoke("❌ 공유키 길이 비정상 (${bytes.size}B)")
                return null
            }

            bytes
        } catch (e: Exception) {
            Log.e(TAG, "❌ shared_key.bin 로드 실패", e)
            null
        }
    }

    fun logSharedKey() {
        val keyFile = File(context.filesDir, "shared_key.bin")
        if (!keyFile.exists()) {
            Log.e(TAG, "❌ shared_key.bin 파일이 존재하지 않습니다.")
            return
        }

        try {
            val keyBytes = keyFile.readBytes()
            val hex = keyBytes.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "🔑 Shared Key (${keyBytes.size}B): $hex")
        } catch (e: Exception) {
            Log.e(TAG, "❌ shared_key.bin 읽기 실패", e)
        }
    }

    private fun aesGcmEncrypt(plaintext: String, key: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
            val keySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val encryptedData = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

            val ciphertext = encryptedData.copyOfRange(0, encryptedData.size - 16)
            val tag = encryptedData.copyOfRange(encryptedData.size - 16, encryptedData.size)

            val result = iv + ciphertext + tag  // 👈 바이트 합치기

            logCallback?.invoke("🔐 AES-GCM 구성: nonce(${iv.size}B) + ciphertext(${ciphertext.size}B) + tag(${tag.size}B) = ${result.size}B")
            Log.d(TAG, "🔐 AES-GCM 구성: nonce(${iv.size}B) + ciphertext(${ciphertext.size}B) + tag(${tag.size}B) = ${result.size}B")

            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ AES 암호화 실패", e)
            null
        }
    }


    private fun isLikelyKyberKey(key: ByteArray): Boolean {
        // Kyber512 공개키는 보통 800~1000B 사이
        return key.size in 700..1100
    }

    @SuppressLint("MissingPermission")
    fun sendData(
        serviceUUID: UUID = SERVICE_UUID,
        characteristicUUID: UUID = CHARACTERISTIC_UUID,
        data: ByteArray
    ) {
        if (!hasPermission()) {
            Log.e(TAG, "❌ 전송 권한 없음 - sendData 차단됨")
            return
        }

        val gatt = bluetoothGatt ?: run {
            Log.e(TAG, "❌ GATT 연결 없음")
            return
        }

        try {
            val service = gatt.getService(serviceUUID) ?: run {
                Log.e(TAG, "❌ Service($serviceUUID) 없음")
                return
            }

            val characteristic = service.getCharacteristic(characteristicUUID) ?: run {
                Log.e(TAG, "❌ Characteristic($characteristicUUID) 없음")
                return
            }

            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = data
            val success = gatt.writeCharacteristic(characteristic)
            Log.d(TAG, "📤 sendData() 요청: ${data.size}B, success=$success")

        } catch (e: SecurityException) {
            Log.e(TAG, "❌ sendData() 권한 오류", e)
        }
    }

    fun sendEncryptedLedCommand(command: String) {
        val key = loadSharedKey()
        if (key == null) {
            Log.e(TAG, "❌ 공유키 없음 - 암호화 중단")
            Toast.makeText(context, "❗ 먼저 공개키를 요청해 주세요.", Toast.LENGTH_SHORT).show()
            logCallback?.invoke("❌ 공유키 없음 - 먼저 공개키를 요청해 주세요.")  // ✅ 추가
            return
        }

        val hexKey = key.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "🔐 [공유키 로그] shared_key.bin: $hexKey")
        logCallback?.invoke("🔐 공유키(hex): $hexKey")

        val encrypted = aesGcmEncrypt(command, key) ?: run {
            Log.e(TAG, "❌ 암호화 실패")
            Toast.makeText(context, "❗ LED 명령 암호화에 실패했습니다.", Toast.LENGTH_SHORT).show()
            logCallback?.invoke("❌ 암호화 실패")
            return
        }

        val hex = encrypted.joinToString(" ") { "%02X".format(it) }
        logCallback?.invoke("📦 전송할 암호화 데이터(${encrypted.size}B): $hex")
        Log.d(TAG, "📦 전송할 암호화 데이터(${encrypted.size}B): $hex")

        val msgId = (0..255).random().toByte()
        sendLargeMessage(encrypted, type = 0x03, msgId = msgId)
        Log.d(TAG, "📤 암호화된 LED 명령 전송 완료: $command")
        logCallback?.invoke("📤 암호화 LED 명령 전송 완료: $command")
    }

    fun sendRawLedCommand(command: String) {
        val serviceUUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val charUUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        sendData(serviceUUID, charUUID, command.toByteArray())
    }
}
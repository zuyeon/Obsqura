// BLEConnectionManager.kt (stable: worker thread for JNI, main-thread GATT writes)

@file:Suppress("DEPRECATION")

package com.example.obsqura

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import androidx.core.content.ContextCompat
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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
    private val logCallback: ((String) -> Unit)? = null,
    private val progressCallback: ((sent: Int, total: Int) -> Unit)? = null,
    private val receiveProgressCallback: ((received: Int, total: Int) -> Unit)? = null
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

    // threading
    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("kyber-worker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    private val TYPE_KYBER_REQ:   Byte = 0x01
    private val TYPE_KYBER_CIPHERTEXT: Byte = 0x02
    private val TYPE_AES_MESSAGE: Byte = 0x03   // 암호 텍스트
    private val TYPE_TEXT_PLAIN: Byte = 0x06 // 평문 텍스트

    fun getConnectedDevice(): BluetoothDevice? = connectedDevice

    companion object {
        private const val KEY_FILE_PREFIX = "shared_key_"
        val SERVICE_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BLE_COMM"
    }

    fun enableNotifications(
        context: Context,   // 👈 context를 하나 받도록 수정
        serviceUUID: UUID,
        characteristicUUID: UUID,
        useIndication: Boolean = false
    ): Boolean {
        val gatt = bluetoothGatt ?: return false
        val service = gatt.getService(serviceUUID) ?: return false
        val characteristic = service.getCharacteristic(characteristicUUID) ?: return false

        val props = characteristic.properties
        val canNotify = (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
        val canIndicate = (props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        if (!canNotify && !canIndicate) return false

        // ====== 🔑 권한 체크 추가 ======
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val ok = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            if (!ok) {
                // 권한 없으면 Activity에서 requestPermissions() 호출 필요
                return false
            }
        }

        // 1) 로컬 설정 (예외 처리)
        try {
            if (!gatt.setCharacteristicNotification(characteristic, true)) return false
        } catch (se: SecurityException) {
            se.printStackTrace()
            return false
        }

        // 2) CCCD 디스크립터 쓰기
        val cccdUUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val descriptor = characteristic.getDescriptor(cccdUUID) ?: return false

        descriptor.value = if (useIndication && canIndicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        return gatt.writeDescriptor(descriptor)
    }



    private fun toastOnMain(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun keyFileFor(addr: String): File =
        File(context.filesDir, "$KEY_FILE_PREFIX$addr.bin")

    fun deleteSharedKeysOnLaunch() {
        context.filesDir.listFiles()?.forEach { f ->
            if (f.name.startsWith(KEY_FILE_PREFIX)) f.delete()
        }
        logCallback?.invoke("🧹 세션 시작: 모든 shared_key_* 삭제")
    }

    private fun saveSharedKeyFor(addr: String, key: ByteArray) {
        try {
            keyFileFor(addr).outputStream().use { it.write(key) }
            Log.d(TAG, "💾 세션 키 저장 완료 for $addr (${key.size}B)")
            logCallback?.invoke("💾 세션 키 저장 완료 ($addr)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 세션 키 저장 실패", e)
            logCallback?.invoke("❌ 세션 키 저장 실패: ${e.message}")
        }
    }

    private fun loadSharedKeyFor(addr: String?): ByteArray? {
        if (addr == null) return null
        return try {
            val f = keyFileFor(addr)
            if (!f.exists()) return null
            val bytes = f.readBytes()
            if (bytes.size < 32) {
                logCallback?.invoke("❌ 공유키 길이 비정상 (${bytes.size}B)")
                null
            } else bytes
        } catch (e: Exception) {
            Log.e(TAG, "❌ shared_key 로드 실패", e)
            null
        }
    }

    private fun deleteSharedKeyFor(addr: String?) {
        if (addr == null) return
        try {
            val f = keyFileFor(addr)
            if (f.exists()) {
                f.delete()
                logCallback?.invoke("🗑️ 세션 키 삭제 ($addr)")
            }
        } catch (_: Exception) {}
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun newMsgId(): Byte = (0..255).random().toByte()

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
        // 항상 메인 스레드에서 writeCharacteristic 수행
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { sendDataWithRetry(data) }
            return
        }

        if (!hasPermission()) return
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(SERVICE_UUID) ?: return
        val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID) ?: return

        if (writeInProgress) {
            Log.w(TAG, "✋ 이전 write 작업 대기 중 - writeCharacteristic() 생략")
            mainHandler.postDelayed({ sendDataWithRetry(data) }, 50)
            return
        }

        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        characteristic.value = data

        writeInProgress = true
        val success = gatt.writeCharacteristic(characteristic)
        if (!success) {
            Log.e(TAG, "❌ writeCharacteristic() 실패 - index=$currentSendingIndex")
            writeInProgress = false
            mainHandler.postDelayed({ sendPacketAt(currentSendingIndex) }, 100)
            return
        }
    }

    fun sendLargeMessage(rawData: ByteArray, type: Byte, msgId: Byte) {
        // 🔧 0x03인 경우 키가 있는지 강제 검증
        if (type == TYPE_AES_MESSAGE) {
            val addr = connectedDevice?.address
            val key = loadSharedKeyFor(addr)
            if (key == null) {
                logCallback?.invoke("❌ (block) 공유키 없음 → 0x03 전송 차단")
                toastOnMain("❗ 먼저 공개키 교환을 해주세요.")
                return
            }
        }

        // 항상 메인 스레드 보장
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { sendLargeMessage(rawData, type, msgId) }
            return
        }

        val payloadSize = 16
        val totalPackets = ceil(rawData.size / payloadSize.toDouble()).toInt()
        sendingType = type
        sendingMsgId = msgId
        currentSendingIndex = 0
        packetList = (0 until totalPackets).map { i ->
            val start = i * payloadSize
            val end = minOf(start + payloadSize, rawData.size)
            val chunk = rawData.sliceArray(start until end)
            val header = byteArrayOf(type, msgId, i.toByte(), totalPackets.toByte())
            header + chunk // 마지막은 패딩 없이
        }

        Log.d(TAG, "📦 전송할 전체 패킷 개수: ${packetList.size}")
        packetList.forEachIndexed { idx, pkt ->
            Log.d(TAG, "📤 Packet[$idx]: ${pkt.joinToString(" ") { "%02X".format(it) }}")
        }

        packetRetryMap.clear()
        logCallback?.invoke("📦 총 ${packetList.size}개 패킷 전송 시작 (msgId=$msgId)")

        // ✅ 진행률 0% 알림
        progressCallback?.let { it(0, packetList.size) }

        sendPacketAt(currentSendingIndex)
    }

    private fun sendPacketAt(index: Int) {
        if (index >= packetList.size) return
        val packet = packetList[index]
        val retryCount = packetRetryMap.getOrDefault(index, 0)

        if (retryCount >= 3) {
            Log.e(TAG, "❌ 패킷 $index 전송 3회 실패 - 전송 중단")
            logCallback?.invoke("❌ 패킷 $index 전송 3회 실패 - 전송 중단")
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
                    mainHandler.post {
                        toastOnMain("BLE 연결됨")
                    }
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        Log.e(TAG, "discoverServices 권한 오류", e)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "⚠️ GATT 연결 끊김 (status=$status)")
                    deleteSharedKeyFor(connectedDevice?.address)
                    connectedDevice = null
                    mainHandler.post {
                        Toast.makeText(context, "BLE 연결 끊김", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> Log.d(TAG, "ℹ️ GATT 상태 변경: newState=$newState, status=$status")
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

                // ✅ 진행률 갱신: (보낸 개수, 전체 개수)
                progressCallback?.let { it(currentSendingIndex + 1, packetList.size) }

                currentSendingIndex++
                if (currentSendingIndex < packetList.size) {
                    mainHandler.postDelayed({ sendPacketAt(currentSendingIndex) }, 60)
                } else {
                    logCallback?.invoke("✅ 전체 패킷 전송 완료 (msgId=$sendingMsgId)")
                    if (sendingType == 0x03.toByte()) {
                        logCallback?.invoke("✅ LED 명령 전체 전송 완료 (${packetList.size}개 패킷)")
                    }
                    val failed = packetRetryMap.count { it.value >= 2 }
                    val retried = packetRetryMap.count { it.value > 1 }
                    val total = packetList.size
                    logCallback?.invoke("📊 전송률 통계: 전체 $total 개 중 ${total - failed} 개 성공 / $failed 개 실패 / $retried 개 재시도 이상")
                }
            } else {
                logCallback?.invoke("⚠️ 패킷 $currentSendingIndex 전송 실패 - 재시도 예정")
                mainHandler.postDelayed({ sendPacketAt(currentSendingIndex) }, 200)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            // --- 0) 입력값/길이 가드 ---
            val packet = characteristic.value ?: run {
                Log.e(TAG, "❌ characteristic.value == null")
                return
            }
            if (packet.size < 4) {
                Log.e(TAG, "❌ 잘못된 패킷 길이=${packet.size} (<4)")
                return
            }

            // --- 1) 헤더 파싱 ---
            val type  = packet[0].toInt() and 0xFF
            val msgId = packet[1].toInt() and 0xFF
            val index = packet[2].toInt() and 0xFF
            val total = packet[3].toInt() and 0xFF

            Log.d(TAG, "📥 패킷 수신: type=$type, msgId=$msgId, index=$index/$total, len=${packet.size}")

            // total/인덱스 체크
            if (total <= 0 || total > 255) {
                Log.e(TAG, "❌ 비정상 total=$total → 패킷 무시"); return
            }
            if (index >= total) {
                Log.e(TAG, "❌ 인덱스 범위 초과: index=$index / total=$total"); return
            }

            // --- 2) 새 메시지 시작/ID 변경 처리 ---
            val curMsgIdInt = currentMsgId?.toInt() ?: -1
            if (currentMsgId == null || msgId != curMsgIdInt) {
                Log.w(TAG, "⚠ 새 메시지 시작 또는 msgId 변경 (old=$currentMsgId, new=$msgId). 버퍼 초기화")
                packetBuffer.clear(); receivedIndices.clear()
                currentMsgId = msgId.toByte(); currentTotalPackets = total
                mainHandler.post { receiveProgressCallback?.invoke(0, total)}
            } else if (currentTotalPackets != total) {
                Log.w(TAG, "⚠ total 변경: $currentTotalPackets -> $total (msgId=$msgId). 버퍼 재설정")
                packetBuffer.clear(); receivedIndices.clear()
                currentTotalPackets = total
            }

            // --- 3) 패킷 저장 ---
            if (!receivedIndices.contains(index)) {
                packetBuffer[index] = packet
                receivedIndices.add(index)
                mainHandler.post {
                    receiveProgressCallback?.invoke(receivedIndices.size, currentTotalPackets) // ✅ 추가
                }
            } else {
                Log.w(TAG, "📛 중복 패킷 index=$index 무시")
            }

            // --- 4) 완료 조건 ---
            if (receivedIndices.size == total) {
                mainHandler.post { receiveProgressCallback?.invoke(total, total) }
            }
            if (receivedIndices.size != total) return
            val missing = (0 until total).firstOrNull { it !in receivedIndices }
            if (missing != null) { Log.w(TAG, "⚠ 수신 누락 index=$missing"); return }

            // --- 5) 재조립 + 공개키 처리 (JNI는 워커 스레드) ---
            try {
                Log.d(TAG, "📦 모든 패킷($total) 수신 완료. 재조립 시작 (msgId=$msgId)")
                val pubkey = reassemblePackets(packetBuffer) // 헤더 제거 후 합침

                // 수신 상태 초기화
                packetBuffer.clear(); receivedIndices.clear()
                currentMsgId = null; currentTotalPackets = -1

                // 디버깅용 로그(길이만)
                val base64Len = Base64.encodeToString(pubkey, Base64.NO_WRAP).length
                Log.d(TAG, "🧩 복원된 공개키(Base64) 길이=$base64Len")
                logCallback?.invoke("📩 공개키 수신 완료")

                if (pubkey.size != 800) {
                    Log.e(TAG, "❌ 공개키 길이 비정상: ${pubkey.size}B")
                    logCallback?.invoke("❌ 공개키 길이 오류 (${pubkey.size}B)")
                    return
                }
                if (!isLikelyKyberKey(pubkey)) {
                    Log.e(TAG, "❌ 수신 데이터가 Kyber 공개키로 보이지 않음")
                    logCallback?.invoke("❌ 유효하지 않은 공개키")
                    return
                }

                // (선택) raw 공개키 저장
                try {
                    File(context.filesDir, "received_publickey_raw.bin").writeBytes(pubkey)
                } catch (saveErr: Exception) {
                    Log.e(TAG, "❌ 공개키 저장 실패", saveErr)
                }

                // JNI는 워커 스레드에서
                workerHandler.post {
                    try {
                        val result = KyberJNI.encapsulate(pubkey)
                        val ciphertext = result.ciphertext
                        val sharedKey  = result.sharedKey

                        Log.d(TAG, "✅ Encapsulation 완료 - ct=${ciphertext.size}B, key=${sharedKey.size}B")
                        logCallback?.invoke("✅ Encapsulation 완료 (ct=${ciphertext.size}B, key=${sharedKey.size}B)")

                        // 키 저장 + 암호문 전송은 메인 스레드에서
                        mainHandler.post {
                            try {
                                val addr = connectedDevice?.address
                                if (addr == null) {
                                    logCallback?.invoke("❌ 저장 실패: 디바이스 주소 없음")
                                } else {
                                    saveSharedKeyFor(addr, sharedKey) // 🔧 NEW: 디바이스별 저장
                                }
                                val newMsgId = newMsgId()
                                sendLargeMessage(ciphertext, type = TYPE_KYBER_CIPHERTEXT, msgId = newMsgId)
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ 키 저장/전송 처리 실패", e)
                                logCallback?.invoke("❌ 키 저장/전송 실패: ${e.message}")
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "❌ Encapsulation 실패/크래시 감지", t)
                        logCallback?.invoke("❌ Encapsulation 실패: ${t.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ 공개키 재조립/처리 중 예외", e)
                logCallback?.invoke("❌ 공개키 처리 실패: ${e.message}")
            }
        }

        private fun reassemblePackets(packets: Map<Int, ByteArray>): ByteArray {
            val sorted = packets.toSortedMap()
            val result = mutableListOf<Byte>()
            for ((_, p) in sorted) result.addAll(p.drop(4)) // 4-byte header 제거
            return result.toByteArray()
        }
    }

    fun logSharedKey() {
        val addr = connectedDevice?.address
        if (addr == null) {
            Log.e(TAG, "❌ 디바이스 주소 없음")
            return
        }
        val keyFile = keyFileFor(addr)
        if (!keyFile.exists()) {
            Log.e(TAG, "❌ 세션 키 파일이 없습니다. ($addr)")
            return
        }
        try {
            val keyBytes = keyFile.readBytes()
            val hex = keyBytes.joinToString(" ") { "%02X".format(it) }
            Log.d(TAG, "🔑 Shared Key (${keyBytes.size}B@$addr): $hex")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 세션 키 읽기 실패", e)
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
            val result = iv + ciphertext + tag
            logCallback?.invoke("🔐 AES-GCM 구성: nonce(${iv.size}B) + ciphertext(${ciphertext.size}B) + tag(${tag.size}B) = ${result.size}B")
            Log.d(TAG, "🔐 AES-GCM 구성: nonce(${iv.size}B) + ciphertext(${ciphertext.size}B) + tag(${tag.size}B) = ${result.size}B")
            return result
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
            Log.e(TAG, "❌ GATT 연결 없음"); return
        }

        try {
            val service = gatt.getService(serviceUUID) ?: run {
                Log.e(TAG, "❌ Service($serviceUUID) 없음"); return
            }
            val characteristic = service.getCharacteristic(characteristicUUID) ?: run {
                Log.e(TAG, "❌ Characteristic($characteristicUUID) 없음"); return
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
        val key = loadSharedKeyFor(connectedDevice?.address)
        if (key == null) {
            Log.e(TAG, "❌ 공유키 없음 - 암호화 중단")
            toastOnMain("❗ 먼저 공개키를 요청해 주세요.")
            logCallback?.invoke("❌ 공유키 없음 - 먼저 공개키를 요청해 주세요.")
            return
        }

        val hexKey = key.joinToString(" ") { "%02X".format(it) }
        Log.d(TAG, "🔐 [공유키 로그] ${connectedDevice?.address}: $hexKey")
        logCallback?.invoke("🔐 공유키(hex@${connectedDevice?.address}): $hexKey")

        val encrypted = aesGcmEncrypt(command, key) ?: run {
            Log.e(TAG, "❌ 암호화 실패")
            toastOnMain("❗ LED 명령 암호화에 실패했습니다.")
            logCallback?.invoke("❌ 암호화 실패")
            return
        }

        val hex = encrypted.joinToString(" ") { "%02X".format(it) }
        logCallback?.invoke("📦 전송할 암호화 데이터(${encrypted.size}B): $hex")
        Log.d(TAG, "📦 전송할 암호화 데이터(${encrypted.size}B): $hex")

        val msgId = (0..255).random().toByte()
        sendLargeMessage(encrypted, type = TYPE_AES_MESSAGE, msgId = msgId)
        Log.d(TAG, "📤 암호화된 LED 명령 전송 완료: $command")
        logCallback?.invoke("📤 암호화 LED 명령 전송 완료: $command")
    }

    fun sendRawLedCommand(command: String) {
        val serviceUUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val charUUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        sendData(serviceUUID, charUUID, command.toByteArray())


    }

    /**
     * ✉️ 평문 텍스트 전송 (암호화 X)
     * 기존 분할/전송 로직: sendLargeMessage(rawData, type, msgId) 재사용
     */
    // BLEConnectionManager.kt
    fun sendPlainTextMessage(text: String, mitmOn: Boolean = false) {
        if (text.isBlank()) {
            logCallback?.invoke("❗보낼 텍스트가 비어있습니다.")
            return
        }

        var payloadBytes = text.toByteArray(Charsets.UTF_8)

        if (mitmOn) {
            // 1) 원문을 살짝 변조 (첫 글자 bit-flip 예시)
            val mutated = payloadBytes.copyOf()
            if (mutated.isNotEmpty()) {
                mutated[0] = (mutated[0].toInt() xor 0x01).toByte() // 예: H→I
            }
            val mutatedStr = String(mutated, Charsets.UTF_8)

            // 2) ATTACKED + 개행 + 변조문자열 형태로 페이로드 구성
            val attackedDisplay = "ATTACKED\n$mutatedStr"
            payloadBytes = attackedDisplay.toByteArray(Charsets.UTF_8)

            // ✅ 로그캣 + 앱 로그 둘 다 출력
            logCallback?.invoke("⚠️ MITM 변조 적용 → '$mutatedStr' (표시: 'ATTACKED + 개행')")
            Log.d(TAG, "[SCENARIO][PLAINTEXT] mitm=true, display='${attackedDisplay.replace("\n", "\\n")}'")
        }

        val msgId = newMsgId()
        logCallback?.invoke("📨 [PLAINTEXT] (${payloadBytes.size}B, msgId=$msgId, mitm=$mitmOn)")
        sendLargeMessage(payloadBytes, type = TYPE_TEXT_PLAIN, msgId = msgId)
    }

    fun sendEncryptedTextMessage(text: String, mitm: Boolean = false) {
        if (text.isBlank()) {
            logCallback?.invoke("❗보낼 텍스트가 비어있습니다.")
            toastOnMain("❗ 텍스트가 비었습니다.")
            return
        }
        val key = loadSharedKeyFor(connectedDevice?.address)
        if (key == null) {
            logCallback?.invoke("❌ 공유키가 없습니다. 먼저 공개키를 요청(KYBER_REQ)하고 키 합의를 완료하세요.")
            toastOnMain("❗ 먼저 공개키를 요청해 키 합의를 완료하세요.")
            return
        }

        val enc = aesGcmEncrypt(text, key) ?: run {
            logCallback?.invoke("❌ 텍스트 암호화 실패")
            toastOnMain("❗ 텍스트 암호화 실패")
            return
        }

        // Logcat: 원본 암호문 (일부만)
        Log.d(TAG, "[SCENARIO][ENCRYPTED] mitm=$mitm msgLen=${text.length}")
        Log.d(TAG, "[SCENARIO][ENCRYPTED] enc orig=${hexdump(enc)}")

        val encrypted = enc.copyOf()
        if (mitm) {
            val ivLen = 12
            val tagLen = 16
            if (encrypted.size > ivLen + tagLen) {
                val i = ivLen // 첫 ciphertext 바이트
                val before = encrypted[i]
                encrypted[i] = (before.toInt() xor 0x01).toByte()
                Log.d(TAG, "⚠️ [MITM] ENCRYPTED bit-flip @ct[0]: ${"%02X".format(before)} -> ${"%02X".format(encrypted[i])}")
                logCallback?.invoke("⚠️ [MITM] ENCRYPTED ct[0] bit-flip → 수신측 GCM 실패 예상")
            } else {
                val before = encrypted[0]
                encrypted[0] = (before.toInt() xor 0x01).toByte()
                Log.d(TAG, "⚠️ [MITM] ENCRYPTED bit-flip @0(fallback): ${"%02X".format(before)} -> ${"%02X".format(encrypted[0])}")
                logCallback?.invoke("⚠️ [MITM] ENCRYPTED 전체 첫 바이트 bit-flip (fallback)")
            }

            // 로컬에서도 '변조본' 복호를 시도해 GCM 실패 로그 남김 (데모용)
            try {
                val nonce = encrypted.copyOfRange(0, 12)
                val tag   = encrypted.copyOfRange(encrypted.size - 16, encrypted.size)
                val ct    = encrypted.copyOfRange(12, encrypted.size - 16)
                val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
                    javax.crypto.spec.GCMParameterSpec(128, nonce))
                cipher.doFinal(ct + tag)
                Log.e(TAG, "[SCENARIO][ENCRYPTED] ⚠️ Expected GCM failure, but decrypt succeeded?!")
            } catch (e: Exception) {
                Log.d(TAG, "[SCENARIO][ENCRYPTED] ✅ Expected GCM FAIL (local): ${e::class.simpleName}: ${e.message}")
            }
        }

        Log.d(TAG, "[SCENARIO][ENCRYPTED] enc mutated=${hexdump(encrypted)} (if mitm)")

        // 원본(enc)만 셀프검증 OK 로그
        selfTestDecryptAndLog(enc, key)

        val msgId = newMsgId()
        logCallback?.invoke("🔒 [ENCRYPTED TEXT] 원문(${text.length}자) → 전송바이트(${encrypted.size}B), msgId=$msgId, mitm=$mitm")
        sendLargeMessage(encrypted, type = TYPE_AES_MESSAGE, msgId = msgId)
    }

    /** 🔐 공개키 요청을 패킷(헤더 포함)으로 전송 */
    fun sendKyberRequestPacketized() {
        val payload = "KYBER_REQ".toByteArray(Charsets.UTF_8)
        val msgId = newMsgId()
        logCallback?.invoke("📡 [REQ] KYBER_REQ packetized (len=${payload.size}, msgId=$msgId)")
        sendLargeMessage(payload, type = TYPE_KYBER_REQ, msgId = msgId)
    }

    private fun selfTestDecryptAndLog(enc: ByteArray, key: ByteArray) {
        try {
            // 앱이 만든 포맷: nonce(12) | ciphertext | tag(16)
            if (enc.size < 12 + 16) {
                Log.e("TEXT_DIAG", "enc too short: ${enc.size}")
                return
            }
            val nonce = enc.copyOfRange(0, 12)
            val tag   = enc.copyOfRange(enc.size - 16, enc.size)
            val ct    = enc.copyOfRange(12, enc.size - 16)

            Log.d("TEXT_DIAG", "NONCE=${nonce.size}, CT=${ct.size}, TAG=${tag.size}")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            val dec = cipher.doFinal(ct + tag)

            val decStr = String(dec, Charsets.UTF_8)
            Log.d("TEXT_DIAG", "DECRYPT OK(local): '$decStr'")
        } catch (e: Exception) {
            Log.e("TEXT_DIAG", "DECRYPT FAIL(local): ${e.message}", e)
        }
    }

    private fun hexdump(bytes: ByteArray, limit: Int = 32): String {
        val shown = bytes.take(limit).toByteArray()
        val hex = shown.joinToString(" ") { "%02X".format(it) }
        return if (bytes.size > limit) "$hex …(+${bytes.size - limit}B)" else hex
    }

    private fun tryUtf8(bytes: ByteArray): String {
        return try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            "<UTF8 decode fail: ${e::class.simpleName}>"
        }
    }

    /** 헤더(type|msgId|index|total) + payload 를 단일 write 로 보냄 (total=1) */
    @SuppressLint("MissingPermission")
    fun sendSinglePacket(type: Byte, payload: ByteArray) {
        if (!hasPermission()) return
        val gatt = bluetoothGatt ?: run { Log.e(TAG, "❌ GATT 없음"); return }
        val service = gatt.getService(SERVICE_UUID) ?: run { Log.e(TAG, "❌ Service 없음"); return }
        val ch = service.getCharacteristic(CHARACTERISTIC_UUID) ?: run { Log.e(TAG, "❌ Char 없음"); return }

        // 🔧 서버가 "Write Without Response"만 받는 경우 대비: 아래 한 줄을 켜보세요
        // ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val msgId = newMsgId()
        val header = byteArrayOf(type, msgId, 0x00, 0x01) // index=0, total=1
        val packet = header + payload
        ch.value = packet

        val ok = gatt.writeCharacteristic(ch)
        Log.d(TAG, "📤 sendSinglePacket type=0x%02X msgId=%d len=%d ok=%s"
            .format(type, msgId, packet.size, ok.toString()))
    }

    /** 0x03 타입(암호 패킷 경로)로 'TEST' 단일 패킷 보내기 */
    fun probePacket03Test() {
        val key = loadSharedKeyFor(connectedDevice?.address)
        if (key == null) {
            logCallback?.invoke("❌ (probe) 공유키 없음 → 0x03 테스트 차단")
            return
        }
        val payload = "TEST".toByteArray(Charsets.UTF_8)
        sendSinglePacket(TYPE_AES_MESSAGE, payload)
    }

}

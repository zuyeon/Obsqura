package com.example.obsqura.ui.proxy

import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.obsqura.WsClient
import com.example.obsqura.splitDataIntoPackets
import com.example.obsqura.reassemblePackets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private fun hexdump(bytes: ByteArray, limit: Int = 32): String {
    val shown = bytes.take(limit).toByteArray()
    val hex = shown.joinToString(" ") { "%02X".format(it) }
    return if (bytes.size > limit) "$hex …(+${bytes.size - limit}B)" else hex
}

class ProxyViewModel : ViewModel(), WsClient.ProxyEventListener {

    companion object {
        private const val TAG = "ProxyVM"
        private const val TYPE_KYBER_CIPHERTEXT: Byte = 0x02
        private const val TYPE_AES_MESSAGE: Byte = 0x03
        private const val TYPE_TEXT_PLAIN: Byte = 0x06
        private const val PUBKEY_FAKE_TYPE: Byte = 0x7F   // 재조립에 type은 안 쓰이므로 임의
    }

    data class UiState(
        val connectedToProxy: Boolean = false,
        val proxyReady: Boolean = false,
        val logs: List<String> = emptyList(),
        val sharedKey: ByteArray? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private fun log(s: String) {
        _ui.value = _ui.value.copy(logs = (_ui.value.logs + s).takeLast(100))
        Log.d(TAG, s)
    }

    init {
        WsClient.addProxyListener(this)
    }

    // ===== 이벤트 =====
    override fun onOpen() {
        _ui.value = _ui.value.copy(connectedToProxy = true)
        log("WS open")
    }
    override fun onClose(code: Int, reason: String) {
        _ui.value = _ui.value.copy(connectedToProxy = false, proxyReady = false)
        log("WS close: $code/$reason")
    }
    override fun onError(err: String) { log("WS error: $err") }
    override fun onRawMessage(text: String) { /* optional log */ }

    override fun onReady(session: String?) {
        _ui.value = _ui.value.copy(proxyReady = true)
        log("READY from proxy (session=$session)")
    }

    override fun onRpiStatus(session: String?, status: String, msgId: Int?) {
        log("RPi status: $status (session=$session msgId=$msgId)")
    }

    // 프록시가 보낸 공개키 fragment(body base64)를 "20바이트 패킷"으로 복원해 기존 reassemblePackets 사용
    private val pubkeyPacketMap = mutableMapOf<Int, MutableList<ByteArray>>() // msgId -> 20B packets

    override fun onPubkeyFragment(session: String?, msgId: Int, idx: Int, total: Int, payloadB64: String) {
        val body = Base64.decode(payloadB64, Base64.NO_WRAP) // ≤16B
        log("PK FRAG: msgId=$msgId idx=$idx/$total body=${body.size}B ${hexdump(body)}")

        // 20바이트 패킷으로 복원(헤더 + 16B padding)
        val packet20 = ByteArray(20).also { p ->
            p[0] = PUBKEY_FAKE_TYPE
            p[1] = (msgId and 0xFF).toByte()
            p[2] = (idx and 0xFF).toByte()
            p[3] = (total and 0xFF).toByte()
            val copyLen = minOf(16, body.size)
            System.arraycopy(body, 0, p, 4, copyLen)
        }

        val list = pubkeyPacketMap.getOrPut(msgId) { mutableListOf() }
        list.add(packet20)

        if (list.size < total) return

        // 재조립
        val pubkey = com.example.obsqura.reassemblePackets(list)
        pubkeyPacketMap.remove(msgId)
        log("PK ASSEMBLED: ${pubkey.size}B head=${hexdump(pubkey)}")

        viewModelScope.launch {
            try {
                log("KEM encapsulate start")
                val result = com.example.obsqura.KyberJNI.encapsulate(pubkey)
                val ct = result.ciphertext
                val key = result.sharedKey
                _ui.value = _ui.value.copy(sharedKey = key)
                log("KEM OK: ct=${ct.size}B key=${key.size}B keyHead=${hexdump(key)}")

                val msgIdCt = newMsgId()
                val packets = com.example.obsqura.splitDataIntoPackets(ct, TYPE_KYBER_CIPHERTEXT, msgIdCt)
                log("CT SPLIT: ${packets.size} packets")
                packets.forEachIndexed { i, pkt ->
                    if (i < 3 || i == packets.lastIndex) {
                        log("→ CT pkt[$i]: ${hexdump(pkt)}")
                    }
                    WsClient.sendPacketToProxy(pkt, typeStr = "handshake")
                }
                log("CT SENT: ${packets.size} packets")
            } catch (t: Throwable) {
                log("KEM FAIL: ${t::class.simpleName} ${t.message}")
            }
        }
    }

    // ===== 사용자 액션 =====
    fun requestPubkey() {
        pubkeyPacketMap.clear()
        WsClient.requestPubkey()
        log("CMD: request_pubkey")
    }

    fun setAutoMitm(enabled: Boolean) {
        WsClient.setAutoMitm(enabled)
        log("CMD: set_auto_mitm = $enabled")
    }

    fun sendLegacy(text: String) {
        val msgId = newMsgId()
        val payload = text.toByteArray(Charsets.UTF_8)
        val packets = com.example.obsqura.splitDataIntoPackets(payload, TYPE_TEXT_PLAIN, msgId)
        log("LEGACY SEND: '${text}' → ${packets.size} packets")
        packets.forEachIndexed { i, pkt ->
            if (i < 3 || i == packets.lastIndex) log("→ L pkt[$i]: ${hexdump(pkt)}")
            WsClient.sendPacketToProxy(pkt, typeStr = "legacy")
        }
    }

    fun sendSecure(text: String) {
        val key = _ui.value.sharedKey
        if (key == null) { log("SECURE SEND FAIL: no shared key"); return }

        val enc = aesGcmEncrypt(text, key) ?: run { log("AES-GCM encrypt fail"); return }
        // enc = nonce(12)|ct|tag(16)
        val nonce = enc.copyOfRange(0, 12)
        val tag = enc.copyOfRange(enc.size - 16, enc.size)
        val ct = enc.copyOfRange(12, enc.size - 16)
        log("AES-GCM: nonce=${nonce.size}B ct=${ct.size}B tag=${tag.size}B")
        log("AES-GCM head: nonce=${hexdump(nonce)} ct=${hexdump(ct)} tag=${hexdump(tag)}")

        val msgId = newMsgId()
        val packets = com.example.obsqura.splitDataIntoPackets(enc, TYPE_AES_MESSAGE, msgId)
        log("SECURE SEND: '${text}' enc=${enc.size}B → ${packets.size} packets")
        packets.forEachIndexed { i, pkt ->
            if (i < 3 || i == packets.lastIndex) log("→ S pkt[$i]: ${hexdump(pkt)}")
            WsClient.sendPacketToProxy(pkt, typeStr = "secure")
        }
    }

    private fun newMsgId(): Byte = (0..255).random().toByte()

    /** javax.crypto 직접 사용 (앱 기존 방식과 동일) */
    private fun aesGcmEncrypt(plaintext: String, key: ByteArray): ByteArray? {
        return try {
            val nonce = ByteArray(12).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            val out = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)) // CT||TAG
            val ct = out.copyOfRange(0, out.size - 16)
            val tag = out.copyOfRange(out.size - 16, out.size)
            nonce + ct + tag
        } catch (e: Exception) {
            Log.e(TAG, "AES-GCM error: ${e.message}", e)
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        WsClient.removeProxyListener(this)
    }
}

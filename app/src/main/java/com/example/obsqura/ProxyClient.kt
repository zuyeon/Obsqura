package com.example.obsqura

import android.util.Log
import android.util.Base64
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private const val PC_TAG = "ProxyClient"
private const val PC_DEBUG = true

/**
 * 가볍고 독립적인 WebSocket 클라이언트.
 * - viewer용 WsClient와 별개로 Proxy(중계) 연결을 따로 띄울 때 사용.
 */
class ProxyClient(private val url: String) : WebSocketListener() {

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    @Volatile var connected = false
        private set

    interface Listener {
        fun onOpen() {}
        fun onClose(code: Int, reason: String) {}
        fun onError(err: String) {}
        fun onRawText(msg: String) {}
        fun onRawBinary(bytes: ByteArray) {}
    }
    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.addIfAbsent(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    // 시작/중지
    fun start() {
        if (connected) return
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, this)
        logd("start -> $url")
    }

    fun stop() {
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        connected = false
        logd("stop")
    }

    /** JSON 커맨드 전송 */
    fun sendCommand(json: JSONObject): Boolean {
        val s = json.toString()
        logd("sendCommand: $s")
        return ws?.send(s) == true
    }

    /**
     * 20바이트 packet(헤더+payload)을 프록시에 그대로 릴레이.
     * typeHint는 디버깅/뷰어용 태그(선택).
     */
    fun sendRelayPacket(
        packet20: ByteArray,
        typeHint: String? = null,
        direction: String = "app->rpi"   // ← 기본값
    ): Boolean {
        val b64 = Base64.encodeToString(packet20, Base64.NO_WRAP)
        val obj = JSONObject().apply {
            put("kind", "relay")
            put("direction", direction)     // ← 추가
            put("payload_b64", b64)
            if (typeHint != null) put("type_hint", typeHint)
        }
        return sendCommand(obj)
    }

    /** 공개키 요청 (세션 아이디 선택) */
    fun requestPubkey(sessionId: String? = null): Boolean {
        val o = JSONObject().apply {
            put("cmd", "request_pubkey")
            if (sessionId != null) put("session", sessionId)
        }
        return sendCommand(o)
    }

    /** 프록시 자동 MITM on/off (데모용) */
    fun setAutoMitm(enabled: Boolean, sessionId: String? = null): Boolean {
        val o = JSONObject().apply {
            put("cmd", "set_auto_mitm")
            put("enabled", enabled)
            if (sessionId != null) put("session", sessionId)
        }
        return sendCommand(o)
    }

    // WebSocketListener callbacks
    override fun onOpen(webSocket: WebSocket, response: Response) {
        connected = true
        logd("onOpen")
        listeners.forEach { runCatching { it.onOpen() } }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        logd("onMessage (text): $text")
        listeners.forEach { runCatching { it.onRawText(text) } }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        val arr = bytes.toByteArray()
        logd("onMessage (bin) ${arr.size}B")
        listeners.forEach { runCatching { it.onRawBinary(arr) } }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        connected = false
        logd("onClosing $code/$reason")
        listeners.forEach { runCatching { it.onClose(code, reason) } }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        connected = false
        val msg = t.message ?: "unknown"
        loge("onFailure: $msg")
        listeners.forEach { runCatching { it.onError(msg) } }
    }

    private fun logd(s: String) { if (PC_DEBUG) Log.d(PC_TAG, s) }
    private fun loge(s: String) { Log.e(PC_TAG, s) }
}

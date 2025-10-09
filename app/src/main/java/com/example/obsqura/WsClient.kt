package com.example.obsqura

import android.util.Base64
import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

private const val DEBUG = true

object WsClient : WebSocketListener() {

    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var connected = false

    // ===== 이벤트 리스너 =====
    interface ProxyEventListener {
        fun onOpen() {}
        fun onClose(code: Int, reason: String) {}
        fun onReady(session: String?) {}
        fun onPubkeyFragment(session: String?, msgId: Int, idx: Int, total: Int, payloadB64: String) {}
        fun onRpiStatus(session: String?, status: String, msgId: Int?) {}
        fun onRawMessage(text: String) {}
        fun onError(err: String) {}
    }
    private val listeners = CopyOnWriteArrayList<ProxyEventListener>()
    fun addProxyListener(l: ProxyEventListener) { listeners.addIfAbsent(l) }
    fun removeProxyListener(l: ProxyEventListener) { listeners.remove(l) }

    // ===== 연결 제어 =====
    fun start(url: String = "ws://10.0.2.2:8080/ws") {
        if (connected) return
        val req = Request.Builder().url(url).build()
        ws = client.newWebSocket(req, this)
        Log.d("WS", "📡 프록시 연결 시도: $url")
    }
    fun stop() {
        ws?.close(1000, "bye")
        ws = null
        connected = false
        Log.d("WS", "🛑 프록시 연결 종료")
    }

    // ===== 기존 관찰용 API (유지) =====
    fun sendCopy(
        direction: String,
        mode: String,
        payloadBytes: ByteArray? = null,
        sessionId: String? = null,
        seq: Int? = null,
        mitm: Boolean? = null,
        event: String? = null,
        part: String? = null            // ★ 추가: "chunk" | "assembled"
    ): Boolean {
        val b64 = payloadBytes?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        val safeSession = sessionId ?: "unknown"   // ★ null-safe
        val jsonSb = StringBuilder()
        jsonSb.append('{')

        // (선택) 관찰용 이벤트임을 식별하고 싶으면 다음 한 줄 추가해도 됨
        // jsonSb.append("\"kind\":\"copy\",")

        jsonSb.append("\"timestamp\":").append("\"").append(System.currentTimeMillis()).append("\",")
        jsonSb.append("\"direction\":").append("\"").append(direction).append("\",")
        jsonSb.append("\"mode\":").append("\"").append(mode).append("\"")
        jsonSb.append(",\"session_id\":\"").append(safeSession).append("\"") // ★ 항상 출력

        if (seq != null)   jsonSb.append(",\"seq\":").append(seq)
        if (mitm != null)  jsonSb.append(",\"mitm\":").append(mitm)
        if (event != null) jsonSb.append(",\"event\":\"").append(event).append("\"")
        if (part != null)  jsonSb.append(",\"part\":\"").append(part).append("\"") // ★ 추가
        if (b64 != null)   jsonSb.append(",\"payload_b64\":\"").append(b64).append("\"")
        jsonSb.append('}')
        return (ws?.send(jsonSb.toString()) == true)
    }

    // ===== 프록시 제어 커맨드 =====
    private fun sendCommand(o: JSONObject): Boolean {
        val json = o.toString()
        if (DEBUG) Log.d("WS", "➡️ CMD: $json")
        return (ws?.send(json) == true)
    }

    fun connectRpi(sessionId: String? = null, rpiName: String? = null): Boolean {
        val o = JSONObject().apply {
            put("cmd", "connect_rpi")
            if (sessionId != null) put("session", sessionId)
            if (rpiName != null) put("rpi_name", rpiName)
        }
        return sendCommand(o)
    }

    fun requestPubkey(sessionId: String? = null): Boolean {
        val o = JSONObject().apply {
            put("cmd", "request_pubkey")
            if (sessionId != null) put("session", sessionId)
        }
        return sendCommand(o)
    }

    /** 프록시에 20바이트 패킷(헤더+payload)을 그대로 전달 */
    fun sendPacketToProxy(packet20: ByteArray, typeStr: String? = null): Boolean {
        // ★ 절대 헤더 재구성 금지! packet20 그대로 보냄
        val b64 = Base64.encodeToString(packet20, Base64.NO_WRAP)
        val json = buildString {
            append('{')
            append("\"kind\":\"relay\",")
            append("\"direction\":\"app->rpi\",")
            if (typeStr != null) append("\"type_hint\":\"").append(typeStr).append("\",")
            append("\"payload_b64\":\"").append(b64).append("\"")
            append('}')
        }
        return (ws?.send(json) == true)
    }

    fun setAutoMitm(enabled: Boolean, sessionId: String? = null): Boolean {
        val o = JSONObject().apply {
            put("cmd", "set_auto_mitm"); put("enabled", enabled)
            if (sessionId != null) put("session", sessionId)
        }
        return sendCommand(o)
    }

    fun requestStatus(sessionId: String? = null): Boolean {
        val o = JSONObject().apply {
            put("cmd", "status")
            if (sessionId != null) put("session", sessionId)
        }
        return sendCommand(o)
    }

    // ===== WS 콜백 =====
    override fun onOpen(webSocket: WebSocket, response: Response) {
        connected = true
        Log.d("WS", "✅ 프록시 연결 성공")
        listeners.forEach { runCatching { it.onOpen() } }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Log.d("WS", "📩 Proxy->App Text: $text")
        listeners.forEach { runCatching { it.onRawMessage(text) } }

        runCatching {
            val obj = JSONObject(text)
            val evt = when {
                obj.has("evt") -> obj.getString("evt")
                obj.has("event") -> obj.getString("event")
                else -> null
            } ?: return

            when (evt) {
                "ready", "connected_rpi" -> {
                    val session = obj.optString("session", null)
                    listeners.forEach { runCatching { it.onReady(session) } }
                }
                "pubkey_fragment" -> {
                    val session = obj.optString("session", null)
                    val msgId = if (obj.has("msgId")) obj.getInt("msgId") else obj.optInt("msg_id", -1)
                    val idx = obj.optInt("idx", -1)
                    val total = obj.optInt("total", -1)
                    val payloadB64 = obj.optString("payload_b64", obj.optString("payload", ""))
                    if (payloadB64.isNotEmpty()) {
                        listeners.forEach { runCatching { it.onPubkeyFragment(session, msgId, idx, total, payloadB64) } }
                    } else {
                        Log.w("WS", "pubkey_fragment without payload")
                    }
                }
                "rpi_status" -> {
                    val session = obj.optString("session", null)
                    val status = obj.optString("status", "unknown")
                    val msgId = if (obj.has("msgId")) obj.optInt("msgId") else obj.optInt("msg_id", -1)
                    listeners.forEach { runCatching { it.onRpiStatus(session, status, if (msgId >= 0) msgId else null) } }
                }
                "status_response" -> {
                    val session = obj.optString("session", null)
                    val status = obj.optString("status", "ok")
                    listeners.forEach { runCatching { it.onRpiStatus(session, status, null) } }
                }
            }
        }.onFailure {
            Log.d("WS", "onMessage parse failure: ${it.message}. raw=$text")
        }
    }

    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        Log.d("WS", "📩 Proxy->App Binary: ${bytes.size}B")
        listeners.forEach { runCatching { it.onRawMessage(bytes.base64()) } }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        connected = false
        Log.d("WS", "⚠️ Proxy 연결 종료중: $code / $reason")
        listeners.forEach { runCatching { it.onClose(code, reason) } }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        connected = false
        Log.e("WS", "❌ Proxy 연결 실패: ${t.message}")
        listeners.forEach { runCatching { it.onError(t.message ?: "unknown") } }
    }
}

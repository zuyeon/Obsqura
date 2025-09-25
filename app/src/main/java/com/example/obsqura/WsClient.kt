// WsClient.kt
package com.example.obsqura // ← 실제 패키지로

import okhttp3.*
import android.util.Base64
import android.util.Log

class WsClient(
    private val url: String,
    private val onOpen: () -> Unit = {},
    private val onFail: (String) -> Unit = {},
    private val onText: (String) -> Unit = {}
) {
    private val client = OkHttpClient()
    private val req = Request.Builder().url(url).build()
    private var ws: WebSocket? = null
    private val tag = "WS"

    fun connect() {
        Log.d(tag, "WS open")
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WS open")
                onOpen()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(tag, "WS recv: $text")
                onText(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WS fail: ${t.message}")
                onFail(t.message ?: "unknown")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WS closed: $code $reason")
            }
        })
    }

    fun sendBytes(payload: ByteArray, mode: String = "legacy", autoForward: Boolean = false) {
        val b64 = Base64.encodeToString(payload, Base64.NO_WRAP)
        val json = """{"type":"app_send","data":"$b64","mode":"$mode","auto_forward":$autoForward}"""
        Log.d(tag, "WS send: ${payload.size}B mode=$mode")
        ws?.send(json)
    }

    fun close() { ws?.close(1000, "bye") }
}

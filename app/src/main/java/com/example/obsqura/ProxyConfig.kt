package com.example.obsqura

object ProxyConfig {
    // WebSocket 프록시 서버 주소 (전시 네트워크에 맞게 수정)
    const val PROXY_WS_URL = "ws://100.99.76.47:8765/ws"

    // (옵션) HTTP Fallback 주소
    const val PROXY_HTTP_URL = "http://100.99.76.47:8765/packet"

    // 자동 재연결 지연 시간 (ms)
    const val SOCKET_RECONNECT_MS = 2000L

    // HTTP POST 전송 timeout (ms)
    const val HTTP_TIMEOUT_MS = 600

    // 전시/디버깅 환경 플래그
    const val IS_DEMO_ENV = true
}

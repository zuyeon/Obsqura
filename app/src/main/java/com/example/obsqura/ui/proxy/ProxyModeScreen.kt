package com.example.obsqura.ui.proxy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.obsqura.ProxyClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxyModeScreen(
    proxyClient: ProxyClient?,   // MainActivity에서 넘기는 Proxy2 클라이언트
    onBack: () -> Unit
) {
    // logs: mutableStateListOf으로 하면 add/remove 시 Compose가 자동으로 다시 그림
    val logs = remember { mutableStateListOf<String>() }
    var mitm by remember { mutableStateOf(false) }
    // connected 상태를 Compose에서 관찰 가능하게 유지
    var connected by remember { mutableStateOf(proxyClient?.connected == true) }

    // ProxyClient 이벤트 구독: 리스너에서 logs/connected 업데이트
    DisposableEffect(proxyClient) {
        val l = object : ProxyClient.Listener {
            override fun onOpen() {
                // Compose 상태는 메인 스레드에서 변경되어야 안전
                logs.add("WS(open)")
                connected = true
            }
            override fun onClose(code: Int, reason: String) {
                logs.add("WS(close) $code/$reason")
                connected = false
            }
            override fun onError(err: String) {
                logs.add("WS(error) $err")
            }
            override fun onRawText(msg: String) {
                logs.add("RX: $msg")
            }
            override fun onRawBinary(bytes: ByteArray) {
                logs.add("RX(bin ${bytes.size}B)")
            }
        }
        proxyClient?.addListener(l)

        onDispose {
            proxyClient?.removeListener(l)
        }
    }

    // UI
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Proxy Mode", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AssistChip(
                onClick = { /* no-op */ },
                label = { Text(if (connected) "Connected" else "Disconnected") },
                enabled = false
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { proxyClient?.start() }, enabled = !connected) {
                    Text("Connect")
                }
                OutlinedButton(onClick = { proxyClient?.stop() }, enabled = connected) {
                    Text("Disconnect")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { proxyClient?.requestPubkey(null) },
                    enabled = connected
                ) { Text("Request Pubkey") }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = mitm,
                        onCheckedChange = {
                            mitm = it
                            proxyClient?.setAutoMitm(it, null)
                        },
                        enabled = connected
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Auto MITM")
                }
            }

            Text("Logs")
            Divider()

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(logs.size) { i ->
                    Text(logs[i], style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

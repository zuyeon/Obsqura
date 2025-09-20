package com.example.obsqura.ui.scenario

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.example.obsqura.BLEConnectionManager
import com.example.obsqura.ui.theme.BLECommunicatorTheme

@Preview(
    name = "Scenario – Guarded Preview (Phone Light)",
    device = Devices.PIXEL_6, showSystemUi = true, showBackground = true
)
@Preview(
    name = "Scenario – Guarded Preview (Phone Dark)",
    device = Devices.PIXEL_6, showSystemUi = true, showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
fun Preview_ScenarioModeScreen_Guarded() {
    val context = LocalContext.current
    val ble = remember {
        BLEConnectionManager(
            context = context,
            onPublicKeyReceived = { /* no-op */ },
            logCallback = { /* no-op */ },
            progressCallback = { _, _ -> /* no-op */ },
            receiveProgressCallback = { _, _ -> /* no-op */ }
        )
    }

    BLECommunicatorTheme {
        // Scenario 화면은 BluetoothAdapter 파라미터가 없으므로 바로 호출 가능
        // (버튼 onClick은 프리뷰 렌더 중엔 실행되지 않음)
        ScenarioModeScreen(
            ble = ble,
            onBack = {}
        )
    }
}

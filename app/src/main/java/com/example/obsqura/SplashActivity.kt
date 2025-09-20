// SplashActivity.kt
package com.example.obsqura

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current

            // 큰 이미지를 안전하게 축소 디코딩
            val logo by remember {
                mutableStateOf(
                    run {
                        // 1) 먼저 크기만 확인
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeResource(context.resources, R.drawable.heart_lock, bounds)

                        // 2) 목표 최대 픽셀 크기(예: 1024px)로 맞추기 위해 inSampleSize 계산
                        val maxSize = 1024 // 필요하면 512~1024 사이로 조절
                        var sample = 1
                        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) {
                            sample *= 2
                        }

                        // 3) 실제 디코딩
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        val bmp = BitmapFactory.decodeResource(context.resources, R.drawable.heart_lock, opts)
                        bmp?.asImageBitmap()
                    }
                )
            }

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                logo?.let {
                    Image(
                        bitmap = it,
                        contentDescription = "앱 로고",
                        modifier = Modifier.size(180.dp) // 화면에 보이는 크기
                    )
                }
            }
        }

        // 2초 뒤 메인으로
        lifecycleScope.launch {
            delay(5000)
            startActivity(Intent(this@SplashActivity, MainActivity::class.java))
            finish()
        }
    }
}


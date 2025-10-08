// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // Jetpack Compose용 Kotlin Compose 플러그인(AGP 8.2+에서 권장)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.obsqura"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.obsqura"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // JNI/NDK: 사용하는 ABI만 유지
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // CMake 인자(필요 시)
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_PLATFORM=android-24"
                    // 필요하면 C99 강제
                    // "-DCMAKE_C_FLAGS=-std=c99"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            // 네트워크/WS 디버깅 편의
            isMinifyEnabled = false
        }
    }

    // Java/Kotlin 17 권장
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // CMakeLists.txt 경로 연결
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    // (선택) 리소스/메타데이터 충돌 방지
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // --- AndroidX / Compose BOM ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)

    // 수명주기와 Compose 연동(collectAsStateWithLifecycle 등)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Coroutines (최신 권장)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // XML 테마 리소스(Material Components)
    implementation("com.google.android.material:material:1.12.0")

    // legacy attr(windowActionBar 등) 참조 안전망
    implementation("androidx.appcompat:appcompat:1.7.0")

    // OkHttp(WebSocket 포함) — 단일 최신 버전만 유지!
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // (선택) M3만 쓰면 legacy Material 제거 가능
    // implementation("com.google.android.material:material:1.12.0")

    // 테스트
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.beaconscanner"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.beaconscanner"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // local.properties의 server.ip = 데이터 수집용 노트북 IP (BeaconConfig.COLLECT_URL에서 사용)
        buildConfigField(
            "String",
            "SERVER_IP",
            "\"${localProps.getProperty("server.ip", "192.168.0.3")}\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // 🟢 1. 핵심: Lifecycle & Service 라이브러리 (LifecycleService 빨간줄 해결원인)
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")

    // 🟢 2. BLE Beacon 스캔 라이브러리
    implementation("org.altbeacon:android-beacon-library:2.20.3")

    // 🟢 3. 네트워크 통신 (OkHttp)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 🟢 4. UI 및 안드로이드 기본 컴포넌트
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
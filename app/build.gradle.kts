plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.implacavel.alarme"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.implacavel.alarme"
        minSdk = 26
        // Testado no Android 16. Subir pra 37 exige revisar as mudanças de comportamento do Android 17.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Só processadores ARM (os de qualquer celular Android): o ML Kit traz ~9 MB por tipo,
        // e x86 só serve pra emulador e Chromebook
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Assinado com a chave de debug: basta pra instalar no seu próprio celular
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    // Câmera frontal (CameraX) e detecção de rosto e olhos abertos no aparelho (ML Kit, modelo embutido)
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.google.mlkit:face-detection:16.1.7")
    testImplementation("junit:junit:4.13.2")
    // org.json de verdade nos testes (o do android.jar dos testes só lança exceção)
    testImplementation("org.json:json:20260814")
}

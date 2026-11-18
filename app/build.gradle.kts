import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 모든 빌드가 같은 키로 서명되게 함 (그래야 덮어 설치가 되고 앱 데이터(치환 규칙)가 유지됨)
val sharedKeyText = file("debug.keystore.b64")
val sharedKeyFile = layout.buildDirectory.file("shared-debug.keystore").get().asFile
if (sharedKeyText.exists()) {
    sharedKeyFile.parentFile.mkdirs()
    sharedKeyFile.writeBytes(
        Base64.getMimeDecoder().decode(sharedKeyText.readText().trim())
    )
}

android {
    namespace = "com.ss2ux.subclean"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ss2ux.subclean"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        getByName("debug") {
            if (sharedKeyText.exists()) {
                storeFile = sharedKeyFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
}

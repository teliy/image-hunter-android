plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.teliy.imagehunter"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.teliy.imagehunter"
        minSdk = 29            // Android 10: MediaStore без разрешений на запись
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Фиксированный debug-keystore в репо: APK из любого прогона CI (и локальные)
    // подписаны одним ключом — иначе установка обновления «поверх» старой версии
    // упадёт с несовпадением подписей. Ключ отладочный, пароль стандартный.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file(".debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")   // смена языка приложения
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // загрузка картинок с Referer
    implementation("io.coil-kt:coil-compose:2.7.0")        // миниатюры галереи
}

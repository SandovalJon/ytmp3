plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sandovaljon.ytmp3"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sandovaljon.ytmp3"
        minSdk = 24
        targetSdk = 34
        versionCode = 5
        versionName = "1.0.4"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
            // El APK debug es el que se instala directamente en el teléfono.
            isDebuggable = true
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Motor de descarga: yt-dlp + ffmpeg para Android.
    // JunkFood02/youtubedl-android 0.18.1 (Maven Central) es el fork mantenido
    // del proyecto original yausername/youtubedl-android (abandonado).
    // La 0.18.1 incluye yt-dlp 2025.11.12 y un runtime QuickJS, necesario
    // porque yt-dlp dejó de usar su intérprete JS interno para YouTube.
    // Se descartó ffmpegkit-maintained/yt-dlp-android 2.x: está basado en
    // Chaquopy (Python 3.13 embebido), es más pesado de compilar, exige un
    // plugin Gradle propio y no permite actualizar yt-dlp en tiempo de
    // ejecución; JunkFood02 sí permite updateYoutubeDL() y es la librería
    // que usan en producción Seal y YTDLnis.
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}

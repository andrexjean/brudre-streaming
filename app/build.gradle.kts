import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val libtorrentVersion = "2.1.0-38"
val media3Version = "1.5.1"
val roomVersion = "2.6.1"

// Assinatura fixa: permite atualizar o app no Mi Stick sem desinstalar (preserva Minha Lista e progresso).
// Chave da API do TMDB: variável de ambiente (CI, segredo do GitHub) ou local.properties (tmdb.apiKey).
// Nunca versionada no repositório.
val tmdbApiKey: String = System.getenv("TMDB_API_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    ?: rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
        Properties().apply { f.inputStream().use { load(it) } }.getProperty("tmdb.apiKey")
    } ?: ""

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.brudre.streaming"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.brudre.streaming"
        minSdk = 24
        targetSdk = 35
        // Número do build do GitHub Actions: cada release tem versionCode maior (permite atualizar por cima).
        val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 92000 + buildNumber
        versionName = "9.2.$buildNumber"
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())
        buildConfigField("String", "UPDATE_REPO", "\"andrexjean/brudre-streaming\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
    }

    signingConfigs {
        create("brudre") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (keystoreProps.isNotEmpty()) signingConfig = signingConfigs.getByName("brudre")
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64", "x86") }
        }
        release {
            // R8: remove código não usado e otimiza o Compose (bem mais fluido no Mi Stick).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("brudre") else signingConfigs.getByName("debug")
            // Mi TV Stick (Amlogic S805Y) usa userland ARM 32 bits; arm64 incluído para outros aparelhos.
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
                // Só no CI: inclui x86_64 para medir o APK release no emulador.
                if (project.hasProperty("perfAbi")) abiFilters += "x86_64"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        animationsDisabled = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")

    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("io.coil-kt:coil-compose:2.7.0")
    // Instala os perfis de compilação do Compose também em APK instalado fora da Play Store.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Motor BitTorrent real (libtorrent 2.x via JNI, licença MIT)
    implementation("org.libtorrent4j:libtorrent4j:$libtorrentVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$libtorrentVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$libtorrentVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:$libtorrentVersion")
    implementation("org.libtorrent4j:libtorrent4j-android-x86_64:$libtorrentVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

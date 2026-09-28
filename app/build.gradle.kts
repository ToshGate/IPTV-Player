import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

// Release signing comes from keystore.properties at the project root — never committed (see
// .gitignore) — so the actual keystore path/passwords never end up in the public repository.
// See keystore.properties.example for the expected format. If this file is missing (e.g. anyone
// else cloning the repo), the release build type simply builds unsigned instead of failing.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasKeystoreProperties = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasKeystoreProperties) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.tosh.iptvplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tosh.iptvplayer"
        minSdk = 24
        targetSdk = 34
        versionCode = 9
        versionName = "1.6.0"
    }

    signingConfigs {
        if (hasKeystoreProperties) {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasKeystoreProperties) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.3.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")

    // Media3 ExoPlayer for IPTV playback (HLS / DASH / progressive / RTMP-ish via extractors)
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    // Image loading for channel logos
    implementation("io.coil-kt:coil:2.7.0")

    // Local persistence for playlists/channels
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // WireGuard VPN — embeddable tunnel library, used for importing/connecting with a
    // manually-exported WireGuard config (e.g. downloaded from a VPN provider's dashboard).
    implementation("com.wireguard.android:tunnel:1.0.20260102")

    // Encrypted storage for the imported VPN config, since it contains a private key.
    // Deprecated by Google. Only kept so VpnRepository can read profiles saved by earlier versions
    // and move them to SecureStore (Android Keystore) — delete this line, together with
    // VpnRepository.migrateFromEncryptedSharedPreferences(), once nobody is on those versions.
    implementation("androidx.security:security-crypto:1.1.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

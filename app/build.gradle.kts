plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.dalbrocante.gestorefile"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.dalbrocante.gestorefile"
        minSdk = 26
        targetSdk = 35
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + run
        versionName = "0.3.$run"
    }

    // Firma stabile: stesso keystore a ogni build, così gli aggiornamenti si installano sopra la versione precedente.
    signingConfigs {
        create("stable") {
            storeFile = rootProject.file("signing/gestorefile.jks")
            storePassword = "euKvURZ7FmumpofWl4h0"
            keyAlias = "gestore"
            keyPassword = "euKvURZ7FmumpofWl4h0"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("stable") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/AL2.0", "META-INF/LGPL2.1",
            "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/versions/**", "META-INF/*.kotlin_module",
        )
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Immagini (gif, webp, heic dove supportato)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    // Media player
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // Archivi
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.github.junrar:junrar:7.5.5")
    implementation("org.tukaani:xz:1.10")

    // Server FTP integrato
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    implementation("org.slf4j:slf4j-android:1.7.36")

    // OCR e scanner documenti (ML Kit)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")

    // PDF: unisci, dividi, firma
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Reti: FTP, SFTP, SMB
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.20")
    implementation("com.hierynomus:smbj:0.13.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// pdfbox-android e smbj portano due varianti di BouncyCastle: teniamo solo jdk18on.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
}

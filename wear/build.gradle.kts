plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "it.dalbrocante.gestorefile.wear"
    compileSdk = 35

    defaultConfig {
        // Stesso applicationId del telefono: obbligatorio per far dialogare le due app via Data Layer.
        applicationId = "it.dalbrocante.gestorefile"
        minSdk = 30
        targetSdk = 28 // volutamente basso: permette il permesso classico Archiviazione, l'unico richiedibile su Wear OS
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 100 + run
        versionName = "0.3.$run"
        buildConfigField("int", "BUILD_NUMBER", "$run")
    }

    // Stesso keystore del telefono (firma identica = requisito del Data Layer).
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":shared"))
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}

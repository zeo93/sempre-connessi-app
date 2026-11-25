plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.sempreconnessi"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.sempreconnessi"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Firma: se nei "secrets" di GitHub c'e' una chiave la usa (cosi' gli
    // aggiornamenti si installano sopra la versione vecchia), altrimenti usa
    // la chiave di debug.
    val ksFile = System.getenv("FIRMA_KEYSTORE_FILE")
    signingConfigs {
        if (ksFile != null && file(ksFile).exists()) {
            create("rilascio") {
                storeFile = file(ksFile)
                storePassword = System.getenv("FIRMA_PASSWORD")
                keyAlias = System.getenv("FIRMA_ALIAS") ?: "sempreconnessi"
                keyPassword = System.getenv("FIRMA_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("rilascio") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

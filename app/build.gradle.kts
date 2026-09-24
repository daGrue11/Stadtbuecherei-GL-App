plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "de.bibgl.konto"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.bibgl.konto"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.6"
    }

    /*
     * Fester Signaturschlüssel.
     *
     * Android installiert eine neue Version nur dann über eine vorhandene, wenn
     * beide mit demselben Schlüssel signiert sind. Ohne feste Konfiguration
     * erzeugt Gradle bei jedem CI-Lauf einen neuen Zufallsschlüssel (die Runner
     * werden jedes Mal frisch aufgesetzt) - Updates schlügen dann fehl.
     *
     * Schlüssel und Passwort liegen NICHT im Repository, sondern in den
     * GitHub-Secrets KEYSTORE_BASE64 und KEYSTORE_PASSWORD. Der Workflow legt
     * keystore.jks vor dem Build an.
     *
     * Fehlt der Schlüssel (z.B. beim Bauen eines Forks), wird stattdessen mit dem
     * Standard-Debugschlüssel signiert. Der APK ist dann lauffähig, taugt aber
     * nicht als Update einer offiziell veröffentlichten Version.
     */
    val keystoreFile = file("keystore.jks")
    val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
    val canSign = keystoreFile.exists() && !keystorePassword.isNullOrBlank()

    signingConfigs {
        create("stable") {
            if (canSign) {
                storeFile = keystoreFile
                storeType = "PKCS12"
                storePassword = keystorePassword
                keyAlias = "bibgl"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (canSign) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            signingConfig =
                if (canSign) signingConfigs.getByName("stable")
                else signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}

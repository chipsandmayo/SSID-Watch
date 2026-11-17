plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

// AdMob IDs: real ones come from GitHub secrets; otherwise Google's official test IDs.
val admobAppId = System.getenv("ADMOB_APP_ID")?.takeIf { it.isNotBlank() }
    ?: "ca-app-pub-3940256099942544~3347511713"
val admobBannerId = System.getenv("ADMOB_BANNER_ID")?.takeIf { it.isNotBlank() }
    ?: "ca-app-pub-3940256099942544/9214589741"

android {
    namespace = "com.ssidwatch"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ssidwatch"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        manifestPlaceholders["admobAppId"] = admobAppId
        buildConfigField("String", "ADMOB_BANNER_ID", "\"$admobBannerId\"")
    }

    // Old, public signing key. Only used for local builds, as the fallback in CI, and as
    // the "previous signer" in the key-rotation proof. See README.
    signingConfigs {
        create("release") {
            storeFile = file("ssidwatch.jks")
            storePassword = "ssidwatch"
            keyAlias = "ssidwatch"
            keyPassword = "ssidwatch"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // In CI with signing secrets, the APK is left unsigned here and signed afterwards
            // with the private key (plus a rotation proof from the old key) by apksigner.
            signingConfig = if (System.getenv("SIGNING_KEYSTORE_B64").isNullOrEmpty()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")
}

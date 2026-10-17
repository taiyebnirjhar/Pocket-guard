plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version lives in VERSION (major.minor.patch). Bump it to publish a release.
val appVersion = rootProject.file("VERSION").readText().trim()
val versionParts = appVersion.split(".").map { it.toIntOrNull() }
require(versionParts.size == 3 && versionParts.all { it != null && it in 0..999 }) {
    "VERSION must be major.minor.patch with numbers 0-999, got \"$appVersion\""
}
// 1.2.3 -> 1002003, so every higher version installs as an update over a lower one.
val appVersionCode = versionParts.fold(0) { code, part -> code * 1000 + part!! }

android {
    namespace = "com.taiyeb.pocketguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.taiyeb.pocketguard"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion

        // The online part (live location, alert emails). CI passes the project and its public
        // (publishable) key from GitHub secrets; without them the app works offline only.
        buildConfigField("String", "SUPABASE_URL", "\"${System.getenv("SUPABASE_URL").orEmpty().trimEnd('/')}\"")
        buildConfigField("String", "SUPABASE_KEY", "\"${System.getenv("SUPABASE_ANON_KEY").orEmpty()}\"")
        buildConfigField("String", "LIVE_PAGE_URL", "\"https://taiyebnirjhar.github.io/Pocket-guard/live/\"")
    }

    // Official builds are signed in CI with a key kept in GitHub secrets (see README), so updates
    // install over each other. Without it (forks, local builds) the standard debug key is used.
    val keystorePath = System.getenv("SIGNING_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("official") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "pocketguard"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("official")
        }
        // The Play build is uploaded as a release bundle, signed with the same key (Play's
        // upload key); GitHub releases stay the debug APK the e2e test installs.
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("official")
        }
    }

    // Two builds from one codebase. They differ only in what Google Play lets this app declare:
    // the Play build leaves out restricted permissions and uses the allowed alternative.
    flavorDimensions += "store"
    productFlavors {
        create("full") {
            dimension = "store"
            buildConfigField("boolean", "PLAY_STORE", "false")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "PLAY_STORE", "true")
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
        abortOnError = true
        checkDependencies = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Play services pulls in an old fragment library, which breaks registerForActivityResult.
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    testImplementation("junit:junit:4.13.2")
}

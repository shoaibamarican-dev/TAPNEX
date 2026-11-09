plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

/**
 * Secrets and ad settings are read from Gradle properties (~/.gradle/gradle.properties or
 * project gradle.properties) or from environment variables with the same name. Nothing secret is
 * stored in this file or in git. See README.md for the full list.
 */
fun setting(name: String): String? =
    (project.findProperty(name) as? String)?.takeIf { it.isNotBlank() } ?: System.getenv(name)?.takeIf { it.isNotBlank() }

val PROD_BANNER_AD_UNIT_ID = "ca-app-pub-2888936097455517/1557814503"
val PROD_REWARDED_AD_UNIT_ID = "ca-app-pub-2888936097455517/9384333631"
val TEST_BANNER_AD_UNIT_ID = "ca-app-pub-3940256099942544/6300978111"
val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

android {
    namespace = "com.tapnex.autoclicker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tapnex.autoclicker"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "USE_TEST_ADS", "false")
        buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$PROD_BANNER_AD_UNIT_ID\"")
        buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"$PROD_REWARDED_AD_UNIT_ID\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        // Optional fixed debug key, so the debug APK keeps the same SHA-1/SHA-256 across CI runs.
        getByName("debug") {
            setting("TAPNEX_DEBUG_KEYSTORE_PATH")?.let { path ->
                storeFile = file(path)
                storePassword = setting("TAPNEX_DEBUG_KEYSTORE_PASSWORD")
                keyAlias = setting("TAPNEX_DEBUG_KEY_ALIAS")
                keyPassword = setting("TAPNEX_DEBUG_KEY_PASSWORD")
            }
        }
        // Release signing is only configured when the keystore secrets exist. Never commit keystores.
        setting("TAPNEX_KEYSTORE_PATH")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = setting("TAPNEX_KEYSTORE_PASSWORD")
                keyAlias = setting("TAPNEX_KEY_ALIAS")
                keyPassword = setting("TAPNEX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Debug builds always use Google test ads and never grant production credits.
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "USE_TEST_ADS", "true")
            buildConfigField("String", "BANNER_AD_UNIT_ID", "\"$TEST_BANNER_AD_UNIT_ID\"")
            buildConfigField("String", "REWARDED_AD_UNIT_ID", "\"$TEST_REWARDED_AD_UNIT_ID\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.03.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    // Firebase BOM must be applied with platform() so the Firebase libraries below get versions.
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-functions")
    // Credential Manager + Google ID token for Firebase Google Sign-In.
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

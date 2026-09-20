plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.jarvis.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        // Non-secret runtime defaults, always safe to bake in.
        val aiBaseUrl = ((project.findProperty("AI_DEFAULT_BASE_URL") as String?)?.trim()?.takeIf { it.isNotBlank() })
            ?: "https://api.openai.com/"
        buildConfigField("String", "DEFAULT_AI_BASE_URL", "\"${aiBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        val aiModel = ((project.findProperty("AI_DEFAULT_MODEL") as String?)?.trim()?.takeIf { it.isNotBlank() })
            ?: "gpt-4o-mini"
        buildConfigField("String", "DEFAULT_AI_MODEL", "\"${aiModel.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        // Only ever pass this in via a GitHub Actions *secret* (never a committed file / repo
        // variable) - this repo is public, and this field ends up readable inside the built APK
        // regardless, so treat it as "shipped with the app", not truly hidden. Empty by default:
        // Settings still lets the owner type/paste their own key on-device at any time, which
        // always takes priority over this baked-in default (see SecurePrefs.aiApiKey).
        val aiApiKey = (project.findProperty("AI_DEFAULT_API_KEY") as String?)?.trim() ?: ""
        buildConfigField("String", "DEFAULT_AI_API_KEY", "\"${aiApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")

        val remoteRelayUrl = (project.findProperty("REMOTE_RELAY_URL") as String?)?.trim()?.trimEnd('/') ?: ""
        buildConfigField("String", "DEFAULT_REMOTE_RELAY_URL", "\"${remoteRelayUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    // Optional release signing: only active when RELEASE_KEYSTORE_PATH (+ password/alias props) are passed
    // in, e.g. from GitHub Actions secrets. Without them release builds stay unsigned; debug is unaffected.
    val releaseKeystorePath = (project.findProperty("RELEASE_KEYSTORE_PATH") as String?)?.trim()?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = (project.findProperty("RELEASE_KEYSTORE_PASSWORD") as String?) ?: ""
                keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?) ?: ""
                keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?) ?: ""
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].apply {
        kotlin.srcDirs("src/main/kotlin")
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // WinGo Analyzer: on-device OCR (bundled Latin model, no network needed)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

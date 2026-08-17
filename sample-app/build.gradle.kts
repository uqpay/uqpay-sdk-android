import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Sandbox credentials come from local.properties, which is gitignored. Never hardcode
// them here, and never commit them. Absent values build fine — the app then tells you
// what is missing instead of failing at runtime with something cryptic.
val localProperties = Properties().apply {
    rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.inputStream()
        ?.use { load(it) }
}

fun localProperty(name: String): String = localProperties.getProperty(name).orEmpty()

// An x-api-key can issue refunds and payouts, and it is NEVER a valid access token —
// pasting one into uqpay.sandboxToken produces a 401 that reads like an expired
// credential, while quietly compiling the key into the sample APK via BuildConfig.
// That happened once. Fail loudly rather than ship it again.
//
// Note uqpay.sandboxApiKey is deliberately NOT exposed as a buildConfigField: the app
// has no use for it. Only scripts/mint-sandbox-token.sh reads it, outside the build.
val sandboxToken = localProperty("uqpay.sandboxToken")
val sandboxApiKey = localProperty("uqpay.sandboxApiKey")
require(sandboxToken.isEmpty() || sandboxToken != sandboxApiKey) {
    "uqpay.sandboxToken in local.properties holds your x-api-key, not an access token. " +
        "The API key must never be compiled into an app. Run " +
        "./scripts/mint-sandbox-token.sh to mint a real token."
}

android {
    namespace = "com.uqpay.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.uqpay.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "UQPAY_CLIENT_ID", "\"${localProperty("uqpay.clientId")}\"")
        // A short-lived sandbox access token, pasted from your backend for manual
        // testing. A real integration fetches this from its own server on demand — see
        // SampleTokenProvider.
        buildConfigField("String", "UQPAY_SANDBOX_TOKEN", "\"${localProperty("uqpay.sandboxToken")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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

dependencies {
    implementation(project(":uqpay-sdk"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
}

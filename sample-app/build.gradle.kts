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
// credential, while quietly compiling the key into the sample APK in the wrong slot.
// That happened once. Fail loudly rather than ship it again.
//
// This guard is about the *slot*, and it is still exactly as necessary now that the key
// has a slot of its own below: a key in the token field is a different mistake, and it
// still produces a payment flow that fails for a reason nobody can read.
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
        // A short-lived sandbox access token, optionally pasted from your backend for
        // manual testing. A real integration fetches this from its own server on demand —
        // see DemoMerchantBackend.
        buildConfigField("String", "UQPAY_SANDBOX_TOKEN", "\"$sandboxToken\"")

        // Empty here, and filled in for `debug` only, below. Every build type gets the
        // field so the source always compiles; only a debug build gets a value.
        buildConfigField("String", "UQPAY_SANDBOX_API_KEY", "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        // ─────────────────────────────────────────────────────────────────────────────
        //  The merchant x-api-key reaches the APK in DEBUG BUILDS ONLY.
        //
        //  It is here so this demo runs standalone: with a key the app mints its own
        //  30-minute access tokens and nobody has to re-run a script and rebuild every
        //  half hour. That convenience is worth exactly one debug build and nothing more.
        //
        //  An x-api-key can issue refunds and payouts, and an APK cannot keep a secret —
        //  `strings` on a downloaded binary is enough. So `release` gets "" and
        //  DemoMerchantBackend refuses to mint, saying why. A real merchant keeps the key
        //  on their own server and never puts it in an app at all; see the file header
        //  on DemoMerchantBackend.kt.
        //
        //  local.properties is gitignored, so nothing here reaches version control. The
        //  exposure is a locally built debug APK, and that is the whole of it.
        // ─────────────────────────────────────────────────────────────────────────────
        debug {
            buildConfigField("String", "UQPAY_SANDBOX_API_KEY", "\"$sandboxApiKey\"")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Explicit, rather than inherited from defaultConfig: a release APK carrying
            // a merchant API key is the failure this whole block exists to prevent, and
            // it should not depend on someone remembering how Gradle defaults work.
            buildConfigField("String", "UQPAY_SANDBOX_API_KEY", "\"\"")
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

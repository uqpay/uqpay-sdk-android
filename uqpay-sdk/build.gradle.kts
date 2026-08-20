plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.uqpay.sdk"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        // Single source of truth for the version: gradle/libs.versions.toml.
        buildConfigField("String", "UQPAY_SDK_VERSION", "\"${libs.versions.uqpaySdk.get()}\"")
    }

    buildFeatures {
        buildConfig = true
        // The payment UI is Compose (D3). Compose stays internal to the SDK: no Compose
        // type may appear in the public API, and `apiCheck` will fail if one does.
        compose = true
    }

    // Compose UI tests need `ui-test-manifest`, which contributes a host Activity for
    // `createComposeRule()` to launch. That artifact is `debugImplementation` by design —
    // it must never reach a merchant's release build — so the release unit-test variant has
    // no such Activity and every Compose test fails there with a Robolectric instrumentation
    // error. The release variant is otherwise identical to debug for this library
    // (`isMinifyEnabled = false`, same sources), so running the suite twice proves nothing
    // that the debug run has not already proven. CI runs `testDebugUnitTest` explicitly.
    // Disabling it here is what makes plain `./gradlew build` green as well.
    androidComponents {
        beforeVariants(selector().withBuildType("release")) { variant ->
            variant.enableUnitTest = false
        }
    }

    testOptions {
        targetSdk = libs.versions.targetSdk.get().toInt()
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    buildTypes {
        release {
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
}

// AC §10.4 — the AAR's size is tracked, and a jump has to be a deliberate act.
//
// A payment SDK's size is a number merchants ask about before they integrate, and the way it
// gets away from you is a hundred unnoticed kilobytes rather than one obvious megabyte. The
// ceiling below is the measured size plus ~15% headroom; when it is hit, the fix is to look
// at *why* and then either shrink it or raise this number on purpose, in a commit with a
// reason. The task always prints the actual size so raising it is an informed decision.
//
// Recorded baselines: 355,452 B before Compose · 366,201 B after Compose, no UI written ·
// 686,791 B with the payment UI (Slice 6, 2026-08-18).
val aarSizeCeilingBytes = 790_000L

val checkAarSize = tasks.register("checkAarSize") {
    group = "verification"
    description = "Fails if the release AAR exceeds the recorded size ceiling (AC 10.4)."
    dependsOn("assembleRelease")
    val aar = layout.buildDirectory.file("outputs/aar/${project.name}-release.aar")
    val ceiling = aarSizeCeilingBytes
    inputs.files(aar)
    outputs.upToDateWhen { false }
    doLast {
        val file = aar.get().asFile
        if (!file.isFile) throw GradleException("No release AAR at ${file.path}; assembleRelease did not produce one.")
        val actual = file.length()
        val percent = actual * 100 / ceiling
        logger.lifecycle("AAR size: $actual B (ceiling $ceiling B, $percent% of it) — ${file.name}")
        if (actual > ceiling) {
            throw GradleException(
                "The release AAR is $actual B, over the $ceiling B ceiling by ${actual - ceiling} B.\n" +
                    "Find out what was added before raising the ceiling in uqpay-sdk/build.gradle.kts; " +
                    "AAR size is a release criterion (docs/acceptance-criteria.md 10.4), not a formality.",
            )
        }
    }
}

// Part of `check`, so `./gradlew build` enforces it too. Costs nothing extra: `build`
// already assembles the release variant.
tasks.named("check") { dependsOn(checkAarSize) }

// Public API must be deliberate: every public declaration needs an explicit visibility
// modifier and return type. Scoped to the library's own sources — test sources are
// exempt so tests stay terse.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>()
    .matching { !it.name.contains("Test") }
    .configureEach {
        compilerOptions.freeCompilerArgs.add("-Xexplicit-api=strict")
    }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Compose — the BOM pins every androidx.compose.* version; see the catalog comment.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Preview tooling and the test manifest are debug-only so they never reach the AAR.
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.arch.core.testing)
    // Compose UI tests run under Robolectric here (the plan's disaster catalogue is
    // JVM-only); the same artifact is on the instrumented classpath below.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

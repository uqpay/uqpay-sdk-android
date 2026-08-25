import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.maven.publish)
}

android {
    namespace = "com.uqpay.sdk"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        // Without this AGP falls back to the legacy android.test.InstrumentationTestRunner,
        // which cannot run AndroidJUnit4 tests: connectedAndroidTest installs the APK and
        // then hangs with nothing executed. The runner class ships in androidx.test:runner,
        // pulled in by espresso-core below.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
// 686,791 B with the payment UI (Slice 6, 2026-08-18) · 761,149 B with the merchant-facing
// work of 2026-08-20 (appearance API, test-mode badge, localised error copy, locale-aware
// amount formatting, method allow-list, builders) — that is +74,358 B, of which the Compose
// output for the badge and the themed colour scheme is the largest single part, and it is
// partly offset by androidx.appcompat no longer being a dependency at all. Ceiling raised
// from 790,000 to 875,000 (measured + ~15%) as a deliberate act, per the note above.
val aarSizeCeilingBytes = 875_000L

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

// Runtime dependencies. Every one of these is transitively imposed on the merchant's app,
// so the list is published in docs/integration-guide.md ("What this SDK depends on") and is
// meant to be argued with before anything is added to it.
//
// androidx.appcompat is deliberately absent. It was here for exactly one thing — the
// Theme.AppCompat.DayNight.NoActionBar window theme on the payment Activity — and it cost
// every merchant an appcompat version constraint for a window background. The Activity now
// extends androidx.activity.ComponentActivity and uses the SDK's own theme
// (res/values/themes.xml), and light/dark is UQPayAppearance.colorMode's decision rather
// than something AppCompat decides from the device.
dependencies {
    implementation(libs.androidx.core.ktx)
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
    // Test-only, and deliberately not an `implementation`. The Fragment host path is served
    // by androidx.activity's ActivityResultCaller, which Fragment implements — so the SDK
    // imposes no fragment dependency on merchants. This is here purely so the Java consumer
    // test can stand a real Fragment up and prove the overload reaches it.
    testImplementation(libs.androidx.fragment)
    testImplementation(libs.androidx.arch.core.testing)
    // Compose UI tests run under Robolectric here (the plan's disaster catalogue is
    // JVM-only); the same artifact is on the instrumented classpath below.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.junit)
    // ActivityScenario for the device suite. Explicit rather than leaning on ext-junit's
    // transitive copy, so a version bump there cannot silently change what these compile
    // against.
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

// Publishing (AC §11.3/§11.4) — docs/release-process.md is the operator's guide.
//
// Target: Maven Central via the Central Portal, under the `com.uqpay.sdk` namespace the
// company account owns. Merchants therefore need no repository block — mavenCentral() is
// in every Android project already. Coordinates live in gradle/libs.versions.toml.
//
// Credentials are never in this file. The plugin reads them from Gradle properties, which
// CI supplies as environment variables:
//   ORG_GRADLE_PROJECT_mavenCentralUsername / ORG_GRADLE_PROJECT_mavenCentralPassword
//     — a Central Portal *user token* (not the account password).
//   ORG_GRADLE_PROJECT_signingInMemoryKey / ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
//     — the ASCII-armoured GPG private key and its passphrase.
// Signing is only wired up when a key is present, so `publishToMavenLocal` and every
// ordinary build work on a developer machine with none of these set. Central rejects
// unsigned release artifacts, so a remote publish without the key fails at upload, loudly.
mavenPublishing {
    coordinates(
        groupId = libs.versions.uqpaySdkGroup.get(),
        artifactId = libs.versions.uqpaySdkArtifact.get(),
        version = libs.versions.uqpaySdk.get(),
    )

    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            // Central requires a -javadoc jar to exist. Dokka is deliberately not a build
            // dependency, so this yields an empty jar; the API reference merchants actually
            // read is docs/api-reference.md.
            publishJavadocJar = true,
        ),
    )

    publishToMavenCentral(automaticRelease = false)

    val hasSigningKey = providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent
    if (hasSigningKey) {
        signAllPublications()
    }

    pom {
        name.set("UQPAY SDK for Android")
        description.set("Accept payments through UQPAY from an Android app.")
        url.set("https://github.com/uqpay/uqpay-sdk-android")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("uqpay")
                name.set("UQPAY")
                email.set("tech@uqpay.com")
                url.set("https://www.uqpay.com")
            }
        }
        scm {
            url.set("https://github.com/uqpay/uqpay-sdk-android")
            connection.set("scm:git:https://github.com/uqpay/uqpay-sdk-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/uqpay/uqpay-sdk-android.git")
        }
    }
}

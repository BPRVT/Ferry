// App module build configuration for Ferry.
//
// Two product flavors are defined from the start:
//   - "googletv": targets Google TV / Android TV (minSdk 29)
//   - "firetv":   targets Amazon Fire TV (minSdk 25)
//
// Shared code lives in src/main/. Flavor-specific overrides in src/googletv/ and src/firetv/.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun String.escapedForBuildConfig(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

val castAppId: String =
    (providers.gradleProperty("ferry.castAppId").orNull
        ?: providers.environmentVariable("FERRY_CAST_APP_ID").orNull
        ?: "").trim()

android {
    namespace = "com.ferry.receiver"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        // applicationId is overridden per flavor below
        minSdk = 25           // Lowest common denominator (Fire TV)
        targetSdk = 35
        versionCode = 34
        versionName = "7.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CAST_APP_ID", "\"${castAppId.escapedForBuildConfig()}\"")

        // ABIs are set per flavor below rather than here — the two targets do not need the same
        // set, and every extra ABI is a full second copy of libalac.so + libplayfair.so in the APK.
    }

    // Native build: RPiPlay's FairPlay (playfair) compiled via CMake → libplayfair.so.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Two flavors: one for Google TV, one for Amazon Fire TV.
    // This separation allows flavor-specific code, resources, and dependencies.
    flavorDimensions += "platform"
    productFlavors {
        create("googletv") {
            dimension = "platform"
            applicationId = "com.ferry.receiver.googletv"
            minSdk = 29        // Google TV requires Android 10+
            versionNameSuffix = "-googletv"
            // Keeps x86/x86_64: Android TV runs on Intel boxes and ChromeOS, and this is the
            // flavor people run in an emulator.
            ndk { abiFilters += setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        }
        create("firetv") {
            dimension = "platform"
            // Fire TV is the primary target, so it gets the unsuffixed application ID.
            // googletv keeps a suffix so both flavors can be installed side by side.
            applicationId = "com.ferry.receiver"
            minSdk = 25        // Fire TV supports Android 7.1+
            versionNameSuffix = "-firetv"
            // ARM only. Amazon has never shipped an x86 Fire TV — every Stick, Cube, and Omni /
            // 4-Series panel is MediaTek, Amlogic, or Novatek, all ARM. The x86 and x86_64 copies
            // were dead weight in an APK that installs onto a stick with very little free storage.
            ndk { abiFilters += setOf("armeabi-v7a", "arm64-v8a") }
        }
    }

    // Release signing: credentials are injected via environment variables in CI.
    // Set KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD to enable.
    // Local builds without these vars produce unsigned release APKs (fine for dev/test).
    //
    // Treat blank as "not configured", not just null. A skipped GitHub Actions step still
    // expands `${{ steps.x.outputs.y }}` to an empty string rather than leaving the variable
    // unset, so a null-only check sees "" as a configured path and fails configuration with
    // "path may not be null or empty string" before any task runs.
    val keystorePath = System.getenv("KEYSTORE_PATH")
    if (!keystorePath.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Enable strict coroutine checks in debug builds
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }

    // Source sets: shared code in main, flavor-specific overrides in flavor directories
    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
            res.srcDirs("src/main/res")
        }
        getByName("googletv") {
            kotlin.srcDirs("src/googletv/kotlin")
            res.srcDirs("src/googletv/res")
        }
        getByName("firetv") {
            kotlin.srcDirs("src/firetv/kotlin")
            res.srcDirs("src/firetv/res")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        // Tests for the protocols that only exist on Google TV. They live outside src/test because
        // src/test is compiled against *every* variant, and MiracastReceiver / CastReceiver are no
        // longer part of the firetv source set at all — see src/firetv/.../OptionalProtocols.kt.
        getByName("testGoogletv") {
            kotlin.srcDirs("src/testGoogletv/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
        }
    }

    // Lint configuration: treat all warnings as errors in CI
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = true
        // Keep lint focused on Ferry sources. The Google Cast SDK pulls a
        // large transitive graph that exceeds the small CI/dev VM during
        // dependency lint analysis, while app-source lint still catches local
        // manifest/resource/API regressions.
        checkDependencies = false
        disable += setOf(
            // Dependency freshness is tracked intentionally, but should not block
            // protocol/build CI when the pinned toolchain is known-good.
            "AndroidGradlePluginVersion",
            "GradleDependency",
            // Localizations are incomplete during the pre-release hardware-test phase.
            "MissingTranslation",
            // Cleanup/style issues that should not block debug APK CI.
            "ButtonStyle",
            "DataExtractionRules",
            "DiscouragedApi",
            "MonochromeLauncherIcon",
            // Launcher-icon shape is advisory; on Android TV the banner is the primary
            // artwork and the icon is rarely shown (sibling of MonochromeLauncherIcon above).
            "IconLauncherShape",
            // The launcher banner is deliberately ONE asset in drawable-xhdpi and nowhere
            // else, so lint sees drawable-xhdpi without its usual density siblings.
            // Fire OS will not resolve a nodpi banner (it silently falls back to the square
            // icon) and sizes its tile for 1280x720, so the asset has to be density-qualified
            // *and* full size. Every device this ships to is a 1080p or 4K TV reporting
            // xhdpi; downscaled mdpi/hdpi copies would be dead weight, and the risk is a
            // launcher picking a smaller one. Verified on hardware — see CHANGELOG 2.0.3.
            "IconMissingDensityFolder",
            "ObsoleteSdkInt",
            "Overdraw",
            "UnusedResources",
            // Advisory: the project deliberately supports a wide API range for old TVs;
            // targetSdk is bumped deliberately, not on every new platform release.
            "OldTargetApi",
            // Fires on the firetv flavor for dropping x86_64. That flavor targets Fire TV and
            // nothing else, and Amazon has never shipped an x86 Fire TV — the ABI was dead weight
            // in an APK that installs onto a stick with very little free storage. ChromeOS is
            // served by the googletv flavor, which still ships x86 and x86_64.
            "ChromeOsAbiSupport"
        )
    }

    packaging {
        // NOTE: do NOT add `keepDebugSymbols += "**/*.so"` here. It applies to every variant, not
        // just debug, and it is not a no-op: it shipped full DWARF debug info inside the release
        // APK. In v4.0.0 that was ~81% of libalac.so — 1.4 MB of .debug_* sections per ABI, for a
        // decoder whose actual .text is 138 KB. AGP strips release native libraries by default;
        // letting it do that is the whole fix.
        resources {
            // BouncyCastle (and some other crypto libs) include OSGI manifest files
            // that conflict when multiple jars are merged. Exclude them — they are
            // not needed at runtime on Android (OSGI is a Java EE/OSGi framework).
            excludes += "META-INF/versions/9/OSGI-INF/**"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }

    buildFeatures {
        // BuildConfig is disabled by default in AGP 8.x — enable it explicitly
        // because FerryApp.kt and SettingsFragment.kt use BuildConfig.VERSION_NAME etc.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // AndroidX UI (View-based, for maximum TV compatibility)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)

    // Leanback — TV focus management, on-screen keyboard, TV-specific widgets
    implementation(libs.androidx.leanback)

    // ProfileInstaller — writes src/main/baseline-prof.txt into the ART profile on first run.
    // This is the component that actually applies a baseline profile on API 24–30; Fire OS 6 is
    // API 25, so without it the profile we ship would be inert on the primary target device.
    implementation(libs.androidx.profileinstaller)

    // DataStore — async, type-safe replacement for SharedPreferences
    implementation(libs.androidx.datastore.preferences)

    // Async I/O — all network and media operations use coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Logging — tagged, level-filtered logs with pluggable backend
    implementation(libs.timber)

    // Cryptography — AES-128-CTR for audio decryption, future SRP-6a pairing
    implementation(libs.bouncycastle)

    // Binary property lists — AirPlay 2 handshake payloads (GET /info, SETUP)
    implementation(libs.ddplist)

    // Google TV Cast Connect receiver SDK. Kept out of the Fire TV flavor because
    // Fire TV lacks Google Play Services and cannot run Google Cast receiver APIs.
    "googletvImplementation"(libs.play.services.cast.tv)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric — real Android framework classes (Intent, Base64, …) in JVM unit tests
    testImplementation(libs.robolectric)

    // Instrumented Testing (on device)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

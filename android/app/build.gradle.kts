plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // @Serializable wire DTOs (data/network) — Graph.json is the one mapper.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * One release-signing value: a Gradle property first (which is how the keys in
 * `~/.gradle/gradle.properties` arrive), an environment variable second for
 * CI. Returns null when neither is set, which leaves the release build
 * unsigned rather than failing the configuration.
 */
fun signingProperty(name: String): String? =
    providers.gradleProperty(name).orNull ?: System.getenv(name)

android {
    namespace = "com.rysal.happytv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rysal.happytv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing. The keystore and its two passwords live OUTSIDE the
    // repository: the path and passphrases come from Gradle properties, which
    // resolve from `~/.gradle/gradle.properties` (HAPPYTV_KEYSTORE_PATH,
    // HAPPYTV_KEYSTORE_PASS, HAPPYTV_KEY_ALIAS, HAPPYTV_KEY_PASS), with an
    // environment-variable fallback for CI. Nothing signing-related is
    // committed, and a build without them simply has no release signing
    // config (assembleRelease still produces an unsigned APK).
    signingConfigs {
        create("release") {
            val keyStorePath = signingProperty("HAPPYTV_KEYSTORE_PATH")
            val storePass = signingProperty("HAPPYTV_KEYSTORE_PASS")
            val keyAlias = signingProperty("HAPPYTV_KEY_ALIAS")
            val keyPass = signingProperty("HAPPYTV_KEY_PASS")
            if (keyStorePath != null && storePass != null && keyAlias != null && keyPass != null) {
                val expandedPath = if (keyStorePath.startsWith("~")) {
                    System.getProperty("user.home") + keyStorePath.substring(1)
                } else {
                    keyStorePath
                }
                storeFile = rootProject.file(expandedPath)
                storePassword = storePass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            // R8 and resource shrinking, so release is the build that was tested
            // (benchmark below is the same configuration with the debug key).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            // Official-build check: when HAPPYTV_RELEASE_CERT_SHA256 is set (Gradle
            // property or environment), the app refuses to start unless it is signed
            // with that certificate. Unset means no check. Colons and whitespace are
            // stripped so keytool/apksigner fingerprints work.
            val expectedCert = (signingProperty("HAPPYTV_RELEASE_CERT_SHA256") ?: "")
                .filterNot { it == ':' || it.isWhitespace() }
                .lowercase()
            buildConfigField("String", "EXPECTED_CERT_SHA256", "\"$expectedCert\"")
        }
        debug {
            buildConfigField("String", "EXPECTED_CERT_SHA256", "\"\"")
        }
        // Optimized, non-debuggable build for realistic speed measurements and
        // everyday use. Signed with the debug keystore (same applicationId) so
        // `adb install -r` upgrades the existing debug install in place and keeps
        // sign-in, favorites and history. Debug-only surfaces (credential
        // injection, HTTP body logging, src/debug exports) key off
        // BuildConfig.DEBUG / the debug source set and are therefore absent.
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            // initWith(release) copies release's cert field; the debug key must not be checked.
            buildConfigField("String", "EXPECTED_CERT_SHA256", "\"\"")
        }
    }

    compileOptions {
        // minSdk 23 + java.time (Room Instant converters, EPG parsing) needs desugaring.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // AGP 8 stopped generating BuildConfig by default; BuildConfig.DEBUG gates
        // the Graph logging interceptor and the debug cert-pin digest.
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            // Robolectric (Room round-trip tests) reads the merged manifest/resources.
            isIncludeAndroidResources = true
            // android.util.Log is called from data/network (cleartext warning,
            // refresh logging); let plain-JVM unit tests no-op it instead of
            // throwing "Method w in android.util.Log not mocked".
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    // Room golden schema → app/schemas/<database fqcn>/<version>.json.
    // Exported on every KSP run; migrations diff against it from version 2 on.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Compose (BOM pins the androidx.compose.* versions as a set)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    // Material Icons Extended — Material Symbols for the dashboard tile,
    // status pill and second-row card icons.
    implementation(libs.material.icons.extended)
    // Compose for TV — Surface, Card, Carousel, Button + D-pad focus ring.
    implementation(libs.tv.material)
    // Compose Navigation — the browse NavHost (Live TV / Movies / Shows).
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    // Android 12+ splash (compat back to API 23) — LoginActivity's launch flash.
    implementation(libs.core.splashscreen)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    // ProcessLifecycleOwner — app foreground/background hooks in HappyTvApp.
    implementation(libs.lifecycle.process)
    // viewModel() Compose helper for DashboardViewModel / screen ViewModels.
    implementation(libs.lifecycle.viewmodel.compose)
    // collectAsStateWithLifecycle — the dashboard's StateFlow→Compose bridge.
    implementation(libs.lifecycle.runtime.compose)

    // Playback
    implementation(libs.media3.exoplayer)
    // HLS media sources — the .m3u8 live default needs media3-exoplayer-hls.
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // Coil — image loading for poster art on the Movies / Shows rails (F-49).
    // `AsyncImage` accepts the HTTPS `stream_icon` URLs panels return; the
    // default ImageLoader needs no network-security-config tweak (cleartext
    // policy gates only http://, and panel poster URLs are https://image.tmdb.org).
    implementation(libs.coil.compose)

    // WorkManager — RefreshGuideWorker (data/guide) periodic guide refresh.
    implementation(libs.work.runtime)

    // Local storage
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    // Credential encryption at rest — EncryptedSharedPreferences, see
    // com.rysal.happytv.security.SecuredCredentialsStore.
    implementation(libs.security.crypto)

    // Networking + JSON
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Xtream Player API client (data/network/XtreamApi) — Retrofit 2 + official
    // kotlinx.serialization converter. Base URL is fixed; every call passes the
    // panel's absolute URL per-request (@Url), so one Retrofit serves all panels.
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Tests
    testImplementation(libs.junit)
    // XmlPullParser impl for the plain-JUnit XmlTvParser tests (jvm has no kxml2).
    testImplementation(libs.kxml2)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.testing)
    // Canned-JSON XtreamApi test (app/src/test/.../XtreamApiMockWebServerTest).
    testImplementation(libs.okhttp.mockwebserver)
    // Dispatchers.setMain / UnconfinedTestDispatcher for the ViewModel tests.
    testImplementation(libs.kotlinx.coroutines.test)
    // ApplicationProvider/AndroidJUnit4 for the Robolectric Room tests (test scope —
    // androidTest scope already has it below).
    testImplementation(libs.androidx.junit)
    // Compose UI tests on the JVM (Robolectric) — LoginScreenImeHandoffTest drives
    // the sign-in card's Gboard Next/Done hand-off end to end. The BOM resolves
    // the versionless ui-test artifact; ui-test-manifest must be on the debug
    // variant so its ComponentActivity entry reaches the merged manifest that
    // Robolectric reads.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

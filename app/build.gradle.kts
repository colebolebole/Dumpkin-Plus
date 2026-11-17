import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.dumpkinplus"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dumpkinplus"
        // Android 5.0 (Lollipop). Everything newer than API 21 is behind a Build.VERSION check.
        minSdk = 21
        // Deliberately 28: Android 10+ blocks exec() of files in an app's data directory for apps
        // targeting API 29+. The Pumpkin engine is downloaded at runtime and executed from there
        // (same approach Termux uses), so this app is meant to be sideloaded, not published on Play.
        targetSdk = 28
        versionCode = 3
        versionName = "0.1.2-alpha"
        // English only: keeps the APK and its resource tables small.
        androidResources.localeFilters += "en"
    }

    // Release signing. The key lives outside the repo; see "Release signing" in the README.
    // Settings come from keystore.properties (git-ignored) or DUMPKINPLUS_* environment variables.
    val signingProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signingValue(prop: String, env: String): String? =
        signingProps.getProperty(prop)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    val releaseStore = signingValue("storeFile", "DUMPKINPLUS_KEYSTORE_FILE")?.let { file(it) }
    val hasReleaseKey = releaseStore?.exists() == true

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStore
                storePassword = signingValue("storePassword", "DUMPKINPLUS_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DUMPKINPLUS_KEY_ALIAS") ?: "dumpkinplus"
                keyPassword = signingValue("keyPassword", "DUMPKINPLUS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                // Contributors without the key can still build an installable APK, but it must never be published.
                // Pass -PrequireReleaseKey (e.g. in a release script) to turn this into a hard error.
                if (project.hasProperty("requireReleaseKey")) {
                    throw GradleException("Release signing key not found. Create keystore.properties (see README).")
                }
                logger.warn("WARNING: no release signing key found, signing the release build with the DEBUG key.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
    }

    lint {
        // targetSdk 28 is intentional (see above).
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
        // But anything that needs an API newer than 21 and isn't guarded is a real bug on this app's devices.
        error += listOf("NewApi", "InlinedApi")
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The only runtime dependency. No AndroidX, no Compose, no Material library: the UI is built from
    // the platform's own widgets, which keeps the APK around a megabyte and start-up fast on slow phones.
    implementation(libs.coroutines.android)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Runs the real UI on an emulated Android 5.0 (API 21) and a current Android, with no phone needed.
    testImplementation("org.robolectric:robolectric:4.12.2")
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Semver; versionCode = major * 10000 + minor * 100 + patch (DESIGN.md section 11).
val appVersionName = "0.1.1"
val appVersionCode =
  appVersionName.split('.').map(String::toInt).let { (major, minor, patch) ->
    major * 10000 + minor * 100 + patch
  }

// Release signing (docs/RELEASING.md). Each value is read from a Gradle property, e.g. in
// ~/.gradle/gradle.properties, or else from an environment variable. With none of them set, release
// builds are unsigned (app-release-unsigned.apk). The keystore and its passwords never go into git.
val releaseSigning: Map<String, String>? = run {
  val sources =
    mapOf(
      "storeFile" to "TTSYNC_KEYSTORE",
      "storePassword" to "TTSYNC_KEYSTORE_PASSWORD",
      "keyAlias" to "TTSYNC_KEY_ALIAS",
      "keyPassword" to "TTSYNC_KEY_PASSWORD",
    )
  val values =
    sources.entries
      .mapNotNull { (name, env) ->
        val value =
          providers.gradleProperty("ttsync.signing.$name").orNull
            ?: providers.environmentVariable(env).orNull
        value?.takeIf { it.isNotBlank() }?.let { name to it }
      }
      .toMap()
  when (values.size) {
    sources.size -> values
    0 -> null
    else -> {
      val missing = (sources.keys - values.keys).map { "ttsync.signing.$it (${sources[it]})" }
      logger.warn("Release signing incomplete, building unsigned. Missing: $missing")
      null
    }
  }
}

android {
  namespace = "de.tergelius.ttsync"
  compileSdk = 37
  defaultConfig {
    applicationId = "de.tergelius.ttsync"
    minSdk = 26
    targetSdk = 37
    versionCode = appVersionCode
    versionName = appVersionName
  }

  signingConfigs {
    if (releaseSigning != null) {
      create("release") {
        // A leading "~/" means the home directory; other relative paths are relative to app/.
        storeFile =
          file(
            releaseSigning.getValue("storeFile")
              .replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home"))
          )
        storePassword = releaseSigning.getValue("storePassword")
        keyAlias = releaseSigning.getValue("keyAlias")
        keyPassword = releaseSigning.getValue("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      // R8 shrinks, optimizes and obfuscates; app/proguard-rules.pro explains each extra rule.
      // Keep build/outputs/mapping/release/mapping.txt of every release (docs/RELEASING.md).
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (releaseSigning != null) signingConfig = signingConfigs.getByName("release")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  androidResources {
    // A locale config, so Android 13+ offers the app under Settings → App languages (device run 5,
    // L5). AGP builds it from the res/values-* folders; values/ is English (res/resources.properties).
    generateLocaleConfig = true
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.kotlinx.coroutines.android)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // TimeTree client (timetree/*)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)

  // Background sync, settings and status (sync/*, settings/*)
  implementation(libs.androidx.work.runtime)
  // ListenableFuture.await() for WorkManager.updateWork (WorkManager's own await() is for Operation)
  implementation(libs.androidx.concurrent.futures.ktx)
  implementation(libs.androidx.datastore.preferences)

  // Local tests: jUnit, coroutines, MockWebServer for the TimeTree client
  testImplementation(libs.junit)
  // SyncDb store tests run the same SQL on the JVM through JDBC (store/*Test)
  testImplementation(libs.sqlite.jdbc)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver3)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}

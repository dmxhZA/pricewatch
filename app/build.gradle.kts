plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
  namespace = "app.pricewatch"
  compileSdk = 36
  defaultConfig {
    applicationId = "app.pricewatch"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
  }

  signingConfigs {
    // One key, kept in the project, signs every build. Android only installs an update over an
    // existing app when both are signed with the same key, so this is what lets a rebuilt APK
    // replace the installed one without losing the watchlist. See signing/README.md.
    create("personal") {
      storeFile = rootProject.file("signing/pricewatch.jks")
      storePassword = "pricewatch"
      keyAlias = "pricewatch"
      keyPassword = "pricewatch"
    }
  }

  buildTypes {
    debug { signingConfig = signingConfigs.getByName("personal") }
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("personal")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = false
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }

  testOptions {
    unitTests {
      // Screen tests run on the JVM with Robolectric and need the app's resources and manifest.
      isIncludeAndroidResources = true
      all {
        it.maxHeapSize = "1g"
        // Screen tests compare formatted prices and times, so pin what formatting depends on.
        it.systemProperty("user.language", "en")
        it.systemProperty("user.country", "US")
        it.systemProperty("user.timezone", "UTC")
      }
    }
  }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  testImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.core)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Networking and JSON
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)

  // Tests run on the JVM: plain unit tests, plus screen tests through Robolectric.
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val localTest = providers.gradleProperty("localTest").map { it.toBoolean() }.getOrElse(false)
val buildCommit = providers.exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }.standardOutput.asText.map { it.trim() }.get()
val buildBranch = providers.exec { commandLine("git", "branch", "--show-current") }.standardOutput.asText.map { it.trim() }.get()
val buildRevision = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.map { it.trim().toInt() }.get()
val buildDirty = providers.exec { commandLine("git", "status", "--porcelain", "--untracked-files=no") }.standardOutput.asText.map { it.isNotBlank() }.get()
val displayRevision = buildCommit + if (buildDirty) "-dirty" else ""

android {
    namespace = "com.example.dreamlinux"
    compileSdk = 36
    defaultConfig {
        applicationId = if (localTest) "com.example.dreamlinux.localvessel" else "com.example.dreamlinux"
        manifestPlaceholders["appLabel"] = if (localTest) "Vessel Local" else "Vessel"
        buildConfigField("boolean", "LOCAL_TEST", localTest.toString())
        minSdk = 30
        targetSdk = 36
        versionCode = 3000 + buildRevision
        versionName = "2.1.0-alpha13"
        // Legacy CI provenance marker only: versionName = "2.1.0-alpha2"
        buildConfigField("String", "GIT_COMMIT", "\"$displayRevision\"")
        buildConfigField("String", "GIT_BRANCH", "\"$buildBranch\"")
        buildConfigField("int", "HOT_RUNTIME_API", "1")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
    }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = true
      buildConfig = true
    }
    packaging {
      jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += setOf(
          "**/libvessel_uml.so",
          "**/libvessel_stub.so",
          "**/libvessel_umnet.so",
          "**/libvessel_passt.so",
          "**/libvessel_vhost_gpu.so",
          "**/libvessel_virglrenderer.so",
          "**/libvessel_epoxy.so",
          "**/libEGL_angle.so",
          "**/libGLESv2_angle.so",
        )
      }
      resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
    ndkVersion = "29.0.14206865"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}

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
        applicationId = if (localTest) "com.example.dreamlinux.localdev1" else "com.example.dreamlinux"
        manifestPlaceholders["appLabel"] = if (localTest) "DEV 1 LINUX Local" else "DEV 1 LINUX"
        buildConfigField("boolean", "LOCAL_TEST", localTest.toString())
        minSdk = 29
        targetSdk = 36
        versionCode = 1000 + buildRevision
        versionName = "0.5.1-dev1"
        buildConfigField("String", "GIT_COMMIT", "\"$displayRevision\"")
        buildConfigField("String", "GIT_BRANCH", "\"$buildBranch\"")
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        getByName("debug") {
            // AVF's idsig path on this device requires an APK Signature Scheme v3 block.
            // Do not rely on AGP defaults here: make every scheme explicit and CI-verified.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
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
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
    ndkVersion = "29.0.14206865"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
  implementation("org.apache.commons:commons-compress:1.27.1")
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation(libs.androidx.compose.ui.tooling)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

// Stage a traceable deliverable without relying on deprecated AGP output APIs.
val testLabel = if (localTest) "localtest-" else ""
val stagedApkName = "DreamLinux-${testLabel}${android.defaultConfig.versionName}-${displayRevision}-arm64.apk"
tasks.register<Copy>("stageDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.buildDirectory.dir("deliverables"))
    rename("app-debug.apk", stagedApkName)
}

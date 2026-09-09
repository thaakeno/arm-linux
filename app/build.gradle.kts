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
val dev1VersionCode = 1000 + buildRevision

android {
    namespace = "com.example.dreamlinux"
    compileSdk = 36
    defaultConfig {
        applicationId = if (localTest) "com.example.dreamlinux.localdev1" else "com.example.dreamlinux"
        manifestPlaceholders["appLabel"] = if (localTest) "DEV 1 LINUX Local" else "DEV 1 LINUX"
        buildConfigField("boolean", "LOCAL_TEST", localTest.toString())
        minSdk = 29
        targetSdk = 36
        versionCode = dev1VersionCode
        versionName = "0.8.2-dev1"
        buildConfigField("String", "GIT_COMMIT", "\"$displayRevision\"")
        buildConfigField("String", "GIT_BRANCH", "\"$buildBranch\"")
        ndk { abiFilters += "arm64-v8a" }
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
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    ndkVersion = "29.0.14206865"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
}

kotlin { jvmToolchain(17) }

// Protected Microdroid instances bind their payload identity to the APK that created them.
// A DEV build therefore must not reuse the previous APK's pVM record. Patch the generated
// build workspace before compilation so each APK gets a fresh VM identity while keeping the
// same Android package and the same user-facing app install.
val prepareDev1ProtectedVmIdentity by tasks.registering {
    doLast {
        val source = file("src/main/java/com/example/dreamlinux/VmBridge.kt")
        var text = source.readText()
        text = text.replace(
            "private val gateVmName = \"dev1-gate-a-v4\"",
            "private val gateVmName = \"dev1-gate-a-v${dev1VersionCode}\""
        )
        text = text.replace(
            "private val linuxVmName = \"dev1-debian-pvm-v1\"",
            "private val linuxVmName = \"dev1-debian-pvm-v${dev1VersionCode}\""
        )
        // Stop retrying vsock for tens of seconds after Microdroid has already powered off.
        val oldLoop = """while (android.os.SystemClock.elapsedRealtime() < deadline) {
            attempt++
            try {
                val pfd = connectVsock(machine, port)"""
        val newLoop = """while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (!isRunning(machine)) {
                error("VM left STATUS_RUNNING while waiting for vsock port=${'$'}port; rawStatus=${'$'}{runCatching { vmStatus(machine) }.getOrDefault(-999)}")
            }
            attempt++
            try {
                val pfd = connectVsock(machine, port)"""
        check(text.contains(oldLoop)) { "VmBridge connectVsockRetry shape changed; update DEV 1 build patch" }
        text = text.replace(oldLoop, newLoop)
        source.writeText(text)
    }
}

tasks.configureEach {
    if (name == "preBuild") dependsOn(prepareDev1ProtectedVmIdentity)
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

val testLabel = if (localTest) "localtest-" else ""
val stagedApkName = "DreamLinux-${testLabel}${android.defaultConfig.versionName}-${displayRevision}-arm64.apk"
tasks.register<Copy>("stageDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.buildDirectory.dir("deliverables"))
    rename("app-debug.apk", stagedApkName)
}

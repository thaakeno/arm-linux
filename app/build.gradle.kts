import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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
        versionCode = 4000 + buildRevision
        versionName = "2.2.7"
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
            // Dedicated public CI/debug key so every Actions APK has the same
            // Android signing identity and can update in place without wiping
            // Vessel's private Linux disk. Never use this key for a release build.
            storeFile = file("signing/vessel-ci-debug.keystore")
            storePassword = "vessel-ci-debug"
            keyAlias = "vessel-ci"
            keyPassword = "vessel-ci-debug"
            storeType = "PKCS12"
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
      shaders = false
    }
    packaging {
      jniLibs {
        useLegacyPackaging = true
        pickFirsts += setOf("**/libtermux.so")
        keepDebugSymbols += setOf(
          "**/libtermux.so",
          "**/libproroot.so",
          "**/libproroot-runtime.so",
          "**/libproroot-bridge.so",
          "**/libproroot-linker.so",
          "**/libproroot-stub-loader.so",
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

kotlin { jvmToolchain(17) }

val officialProrootNames = listOf(
    "libproroot.so",
    "libproroot-runtime.so",
    "libproroot-bridge.so",
    "libproroot-linker.so",
    "libproroot-stub-loader.so",
)
val prepareOfficialProrootRuntime by tasks.registering {
    group = "vessel"
    description = "Fetch hash-verified unmodified proroot v1.2.8 runtime libraries"
    outputs.files(
        officialProrootNames.map { name ->
            layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/" + name)
        },
    )
    doLast {
        val expected = linkedMapOf(
            "libproroot.so" to "a4e74d75b66cdc02b080adfe863dbf9951c3b30610d77beddc95488d5fe5de01",
            "libproroot-runtime.so" to "8c47a0a7db32d84c179ebb5bf3640f655a3181860ece5886ae44d92858730c34",
            "libproroot-bridge.so" to "1c5bc9537a270e8bf8b1c70222813f57b60b828bfb5503ddf8fe37685092de2f",
            "libproroot-linker.so" to "51a0ec5bfed00e572a0de09e22d9057e2befc386b78e426613d3e0ab03f4ecee",
            "libproroot-stub-loader.so" to "06c6624db3bdc45b9ced151cd781df439a37b47731d244b93e9d6a58cd48cde0",
        )
        val outputFiles = outputs.files.files.associateBy { it.name }
        val destination = outputFiles.values.first().parentFile
        destination.mkdirs()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(128 * 1024).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        }

        expected.forEach { (name, digest) ->
            val target = outputFiles.getValue(name)
            if (!target.isFile || sha256(target) != digest) {
                val temp = destination.resolve(name + ".tmp")
                temp.delete()
                val url = URI(
                    "https://github.com/coderredlab/proroot/releases/download/v1.2.8/" + name,
                ).toURL()
                url.openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }.getInputStream().use { input ->
                    temp.outputStream().buffered(128 * 1024).use { output ->
                        input.copyTo(output, 128 * 1024)
                    }
                }
                check(sha256(temp) == digest) {
                    "Official proroot SHA-256 mismatch for " + name
                }
                Files.move(
                    temp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            check(sha256(target) == digest) {
                "Official proroot runtime integrity failed for " + name
            }
            target.setExecutable(true, false)
        }
    }
}

tasks.named("preBuild") {
    dependsOn(prepareOfficialProrootRuntime)
}

dependencies {
  implementation("dev.rikka.shizuku:api:13.1.5")
  implementation("dev.rikka.shizuku:provider:13.1.5")
  implementation("org.apache.commons:commons-compress:1.27.1")
  implementation("com.github.luben:zstd-jni:1.5.7-12@aar")
  // Apache-2.0 terminal-view/emulator modules; Vessel supplies its own
  // directly compiled libtermux.so PTY implementation.
  implementation("com.termux.termux-app:terminal-view:0.118.0")
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
val stagedApkName = "Vessel-${testLabel}${android.defaultConfig.versionName}-${displayRevision}-arm64.apk"
tasks.register<Copy>("stageDebugApk") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("deliverables"))
    rename { stagedApkName }
}

tasks.register<Copy>("stageReleaseApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk"))
    into(layout.buildDirectory.dir("deliverables"))
    rename { "Vessel-${testLabel}${android.defaultConfig.versionName}-release-unsigned-${displayRevision}-arm64.apk" }
}

tasks.register("stageApks") {
    dependsOn("stageDebugApk", "stageReleaseApk")
}

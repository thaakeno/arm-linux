// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
  alias(libs.plugins.kotlin.serialization) apply false
}

// Keep the app on the Android-36/AGP-9.0-compatible Lifecycle line. Some
// transitive dependencies otherwise float to Lifecycle 2.11, which requires
// compileSdk 37 + AGP 9.1 even though Vessel itself does not need those APIs.
allprojects {
  configurations.configureEach {
    resolutionStrategy {
      force(
        "androidx.lifecycle:lifecycle-runtime:2.10.0",
        "androidx.lifecycle:lifecycle-runtime-android:2.10.0",
        "androidx.lifecycle:lifecycle-runtime-ktx:2.10.0",
        "androidx.lifecycle:lifecycle-runtime-ktx-android:2.10.0",
        "androidx.lifecycle:lifecycle-runtime-compose:2.10.0",
        "androidx.lifecycle:lifecycle-runtime-compose-android:2.10.0",
        "androidx.lifecycle:lifecycle-viewmodel:2.10.0",
        "androidx.lifecycle:lifecycle-viewmodel-android:2.10.0",
        "androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0",
        "androidx.lifecycle:lifecycle-viewmodel-compose-android:2.10.0",
        "androidx.lifecycle:lifecycle-viewmodel-navigation3:2.10.0",
        "androidx.lifecycle:lifecycle-viewmodel-navigation3-android:2.10.0"
      )
    }
  }
}

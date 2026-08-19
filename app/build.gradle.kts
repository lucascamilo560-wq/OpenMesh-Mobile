plugins {
    id("com.android.application")
}

android {
    namespace = "com.openmesh.demo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openmesh.demo"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-test"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":mesh-android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}

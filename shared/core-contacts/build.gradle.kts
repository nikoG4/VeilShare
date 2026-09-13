plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()
    jvm("desktop")
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-model"))
            implementation(project(":shared:core-crypto"))
            implementation(project(":shared:core-identity"))
            implementation(project(":shared:core-secure-store"))
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        androidInstrumentedTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
            implementation("androidx.test:runner:1.6.2")
            implementation("androidx.test:core:1.6.1")
        }
    }
}

android {
    namespace = "dev.veilshare.core.contacts"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

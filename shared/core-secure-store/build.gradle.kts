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
            implementation(project(":shared:core-crypto"))
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.jna.platform)
            }
        }
        val desktopTest by getting {
            dependsOn(sourceSets["commonTest"]!!)
            dependsOn(sourceSets["desktopMain"]!!)
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
    namespace = "dev.veilshare.core.securestore"
    compileSdk = 36
    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

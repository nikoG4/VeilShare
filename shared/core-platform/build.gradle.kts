plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.android.library) }

kotlin {
    androidTarget()
    jvm("desktop")
    iosX64(); iosArm64(); iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(project(":shared:core-model"))
        }
        commonTest.dependencies { implementation(libs.kotlin.test); implementation(libs.coroutines.test) }
    }
}
android { namespace = "dev.veilshare.core.platform"; compileSdk = 36 }

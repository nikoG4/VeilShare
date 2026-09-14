plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.android.library); alias(libs.plugins.jetbrains.compose); alias(libs.plugins.compose.compiler) }

kotlin {
    androidTarget(); jvm("desktop"); iosX64(); iosArm64(); iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-model"))
            implementation(project(":shared:core-crypto"))
            implementation(project(":shared:core-identity"))
            implementation(project(":shared:core-contacts"))
            implementation(project(":shared:core-transfer"))
            implementation(project(":shared:core-platform"))
            implementation(project(":shared:core-secure-store"))
            implementation(project(":shared:core-vault"))
            implementation(project(":shared:ui-features"))
            implementation(project(":shared:ui-design"))
            implementation(libs.coroutines.core)
            implementation(compose.runtime)
        }
        androidMain.dependencies { implementation(libs.ktor.client.cio) }
        desktopMain.dependencies { implementation(libs.ktor.client.cio) }
        commonTest.dependencies { implementation(libs.kotlin.test); implementation(libs.coroutines.test) }
    }
}
android { namespace = "dev.veilshare.app"; compileSdk = 36 }

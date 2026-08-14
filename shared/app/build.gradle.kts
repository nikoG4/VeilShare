plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.android.library); alias(libs.plugins.jetbrains.compose); alias(libs.plugins.compose.compiler) }

kotlin {
    androidTarget(); jvm("desktop"); iosX64(); iosArm64(); iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-platform")); implementation(project(":shared:ui-features")); implementation(project(":shared:ui-design")); implementation(compose.runtime)
        }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
android { namespace = "dev.veilshare.app"; compileSdk = 36 }

plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.android.library); alias(libs.plugins.jetbrains.compose); alias(libs.plugins.compose.compiler) }

kotlin {
    androidTarget()
    jvm("desktop")
    iosX64(); iosArm64(); iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime); implementation(compose.foundation); implementation(compose.material3)
            implementation(libs.adaptive.core); implementation(libs.adaptive.components)
        }
    }
}
android { namespace = "dev.veilshare.ui.design"; compileSdk = 36 }

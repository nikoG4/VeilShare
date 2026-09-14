plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.android.library); alias(libs.plugins.jetbrains.compose); alias(libs.plugins.compose.compiler) }

kotlin {
    androidTarget(); jvm("desktop"); iosX64(); iosArm64(); iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-model")); implementation(project(":shared:core-platform")); implementation(project(":shared:core-crypto")); implementation(project(":shared:core-vault")); implementation(project(":shared:core-transfer")); implementation(project(":shared:ui-design"))
            implementation(libs.coroutines.core); implementation(compose.runtime); implementation(compose.foundation); implementation(compose.material3); implementation(compose.components.resources)
        }
        commonTest.dependencies { implementation(libs.kotlin.test); implementation(libs.coroutines.test) }
    }
}
compose.resources { packageOfResClass = "dev.veilshare.ui.features.resources" }
android { namespace = "dev.veilshare.ui.features"; compileSdk = 36 }

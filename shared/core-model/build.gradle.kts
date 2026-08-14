plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.android.library) }

kotlin {
    androidTarget()
    jvm("desktop")
    iosX64(); iosArm64(); iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies { implementation(libs.serialization.core) }
        commonTest.dependencies { implementation(libs.kotlin.test) }
    }
}
android { namespace = "dev.veilshare.core.model"; compileSdk = 36 }

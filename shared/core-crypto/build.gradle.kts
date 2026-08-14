plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.android.library) }
kotlin {
    androidTarget(); jvm("desktop"); iosX64(); iosArm64(); iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies { implementation(project(":shared:core-model")); implementation(libs.coroutines.core) }
        androidMain.dependencies { implementation(libs.bouncycastle) }
        getByName("desktopMain").dependencies { implementation(libs.bouncycastle) }
        commonTest.dependencies { implementation(libs.kotlin.test); implementation(libs.coroutines.test) }
    }
}
android { namespace = "dev.veilshare.core.crypto"; compileSdk = 36 }

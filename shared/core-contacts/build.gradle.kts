plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.android.library) }
kotlin { androidTarget(); jvm("desktop"); iosX64(); iosArm64(); iosSimulatorArm64(); sourceSets { commonMain.dependencies { implementation(project(":shared:core-model")); implementation(project(":shared:core-identity")) }; commonTest.dependencies { implementation(libs.kotlin.test) } } }
android { namespace = "dev.veilshare.core.contacts"; compileSdk = 36 }

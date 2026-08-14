plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.android.library) }
kotlin { androidTarget(); jvm("desktop"); iosX64(); iosArm64(); iosSimulatorArm64(); sourceSets { commonMain.dependencies { implementation(project(":shared:core-model")); implementation(project(":shared:core-crypto")); implementation(project(":shared:core-platform")); implementation(libs.coroutines.core) }; commonTest.dependencies { implementation(libs.kotlin.test) } } }
android { namespace = "dev.veilshare.core.transfer"; compileSdk = 36 }

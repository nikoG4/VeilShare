plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.veilshare.android"
    compileSdk = 36
    defaultConfig { applicationId = "dev.veilshare.android"; minSdk = 23; targetSdk = 35; versionCode = 1; versionName = "0.1.0"; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared:app"))
    implementation(project(":shared:core-vault"))
    implementation(project(":shared:ui-features"))
    implementation(project(":shared:ui-design"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(compose.foundation)
    implementation(libs.coroutines.core)
    testImplementation(libs.kotlin.test)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.8.2")
    androidTestImplementation(libs.kotlin.test)
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.8.2")
}

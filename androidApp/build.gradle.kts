plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.compose.compiler)
}

val sharingSignalingUrlLiteral = providers.gradleProperty("veilshare.signalingUrl")
    .orElse("")
    .get()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .let { "\"$it\"" }

android {
    namespace = "dev.veilshare.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.veilshare.android"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SHARING_SIGNALING_URL", sharingSignalingUrlLiteral)
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("releaseCheck") {
            initWith(getByName("release"))
            applicationIdSuffix = ".releasecheck"
            versionNameSuffix = "-releasecheck"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            matchingFallbacks += listOf("release")
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":shared:app"))
    implementation(project(":shared:core-vault"))
    implementation(project(":shared:ui-features"))
    implementation(project(":shared:ui-design"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(compose.foundation)
    implementation(libs.coroutines.core)
    testImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlin.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

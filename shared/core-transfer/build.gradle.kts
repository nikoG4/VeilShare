plugins { alias(libs.plugins.kotlin.multiplatform); alias(libs.plugins.android.library); alias(libs.plugins.kotlin.serialization) }
kotlin {
    androidTarget()
    jvm("desktop")
    iosX64()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared:core-model"))
            implementation(project(":shared:core-crypto"))
            implementation(project(":shared:core-platform"))
            implementation(project(":shared:core-vault"))
            implementation(libs.coroutines.core)
            implementation(libs.serialization.core)
            implementation(libs.serialization.json)
        }
        val desktopMain by getting {
            dependencies {
                implementation(project(":shared:core-crypto"))
            }
        }
        val desktopTest by getting {
            dependsOn(sourceSets["commonTest"]!!)
            dependsOn(sourceSets["desktopMain"]!!)
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.test)
            }
        }
    }
}
android { namespace = "dev.veilshare.core.transfer"; compileSdk = 36 }
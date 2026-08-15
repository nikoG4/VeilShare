plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.jetbrains.compose); alias(libs.plugins.compose.compiler); application }

kotlin { jvmToolchain(17) }
application { mainClass.set("dev.veilshare.desktop.MainKt") }
dependencies {
    implementation(project(":shared:app"))
    implementation(project(":shared:core-vault"))
    implementation(project(":shared:ui-features"))
    implementation(project(":shared:core-platform"))
    implementation(project(":shared:ui-design"))
    implementation(compose.desktop.currentOs)
    implementation(libs.coroutines.core)
    testImplementation(libs.kotlin.test)
}

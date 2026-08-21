plugins { alias(libs.plugins.kotlin.jvm); alias(libs.plugins.kotlin.serialization); application }
kotlin { jvmToolchain(17) }
application { mainClass.set("dev.veilshare.signaling.MainKt") }
dependencies {
    implementation(project(":shared:core-model"))
    implementation(libs.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    testImplementation(libs.kotlin.test)
    testImplementation(project(":shared:core-platform"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}

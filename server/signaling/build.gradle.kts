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
    testImplementation(project(":shared:app"))
    testImplementation(project(":shared:core-crypto"))
    testImplementation(project(":shared:core-identity"))
    testImplementation(project(":shared:core-contacts"))
    testImplementation(project(":shared:core-vault"))
    testImplementation(project(":shared:ui-features"))
    testImplementation(project(":shared:core-platform"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.core)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}

tasks.test {
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

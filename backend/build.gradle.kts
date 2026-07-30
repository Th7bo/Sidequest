// The self-hosted backend.
//
// A Ktor server, in this build rather than a repository of its own, for one reason: a protocol
// change has to break both sides at compile time. Two repositories sharing a hand-copied DTO
// agree until somebody edits one of them.
//
// Nothing in the mod depends on this module. The dependency runs the other way — both depend on
// `:protocol` — so none of Ktor, Netty or Logback ends up anywhere near a Minecraft classpath.
plugins {
    application
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

application {
    mainClass = "dev.th7bo.sidequest.backend.MainKt"
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.kotlinx.coroutines.test)
}

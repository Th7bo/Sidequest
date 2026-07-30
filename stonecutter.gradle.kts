import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("dev.kikugie.stonecutter")
    alias(libs.plugins.fabric.loom) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

stonecutter active "26.2"

stonecutter parameters {
    // Version-conditional code uses `//? if >=26.2 {` ... `//?}` comments.
    // Note: swaps (`//$ name`) are not rewritten in the *active* version's sources,
    // so anything build-dependent should come from a version catalog or the loader.
    filters.include("**/*.fsh", "**/*.vsh")
}

stonecutter tasks {
    // `./gradlew build` from the root builds every target; run them one at a time
    // so stonecutter's source switching never races between versions.
    order("build")
}

/**
 * Launches only the active version's client.
 *
 * Plain `./gradlew runClient` matches the task in *every* stonecutter node and starts a
 * Minecraft instance per version, which is almost never what anyone wants.
 */
val activeNode = checkNotNull(stonecutter.current) {
    "No active stonecutter version; check the `stonecutter active` call above."
}

tasks.register("runActive") {
    group = "sidequest"
    description = "Runs the client for the active Minecraft version (${activeNode.version})."
    dependsOn(":${activeNode.project}:runClient")
}

// ---------------------------------------------------------------------------
// Shared configuration for the framework-independent modules.
//
// These are ordinary JVM subprojects (:ui-api, :ui-core, :platform-api, …), not
// stonecutter nodes — the stonecutter nodes are named after Minecraft versions
// ("26.2", "26.1.2"). None of them contain Minecraft code, so they target Java 21
// and are compiled once and shared by every Minecraft target.
//
// Keeping Minecraft off their classpath is the enforcement mechanism for the
// architecture, not a convention: feature code physically cannot reach a
// Minecraft class, so version-specific detail cannot leak out of the adapters.
// ---------------------------------------------------------------------------
val sharedModulePrefixes = listOf("ui-", "platform-", "protocol", "backend")

configure(subprojects.filter { project -> sharedModulePrefixes.any { project.name.startsWith(it) } }) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    group = "dev.th7bo.sidequest." + name.substringBefore('-')
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion = JavaLanguageVersion.of(21)
        withSourcesJar()
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_21
            // Warnings in the framework core are bugs waiting to happen.
            allWarningsAsErrors = true
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
    }

    dependencies {
        "testImplementation"(platform(rootProject.libs.junit.bom))
        "testImplementation"(rootProject.libs.junit.jupiter)
        "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}

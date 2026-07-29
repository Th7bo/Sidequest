rootProject.name = "Sidequest"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.7"
}

// Newest first; the first entry is the default/vcs version.
val targets = listOf("26.2", "26.1.2")

stonecutter {
    create(rootProject) {
        versions(*targets.toTypedArray())
        vcsVersion = targets.first()
    }
}

// Framework-independent UI modules. These are plain JVM subprojects, deliberately
// outside the stonecutter tree: they contain no Minecraft code, so they compile once
// and every Minecraft target consumes the same artifacts.
include("ui-api")
include("ui-core")
include("ui-components")
include("ui-testkit")

// The mod platform: feature registry, event bus, scheduler, and the interfaces that
// keep Minecraft out of feature code. Same deal as the UI modules — no Minecraft on
// the classpath, so the compiler enforces the boundary rather than a review comment.
include("platform-api")
include("platform-core")
include("platform-testkit")

dependencyResolutionManagement {
    versionCatalogs {
        // One catalog per Minecraft version, resolved in build.gradle.kts via `versioned(...)`.
        targets.forEach { target ->
            create("libs${target.replace(".", "")}") {
                from(files(rootProject.projectDir.resolve("gradle/${target.replace(".", "_")}.versions.toml")))
            }
        }
    }
}

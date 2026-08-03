import net.fabricmc.loom.task.ValidateAccessWidenerTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    idea
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.fabric.loom)
}

/** The Minecraft version this stonecutter node builds, e.g. `26.2`. */
val mcVersion: String = stonecutter.current.version

/** Per-version catalog declared in settings.gradle.kts, e.g. `libs262`. */
val versioned: VersionCatalog = extensions.getByType<VersionCatalogsExtension>()
    .named("libs" + mcVersion.replace(".", ""))

fun lib(alias: String) = versioned.findLibrary(alias).orElseThrow {
    IllegalStateException("No library '$alias' in the $mcVersion catalog")
}

fun ver(alias: String): String = versioned.findVersion(alias).orElseThrow {
    IllegalStateException("No version '$alias' in the $mcVersion catalog")
}.requiredVersion

// Minecraft 26.1+ ships unobfuscated, so no mappings and no `mod*` remapping configurations.
val javaVersion = 25

repositories {
    mavenCentral()

    exclusiveContent {
        forRepository { maven("https://maven.fabricmc.net/") }
        filter {
            includeGroup("net.fabricmc")
            includeGroup("net.fabricmc.fabric-api")
        }
    }

    exclusiveContent {
        forRepository { maven("https://repo.spongepowered.org/repository/maven-public") }
        filter { includeGroup("org.spongepowered") }
    }

    exclusiveContent {
        forRepository { maven("https://pkgs.dev.azure.com/djtheredstoner/DevAuth/_packaging/public/maven/v1") }
        filter { includeGroup("me.djtheredstoner") }
    }

    // Hypixel's Mod API. The location packet it carries is a far better source of truth
    // than reading the scoreboard, so it is used when present and scraping is the fallback.
    exclusiveContent {
        forRepository { maven("https://repo.hypixel.net/repository/Hypixel/") }
        filter { includeGroup("net.hypixel") }
    }

    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
}

dependencies {
    "minecraft"(lib("minecraft"))

    // The framework modules are plain JVM libraries, compiled once and nested into the
    // mod jar so a user installs one file.
    implementation(project(":ui-api"))
    implementation(project(":ui-core"))
    implementation(project(":ui-components"))
    implementation(project(":platform-api"))
    implementation(project(":platform-core"))
    implementation(project(":feature-ui"))
    "include"(project(":ui-api"))
    "include"(project(":ui-core"))
    "include"(project(":ui-components"))
    "include"(project(":platform-api"))
    "include"(project(":platform-core"))
    "include"(project(":feature-ui"))

    // Compile-only: the classes come from the hypixel-mod-api mod at runtime. Everything
    // touching them is behind an `isModLoaded` guard, so a missing mod costs the extra
    // accuracy and nothing else.
    // Compile-only: the classes are provided at runtime by the `hypixel-mod-api` mod,
    // which players install alongside Sidequest. Everything touching them sits behind an
    // `isModLoaded` guard.
    //
    // Not added to the dev runtime. Minecraft 26.1+ is unobfuscated, so Loom creates no
    // `mod*` configurations and there is no supported way to put another mod in the dev
    // mod list from here — dropping the jar in `run/<version>/mods` is the way to try it
    // locally. The gametests therefore exercise the *fallback*, which is the path that
    // has to keep working for anyone without the mod.
    compileOnly(libs.hypixel.mod.api)

    implementation(lib("fabric-api"))
    // Loom takes the dev-launch loader from the runtime classpath. Declared
    // `compileOnly` it is invisible there, and the launcher silently falls back to a
    // much older bundled loader than the one the mod declares it needs.
    implementation(libs.fabric.loader)
    runtimeOnly(libs.fabric.language.kotlin)

    runtimeOnly(libs.devauth)
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(javaVersion)
    withSourcesJar()
}

kotlin {
    jvmToolchain(javaVersion)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(javaVersion.toString())
        freeCompilerArgs.add("-Xcontext-sensitive-resolution")
    }
}

val classTweaker = rootProject.file("src/main/resources/sidequest.classtweaker")

loom {
    if (classTweaker.exists()) {
        accessWidenerPath = classTweaker
    }

    runs {
        // Lazy: the gametest run config is created later, by `configureTests()` below,
        // so looking it up eagerly here would find nothing.
        //
        // The default window is very small, which makes every screenshot look far more
        // cramped than the game ever is.
        matching { it.name == "clientGameTest" }.configureEach {
            programArgs("--width", "1280", "--height", "720")
        }

        named("client") {
            appendProjectPathToDisplayName = true
            runDir(rootProject.file("run/$mcVersion").relativeTo(projectDir).toString())
            vmArgs("-Xmx4G", "-Dmixin.debug=true")
            property("devauth.configDir", rootProject.file(".devauth").absolutePath)
        }
        removeIf { it.name == "server" }
    }
}

// Client game tests: they launch a real client, open a screen and screenshot it, which
// is the only way to verify the Minecraft adapter actually draws what it is asked to.
extensions.getByType<net.fabricmc.loom.api.fabricapi.FabricApiExtension>().configureTests {
    createSourceSet = true
    modId = "sidequest-gametest"
    enableGameTests = false
    enableClientGameTests = true
    clearRunDirectory = false
}

tasks.processResources {
    val replacements = mapOf(
        "version" to project.version,
        "minecraft_range" to ver("minecraft-range"),
        "fabric_api" to ver("fabric-api"),
        "fabric_language_kotlin" to libs.versions.fabric.language.kotlin.get(),
        "fabric_loader" to libs.versions.fabric.loader.get(),
    )
    inputs.properties(replacements)

    filesMatching("fabric.mod.json") { expand(replacements) }
}

base {
    archivesName = "Sidequest-$mcVersion"
}

// Collect every version's jar into the root build/libs so one `./gradlew build` yields one folder.
tasks.named("build") {
    doLast {
        val built = layout.buildDirectory.file("libs/Sidequest-$mcVersion-${project.version}.jar").get().asFile
        if (built.exists()) {
            rootProject.layout.projectDirectory.file("build/libs/${built.name}").asFile.apply {
                parentFile.mkdirs()
                writeBytes(built.readBytes())
            }
        }
    }
}

tasks.withType<ValidateAccessWidenerTask>().configureEach { enabled = false }

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
        excludeDirs.add(file("run"))
    }
}

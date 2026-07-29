// The standard control library. Builds on the runtime, still no Minecraft.
dependencies {
    api(project(":ui-api"))
    api(project(":ui-core"))

    testImplementation(project(":ui-testkit"))
}

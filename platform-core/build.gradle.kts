// Runtime implementations of the platform contracts: the event bus, the scheduler, the
// feature registry and the scopes that own their registrations. Still no Minecraft.
dependencies {
    api(project(":platform-api"))

    testImplementation(project(":platform-testkit"))
}

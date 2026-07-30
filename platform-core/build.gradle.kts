// Runtime implementations of the platform contracts: the event bus, the scheduler, the
// feature registry and the scopes that own their registrations. Still no Minecraft.
plugins {
    // The storage layer writes JSON, and its tests declare their own `@Serializable`
    // fixtures — which need the plugin in this module, not only in `platform-api`.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":platform-api"))
    // The wire format. `api`, because the backend client's public signatures speak it — a feature that
    // submits an event names the payload type.
    api(project(":protocol"))

    testImplementation(project(":platform-testkit"))
    // `runTest` for the suspending storage API.
    testImplementation(libs.kotlinx.coroutines.test)
}

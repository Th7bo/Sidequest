// Fake renderer, fake input driver and snapshot helpers. Consumed by the tests of
// every other module, so it must not depend on :ui-core (that would be a cycle).
dependencies {
    api(project(":ui-api"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
}

// Fakes for everything the platform hides behind an interface: a controllable clock, a
// deterministic scheduler, a recording event bus, a fake game client.
//
// Consumed by the tests of :platform-core, so it must not depend on it — that would be
// a cycle. It sits on :platform-api like the code under test does.
dependencies {
    api(project(":platform-api"))
    // The fakes speak the wire format: a scripted response is a real payload.
    api(project(":protocol"))
    api(platform(libs.junit.bom))
    api(libs.junit.jupiter)
    api(libs.kotlinx.coroutines.test)
}

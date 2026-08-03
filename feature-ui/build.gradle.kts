// Screens described from platform data.
//
// The one module that sees both sides. `ui-*` deliberately knows nothing about SkyBlock and `platform-*`
// knows nothing about drawing, which is right — and leaves nowhere for "the waypoint screen" to live. This
// is that place: it depends on both and still contains no Minecraft, so the boundary that matters is intact
// and these screens become testable, which they were not while they sat in the mod.
dependencies {
    api(project(":ui-api"))
    api(project(":platform-api"))
    api(project(":platform-core"))

    testImplementation(project(":ui-testkit"))
}

// The wire format, shared by the mod and the backend.
//
// The point of this module existing is that a protocol change breaks *both* sides at
// compile time. A server and a client that each declare their own copy of a payload
// agree right up until somebody edits one of them, and then they disagree at runtime,
// in production, on somebody else's machine.
//
// It depends on `platform-api` for the domain vocabulary — an island, an item snapshot,
// a permission — because those are the words both sides need and duplicating sixty
// island entries server-side is a drift waiting to happen. Nothing here knows about
// Minecraft, Ktor, or HTTP: it is data and constants.
plugins {
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":platform-api"))
}

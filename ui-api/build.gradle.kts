// Stable public API. Depends on nothing but the Kotlin stdlib and kotlinx.serialization —
// no Minecraft, no runtime internals. Everything a consumer or a third-party module
// touches lives here.
plugins {
    alias(libs.plugins.kotlin.serialization)
}

// This module *is* the public surface, so every declaration must state its visibility
// and every public declaration must carry an explicit return type.
kotlin {
    explicitApi()
}

dependencies {
    api(libs.kotlinx.serialization.json)
}

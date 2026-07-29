// The mod platform's stable public surface: feature declarations, typed events, the
// scheduler contract, and the interfaces that stand in for Minecraft.
//
// Depends on the Kotlin stdlib and coroutines only. No Minecraft, no UI framework —
// a feature written against this module compiles without either, which is what keeps
// version-specific detail confined to the adapters.
plugins {
    alias(libs.plugins.kotlin.serialization)
}

// This module *is* the public surface, so every declaration states its visibility and
// every public declaration carries an explicit return type.
kotlin {
    explicitApi()
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // `api`, not `implementation`: the scheduler exposes CoroutineScope and Job in its
    // public signatures, so consumers need them on their compile classpath.
    api(libs.kotlinx.coroutines.core)
}

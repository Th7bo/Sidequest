// Framework-independent runtime: node tree, invalidation, layout, input, focus,
// animation, diagnostics. Still no Minecraft.
dependencies {
    api(project(":ui-api"))
    // `api`, not `implementation`: ConfigPersistenceController takes a CoroutineScope
    // in its public signature, so consumers need the type on their compile classpath.
    api(libs.kotlinx.coroutines.core)

    testImplementation(project(":ui-testkit"))
}

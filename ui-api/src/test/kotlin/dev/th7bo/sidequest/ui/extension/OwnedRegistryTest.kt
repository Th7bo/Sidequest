package dev.th7bo.sidequest.ui.extension

import dev.th7bo.sidequest.ui.ids.UiId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OwnedRegistryTest {

    private val coreOwner = UiId.of("sidequest", "core")
    private val addonOwner = UiId.of("thirdparty", "addon")

    private fun registry() = OwnedRegistry<UiId, String>("component")

    @Test
    fun `registered entries are retrievable and attributed to their owner`() {
        val registry = registry()
        val scope = RegistrationScope(coreOwner)
        val key = UiId.of("sidequest", "widget.toggle")

        registry.register(scope, key, "toggle renderer")

        assertEquals("toggle renderer", registry[key])
        assertEquals(coreOwner, registry.ownerOf(key))
        assertTrue(key in registry)
        assertEquals(setOf(key), registry.keysOwnedBy(coreOwner))
    }

    @Test
    fun `a duplicate registration names both owners`() {
        val registry = registry()
        val key = UiId.of("sidequest", "widget.toggle")
        registry.register(RegistrationScope(coreOwner), key, "first")

        val failure = assertThrows(DuplicateRegistrationException::class.java) {
            registry.register(RegistrationScope(addonOwner), key, "second")
        }

        assertEquals(coreOwner, failure.existingOwner)
        assertEquals(addonOwner, failure.attemptedOwner)
        assertEquals("component", failure.entryKind)
        assertEquals("first", registry[key], "the failed registration must not have replaced anything")
    }

    @Test
    fun `disposing a scope removes everything it registered`() {
        val registry = registry()
        val scope = RegistrationScope(addonOwner)
        val first = UiId.of("thirdparty", "widget.gradient")
        val second = UiId.of("thirdparty", "widget.chart")

        registry.register(scope, first, "gradient")
        registry.register(scope, second, "chart")
        assertEquals(2, registry.size)

        scope.dispose()

        assertEquals(0, registry.size)
        assertNull(registry[first])
        assertNull(registry[second])
        assertTrue(registry.keysOwnedBy(addonOwner).isEmpty())
    }

    @Test
    fun `disposing one scope leaves another scope's entries alone`() {
        val registry = registry()
        val coreScope = RegistrationScope(coreOwner)
        val addonScope = RegistrationScope(addonOwner)
        val coreKey = UiId.of("sidequest", "widget.toggle")
        val addonKey = UiId.of("thirdparty", "widget.gradient")

        registry.register(coreScope, coreKey, "core")
        registry.register(addonScope, addonKey, "addon")

        addonScope.dispose()

        assertEquals(1, registry.size)
        assertEquals("core", registry[coreKey])
        assertNull(registry[addonKey])
    }

    @Test
    fun `an individual registration can be released early`() {
        val registry = registry()
        val scope = RegistrationScope(coreOwner)
        val key = UiId.of("sidequest", "widget.toggle")

        val registration = registry.register(scope, key, "toggle")
        registration.dispose()

        assertFalse(key in registry)
        // The key is free again, so a different module may claim it.
        registry.register(RegistrationScope(addonOwner), key, "replacement")
        assertEquals("replacement", registry[key])
    }

    @Test
    fun `registering into a disposed scope is rejected`() {
        val registry = registry()
        val scope = RegistrationScope(coreOwner)
        scope.dispose()

        assertThrows(IllegalStateException::class.java) {
            registry.register(scope, UiId.of("sidequest", "widget.toggle"), "toggle")
        }
    }

    @Test
    fun `re-registering after disposal works, so a module can be reloaded`() {
        val registry = registry()
        val key = UiId.of("thirdparty", "widget.gradient")

        val first = RegistrationScope(addonOwner)
        registry.register(first, key, "v1")
        first.dispose()

        val second = RegistrationScope(addonOwner)
        registry.register(second, key, "v2")

        assertEquals("v2", registry[key])
        assertEquals(1, registry.size)
    }

    @Test
    fun `snapshot is stable while registrations change`() {
        val registry = registry()
        val scope = RegistrationScope(coreOwner)
        registry.register(scope, UiId.of("sidequest", "a"), "a")

        val snapshot = registry.snapshot()
        registry.register(scope, UiId.of("sidequest", "b"), "b")

        assertEquals(1, snapshot.size, "the snapshot must not see later registrations")
        assertEquals(2, registry.size)
    }
}

package dev.th7bo.sidequest.ui.ids

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class UiIdTest {

    @Test
    fun `splits namespace and path`() {
        val id = UiId.of("sidequest", "general.notifications")

        assertEquals("sidequest", id.namespace)
        assertEquals("general.notifications", id.path)
        assertEquals("sidequest:general.notifications", id.value)
    }

    @Test
    fun `parse round-trips through toString`() {
        val original = UiId.of("thirdpartymod", "widget.gradient")
        assertEquals(original, UiId.parse(original.toString()))
    }

    @Test
    fun `child appends a path segment`() {
        val parent = UiId.of("sidequest", "hud")
        assertEquals(UiId.of("sidequest", "hud.mining_xp"), parent.child("mining_xp"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["Sidequest", "side-quest", "side quest", "", "side.quest"])
    fun `rejects malformed namespaces`(namespace: String) {
        val failure = assertThrows(InvalidUiIdException::class.java) {
            UiId.of(namespace, "valid")
        }
        assertEquals("$namespace:valid", failure.offendingValue)
    }

    @ParameterizedTest
    @ValueSource(strings = ["General", "general..notifications", ".leading", "trailing.", "has space", ""])
    fun `rejects malformed paths`(path: String) {
        assertThrows(InvalidUiIdException::class.java) { UiId.of("sidequest", path) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["no_separator", ":path", "namespace:", "a:b:c"])
    fun `rejects malformed full identifiers`(value: String) {
        assertThrows(InvalidUiIdException::class.java) { UiId.parse(value) }
    }

    @Test
    fun `parseOrNull returns null instead of throwing`() {
        assertNull(UiId.parseOrNull("not an id"))
        assertEquals(UiId.of("a", "b"), UiId.parseOrNull("a:b"))
    }

    @Test
    fun `sorts lexicographically for stable diagnostics output`() {
        val ids = listOf(
            UiId.of("z", "a"),
            UiId.of("a", "z"),
            UiId.of("a", "a"),
        )
        assertEquals(
            listOf("a:a", "a:z", "z:a"),
            ids.sorted().map { it.value },
        )
    }

    @Test
    fun `profile ids reject filesystem-unsafe values`() {
        assertThrows(IllegalArgumentException::class.java) { ProfileId("../escape") }
        assertThrows(IllegalArgumentException::class.java) { ProfileId("") }
        assertEquals("default", ProfileId.DEFAULT.value)
    }
}

package dev.th7bo.sidequest.ui.config

import dev.th7bo.sidequest.ui.binding.bind
import dev.th7bo.sidequest.ui.ids.UiId
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The `list` builder.
 *
 * [ListSetting] and its renderer both existed since phase 2; the builder did not, so the control could only be
 * reached by constructing the setting by hand — which is to say, not at all. The first thing that needed one
 * was the rare-drop ignore list, a set that grows from gameplay and has to be prunable somewhere other than a
 * chat command.
 */
class ListSettingDslTest {

    private fun id(path: String) = UiId.of("sidequest", path)

    private class Holder {
        var items: List<String> = listOf("Enchanted Hay Bale", "Rotten Flesh")
    }

    @BeforeEach
    fun setUp() = resetReactiveGraphForTesting()

    @AfterEach
    fun tearDown() = resetReactiveGraphForTesting()

    private fun screenWith(
        holder: Holder,
        createItem: (() -> String)? = null,
        maxItems: Int = Int.MAX_VALUE,
    ): ListSetting<String> {
        var built: ListSetting<String>? = null
        configScreen(id("config"), "Test") {
            category(id("c"), "Category") {
                section("Section") {
                    built = list(
                        id = id("c.items"),
                        title = "Items",
                        value = bind(holder::items),
                        elementSerializer = SettingSerializers.string,
                        itemLabel = { it },
                        createItem = createItem,
                        maxItems = maxItems,
                        isReorderable = false,
                    )
                }
            }
        }
        return built!!
    }

    @Test
    fun `a list setting reaches its backing property`() {
        val holder = Holder()

        val setting = screenWith(holder)

        assertEquals(listOf("Enchanted Hay Bale", "Rotten Flesh"), setting.value)
    }

    @Test
    fun `removing an entry writes back through the binding`() {
        val holder = Holder()
        val setting = screenWith(holder)

        assertTrue(setting.removeAt(0))

        assertEquals(listOf("Rotten Flesh"), holder.items, "the property, not just the setting")
    }

    @Test
    fun `adding is refused past the maximum`() {
        val holder = Holder()
        val setting = screenWith(holder, maxItems = 2)

        assertFalse(setting.add("Dirt"), "already at the limit")
        assertEquals(2, holder.items.size)
    }

    /**
     * A list with no `createItem` has no add button.
     *
     * Which is right for the ignore list: an item is added from the toast at the moment it interrupted
     * somebody, because that is the only time they know its exact name. Typing one here would mostly produce
     * entries that never match anything.
     */
    @Test
    fun `omitting createItem hides the add button`() {
        assertNull(screenWith(Holder()).createItem)
        assertNotNull(screenWith(Holder(), createItem = { "New" }).createItem)
    }

    @Test
    fun `a list setting is persisted like any other value`() {
        val setting = screenWith(Holder())

        assertTrue(setting.isPersistent())
        // Round-trips through the element serializer, so a saved list comes back as the same list rather than
        // as a string that happens to look like one.
        val encoded = setting.encode()
        assertEquals(listOf("Enchanted Hay Bale", "Rotten Flesh"), setting.serializer.decode(encoded))
    }

    @Test
    fun `reordering is refused when the list says it is not reorderable`() {
        val holder = Holder()
        val setting = screenWith(holder)

        assertFalse(setting.move(0, 1))
        assertEquals(listOf("Enchanted Hay Bale", "Rotten Flesh"), holder.items)
    }
}

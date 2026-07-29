package dev.th7bo.sidequest.ui.binding

import dev.th7bo.sidequest.ui.state.DisposableScope
import dev.th7bo.sidequest.ui.state.derivedStateOf
import dev.th7bo.sidequest.ui.state.mutableStateOf
import dev.th7bo.sidequest.ui.state.resetReactiveGraphForTesting
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BindingTest {

    private lateinit var scope: DisposableScope

    @BeforeEach
    fun setUp() {
        resetReactiveGraphForTesting()
        scope = DisposableScope()
    }

    @AfterEach
    fun tearDown() {
        scope.dispose()
        resetReactiveGraphForTesting()
    }

    private class Config {
        var enabled: Boolean = false
        var volume: Int = 50
            set(value) {
                // Deliberately clamps, to prove the binding reads back rather than
                // trusting what it wrote.
                field = value.coerceIn(0, 100)
            }
    }

    @Test
    fun `state binding writes through and stays observable`() {
        val state = mutableStateOf(1)
        val binding = state.asBinding()

        val seen = ArrayList<Int>()
        binding.observe(scope) { seen.add(it) }

        binding.set(2)

        assertTrue(binding.isWritable)
        assertEquals(2, binding.value)
        assertEquals(2, state.value)
        assertEquals(listOf(2), seen)
    }

    @Test
    fun `read-only binding refuses writes instead of ignoring them`() {
        val source = mutableStateOf(1)
        val binding = derivedStateOf { source.value * 2 }.asReadOnlyBinding()

        assertFalse(binding.isWritable)
        assertEquals(2, binding.value)

        val failure = assertThrows(BindingException::class.java) { binding.set(99) }
        assertTrue(failure.message!!.contains("read-only"))
    }

    @Test
    fun `getter setter binding writes through to the model`() {
        val config = Config()
        val binding = bind(get = config::enabled, set = { config.enabled = it }, debugName = "enabled")

        assertFalse(binding.value)
        binding.set(true)

        assertTrue(config.enabled)
        assertTrue(binding.value)
    }

    @Test
    fun `getter setter binding reads back a value the model normalised`() {
        val config = Config()
        val binding = bind(get = config::volume, set = { config.volume = it }, debugName = "volume")

        binding.set(500)

        assertEquals(100, config.volume)
        assertEquals(100, binding.value, "the UI must show what the model accepted, not what was offered")
    }

    @Test
    fun `property reference binding works in both directions`() {
        val config = Config()
        val binding = bind(config::enabled)

        binding.set(true)
        assertTrue(config.enabled)

        // A change made behind the framework's back is picked up on refresh.
        config.enabled = false
        assertTrue(binding.value, "the mirror must not silently track the model")
        binding.refresh()
        assertFalse(binding.value)
    }

    @Test
    fun `refresh notifies observers of an external change`() {
        val config = Config()
        val binding = bind(config::volume)
        val seen = ArrayList<Int>()
        binding.observe(scope) { seen.add(it) }

        config.volume = 75
        binding.refresh()

        assertEquals(listOf(75), seen)
    }

    @Test
    fun `mapped binding round-trips through both transforms`() {
        val percent = mutableStateOf(50)
        val fraction = percent.asBinding().map(to = { it / 100f }, from = { (it * 100).toInt() })

        assertEquals(0.5f, fraction.value)

        fraction.set(0.25f)
        assertEquals(25, percent.value)
        assertEquals(0.25f, fraction.value)
    }

    @Test
    fun `mapped binding over a read-only source refuses writes`() {
        val source = mutableStateOf(1)
        val mapped = derivedStateOf { source.value }
            .asReadOnlyBinding()
            .map(to = { it.toString() }, from = { it.toInt() })

        assertFalse(mapped.isWritable)
        assertThrows(BindingException::class.java) { mapped.set("5") }
    }

    @Test
    fun `a throwing setter surfaces as a binding exception naming the binding`() {
        val binding = bind<Int>(
            get = { 1 },
            set = { throw IllegalStateException("model rejected it") },
            debugName = "explosive",
        )

        val failure = assertThrows(BindingException::class.java) { binding.set(2) }

        assertEquals("explosive", failure.bindingName)
        assertEquals("model rejected it", failure.cause?.message)
    }

    @Test
    fun `a throwing getter surfaces as a binding exception`() {
        val failure = assertThrows(BindingException::class.java) {
            bind<Int>(get = { throw IllegalStateException("no value") }, set = { }, debugName = "broken")
        }
        assertEquals("broken", failure.bindingName)
    }
}

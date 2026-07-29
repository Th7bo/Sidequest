package dev.th7bo.sidequest.ui.state

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReactiveStateTest {

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

    // -- sources ------------------------------------------------------------

    @Test
    fun `writing an equal value is a complete no-op`() {
        val state = mutableStateOf(5)
        var notifications = 0
        state.observe(scope) { notifications++ }

        state.value = 5
        assertEquals(0, notifications, "an equal write must not notify")

        state.value = 6
        assertEquals(1, notifications)
    }

    @Test
    fun `custom equality controls what counts as a change`() {
        // Compare only the first element, so appending must not notify.
        val state = mutableStateOf(listOf(1), equality = { a, b -> a.firstOrNull() == b.firstOrNull() })
        var notifications = 0
        state.observe(scope) { notifications++ }

        state.value = listOf(1, 2, 3)
        assertEquals(0, notifications)

        state.value = listOf(9)
        assertEquals(1, notifications)
    }

    // -- derivations --------------------------------------------------------

    @Test
    fun `derivation recomputes only when a dependency actually changes`() {
        val width = mutableStateOf(2)
        val height = mutableStateOf(3)
        var computations = 0
        val area = derivedStateOf { computations++; width.value * height.value }

        assertEquals(6, area.value)
        assertEquals(1, computations)

        // Repeated reads with no writes must not recompute.
        assertEquals(6, area.value)
        assertEquals(6, area.value)
        assertEquals(1, computations)

        width.value = 4
        assertEquals(12, area.value)
        assertEquals(2, computations)
    }

    @Test
    fun `derivation is lazy and does not compute until read`() {
        var computations = 0
        val source = mutableStateOf(1)
        val doubled = derivedStateOf { computations++; source.value * 2 }

        source.value = 2
        source.value = 3
        assertEquals(0, computations, "an unread derivation must never compute")

        assertEquals(6, doubled.value)
        assertEquals(1, computations)
    }

    @Test
    fun `conditional dependencies narrow to the branch actually taken`() {
        val useFirst = mutableStateOf(true)
        val first = mutableStateOf("A")
        val second = mutableStateOf("B")
        var computations = 0
        val chosen = derivedStateOf { computations++; if (useFirst.value) first.value else second.value }

        assertEquals("A", chosen.value)
        assertEquals(1, computations)

        // `second` was not read, so changing it must not invalidate anything.
        second.value = "B2"
        assertEquals("A", chosen.value)
        assertEquals(1, computations)

        useFirst.value = false
        assertEquals("B2", chosen.value)
        assertEquals(2, computations)

        // Now `first` is the untaken branch.
        first.value = "A2"
        assertEquals("B2", chosen.value)
        assertEquals(2, computations)
    }

    @Test
    fun `equal recomputation does not propagate to downstream derivations`() {
        val source = mutableStateOf(4)
        val isEven = source.map { it % 2 == 0 }
        var downstream = 0
        val label = isEven.map { downstream++; if (it) "even" else "odd" }

        assertEquals("even", label.value)
        assertEquals(1, downstream)

        // 4 -> 6 changes the source but `isEven` still yields true, so the version
        // cut-off must stop the change from reaching `label`.
        source.value = 6
        assertEquals("even", label.value)
        assertEquals(1, downstream, "downstream must not recompute when its input is unchanged")

        source.value = 7
        assertEquals("odd", label.value)
        assertEquals(2, downstream)
    }

    @Test
    fun `chained derivations propagate through several levels`() {
        val base = mutableStateOf(1)
        val doubled = base.map { it * 2 }
        val quadrupled = doubled.map { it * 2 }
        val described = quadrupled.map { "value=$it" }

        assertEquals("value=4", described.value)
        base.value = 5
        assertEquals("value=20", described.value)
    }

    @Test
    fun `combine tracks both sources`() {
        val first = mutableStateOf(1)
        val second = mutableStateOf(2)
        val sum = combine(first, second) { a, b -> a + b }

        assertEquals(3, sum.value)
        first.value = 10
        assertEquals(12, sum.value)
        second.value = 20
        assertEquals(30, sum.value)
    }

    @Test
    fun `boolean operators short-circuit and stay reactive`() {
        val enabled = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val both = enabled and visible
        val either = enabled or visible
        val negated = !enabled

        assertFalse(both.value)
        assertTrue(either.value)
        assertTrue(negated.value)

        enabled.value = true
        assertTrue(both.value)
        assertFalse(negated.value)
    }

    // -- cycles -------------------------------------------------------------

    @Test
    fun `direct self-reference is reported with a path`() {
        lateinit var selfReferential: UiState<Int>
        selfReferential = derivedStateOf("self") { selfReferential.value + 1 }

        val failure = assertThrows(StateCycleException::class.java) { selfReferential.value }
        assertEquals(listOf("self", "self"), failure.path)
    }

    @Test
    fun `indirect cycle reports the full dependency path`() {
        lateinit var a: UiState<Int>
        lateinit var b: UiState<Int>
        lateinit var c: UiState<Int>
        a = derivedStateOf("a") { b.value }
        b = derivedStateOf("b") { c.value }
        c = derivedStateOf("c") { a.value }

        val failure = assertThrows(StateCycleException::class.java) { a.value }
        assertEquals(listOf("a", "b", "c", "a"), failure.path)
        assertTrue(failure.message!!.contains("a -> b -> c -> a"))
    }

    // -- batching -----------------------------------------------------------

    @Test
    fun `batch collapses several writes into one notification`() {
        val width = mutableStateOf(1)
        val height = mutableStateOf(1)
        val area = combine(width, height) { w, h -> w * h }

        val seen = ArrayList<Int>()
        area.observe(scope) { seen.add(it) }

        batch {
            width.value = 10
            height.value = 10
        }

        assertEquals(listOf(100), seen, "observers must see one settled value, not each step")
    }

    @Test
    fun `a batched write that cancels itself out notifies nobody`() {
        val state = mutableStateOf("start")
        var notifications = 0
        state.observe(scope) { notifications++ }

        batch {
            state.value = "middle"
            state.value = "start"
        }

        assertEquals(0, notifications)
    }

    @Test
    fun `nested batches flush once at the outermost exit`() {
        val state = mutableStateOf(0)
        val seen = ArrayList<Int>()
        state.observe(scope) { seen.add(it) }

        batch {
            state.value = 1
            batch {
                state.value = 2
                state.value = 3
            }
            state.value = 4
        }

        assertEquals(listOf(4), seen)
    }

    @Test
    fun `untracked reads do not create dependencies`() {
        val tracked = mutableStateOf(1)
        val ignored = mutableStateOf(100)
        var computations = 0
        val result = derivedStateOf { computations++; tracked.value + untracked { ignored.value } }

        assertEquals(101, result.value)
        assertEquals(1, computations)

        ignored.value = 200
        assertEquals(101, result.value, "an untracked source must not invalidate the derivation")
        assertEquals(1, computations)

        tracked.value = 2
        assertEquals(202, result.value)
    }

    // -- subscriptions and disposal ----------------------------------------

    @Test
    fun `disposing a scope detaches its listeners`() {
        val state = mutableStateOf(0)
        val localScope = DisposableScope()
        var notifications = 0
        state.observe(localScope) { notifications++ }

        state.value = 1
        assertEquals(1, notifications)

        localScope.dispose()
        state.value = 2
        assertEquals(1, notifications, "a disposed scope must not receive further notifications")
    }

    @Test
    fun `disposing one subscription leaves the others attached`() {
        val state = mutableStateOf(0)
        var first = 0
        var second = 0
        val subscription = state.observe(scope) { first++ }
        state.observe(scope) { second++ }

        state.value = 1
        subscription.dispose()
        state.value = 2

        assertEquals(1, first)
        assertEquals(2, second)
        assertTrue(subscription.isDisposed)
    }

    @Test
    fun `registering into a disposed scope fails loudly`() {
        val localScope = DisposableScope()
        localScope.dispose()

        assertThrows(IllegalStateException::class.java) {
            localScope.register(Disposable { })
        }
    }

    @Test
    fun `scope disposal runs every child even when one throws`() {
        val localScope = DisposableScope()
        var disposedCount = 0
        localScope.register(Disposable { disposedCount++ })
        localScope.register(Disposable { throw IllegalStateException("boom") })
        localScope.register(Disposable { disposedCount++ })

        val failure = assertThrows(IllegalStateException::class.java) { localScope.dispose() }

        assertEquals("boom", failure.message)
        assertEquals(2, disposedCount, "a failing disposer must not strand its siblings")
    }

    @Test
    fun `observing a derivation delivers changes from its transitive sources`() {
        val source = mutableStateOf(1)
        val derived = source.map { it * 10 }
        val seen = ArrayList<Int>()
        derived.observe(scope) { seen.add(it) }

        source.value = 2
        source.value = 3

        assertEquals(listOf(20, 30), seen)
    }

    @Test
    fun `peek reads without creating a dependency`() {
        val tracked = mutableStateOf(1)
        val peeked = mutableStateOf(10)
        var computations = 0
        val result = derivedStateOf { computations++; tracked.value + peeked.peek() }

        assertEquals(11, result.value)
        peeked.value = 20
        assertEquals(11, result.value)
        assertEquals(1, computations)
    }

    @Test
    fun `constant state never notifies`() {
        val constant = constantState(42)
        var notifications = 0
        constant.observe(scope) { notifications++ }

        assertEquals(42, constant.value)
        assertEquals(0, notifications)
    }

    // -- threading ----------------------------------------------------------

    @Test
    fun `access from another thread is rejected`() {
        val state = mutableStateOf(1)
        val failure = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)

        Thread {
            try {
                state.value = 2
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                latch.countDown()
            }
        }.also { it.name = "background" }.start()

        assertTrue(latch.await(5, TimeUnit.SECONDS), "background thread did not finish")
        val thrown = failure.get()
        assertTrue(thrown is WrongThreadException, "expected WrongThreadException, got $thrown")
        assertEquals(1, state.value, "the rejected write must not have taken effect")
    }

    @Test
    fun `binding the ui thread twice to different threads is rejected`() {
        UiThread.bind()
        assertThrows(IllegalStateException::class.java) {
            UiThread.bind(Thread({ }, "someone-else"))
        }
    }
}

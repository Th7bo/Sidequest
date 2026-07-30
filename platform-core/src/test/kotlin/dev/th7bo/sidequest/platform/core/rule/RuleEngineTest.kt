package dev.th7bo.sidequest.platform.core.rule

import dev.th7bo.sidequest.platform.chat.ChatMessage
import dev.th7bo.sidequest.platform.chat.DropRarity
import dev.th7bo.sidequest.platform.chat.RareDropEvent
import dev.th7bo.sidequest.platform.core.context.DefaultGameContextService
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.core.party.DefaultPartyService
import dev.th7bo.sidequest.platform.core.player.DefaultPlayerDirectory
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.MinecraftDisconnectEvent
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.parser.ScoreboardSnapshot
import dev.th7bo.sidequest.platform.rule.ActionHandler
import dev.th7bo.sidequest.platform.rule.Condition
import dev.th7bo.sidequest.platform.rule.RuleAction
import dev.th7bo.sidequest.platform.rule.RuleEvaluation
import dev.th7bo.sidequest.platform.rule.RuleFiredEvent
import dev.th7bo.sidequest.platform.rule.RuleReset
import dev.th7bo.sidequest.platform.rule.RuleStore
import dev.th7bo.sidequest.platform.rule.RuleTrigger
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * The rule engine.
 *
 * The assertions worth reading are the ones about rules that do *not* fire, and specifically about the reason
 * given. A rule that silently does nothing is the most frustrating thing in a system like this, and every one
 * of these tests checks that the engine can say which condition stopped it.
 */
class RuleEngineTest {

    private lateinit var events: DefaultEventBus
    private lateinit var context: DefaultGameContextService
    private lateinit var players: DefaultPlayerDirectory
    private lateinit var engine: DefaultRuleEngine

    private val ran = mutableListOf<String>()
    private var clock = 1_000L

    private val me = dev.th7bo.sidequest.platform.player.PlayerId.of(
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
    )

    @BeforeEach
    fun setUp() {
        events = DefaultEventBus(TestScheduler(), NoopLogger)
        context = DefaultGameContextService(events, NoopLogger)
        players = DefaultPlayerDirectory(events, now = { clock })
        engine = DefaultRuleEngine(
            events = events,
            context = context,
            party = DefaultPartyService(events, players, NoopLogger, now = { clock }),
            players = players,
            log = NoopLogger,
            localPlayer = { me },
            now = { clock },
        )
        ran.clear()
        // Handlers for the two kinds these tests use. A missing handler is deliberately not an error, so a
        // test that forgot one would otherwise pass while doing nothing.
        engine.handle("notify") { action, outcome ->
            ran.add("notify:" + outcome.format((action as RuleAction.Notify).title))
            true
        }
        engine.handle("sound") { action, _ ->
            ran.add("sound:" + (action as RuleAction.PlaySound).soundId.value)
            true
        }
    }

    private fun drop(item: String = "Hyperion", amount: Int = 1) = RareDropEvent(
        itemName = item,
        rarity = DropRarity.RARE,
        amount = amount,
        message = ChatMessage.of("§6§lRARE DROP! §6§l$item"),
    )

    private fun onHypixel(island: String = "Village", floor: String? = null) {
        context.setOnHypixel(true)
        val area = if (floor != null) " §7⏣ §cThe Catacombs §8($floor)" else " §7⏣ §b$island"
        context.onScoreboard(ScoreboardSnapshot("§e§lSKYBLOCK", listOf(area)))
    }

    // -- firing --------------------------------------------------------------

    @Test
    fun `a rule with no conditions fires on its trigger`() {
        engine.register(
            rule(SqId.sidequest("test.any_drop"), RuleTrigger.of<RareDropEvent>(), "Any drop") {
                then(RuleAction.Notify("Got {progress}"))
                then(RuleAction.AddProgress())
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)

        assertEquals(listOf("notify:Got 1"), ran)
        assertEquals(1, engine.progressOf(SqId.sidequest("test.any_drop"), me).progress)
    }

    /** A rule triggered on a family base class sees every member of it. */
    @Test
    fun `a trigger on a base class matches a subclass`() {
        engine.register(
            rule(
                SqId.sidequest("test.any_chat"),
                RuleTrigger.of<dev.th7bo.sidequest.platform.chat.ChatDerivedEvent>(),
            ) {
                then(RuleAction.Notify("chat"))
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)

        assertEquals(listOf("notify:chat"), ran)
    }

    @Test
    fun `an unrelated event does not wake a rule`() {
        engine.register(
            rule(SqId.sidequest("test.drop"), RuleTrigger.of<RareDropEvent>()) {
                then(RuleAction.Notify("drop"))
            },
        )
        engine.install()

        events.post(MinecraftDisconnectEvent(), EventSource.GAME)

        assertTrue(ran.isEmpty())
        assertTrue(engine.trace().isEmpty()) { "an unrelated event should not even be evaluated" }
    }

    @Test
    fun `a disabled rule is inert`() {
        engine.register(
            rule(SqId.sidequest("test.off"), RuleTrigger.of<RareDropEvent>()) {
                then(RuleAction.Notify("nope"))
            }.copy(isEnabled = false),
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)
        assertTrue(ran.isEmpty())
    }

    // -- conditions, and the reasons they give -------------------------------

    /**
     * The most important behaviour in the file.
     *
     * When a rule does not fire, the engine names the condition that stopped it — in terms of the *actual*
     * state, not a restatement of the rule. "You are on the Hub" answers the question; "the condition was
     * false" does not.
     */
    @Test
    fun `a skip names the condition that stopped it, in terms of the actual state`() {
        engine.register(
            rule(SqId.sidequest("test.dungeon_drop"), RuleTrigger.of<RareDropEvent>()) {
                where(Condition.OnIsland(setOf(Island.CATACOMBS)))
                then(RuleAction.Notify("in a dungeon"))
            },
        )
        engine.install()
        onHypixel(island = "Village")

        events.post(drop(), EventSource.PARSER)

        val skipped = engine.trace().filterIsInstance<RuleEvaluation.Skipped>().single()
        assertTrue(ran.isEmpty())
        assertTrue("Hub" in skipped.reason || "???" in skipped.reason) { "unhelpful reason: ${skipped.reason}" }
    }

    /** Of several conditions, the reason names the one that failed and not the others. */
    @Test
    fun `only the failing condition is named`() {
        engine.register(
            rule(SqId.sidequest("test.two"), RuleTrigger.of<RareDropEvent>()) {
                where(Condition.ItemIs(setOf("Hyperion")))
                where(Condition.DoingActivity(setOf(Activity.DUNGEONS)))
                then(RuleAction.Notify("both"))
            },
        )
        engine.install()
        onHypixel(island = "Village")

        events.post(drop(item = "Hyperion"), EventSource.PARSER)

        val reason = engine.trace().filterIsInstance<RuleEvaluation.Skipped>().single().reason
        assertTrue("doing" in reason) { "expected the activity condition to be named, got: $reason" }
        assertFalse("item" in reason) { "the condition that passed should not be mentioned: $reason" }
    }

    /**
     * A chat-derived drop has a name and no snapshot, and `ItemIs` matches either.
     *
     * A rule author thinks "the item is a Hyperion" and should not have to know which of the two the event
     * happened to carry.
     */
    @Test
    fun `an item condition matches a name from chat`() {
        engine.register(
            rule(SqId.sidequest("test.hyperion"), RuleTrigger.of<RareDropEvent>()) {
                where(Condition.ItemIs(setOf("hyperion")))
                then(RuleAction.Notify("hyperion"))
            },
        )
        engine.install()

        events.post(drop(item = "Hyperion"), EventSource.PARSER)
        assertEquals(listOf("notify:hyperion"), ran)

        ran.clear()
        events.post(drop(item = "Necron's Blade"), EventSource.PARSER)
        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a value condition compares against a range`() {
        engine.register(
            rule(SqId.sidequest("test.stack"), RuleTrigger.of<RareDropEvent>()) {
                where(Condition.ValueIn(3L..64L))
                then(RuleAction.Notify("a stack"))
            },
        )
        engine.install()

        events.post(drop(amount = 1), EventSource.PARSER)
        assertTrue(ran.isEmpty())

        events.post(drop(amount = 5), EventSource.PARSER)
        assertEquals(listOf("notify:a stack"), ran)
    }

    @Test
    fun `conditions compose with any and not`() {
        engine.register(
            rule(SqId.sidequest("test.compose"), RuleTrigger.of<RareDropEvent>()) {
                where(
                    Condition.any(
                        Condition.ItemIs(setOf("Hyperion")),
                        Condition.ItemIs(setOf("Terminator")),
                    ),
                )
                where(Condition.Not(Condition.OnIsland(setOf(Island.CATACOMBS))))
                then(RuleAction.Notify("ok"))
            },
        )
        engine.install()
        onHypixel(island = "Village")

        events.post(drop(item = "Terminator"), EventSource.PARSER)
        assertEquals(listOf("notify:ok"), ran)
    }

    /** The condition that stops a rule acting on a heuristic. */
    @Test
    fun `a rule can require the activity to be believed`() {
        engine.register(
            rule(SqId.sidequest("test.reliable"), RuleTrigger.of<RareDropEvent>()) {
                where(Condition.ActivityIsReliable)
                then(RuleAction.Notify("sure"))
            },
        )
        engine.install()
        onHypixel(island = "Village")

        events.post(drop(), EventSource.PARSER)
        assertTrue(ran.isEmpty()) { "an island alone is only a guess" }

        onHypixel(floor = "F7")
        events.post(drop(), EventSource.PARSER)
        assertEquals(listOf("notify:sure"), ran)
    }

    // -- cooldowns and limits ------------------------------------------------

    @Test
    fun `a rule on cooldown says how long is left`() {
        engine.register(
            rule(SqId.sidequest("test.cool"), RuleTrigger.of<RareDropEvent>()) {
                cooldown = 10.seconds
                then(RuleAction.Notify("fired"))
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)
        clock += 1_000
        events.post(drop(), EventSource.PARSER)

        assertEquals(listOf("notify:fired"), ran)
        val reason = engine.trace().filterIsInstance<RuleEvaluation.Skipped>().single().reason
        assertTrue("cooldown" in reason) { reason }
    }

    @Test
    fun `the cooldown ends`() {
        engine.register(
            rule(SqId.sidequest("test.cool"), RuleTrigger.of<RareDropEvent>()) {
                cooldown = 10.seconds
                then(RuleAction.Notify("fired"))
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)
        clock += 10_001
        events.post(drop(), EventSource.PARSER)

        assertEquals(2, ran.size)
    }

    /** An achievement is a rule with a limit of one, enforced by the engine rather than by the rule. */
    @Test
    fun `a rule can only fire so many times`() {
        engine.register(
            rule(SqId.sidequest("test.once"), RuleTrigger.of<RareDropEvent>()) {
                maxFirings = 1
                then(RuleAction.Notify("first"))
            },
        )
        engine.install()

        repeat(3) {
            clock += 1_000
            events.post(drop(), EventSource.PARSER)
        }

        assertEquals(listOf("notify:first"), ran)
        assertTrue(engine.trace().filterIsInstance<RuleEvaluation.Skipped>().any { "already fired" in it.reason })
    }

    // -- tiers ---------------------------------------------------------------

    @Test
    fun `a tiered rule fires only when a threshold is crossed`() {
        engine.register(
            rule(SqId.sidequest("test.tiers"), RuleTrigger.of<RareDropEvent>()) {
                tiers = listOf(2, 4)
                then(RuleAction.AddProgress())
                then(RuleAction.Notify("tier {tier} at {progress}"))
            },
        )
        engine.install()

        repeat(4) {
            clock += 1_000
            events.post(drop(), EventSource.PARSER)
        }

        assertEquals(listOf("notify:tier 2 at 2", "notify:tier 4 at 4"), ran)
    }

    /**
     * Progress that jumps past a tier awards the highest crossed, not the lowest.
     *
     * Somebody who goes from nothing to a hundred and fifty has earned the hundred, and announcing the ten
     * would be announcing the wrong thing.
     */
    @Test
    fun `a jump past several tiers awards the highest`() {
        engine.register(
            rule(SqId.sidequest("test.jump"), RuleTrigger.of<RareDropEvent>()) {
                tiers = listOf(1, 10, 100)
                then(RuleAction.AddProgress(150))
                then(RuleAction.Notify("tier {tier}"))
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)

        assertEquals(listOf("notify:tier 100"), ran)
    }

    @Test
    fun `a tier is never awarded twice`() {
        engine.register(
            rule(SqId.sidequest("test.twice"), RuleTrigger.of<RareDropEvent>()) {
                tiers = listOf(1)
                then(RuleAction.AddProgress())
                then(RuleAction.Notify("tier {tier}"))
            },
        )
        engine.install()

        repeat(3) {
            clock += 1_000
            events.post(drop(), EventSource.PARSER)
        }

        assertEquals(listOf("notify:tier 1"), ran)
    }

    @Test
    fun `tiers out of order are refused at registration`() {
        val thrown = runCatching {
            engine.register(
                rule(SqId.sidequest("test.bad"), RuleTrigger.of<RareDropEvent>()) {
                    tiers = listOf(10, 5)
                },
            )
        }.exceptionOrNull()
        assertNotNull(thrown)
        assertTrue(thrown is IllegalArgumentException)
    }

    // -- state ---------------------------------------------------------------

    @Test
    fun `a rule can depend on another having fired`() {
        engine.register(
            rule(SqId.sidequest("test.first"), RuleTrigger.of<RareDropEvent>()) {
                maxFirings = 1
                then(RuleAction.Notify("first"))
            },
        )
        engine.register(
            rule(SqId.sidequest("test.second"), RuleTrigger.of<RareDropEvent>()) {
                where(Condition.HasFired(SqId.sidequest("test.first")))
                then(RuleAction.Notify("second"))
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)

        // Both on the same event: the first fires, and the second sees that it has.
        assertTrue("notify:first" in ran)
        assertTrue("notify:second" in ran)
    }

    @Test
    fun `progress is remembered per subject`() {
        val other = dev.th7bo.sidequest.platform.player.PlayerId.of(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
        )
        val id = SqId.sidequest("test.subject")
        engine.register(
            rule(id, RuleTrigger.of<RareDropEvent>()) { then(RuleAction.AddProgress()) },
        )

        engine.evaluate(id, drop(), me)
        engine.evaluate(id, drop(), me)
        engine.evaluate(id, drop(), other)

        assertEquals(2, engine.progressOf(id, me).progress)
        assertEquals(1, engine.progressOf(id, other).progress)
    }

    @Test
    fun `resetting clears one subject and leaves the rest`() {
        val other = dev.th7bo.sidequest.platform.player.PlayerId.of(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
        )
        val id = SqId.sidequest("test.reset")
        engine.register(rule(id, RuleTrigger.of<RareDropEvent>()) { then(RuleAction.AddProgress()) })

        engine.evaluate(id, drop(), me)
        engine.evaluate(id, drop(), other)
        engine.reset(id, me)

        assertEquals(0, engine.progressOf(id, me).progress)
        assertEquals(1, engine.progressOf(id, other).progress)
    }

    /**
     * Asking without naming a subject asks about the local player.
     *
     * Every other test here passes an explicit subject, which is how this got out: an event files progress
     * under the local player, so a caller who read `progressOf(id)` — the obvious spelling, and the one the
     * debug command used — got zero and read it as a rule that had never fired. A real client run found it.
     */
    @Test
    fun `progress read without a subject is the local player's`() {
        val id = SqId.sidequest("test.default_subject")
        engine.register(rule(id, RuleTrigger.of<RareDropEvent>()) { then(RuleAction.AddProgress()) })
        engine.install()

        events.post(drop(), EventSource.PARSER)

        assertEquals(1, engine.progressOf(id).progress)
        assertEquals(engine.progressOf(id, me), engine.progressOf(id))
    }

    @Test
    fun `resetting without a subject clears the local player, and every-subject clears the rest`() {
        val other = dev.th7bo.sidequest.platform.player.PlayerId.of(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
        )
        val id = SqId.sidequest("test.reset_default")
        engine.register(rule(id, RuleTrigger.of<RareDropEvent>()) { then(RuleAction.AddProgress()) })

        engine.evaluate(id, drop(), me)
        engine.evaluate(id, drop(), other)

        engine.reset(id)
        assertEquals(0, engine.progressOf(id).progress)
        // The other subject is untouched, which is the half that would break if the default meant "everybody".
        assertEquals(1, engine.progressOf(id, other).progress)

        engine.resetEverySubject(id)
        assertEquals(0, engine.progressOf(id, other).progress)
    }

    @Test
    fun `a rule that resets on disconnect does`() {
        val id = SqId.sidequest("test.session")
        engine.register(
            rule(id, RuleTrigger.of<RareDropEvent>()) {
                reset = RuleReset.ON_DISCONNECT
                then(RuleAction.AddProgress())
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)
        assertEquals(1, engine.progressOf(id, me).progress)

        events.post(MinecraftDisconnectEvent(), EventSource.GAME)
        assertEquals(0, engine.progressOf(id, me).progress)
    }

    @Test
    fun `progress survives a reload`() {
        val id = SqId.sidequest("test.persist")
        var saved: RuleStore? = null
        engine.onStoreChanged = { saved = it }
        engine.register(rule(id, RuleTrigger.of<RareDropEvent>()) { then(RuleAction.AddProgress()) })

        engine.evaluate(id, drop(), me)
        assertNotNull(saved)

        val reloaded = DefaultRuleEngine(
            events, context,
            DefaultPartyService(events, players, NoopLogger, now = { clock }),
            players, NoopLogger, { me }, { clock },
        )
        reloaded.register(rule(id, RuleTrigger.of<RareDropEvent>()) { then(RuleAction.AddProgress()) })
        reloaded.load(saved!!)

        assertEquals(1, reloaded.progressOf(id, me).progress)
    }

    // -- actions -------------------------------------------------------------

    /**
     * A missing handler skips the action, not the rule.
     *
     * The plan lists actions whose subsystems do not exist yet. A rule that is half-supported should do the
     * half that works rather than failing whole.
     */
    @Test
    fun `an action with no handler does not stop the others`() {
        engine.register(
            rule(SqId.sidequest("test.partial"), RuleTrigger.of<RareDropEvent>()) {
                then(RuleAction.GrantCurrency(100))
                then(RuleAction.Notify("still ran"))
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)

        assertEquals(listOf("notify:still ran"), ran)
        assertTrue(engine.trace().single() is RuleEvaluation.Fired) { "the rule itself should have fired" }
    }

    /** Same isolation as everywhere else: one broken action must not take the rest of the rule with it. */
    @Test
    fun `an action that throws does not stop the others`() {
        engine.handle("sound") { _, _ -> error("badly behaved") }
        engine.register(
            rule(SqId.sidequest("test.throwing"), RuleTrigger.of<RareDropEvent>()) {
                then(RuleAction.PlaySound(SqId.sidequest("boom")))
                then(RuleAction.Notify("after"))
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)

        assertEquals(listOf("notify:after"), ran)
    }

    @Test
    fun `a firing is announced on the bus`() {
        val fired = mutableListOf<String>()
        events.on<RuleFiredEvent>(OwnerId(SqId.sidequest("test"))) { fired.add(it.describe()) }
        engine.register(
            rule(SqId.sidequest("test.announce"), RuleTrigger.of<RareDropEvent>()) {
                then(RuleAction.AddProgress())
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)

        assertEquals(1, fired.size)
        assertTrue("test.announce" in fired.single())
    }

    /** A rule triggered on a rule event would trigger itself. One recursion is all it takes. */
    @Test
    fun `a rule cannot be triggered by a rule firing`() {
        engine.register(
            rule(SqId.sidequest("test.recursive"), RuleTrigger.of<RuleFiredEvent>()) {
                then(RuleAction.Notify("again"))
            },
        )
        engine.register(
            rule(SqId.sidequest("test.trigger"), RuleTrigger.of<RareDropEvent>()) {
                then(RuleAction.Notify("once"))
            },
        )
        engine.install()

        events.post(drop(), EventSource.PARSER)

        assertEquals(listOf("notify:once"), ran)
    }

    // -- manual evaluation ---------------------------------------------------

    /**
     * Exposed for the plan's "manual admin trigger" and "testing".
     *
     * A rule that can only run from the event bus can only be tested by producing the event, and producing a
     * rare drop on demand is not something a developer can do.
     */
    @Test
    fun `a rule can be evaluated by hand`() {
        val id = SqId.sidequest("test.manual")
        engine.register(rule(id, RuleTrigger.of<RareDropEvent>()) { then(RuleAction.Notify("by hand")) })

        val result = engine.evaluate(id, drop(), me)

        assertTrue(result is RuleEvaluation.Fired)
        assertEquals(listOf("notify:by hand"), ran)
    }

    @Test
    fun `evaluating an unknown rule says nothing happened`() {
        assertNull(engine.evaluate(SqId.sidequest("test.nope"), drop(), me))
    }

    @Test
    fun `registering the same id twice is refused`() {
        val id = SqId.sidequest("test.dup")
        assertTrue(engine.register(rule(id, RuleTrigger.of<RareDropEvent>())))
        assertFalse(engine.register(rule(id, RuleTrigger.of<RareDropEvent>())))
        assertEquals(1, engine.rules().size)
    }

    @Test
    fun `the trace is bounded and newest first`() {
        engine.register(
            rule(SqId.sidequest("test.trace"), RuleTrigger.of<RareDropEvent>()) {
                cooldown = 1_000.seconds
                then(RuleAction.Notify("x"))
            },
        )
        engine.install()

        repeat(250) { events.post(drop(), EventSource.PARSER) }

        val trace = engine.trace()
        assertEquals(200, trace.size)
        assertTrue(trace.first() is RuleEvaluation.Skipped) { "the newest is the most recent skip" }
    }
}

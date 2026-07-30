package dev.th7bo.sidequest.platform.core.chat

import dev.th7bo.sidequest.platform.chat.ChatKind
import dev.th7bo.sidequest.platform.chat.ChatMessage
import dev.th7bo.sidequest.platform.chat.ChatMessageEvent
import dev.th7bo.sidequest.platform.chat.MatchTarget
import dev.th7bo.sidequest.platform.chat.chatRule
import dev.th7bo.sidequest.platform.core.event.DefaultEventBus
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.log.LogCategory
import dev.th7bo.sidequest.platform.core.log.LoggerFactory
import dev.th7bo.sidequest.platform.testkit.NoopLogger
import dev.th7bo.sidequest.platform.testkit.RecordingLogSink
import dev.th7bo.sidequest.platform.testkit.TestScheduler
import dev.th7bo.sidequest.platform.text.ClickAction
import dev.th7bo.sidequest.platform.text.ClickActionType
import dev.th7bo.sidequest.platform.text.SqText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The registry's own behaviour, with rules that exist only here.
 *
 * Separate from [ChatRulesTest] on purpose: this file is about ownership, versioning,
 * duplicate suppression and failure isolation, none of which should depend on what Hypixel
 * happens to be saying this month.
 */
class ChatParserTest {

    /** A test event, so nothing here depends on the shape of the real ones. */
    private class Marked(val label: String) : SidequestEvent() {
        override fun describe(): String = label
    }

    private lateinit var events: DefaultEventBus
    private lateinit var parser: DefaultChatParser
    private val marks = mutableListOf<String>()
    private val lines = mutableListOf<ChatMessageEvent>()

    private val owner = OwnerId(SqId.sidequest("test"))
    private val other = OwnerId(SqId.sidequest("test.other"))

    private var clock = 1_000L

    @BeforeEach
    fun setUp() {
        events = DefaultEventBus(TestScheduler(), NoopLogger)
        parser = DefaultChatParser(events, NoopLogger, now = { clock })
        marks.clear()
        lines.clear()
        events.on<Marked>(owner) { marks.add(it.label) }
        events.on<ChatMessageEvent>(owner) { lines.add(it) }
    }

    private fun rule(
        id: String,
        regex: String,
        version: Int = 1,
        target: MatchTarget = MatchTarget.FORMATTED,
        label: String = id,
    ) = chatRule(SqId.sidequest(id), regex, version = version, target = target) { Marked(label) }

    private fun feed(raw: String) = parser.onMessage(ChatMessage.of(raw))

    // ---------------------------------------------------------------
    // Matching and ownership
    // ---------------------------------------------------------------

    @Test
    fun `a registered rule turns a line into its event`() {
        parser.register(rule("test.hello", "hello (?<who>.*)"), owner)
        feed("hello world")
        assertEquals(listOf("test.hello"), marks)
    }

    @Test
    fun `a line no rule matches produces no derived event`() {
        parser.register(rule("test.hello", "hello .*"), owner)
        feed("goodbye world")
        assertEquals(emptyList<String>(), marks)
        assertEquals(1, parser.stats.received)
        assertEquals(1, parser.stats.unclassified)
    }

    /**
     * Every rule gets a look, not just the first.
     *
     * First-match-wins would mean the second feature to register on a line silently stops
     * working, which is a bug nobody can see from the outside.
     */
    @Test
    fun `two rules matching the same line both fire`() {
        parser.register(rule("test.one", ".*world.*", label = "one"), owner)
        parser.register(rule("test.two", "hello.*", label = "two"), other)
        feed("hello world")
        assertEquals(listOf("one", "two"), marks)
        assertEquals(1, parser.stats.classified)
    }

    @Test
    fun `cancelling a registration stops the rule matching`() {
        val registration = parser.register(rule("test.hello", "hello .*"), owner)
        registration.cancel()
        feed("hello world")
        assertEquals(emptyList<String>(), marks)
        assertEquals(emptyList<SqId>(), parser.patterns().map { it.id })
    }

    @Test
    fun `unregisterAll drops one owner's rules and leaves the rest`() {
        parser.register(rule("test.one", ".*", label = "one"), owner)
        parser.register(rule("test.two", ".*", label = "two"), other)
        parser.unregisterAll(owner)
        feed("anything")
        assertEquals(listOf("two"), marks)
    }

    @Test
    fun `registerAll cancels as one`() {
        val registration = parser.registerAll(
            listOf(rule("test.one", ".*", label = "one"), rule("test.two", ".*", label = "two")),
            owner,
        )
        registration.cancel()
        feed("anything")
        assertEquals(emptyList<String>(), marks)
    }

    // ---------------------------------------------------------------
    // Versioning
    // ---------------------------------------------------------------

    @Test
    fun `a newer version of a pattern replaces the older one`() {
        parser.register(rule("test.hello", "hello .*", version = 1, label = "old"), owner)
        parser.register(rule("test.hello", "hi .*", version = 2, label = "new"), other)

        feed("hi there")
        assertEquals(listOf("new"), marks)

        marks.clear()
        feed("hello there")
        assertEquals(emptyList<String>(), marks, "the replaced pattern should be gone, not shadowed")
        assertEquals(1, parser.patterns().size)
    }

    @Test
    fun `an older or equal version is refused`() {
        parser.register(rule("test.hello", "hello .*", version = 3, label = "keep"), owner)
        parser.register(rule("test.hello", "hi .*", version = 3, label = "reject"), other)
        parser.register(rule("test.hello", "hey .*", version = 2, label = "reject"), other)

        feed("hello there")
        assertEquals(listOf("keep"), marks)
        assertEquals(1, parser.patterns().size)
    }

    // ---------------------------------------------------------------
    // Duplicate suppression
    // ---------------------------------------------------------------

    /**
     * The distinction the whole feature turns on: the line is always reported, the derived
     * event is not. A feature mirroring chat wants what the player saw; a feature counting
     * loot must not count the same drop twice.
     */
    @Test
    fun `a repeated line is reported once but derived once`() {
        parser.register(rule("test.drop", "drop"), owner)
        feed("drop")
        feed("drop")

        assertEquals(listOf("test.drop"), marks)
        assertEquals(2, lines.size)
        assertEquals(listOf(false, true), lines.map { it.isDuplicate })
        assertEquals(1, parser.stats.duplicates)
    }

    @Test
    fun `the same line outside the window is a new line`() {
        parser.register(rule("test.drop", "drop"), owner)
        feed("drop")
        clock += DefaultChatParser.DEFAULT_DUPLICATE_WINDOW_MILLIS + 1
        feed("drop")
        assertEquals(listOf("test.drop", "test.drop"), marks)
        assertEquals(0, parser.stats.duplicates)
    }

    @Test
    fun `two different lines are never duplicates of each other`() {
        parser.register(rule("test.any", ".*"), owner)
        feed("one")
        feed("two")
        assertEquals(2, marks.size)
        assertEquals(0, parser.stats.duplicates)
    }

    /** Visually identical lines with different formatting came from different messages. */
    @Test
    fun `lines differing only in formatting are not duplicates`() {
        parser.register(rule("test.any", "(?:§.)*hi", label = "hi"), owner)
        feed("§ahi")
        feed("§chi")
        assertEquals(listOf("hi", "hi"), marks)
    }

    @Test
    fun `reset forgets the previous line`() {
        parser.register(rule("test.drop", "drop"), owner)
        feed("drop")
        parser.reset()
        feed("drop")
        assertEquals(2, marks.size)
    }

    // ---------------------------------------------------------------
    // Failure isolation
    // ---------------------------------------------------------------

    @Test
    fun `a rule that throws is logged and does not stop the others`() {
        val sink = RecordingLogSink()
        val log = LoggerFactory(sink).create(LogCategory.PARSER, SqId.sidequest("chat"))
        parser = DefaultChatParser(events, log, now = { clock })

        parser.register(
            chatRule<Marked>(SqId.sidequest("test.boom"), ".*") { error("pattern author's fault") },
            owner,
        )
        parser.register(rule("test.fine", ".*", label = "fine"), other)

        feed("anything")

        assertEquals(listOf("fine"), marks)
        assertEquals(1, parser.stats.failures)
        assertTrue(sink.errors().any { "test.boom" in it.message }) { sink.messages().toString() }
    }

    @Test
    fun `a rule that builds nothing is not a failure`() {
        parser.register(chatRule<Marked>(SqId.sidequest("test.quiet"), ".*") { null }, owner)
        feed("anything")
        assertEquals(emptyList<String>(), marks)
        assertEquals(0, parser.stats.failures)
        // It matched, so the line counts as classified even though nothing came of it.
        assertEquals(1, parser.stats.classified)
    }

    // ---------------------------------------------------------------
    // Targets and structure
    // ---------------------------------------------------------------

    @Test
    fun `the formatted target keeps colours and drops resets`() {
        val message = ChatMessage.of("§r§9Party §8> §7nea89o§f: hi§r")
        assertEquals("§9Party §8> §7nea89o§f: hi", message.formatted)
        assertEquals("Party > nea89o: hi", message.plain)
        assertEquals("Party > nea89o: hi", message.clean)
    }

    /** The layout matters: the plain view keeps the indentation Hypixel banners with. */
    @Test
    fun `the plain target keeps leading whitespace and the clean one does not`() {
        val message = ChatMessage.of("  §b§lSKILL LEVEL UP")
        assertEquals("  SKILL LEVEL UP", message.plain)
        assertEquals("SKILL LEVEL UP", message.clean)
    }

    @Test
    fun `a pattern matches the target it asked for`() {
        parser.register(rule("test.plain", "  indented", target = MatchTarget.PLAIN, label = "plain"), owner)
        parser.register(rule("test.clean", "indented", target = MatchTarget.CLEAN, label = "clean"), other)
        feed("  §a§lindented")
        assertEquals(listOf("plain", "clean"), marks)
    }

    /**
     * The click action is readable from a rule.
     *
     * This is the durable half of a Hypixel prompt: the wording changes and the command
     * behind it does not.
     */
    @Test
    fun `a rule can read the command behind a click`() {
        val text = SqText(
            content = "Notch invited you ",
            children = listOf(
                SqText(
                    content = "ACCEPT",
                    clickAction = ClickAction(ClickActionType.RUN_COMMAND, "/party accept Notch"),
                ),
            ),
        )
        val message = ChatMessage.of("§eNotch invited you §a§lACCEPT", text, ChatKind.SYSTEM)

        assertEquals(listOf("/party accept Notch"), message.commands)
        assertEquals("/party accept Notch", message.commandStartingWith("/party accept"))
        assertNull(message.commandStartingWith("/guild"))
    }

    @Test
    fun `the message on a derived event is the one that produced it`() {
        var captured: ChatMessage? = null
        parser.register(
            chatRule<Marked>(SqId.sidequest("test.capture"), ".*") { captured = it.message; Marked("x") },
            owner,
        )
        val message = ChatMessage.of("anything")
        parser.onMessage(message)
        assertSame(message, captured)
    }

    // ---------------------------------------------------------------
    // Debug logging
    // ---------------------------------------------------------------

    @Test
    fun `debug logging names the pattern that matched and the lines that did not`() {
        val sink = RecordingLogSink()
        val log = LoggerFactory(sink).apply { setLevel(LogCategory.PARSER, dev.th7bo.sidequest.platform.log.LogLevel.DEBUG) }
            .create(LogCategory.PARSER, SqId.sidequest("chat"))
        parser = DefaultChatParser(events, log, now = { clock })
        parser.isDebugLogging = true
        parser.register(rule("test.hello", "hello .*"), owner)

        feed("hello world")
        feed("nothing matches this")

        val messages = sink.messages()
        assertTrue(messages.any { "sidequest:test.hello" in it }) { messages.toString() }
        assertTrue(messages.any { "unclassified" in it }) { messages.toString() }
    }
}

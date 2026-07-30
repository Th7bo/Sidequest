package dev.th7bo.sidequest.platform.core.rule

import dev.th7bo.sidequest.platform.chat.ChatDerivedEvent
import dev.th7bo.sidequest.platform.chat.PlayerChatEvent
import dev.th7bo.sidequest.platform.chat.RareDropEvent
import dev.th7bo.sidequest.platform.chat.SkillLevelUpEvent
import dev.th7bo.sidequest.platform.event.DispatchMode
import dev.th7bo.sidequest.platform.event.EventBus
import dev.th7bo.sidequest.platform.event.EventSource
import dev.th7bo.sidequest.platform.event.MinecraftDisconnectEvent
import dev.th7bo.sidequest.platform.event.SidequestEvent
import dev.th7bo.sidequest.platform.event.on
import dev.th7bo.sidequest.platform.id.OwnerId
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.item.SqItem
import dev.th7bo.sidequest.platform.lifecycle.RegistrationScope
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.platform.party.PartyService
import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.rule.ActionHandler
import dev.th7bo.sidequest.platform.rule.Condition
import dev.th7bo.sidequest.platform.rule.Rule
import dev.th7bo.sidequest.platform.rule.RuleAction
import dev.th7bo.sidequest.platform.rule.RuleContext
import dev.th7bo.sidequest.platform.rule.RuleEngine
import dev.th7bo.sidequest.platform.rule.RuleEvaluation
import dev.th7bo.sidequest.platform.rule.RuleFiredEvent
import dev.th7bo.sidequest.platform.rule.RuleOutcome
import dev.th7bo.sidequest.platform.rule.RuleProgress
import dev.th7bo.sidequest.platform.rule.RuleReset
import dev.th7bo.sidequest.platform.rule.RuleStore
import dev.th7bo.sidequest.platform.skyblock.GameContext
import dev.th7bo.sidequest.platform.skyblock.GameContextService
import dev.th7bo.sidequest.platform.skyblock.ProfileChangedEvent
import dev.th7bo.sidequest.platform.core.backend.RealtimeMessageReceivedEvent
import dev.th7bo.sidequest.platform.skyblock.SqLocation
import dev.th7bo.sidequest.protocol.RealtimePayload
import java.util.concurrent.ConcurrentHashMap

/**
 * Evaluates rules.
 *
 * Four things shape it, and each is a way the obvious implementation goes wrong.
 *
 * **Rules are indexed by their trigger's event class.** A hundred rules evaluating their conditions on every
 * event, at twenty ticks a second, is a lot of work to conclude nothing. The index means an event wakes only
 * the rules that could possibly match it.
 *
 * **A skip is explained, not swallowed.** Every evaluation produces a reason, and the last few hundred are
 * kept. A rule that silently does nothing is the most frustrating thing in a system like this, and
 * "you are on the Hub, not in the Catacombs" is the difference between an hour and a minute.
 *
 * **Actions are dispatched by kind, and a missing handler is a skipped action rather than a failure.** The
 * plan's action list includes granting currency and creating evidence, neither of which has a subsystem yet.
 * A rule that is half-supported should do the half that works.
 *
 * **State is per subject.** Most rules are about the local player, but "Alice has paid three debts" is the
 * same machinery with a different subject, and building it for one player means rebuilding it later.
 */
public class DefaultRuleEngine(
    private val events: EventBus,
    private val context: GameContextService,
    private val party: PartyService,
    private val players: PlayerDirectory,
    private val log: Logger,
    private val localPlayer: () -> PlayerId?,
    private val now: () -> Long = System::currentTimeMillis,
) : RuleEngine {

    private val byId = LinkedHashMap<SqId, Rule>()

    /**
     * Rules by the exact class of their trigger.
     *
     * Looked up by walking an event's class hierarchy, so a rule triggered on a family base class — every
     * `ChatDerivedEvent` — still matches a specific subclass without being registered under each.
     */
    private val byTrigger = HashMap<Class<*>, MutableList<Rule>>()

    private val handlers = ConcurrentHashMap<String, ActionHandler>()

    private var store = RuleStore()

    /** Newest first, bounded. */
    private val recent = ArrayDeque<RuleEvaluation>()

    private val scope = RegistrationScope("rules")

    /** Called after every change, so the caller can persist. Set by whoever owns the storage. */
    public var onStoreChanged: ((RuleStore) -> Unit)? = null

    init {
        // The one action the engine performs itself, because progress is its own state and handing it to a
        // handler would mean a handler that has to reach back into the engine.
        handlers["progress"] = ActionHandler { _, _ -> true }
    }

    /** Restores persisted progress. */
    public fun load(store: RuleStore) {
        this.store = store
        log.debug { "Loaded progress for ${store.byRule.size} rule(s)" }
    }

    override fun register(rule: Rule): Boolean {
        if (byId.containsKey(rule.id)) {
            log.warn { "A rule is already registered as ${rule.id}" }
            return false
        }
        for (index in 1 until rule.tiers.size) {
            require(rule.tiers[index] > rule.tiers[index - 1]) {
                "${rule.id} has tiers out of order: ${rule.tiers}"
            }
        }
        byId[rule.id] = rule
        byTrigger.getOrPut(rule.trigger.eventType) { ArrayList() }.add(rule)
        log.debug { "Registered rule ${rule.id} on ${rule.trigger}" }
        return true
    }

    override fun unregister(ruleId: SqId): Boolean {
        val rule = byId.remove(ruleId) ?: return false
        byTrigger[rule.trigger.eventType]?.remove(rule)
        return true
    }

    override fun rules(): List<Rule> = byId.values.toList()

    override fun handle(kind: String, handler: ActionHandler) {
        handlers[kind] = handler
    }

    /**
     * Subscribes to everything.
     *
     * One listener on `SidequestEvent` rather than one per trigger. The bus delivers subtypes, so a single
     * subscription sees everything and the index does the narrowing — and the alternative is a subscription
     * per rule, which would have to be torn down and rebuilt every time a rule is registered.
     *
     * `IMMEDIATE`, so a rule that fires in response to an event has already run by the time a feature
     * listening for the same event reads its progress.
     */
    public fun install() {
        scope.add(
            events.on<SidequestEvent>(OWNER, mode = DispatchMode.IMMEDIATE) { event ->
                // Never rule events themselves: a rule that triggered on `RuleFiredEvent` and fired would
                // trigger itself, and one recursion is all it takes.
                if (event is RuleFiredEvent) return@on
                onEvent(event)
            },
        )
        scope.add(
            events.on<MinecraftDisconnectEvent>(OWNER, mode = DispatchMode.IMMEDIATE) {
                resetAll(RuleReset.ON_DISCONNECT)
            },
        )
        scope.add(
            events.on<ProfileChangedEvent>(OWNER, mode = DispatchMode.IMMEDIATE) {
                resetAll(RuleReset.ON_PROFILE_CHANGE)
            },
        )
        log.debug { "Rule engine installed with ${byId.size} rule(s)" }
    }

    private fun onEvent(event: SidequestEvent) {
        for (rule in rulesFor(event)) {
            if (!rule.isEnabled) continue
            evaluateInternal(rule, event, subjectFor(event))
        }
    }

    /**
     * Rules whose trigger matches this event, including by supertype.
     *
     * Walked up the class hierarchy so a rule on `ChatDerivedEvent` sees a `RareDropEvent`. Without that, a
     * rule that wants "any chat-derived event" would have to name all twenty.
     */
    private fun rulesFor(event: SidequestEvent): List<Rule> {
        if (byTrigger.isEmpty()) return emptyList()
        val matched = ArrayList<Rule>()
        var type: Class<*>? = event::class.java
        while (type != null && type != Any::class.java) {
            byTrigger[type]?.let { matched.addAll(it) }
            type = type.superclass
        }
        return matched
    }

    override fun evaluate(ruleId: SqId, event: SidequestEvent, subject: PlayerId?): RuleEvaluation? {
        val rule = byId[ruleId] ?: return null
        return evaluateInternal(rule, event, subject ?: subjectFor(event))
    }

    /**
     * The whole decision, in the order that matters.
     *
     * Cheap checks first — a firing limit and a cooldown are two map lookups — because most evaluations end
     * there, and running conditions before them would do work to reach a conclusion already known.
     */
    private fun evaluateInternal(rule: Rule, event: SidequestEvent, subject: PlayerId?): RuleEvaluation {
        val progress = progressOf(rule.id, subject)

        rule.maxFirings?.let { limit ->
            if (progress.firings >= limit) return skip(rule, "already fired $limit time(s)")
        }

        if (rule.cooldown.inWholeMilliseconds > 0) {
            val last = progress.lastFiredAtMillis
            if (last != null && now() - last < rule.cooldown.inWholeMilliseconds) {
                val remaining = rule.cooldown.inWholeMilliseconds - (now() - last)
                return skip(rule, "on cooldown for another ${remaining}ms")
            }
        }

        val ruleContext = Context(rule, event, subject, progress)
        if (!rule.condition.test(ruleContext)) {
            // The condition's own explanation, which names the branch that failed rather than the whole rule.
            return skip(rule, rule.condition.explain(ruleContext))
        }

        return fire(rule, event, subject, progress, ruleContext)
    }

    private fun fire(
        rule: Rule,
        event: SidequestEvent,
        subject: PlayerId?,
        before: RuleProgress,
        ruleContext: Context,
    ): RuleEvaluation {
        // Progress is added before the tier check, because a tier is a threshold on the *new* total.
        val added = rule.actions.filterIsInstance<RuleAction.AddProgress>().sumOf { it.amount }
        val progress = before.progress + added

        val tier = rule.tiers
            .filter { it <= progress && it !in before.awardedTiers }
            // The highest newly-crossed tier, not the lowest: progress that jumps from 0 to 150 past tiers of
            // 10/100 has earned the 100, and announcing the 10 would be announcing the wrong thing.
            .maxOrNull()

        if (rule.tiers.isNotEmpty() && tier == null) {
            // Progress moved but no threshold was crossed. Recorded, and not a firing — otherwise a tiered rule
            // would fire on every increment and tiers would mean nothing.
            update(rule.id, subject) { it.copy(progress = progress) }
            val next = rule.tiers.firstOrNull { t -> t > progress }
            return skip(
                rule,
                if (next != null) {
                    "progress $progress, next tier $next"
                } else {
                    // Not "next tier null". A finished rule is a different thing from one part-way to a
                    // threshold, and a reason nobody can read is the thing this whole mechanism exists against.
                    "progress $progress, every tier already awarded"
                },
            )
        }

        val firings = before.firings + 1
        update(rule.id, subject) {
            it.copy(
                progress = progress,
                firings = firings,
                lastFiredAtMillis = now(),
                awardedTiers = if (tier != null) it.awardedTiers + tier else it.awardedTiers,
            )
        }

        val outcome = RuleOutcome(
            rule = rule,
            subject = subject,
            progress = progress,
            tier = tier,
            firings = firings,
            event = event,
            game = context.context,
            item = ruleContext.item,
            location = ruleContext.location,
            timestampMillis = now(),
        )

        log.info { "Rule ${rule.id} fired: progress $progress" + (tier?.let { ", tier $it" } ?: "") }
        runActions(rule, outcome)

        val evaluation = RuleEvaluation.Fired(rule, outcome)
        record(evaluation)
        events.post(RuleFiredEvent(outcome), EventSource.DERIVED)
        return evaluation
    }

    /**
     * Runs a rule's actions.
     *
     * Each is isolated, for the reason everything in the platform is: one broken action must not stop the rest
     * of a rule, and a rule that half-ran is better than one that threw out of the event bus.
     */
    private fun runActions(rule: Rule, outcome: RuleOutcome) {
        for (action in rule.actions) {
            if (action is RuleAction.AddProgress) continue

            val handler = handlers[action.kind]
            if (handler == null) {
                // Not an error. The plan lists actions whose subsystems do not exist yet, and a rule that is
                // half-supported should do the half that works.
                log.debug { "No handler for '${action.kind}'; ${rule.id} skipped that action" }
                continue
            }
            runCatching { handler.run(action, outcome) }
                .onFailure { log.error(it) { "Action '${action.kind}' of ${rule.id} threw" } }
                .onSuccess { if (!it) log.debug { "Action '${action.kind}' of ${rule.id} did nothing" } }
        }
    }

    override fun progressOf(ruleId: SqId, subject: PlayerId?): RuleProgress {
        val stored = store.byRule[ruleId.value]?.get(subjectKey(subject ?: localPlayer()))
            ?: return RuleProgress()
        // Checked on read rather than on a timer, so there is nothing scheduled to go wrong. A daily rule that
        // is never read never needs resetting.
        val rule = byId[ruleId]
        if (rule?.reset == RuleReset.DAILY && stored.isFromAnotherDay()) return RuleProgress(resetAtMillis = now())
        return stored
    }

    private fun RuleProgress.isFromAnotherDay(): Boolean {
        val since = resetAtMillis ?: lastFiredAtMillis ?: return false
        return now() - since >= DAY_MILLIS
    }

    override fun resetEverySubject(ruleId: SqId) {
        if (store.byRule[ruleId.value] == null) return
        store = store.copy(byRule = store.byRule + (ruleId.value to emptyMap()))
        onStoreChanged?.invoke(store)
        log.debug { "Reset $ruleId for every subject" }
    }

    override fun reset(ruleId: SqId, subject: PlayerId?) {
        val forRule = store.byRule[ruleId.value] ?: return
        val updated = forRule - subjectKey(subject ?: localPlayer())
        store = store.copy(byRule = store.byRule + (ruleId.value to updated))
        onStoreChanged?.invoke(store)
        log.debug { "Reset $ruleId" + (subject?.let { " for $it" } ?: " for everybody") }
    }

    private fun resetAll(trigger: RuleReset) {
        val affected = byId.values.filter { it.reset == trigger }
        if (affected.isEmpty()) return
        store = store.copy(byRule = store.byRule - affected.map { it.id.value }.toSet())
        onStoreChanged?.invoke(store)
        log.debug { "Reset ${affected.size} rule(s) on $trigger" }
    }

    override fun trace(): List<RuleEvaluation> = recent.toList()

    public fun close() {
        if (!scope.isClosed) scope.cancel()
        events.unsubscribeAll(OWNER)
    }

    // -- state ---------------------------------------------------------------

    private inline fun update(ruleId: SqId, subject: PlayerId?, transform: (RuleProgress) -> RuleProgress) {
        val key = subjectKey(subject)
        val forRule = store.byRule[ruleId.value].orEmpty()
        val next = transform(forRule[key] ?: RuleProgress())
        store = store.copy(byRule = store.byRule + (ruleId.value to (forRule + (key to next))))
        onStoreChanged?.invoke(store)
    }

    /** `""` for a rule about nobody in particular, so the map has no nullable key. */
    private fun subjectKey(subject: PlayerId?): String = subject?.value ?: ""

    /**
     * Who a firing is about.
     *
     * The event's own subject where it names one — a chat message is about its sender — and otherwise the
     * local player. Getting this wrong would credit one player's progress to another, so it is one function
     * rather than a guess at each call site.
     */
    private fun subjectFor(event: SidequestEvent): PlayerId? = when (event) {
        is PlayerChatEvent -> players.resolveUsername(event.sender)?.id ?: localPlayer()
        else -> localPlayer()
    }

    private fun skip(rule: Rule, reason: String): RuleEvaluation {
        val evaluation = RuleEvaluation.Skipped(rule, reason)
        // At TRACE, not DEBUG: a skip happens on nearly every event and at DEBUG it would drown everything
        // else. The trace buffer is what makes them readable, on demand.
        log.trace { "${rule.id} skipped: $reason" }
        record(evaluation)
        return evaluation
    }

    private fun record(evaluation: RuleEvaluation) {
        recent.addFirst(evaluation)
        while (recent.size > TRACE_LIMIT) recent.removeLast()
    }

    /**
     * The view a condition gets.
     *
     * The event-shaped fields are extracted once, here, from whatever triggered the rule — which is what lets
     * `TextContains` work against a chat line, a drop and a level-up without three conditions. Extraction is a
     * `when` over the event types that have such a field, and an event that has none simply yields null.
     */
    private inner class Context(
        private val rule: Rule,
        override val event: SidequestEvent,
        override val subject: PlayerId?,
        private val own: RuleProgress,
    ) : RuleContext {

        override val game: GameContext get() = context.context

        override val nowMillis: Long get() = now()

        override val isInParty: Boolean get() = party.party.isInParty

        override val isSubjectCustomFriend: Boolean
            get() = subject?.let { players.byId(it)?.isCustomFriend } == true

        override val text: String? by lazy {
            when (event) {
                is PlayerChatEvent -> event.content
                is ChatDerivedEvent -> event.message.clean
                else -> event.describe()
            }
        }

        /**
         * No event carries a snapshot yet, and saying so is better than a `when` that looks like it does.
         *
         * Everything that names an item today comes from chat, and a chat line has a name and nothing else.
         * When an inventory-derived event arrives — an item picked up, a trade completed — it gains a branch
         * here and this stops being null.
         */
        override val item: SqItem? get() = null

        override val itemName: String? by lazy {
            when (event) {
                is RareDropEvent -> event.itemName
                else -> null
            }
        }

        override val value: Long? by lazy {
            when (event) {
                is RareDropEvent -> event.amount.toLong()
                is SkillLevelUpEvent -> event.newLevel.toLong()
                else -> null
            }
        }

        /**
         * Where the event happened, for the events that say.
         *
         * A realtime ping or waypoint carries one; nothing local does yet, because the client's own position is
         * not on any event the rules see. A rule that needs the player's position asks the game context.
         */
        override val location: SqLocation? by lazy {
            when (val payload = (event as? RealtimeMessageReceivedEvent)?.message?.payload) {
                is RealtimePayload.Ping -> payload.location
                is RealtimePayload.Waypoint -> payload.location
                is RealtimePayload.Presence -> payload.location
                else -> null
            }
        }

        override fun progressOf(ruleId: SqId?): Int =
            if (ruleId == null || ruleId == rule.id) own.progress else progressOf(ruleId, subject).progress

        override fun firingsOf(ruleId: SqId): Int =
            if (ruleId == rule.id) own.firings else this@DefaultRuleEngine.progressOf(ruleId, subject).firings

        override fun lastFiredAt(ruleId: SqId): Long? =
            if (ruleId == rule.id) own.lastFiredAtMillis
            else this@DefaultRuleEngine.progressOf(ruleId, subject).lastFiredAtMillis
    }

    private companion object {
        val OWNER = OwnerId(SqId.sidequest("rules"))
        const val TRACE_LIMIT = 200
        const val DAY_MILLIS = 24L * 60 * 60 * 1_000
    }
}

/** Builds a rule, reading as a declaration rather than as a constructor call. */
public fun rule(
    id: SqId,
    trigger: dev.th7bo.sidequest.platform.rule.RuleTrigger,
    displayName: String = "",
    block: RuleBuilder.() -> Unit = {},
): Rule = RuleBuilder(id, trigger, displayName).apply(block).build()

/** The DSL behind [rule]. */
public class RuleBuilder(
    private val id: SqId,
    private val trigger: dev.th7bo.sidequest.platform.rule.RuleTrigger,
    private var displayName: String,
) {
    public var description: String = ""
    public var tiers: List<Int> = emptyList()
    public var cooldown: kotlin.time.Duration = kotlin.time.Duration.ZERO
    public var maxFirings: Int? = null
    public var reset: RuleReset = RuleReset.NEVER
    public var isHidden: Boolean = false

    private val conditions = ArrayList<Condition>()
    private val actions = ArrayList<RuleAction>()

    public fun where(condition: Condition) {
        conditions.add(condition)
    }

    public fun then(action: RuleAction) {
        actions.add(action)
    }

    internal fun build(): Rule = Rule(
        id = id,
        displayName = displayName,
        description = description,
        trigger = trigger,
        // Several `where` calls read as "and", which is what anybody writing them means.
        condition = when (conditions.size) {
            0 -> Condition.Always
            1 -> conditions.single()
            else -> Condition.All(conditions.toList())
        },
        actions = actions.toList(),
        tiers = tiers,
        cooldown = cooldown,
        maxFirings = maxFirings,
        reset = reset,
        isHidden = isHidden,
    )
}

package dev.th7bo.sidequest.features

import dev.th7bo.sidequest.platform.chat.DropRarity
import dev.th7bo.sidequest.platform.chat.RareDropEvent
import dev.th7bo.sidequest.platform.chat.TrophyCatchEvent
import dev.th7bo.sidequest.platform.cinematic.Cinematic
import dev.th7bo.sidequest.platform.cinematic.CinematicComponent
import dev.th7bo.sidequest.platform.cinematic.CinematicPriority
import dev.th7bo.sidequest.platform.feature.Feature
import dev.th7bo.sidequest.platform.feature.FeatureCategory
import dev.th7bo.sidequest.platform.feature.FeatureContext
import dev.th7bo.sidequest.platform.feature.FeatureDescriptor
import dev.th7bo.sidequest.platform.feature.command
import dev.th7bo.sidequest.platform.id.SqId
import dev.th7bo.sidequest.platform.notification.NotificationAction
import dev.th7bo.sidequest.platform.notification.NotificationCategory
import dev.th7bo.sidequest.platform.notification.NotificationPriority
import dev.th7bo.sidequest.platform.core.drops.DropDecision
import dev.th7bo.sidequest.platform.core.drops.RareDropPolicy
import dev.th7bo.sidequest.platform.core.drops.RareDropSettings
import dev.th7bo.sidequest.platform.core.notification.notification
import dev.th7bo.sidequest.platform.storage.Repository
import dev.th7bo.sidequest.platform.storage.StorageScope

/**
 * The animation for a rare drop.
 *
 * Almost none of this feature is new machinery, and that is the point of everything built before it: the
 * cinematic director already decides whether now is a safe moment and queues what it will not interrupt, the
 * sound manager already handles cooldowns and mute groups, the chat parser already recognises the drop, and
 * the notification manager already keeps the history. What is left here is the part that is actually about
 * rare drops — deciding whether one is worth announcing, and what it should look like.
 *
 * **Nothing is drawn directly.** The cinematic is submitted, not played: if the player is in combat, in a
 * dungeon boss fight or has a screen open, the director holds it or falls back to a toast. A feature that
 * drew its own animation would be the one that covers somebody's screen mid-fight, which is exactly the
 * failure the gate exists to prevent.
 */
class RareDropAnimation(
    /** Plays Minecraft's own totem animation for an item. Supplied by the mod, since it needs the game. */
    private val totemAnimation: (itemName: String) -> Boolean = { false },
    /**
     * What a drop is worth, if anything knows.
     *
     * A hook rather than a lookup, and it currently always returns null: **there is no price source in this
     * mod yet.** A chat-derived drop carries a name and nothing else — no `SqItem`, so no `estimatedValue` —
     * and nothing fetches from the bazaar or the auction house. The parameter exists so the composition is
     * right when one arrives, and the animation simply omits a line it has no number for.
     */
    private val priceOf: (itemName: String) -> Long? = { null },
) : Feature {

    override val descriptor: FeatureDescriptor = FeatureDescriptor(
        id = SqId.sidequest("drops.animation"),
        displayName = "Rare drop animation",
        category = FeatureCategory.VISUALS,
        description = "Shows a cinematic when something rare drops",
    )

    private lateinit var context: FeatureContext
    private lateinit var storage: Repository<RareDropSettings>

    /** Read on the client thread and written from storage, so it is held rather than re-read. */
    @Volatile
    var settings: RareDropSettings = RareDropSettings()
        private set

    override fun onEnable(context: FeatureContext) {
        this.context = context

        // Global rather than per account, deliberately. An ignore list is about what a person finds
        // interesting, and somebody tired of Enchanted Hay Bale is tired of it on their alt too.
        storage = context.store(
            name = "drops",
            scope = StorageScope.Global,
            serializer = RareDropSettings.serializer(),
            default = { RareDropSettings() },
        )
        context.async {
            val stored = storage.load()
            if (stored.report.isWorthReporting) context.log.warn { "Drop settings: ${stored.report}" }
            settings = stored.value
        }

        // Registered here rather than assumed: the sound manager warns about an unknown id, and a feature
        // that played one it never declared would fill the log with its own mistake.
        context.sounds.register(
            dev.th7bo.sidequest.platform.audio.SoundDefinition(
                id = SOUND,
                resource = "minecraft:entity.player.levelup",
                group = dev.th7bo.sidequest.platform.audio.SoundGroup.EFFECTS,
            ),
        )

        context.listen(RareDropEvent::class) { drop -> onDrop(drop) }
        context.listen(TrophyCatchEvent::class) { catch -> onTrophy(catch) }

        context.command(
            name = "sqdrops",
            description = "What a rare drop does",
            usage = "[show|ignore|unignore|totem|sound|off|on] [item]",
            completions = { done ->
                when {
                    done.isEmpty() -> VERBS
                    done.first().lowercase() == "unignore" -> settings.ignoredItems.toList()
                    else -> emptyList()
                }
            },
        ) { arguments -> command(arguments) }
    }

    // -- deciding ------------------------------------------------------------

    private fun onDrop(drop: RareDropEvent) {
        val skip = whyNot(drop.itemName, drop.rarity)
        if (skip != null) {
            context.log.debug { "Not announcing ${drop.itemName}: $skip" }
            return
        }
        announce(
            item = drop.itemName,
            amount = drop.amount,
            headline = RareDropPolicy.headlineFor(drop.rarity),
            colour = RareDropPolicy.colourFor(drop.rarity),
            subtitle = drop.magicFindPercent?.let { "+${it.toInt()}% Magic Find" },
        )
    }

    /**
     * A trophy catch, graded on its own scale.
     *
     * A diamond trophy is worth as much noise as an insane rare drop and a bronze one is worth none, so the
     * tier is mapped onto the rarity ladder rather than every trophy being announced identically.
     */
    private fun onTrophy(catch: TrophyCatchEvent) {
        val asRarity = RareDropPolicy.rarityOf(catch.tier)
        val skip = whyNot(catch.fishName, asRarity)
        if (skip != null) {
            context.log.debug { "Not announcing ${catch.fishName}: $skip" }
            return
        }
        announce(
            item = catch.fishName,
            amount = 1,
            headline = "${catch.tier.displayName.uppercase()} TROPHY",
            colour = catch.tier.colour,
            subtitle = null,
        )
    }

    /** Why this drop is not worth announcing, or null. See [RareDropPolicy], where the decision lives. */
    private fun whyNot(itemName: String, rarity: DropRarity): String? =
        (RareDropPolicy.decide(settings, itemName, rarity, context.gameContext.context.island)
            as? DropDecision.Skip)?.reason

    // -- showing -------------------------------------------------------------

    private fun announce(item: String, amount: Int, headline: String, colour: Int, subtitle: String?) {
        // The toast first and unconditionally, because it is the durable record. The cinematic may be
        // refused, held or skipped, and "what did I just get" should still be answerable afterwards.
        context.notifications.notify(
            notification(
                category = NotificationCategory.PROGRESSION,
                title = headline,
                subtitle = if (amount > 1) "${amount}x $item" else item,
                priority = NotificationPriority.HIGH,
            ).copy(
                // The ignore action is on the toast rather than only in a command, because the moment
                // somebody wants to ignore an item is the moment it has just interrupted them.
                actions = listOf(
                    NotificationAction(id = "ignore", label = "Ignore $item") { ignore(item) },
                ),
            ),
        )

        if (settings.playsSound) {
            context.sounds.play(dev.th7bo.sidequest.platform.audio.SoundRequest(SOUND))
        }

        // Minecraft's own animation instead, for somebody who would rather have the familiar thing. It is
        // not a fallback — if it fails, nothing further is shown, because the toast already went out.
        if (settings.useTotemAnimation) {
            if (!totemAnimation(item)) context.log.debug { "The totem animation could not show '$item'" }
            return
        }

        context.cinematics.submit(cinematicFor(item, amount, headline, colour, subtitle))
    }

    private fun cinematicFor(
        item: String,
        amount: Int,
        headline: String,
        colour: Int,
        subtitle: String?,
    ): Cinematic {
        val components = buildList {
            add(CinematicComponent.Letterbox())
            add(CinematicComponent.Title(headline, colour = colour))
            subtitle?.let { add(CinematicComponent.Subtitle(it)) }
            // The reward, revealed part way through rather than shown from the first frame. The pause is the
            // whole effect: a drop announced instantly is a label, and one that arrives is a moment.
            add(CinematicComponent.RewardReveal(if (amount > 1) "${amount}x $item" else item, atFraction = REVEAL_AT))
            // Omitted rather than shown as zero when nothing knows the price. See `priceOf`.
            priceOf(item)?.let { add(CinematicComponent.AnimatedNumber(it * amount, suffix = " coins")) }
            if (settings.takesScreenshot) add(CinematicComponent.Screenshot)
        }

        return Cinematic(
            id = SqId.sidequest("drops.animation"),
            priority = CinematicPriority.HIGH,
            duration = settings.duration,
            components = components,
        )
    }

    // -- settings ------------------------------------------------------------

    private fun ignore(item: String) {
        update { it.copy(ignoredItems = it.ignoredItems + item) }
        context.log.info { "Ignoring '$item'" }
    }

    private fun update(transform: (RareDropSettings) -> RareDropSettings) {
        settings = transform(settings)
        val saved = settings
        context.async { storage.save(saved) }
    }

    private fun command(arguments: List<String>) {
        val notifications = context.notifications
        fun tell(text: String) = notifications.notify(
            notification(NotificationCategory.DEBUG, "Drops", subtitle = text),
        )

        when (arguments.firstOrNull()?.lowercase()) {
            null, "show" -> {
                tell(
                    "${if (settings.isEnabled) "on" else "off"} · " +
                        "from ${settings.minimumRarity} · " +
                        "${settings.ignoredItems.size} ignored · " +
                        (if (settings.useTotemAnimation) "totem" else "cinematic"),
                )
            }
            "on" -> update { it.copy(isEnabled = true) }.also { tell("On.") }
            "off" -> update { it.copy(isEnabled = false) }.also { tell("Off.") }
            "totem" -> update { it.copy(useTotemAnimation = !it.useTotemAnimation) }
                .also { tell(if (settings.useTotemAnimation) "Totem animation." else "Cinematic.") }
            "sound" -> update { it.copy(playsSound = !it.playsSound) }
                .also { tell(if (settings.playsSound) "Sound on." else "Sound off.") }
            "ignore" -> {
                val item = arguments.drop(1).joinToString(" ")
                if (item.isBlank()) tell("Which item? /sqdrops ignore <name>") else { ignore(item); tell("Ignoring '$item'.") }
            }
            "unignore" -> {
                val item = arguments.drop(1).joinToString(" ")
                update { settings -> settings.copy(ignoredItems = settings.ignoredItems.filterNot { it.equals(item, true) }.toSet()) }
                tell("No longer ignoring '$item'.")
            }
            else -> tell("One of: ${VERBS.joinToString(", ")}")
        }
    }

    private companion object {
        val VERBS = listOf("show", "ignore", "unignore", "totem", "sound", "off", "on")

        val SOUND = SqId.sidequest("drops.rare")

        /** Where the item appears, as a fraction of the run. Late enough to be a reveal, early enough to read. */
        const val REVEAL_AT = 0.5f


    }
}

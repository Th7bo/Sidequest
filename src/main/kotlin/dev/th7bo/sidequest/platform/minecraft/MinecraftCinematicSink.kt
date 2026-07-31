package dev.th7bo.sidequest.platform.minecraft

import dev.th7bo.sidequest.platform.audio.SoundManager
import dev.th7bo.sidequest.platform.audio.SoundRequest
import dev.th7bo.sidequest.platform.cinematic.Cinematic
import dev.th7bo.sidequest.platform.cinematic.CinematicComponent
import dev.th7bo.sidequest.platform.cinematic.CinematicSink
import dev.th7bo.sidequest.platform.item.SkyBlockItemRepository
import dev.th7bo.sidequest.platform.log.Logger
import dev.th7bo.sidequest.ui.components.cinematic.CinematicStageNode
import dev.th7bo.sidequest.ui.components.cinematic.StageElement
import dev.th7bo.sidequest.ui.rendering.ItemRef

/**
 * Draws a cinematic on the HUD layer, and runs its clock.
 *
 * The translation between the platform's `CinematicComponent` and the UI framework's [StageElement], which is
 * the same split as the two `Notification` types and exists for the same reason: the UI framework has no
 * Minecraft and no SkyBlock on its classpath, and a component that named an `SqItem` would put SkyBlock there.
 *
 * The clock lives here rather than in the node because it is the sink that owns playback: the node draws a
 * fraction and knows nothing about when it ends. Advanced from the render clock, so a cinematic runs the same
 * wall-clock length whatever the tick rate is doing — and stops while the game is paused, which is what a
 * player expects of something they are meant to be watching.
 */
class MinecraftCinematicSink(
    /** Supplied for the same reason as the notification sink's: taking it at construction is a cycle. */
    private val log: () -> Logger,
    /** The stage lives on the HUD layer, which does not exist until the first frame in a world. */
    private val stage: () -> CinematicStageNode?,
    /** Cinematic sounds go through the manager, so volume groups and serious mode still apply. */
    private val sounds: () -> SoundManager,
    /**
     * What SkyBlock's items are, for the ones the game has never heard of.
     *
     * Asked without waiting — see [CinematicComponent.ItemIcon] below. Defaulted to knowing nothing so the
     * sink can be built without it, in which case only vanilla-named drops get a picture.
     */
    private val items: () -> SkyBlockItemRepository = { SkyBlockItemRepository.None },
) : CinematicSink {

    private var playing: Cinematic? = null
    private var onFinished: (() -> Unit)? = null

    /** Seconds since it started, from the render clock. */
    private var elapsed = 0f

    /** Sounds already fired for the current cinematic, so a cue plays once and not every frame. */
    private val firedSounds = HashSet<String>()

    override val isPlaying: Boolean get() = playing != null

    override fun supports(kind: String): Boolean = kind in SUPPORTED

    override fun play(cinematic: Cinematic, onFinished: () -> Unit): Boolean {
        val target = stage()
        if (target == null) {
            // No HUD yet — the main menu, or the first frame of a world. Refused rather than silently dropped:
            // the director treats a refusal as "this did not play" and falls back to a notification, which is
            // the outcome the player should get.
            log().debug { "No HUD layer yet; ${cinematic.id} cannot be drawn" }
            return false
        }

        val elements = cinematic.components.mapNotNull { it.toStageElement() }
        if (elements.isEmpty() && cinematic.components.none { it is CinematicComponent.Sound }) {
            // Nothing to draw and nothing to hear. Refused, because a cinematic that occupies the gate for four
            // seconds and shows nothing is worse than no cinematic.
            log().warn { "${cinematic.id} has nothing this sink can draw" }
            return false
        }

        playing = cinematic
        this.onFinished = onFinished
        elapsed = 0f
        firedSounds.clear()
        target.elements = elements
        target.progress = 0f
        log().debug { "Drawing ${cinematic.id} for ${cinematic.duration}" }
        return true
    }

    /**
     * Advances the clock. Called once per frame from the HUD layer.
     *
     * @param deltaSeconds real time since the last frame.
     */
    fun advance(deltaSeconds: Float) {
        val cinematic = playing ?: return
        val target = stage() ?: return

        elapsed += deltaSeconds
        val total = cinematic.duration.inWholeMilliseconds / MILLIS_PER_SECOND
        val progress = if (total <= 0f) 1f else (elapsed / total).coerceIn(0f, 1f)
        target.progress = progress

        for (component in cinematic.components) {
            if (component !is CinematicComponent.Sound) continue
            if (progress < component.atFraction) continue
            // Keyed on the sound and its cue point, so two cues of the same sound both fire and one cue does
            // not fire twice.
            if (!firedSounds.add(component.soundId.value + "@" + component.atFraction)) continue
            sounds().play(SoundRequest(component.soundId, volume = component.volume))
        }

        if (progress >= 1f) finish()
    }

    override fun skip() {
        if (playing == null) return
        log().debug { "Skipped ${playing?.id}" }
        finish()
    }

    private fun finish() {
        val callback = onFinished
        playing = null
        onFinished = null
        elapsed = 0f
        firedSounds.clear()
        stage()?.let {
            it.elements = emptyList()
            it.progress = 0f
        }
        // Last, so a callback that submits another cinematic finds this one already over rather than finding
        // the sink still claiming to be busy.
        callback?.invoke()
    }

    /**
     * Turns a platform component into something the stage can draw, or null.
     *
     * Null for the ones nothing can draw yet — particles, shaders, voice clips, screenshots, reactions, and the
     * two that need Minecraft's own model rendering. The director already logs those; returning null here is
     * what makes a cinematic naming them degrade instead of failing.
     */
    /**
     * A picture of what dropped, by the best route available.
     *
     * Three answers, in order. A SkyBlock item the repository already knows about is drawn as the real thing —
     * model, shimmer, and for the custom-skinned heads that most of SkyBlock is, its actual skin. Failing that
     * a vanilla name resolves to a flat texture, which covers the enchanted books and raw materials. Failing
     * both, nothing: a plausible wrong item is worse than none, because nobody can tell it is wrong.
     *
     * **Only what is already resident.** This runs while deciding what to draw, and a cinematic cannot wait on
     * a network round trip — so the drop is prefetched when it is announced and this reads the result. A drop
     * whose lookup has not landed yet falls through to the vanilla texture, which is the same outcome as
     * before the repository existed.
     */
    private fun iconFor(itemName: String, glow: Int?): StageElement? {
        items().resident(itemName)?.let { known ->
            return StageElement.Item(ItemRef(known.minecraftId, known.skullTexture), glow = glow)
        }
        return ItemTextures.textureFor(itemName)?.let { StageElement.Image(it, glow = glow) }
    }

    private fun CinematicComponent.toStageElement(): StageElement? = when (this) {
        is CinematicComponent.Letterbox -> StageElement.Letterbox(heightFraction)
        is CinematicComponent.Background -> StageElement.Backdrop(colour, opacity)
        is CinematicComponent.Title -> StageElement.Title(text, colour ?: DEFAULT_TITLE_COLOUR)
        is CinematicComponent.Subtitle -> StageElement.Subtitle(text)
        is CinematicComponent.AnimatedNumber -> StageElement.Number(value, prefix, suffix)
        is CinematicComponent.ProgressBar -> StageElement.Progress(fraction, label)
        is CinematicComponent.RewardReveal -> StageElement.Reveal(label, atFraction)
        is CinematicComponent.ItemIcon -> iconFor(itemName, glowColour)
        // Handled by the clock rather than drawn.
        is CinematicComponent.Sound -> null
        else -> null
    }

    private companion object {
        /**
         * What this sink can draw.
         *
         * `item` and `player_head` are absent and that is a statement, not an oversight. Both take something
         * the *player already has* — a stack snapshot, another player's identity — where `item_icon` takes a
         * name, and only the last of those is what a drop read from chat can supply.
         */
        val SUPPORTED = setOf(
            "letterbox", "background", "title", "subtitle", "number", "progress", "reward", "sound",
            "item_icon",
        )

        const val MILLIS_PER_SECOND = 1000f

        /** White. A title with no colour of its own is not a title with an opinion about colour. */
        const val DEFAULT_TITLE_COLOUR = 0xFFFFFF
    }
}

package dev.th7bo.sidequest.platform.ping

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a ping means.
 *
 * The protocol carries the style as a free-form string precisely so the client owns this vocabulary — the
 * server has no business knowing what "danger" looks like, and a style it did not recognise would otherwise
 * be a style nobody could add.
 *
 * Every entry is a *meaning*, not a colour. `DANGER` is red because danger is red, and somebody recolouring
 * it in the settings is changing how danger is drawn rather than inventing a ninth kind of ping. That is why
 * the colour lives here next to the meaning and not in a theme.
 */
public enum class PingStyle(
    /** What the sender chose, on somebody else's screen. Kept short: it is drawn in the world. */
    public val displayName: String,
    /** ARGB. */
    public val colour: Int,
    /** How long it stays up. */
    public val lifetime: Duration,
    /**
     * Whether it is worth interrupting somebody for.
     *
     * Only the two that mean "something is wrong". A "go here" that made a noise every time would be muted
     * within a session, taking the ones that matter with it.
     */
    public val isUrgent: Boolean = false,
) {
    /** The default. Somewhere to move to. */
    GO_HERE("Go here", colour = 0xFF4ADE80.toInt(), lifetime = 20.seconds),

    /** Attention, without a destination. */
    LOOK_HERE("Look here", colour = 0xFF60A5FA.toInt(), lifetime = 15.seconds),

    NPC_HERE("NPC", colour = 0xFFFBBF24.toInt(), lifetime = 30.seconds),

    ITEM_HERE("Item", colour = 0xFFA78BFA.toInt(), lifetime = 30.seconds),

    /** Something that will kill you. */
    DANGER("Danger", colour = 0xFFF87171.toInt(), lifetime = 25.seconds, isUrgent = true),

    NEED_HELP("Need help", colour = 0xFFFB923C.toInt(), lifetime = 45.seconds, isUrgent = true),

    /**
     * Come to me.
     *
     * Long, because it is the one ping whose whole purpose is that somebody walks across an island to reach
     * it — and one that expires on the way is worse than none.
     */
    COME_TO_ME("Come to me", colour = 0xFF38BDF8.toInt(), lifetime = 60.seconds),

    /** Whatever the sender typed. */
    CUSTOM("Ping", colour = 0xFFE5E7EB.toInt(), lifetime = 30.seconds),
    ;

    /** The string that crosses the wire. Lowercase and stable — renaming an entry is a protocol change. */
    public val wireName: String get() = name.lowercase()

    public companion object {
        /**
         * The style [wireName] refers to, or [CUSTOM].
         *
         * Never null and never throws. This reads a string another client chose, possibly a newer one that
         * knows styles this build does not, and the right answer to "some ping I have not heard of" is to
         * draw a generic ping rather than to drop it. Somebody was pointing at something either way.
         */
        public fun ofWire(wireName: String): PingStyle =
            entries.firstOrNull { it.wireName.equals(wireName, ignoreCase = true) } ?: CUSTOM
    }
}

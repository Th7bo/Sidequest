package dev.th7bo.sidequest.platform.core.settings

/**
 * Holds one of the game's own settings at a value while some condition lasts, and gives it back afterwards.
 *
 * Turning view bobbing off on the Garden is a one-line idea with a four-line failure: set it, forget to
 * restore it, and somebody finds their bobbing permanently off with no memory of having changed it. Worse is
 * restoring the wrong value — overriding a setting, watching the player change it themselves in the options
 * menu, and then "restoring" over the choice they just made.
 *
 * So this owns the whole state machine and the caller owns nothing:
 *
 * - It remembers the value only while it is actually overriding.
 * - It restores only if the value on the way out is still the one it set. Anything else means somebody moved
 *   it and their choice wins.
 * - It notices the player taking over *during* the override and stands down permanently rather than fighting
 *   them every tick.
 *
 * Deliberately not a Minecraft type. It is told the current value and answers with the value to write, which
 * is what makes every one of those cases testable without a game.
 */
public class ContextualOverride<T : Any> {

    /** What the player had before this started overriding. Null when it is not overriding. */
    private var remembered: T? = null

    /** What this last wrote, so it can tell its own value from one somebody else set. */
    private var applied: T? = null

    /**
     * Set once the player has overruled this, and cleared only when the condition lapses.
     *
     * Without it, standing down lasts exactly one call: the next one finds nothing remembered, decides the
     * setting needs overriding, and takes it again — so the player toggles it back and the mod toggles it
     * away, once a tick, forever. Which is what "stands down permanently" has to mean to be worth anything.
     */
    private var stoodDown: Boolean = false

    /** True while the override is in force. */
    public val isOverriding: Boolean get() = remembered != null

    /**
     * What to write, or null to leave the setting alone.
     *
     * @param current what the setting reads right now.
     * @param desired what it should read, or null when the condition no longer applies.
     */
    public fun apply(current: T, desired: T?): T? {
        val held = remembered

        if (desired == null) {
            // The condition lapsed, which is also what clears a stand-down: leaving the Garden and coming
            // back is a fresh stint, and the override is welcome to try again.
            stoodDown = false
            if (held == null) return null
            remembered = null
            val ours = applied
            applied = null
            // Restored only if what is there is still what this put there. If the player changed it while
            // the override was on, that is their setting now and handing back an older value would undo a
            // deliberate choice.
            return if (current == ours) held else null
        }

        if (stoodDown) return null

        if (held == null) {
            // Nothing to do, and nothing to remember: it already reads the way this wants it. Remembering
            // here would mean "restoring" it later to the value it already has, which is harmless until the
            // player changes it in between and then finds it changed back.
            if (current == desired) return null
            remembered = current
            applied = desired
            return desired
        }

        if (current != applied) {
            // The player moved it while this was overriding. Stand down for the rest of the stint rather
            // than writing over them again next tick, which is the difference between a setting and a fight.
            remembered = null
            applied = null
            stoodDown = true
            return null
        }

        return null
    }

    /**
     * Gives the setting back immediately, whatever the condition says.
     *
     * For a feature being disabled or a game shutting down. Returns the value to write, or null when there is
     * nothing to give back.
     */
    public fun release(current: T): T? = apply(current, desired = null)
}

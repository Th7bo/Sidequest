package dev.th7bo.sidequest.platform.minecraft

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.gui.screens.Screen

/**
 * Tracks whether a screen is open.
 *
 * Minecraft 26.x stopped exposing its current screen for reading, so this follows the
 * open and close events instead of asking. Worth the indirection: "is the player looking
 * at a menu" is the difference between a cinematic that plays over an inventory and one
 * that waits, and every future feature that decides whether it is safe to interrupt
 * reads it.
 *
 * State is a single flag rather than the screen itself. Holding a reference to a screen
 * that has been closed is a leak, and no caller above the adapter is allowed a Minecraft
 * type anyway.
 */
internal object ScreenState {

    @Volatile
    var isOpen: Boolean = false
        private set

    /** The screen currently open, weakly identified. Only ever compared, never used. */
    private var current: Screen? = null

    fun install() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            current = screen
            isOpen = true

            // Fires when this screen goes away. A close with no replacement leaves
            // nothing open; a transition to another screen re-runs AFTER_INIT first, so
            // the guard stops a stale removal from clearing the new screen's state.
            ScreenEvents.remove(screen).register { removed ->
                if (current === removed) {
                    current = null
                    isOpen = false
                }
            }
        }
    }
}

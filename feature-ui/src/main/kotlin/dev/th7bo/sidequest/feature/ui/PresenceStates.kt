package dev.th7bo.sidequest.feature.ui

import dev.th7bo.sidequest.platform.player.PlayerDirectory
import dev.th7bo.sidequest.platform.player.PlayerId
import dev.th7bo.sidequest.platform.player.PlayerPresence
import dev.th7bo.sidequest.ui.state.MutableUiState
import dev.th7bo.sidequest.ui.state.UiState
import dev.th7bo.sidequest.ui.state.mutableStateOf

/**
 * Who is online, as something a screen can watch.
 *
 * The player directory is a plain map with an event bus beside it, and the UI framework only redraws what it
 * is told has changed. Between the two sat a real defect: the friend hub read presence when it was built and
 * kept saying "Online" for as long as it stayed open. That was documented rather than fixed twice, on the
 * grounds that a state which recomputed on read but never notified would be the same staleness with more
 * machinery in the way — which is true, and is exactly what this avoids by being a *source* state that
 * something writes to.
 *
 * **This module is the only place it could live.** The reactive graph is in `ui-api` and the directory is in
 * `platform-api`, and nothing else depends on both — which is what `:feature-ui` exists for.
 *
 * Everything here is UI-thread only, like the rest of the graph. The mod marshals the directory's events
 * across; see [onChanged].
 */
public class PresenceStates(
    /** Where a presence is read from the first time somebody asks for one. */
    private val players: PlayerDirectory,
) {

    private val states = HashMap<PlayerId, MutableUiState<PlayerPresence>>()

    /**
     * A watchable presence for one player.
     *
     * Created on first ask and kept, because a screen that built a new state per redraw would observe a
     * different object each time and never see a change. Seeded from the directory so the first read is
     * already right rather than briefly claiming everybody is offline.
     */
    public fun of(id: PlayerId): UiState<PlayerPresence> = state(id)

    private fun state(id: PlayerId): MutableUiState<PlayerPresence> = states.getOrPut(id) {
        mutableStateOf(
            players.byId(id)?.presence ?: PlayerPresence.Unknown,
            debugName = "presence.${id.value.take(SHORT_ID)}",
        )
    }

    /**
     * Records that somebody's presence changed.
     *
     * Called on the UI thread by whoever is listening to the directory. Writing a value equal to the
     * current one is free — the state's own equality check makes it a no-op — so the caller does not have
     * to filter, and presence arrives often enough that it would otherwise be tempted to.
     *
     * Deliberately does *not* create a state for somebody nobody is watching. Presence lands for every
     * player the client sees, and holding one of these per stranger would be a map that only grows.
     */
    public fun onChanged(id: PlayerId, presence: PlayerPresence) {
        states[id]?.value = presence
    }

    /** How many players are being watched. For diagnostics. */
    public val watched: Int get() = states.size

    /** Drops everything. For a client returning to the title screen, where none of it is worth keeping. */
    public fun clear() {
        states.clear()
    }

    private companion object {
        /** Enough of a UUID to tell two apart in a cycle report. */
        const val SHORT_ID = 8
    }
}

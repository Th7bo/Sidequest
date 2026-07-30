package dev.th7bo.sidequest.platform.parser

import dev.th7bo.sidequest.platform.event.SidequestEvent

/**
 * The boards changed.
 *
 * Both carry what *changed* rather than only the new state, because that is the shape almost
 * every consumer wants. "A line appeared saying the dungeon started" is a different question
 * from "the board currently contains that line", and the second one is true for as long as
 * the line stays up — a listener written against it fires on every poll.
 *
 * Posted once per real change. The boards are polled every tick and are almost always
 * identical to the tick before, so an unchanged poll produces nothing at all.
 */
public sealed class BoardEvent : SidequestEvent()

/**
 * The sidebar changed.
 *
 * [added] and [removed] are compared on the *cleaned* lines. Formatting on the scoreboard
 * flickers — Hypixel animates colours on several lines — and a diff over the raw text would
 * report a change several times a second on a board that says the same thing.
 */
public class ScoreboardChangedEvent(
    public val snapshot: ScoreboardSnapshot,
    public val added: List<String>,
    public val removed: List<String>,
) : BoardEvent() {
    override fun describe(): String = "+${added.size} -${removed.size} scoreboard line(s)"
}

/**
 * The tab list changed.
 *
 * Widget-level rather than line-level: a widget appearing or disappearing is what says the
 * player started doing something, and the values inside a widget change constantly.
 * [changedWidgets] covers the rest — a widget whose lines moved without it coming or going.
 */
public class TabListChangedEvent(
    public val snapshot: TabListSnapshot,
    public val addedWidgets: Set<TabWidget>,
    public val removedWidgets: Set<TabWidget>,
    public val changedWidgets: Set<TabWidget>,
) : BoardEvent() {
    override fun describe(): String =
        "+${addedWidgets.size} -${removedWidgets.size} ~${changedWidgets.size} widget(s)"
}

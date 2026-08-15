package dev.th7bo.sidequest.platform.core.presence

import dev.th7bo.sidequest.platform.party.PartyMember
import dev.th7bo.sidequest.platform.party.PartyState
import dev.th7bo.sidequest.platform.skyblock.Activity
import dev.th7bo.sidequest.platform.skyblock.ActivityReading
import dev.th7bo.sidequest.platform.skyblock.ContextConfidence
import dev.th7bo.sidequest.platform.skyblock.GameContext
import dev.th7bo.sidequest.platform.skyblock.Island
import dev.th7bo.sidequest.platform.skyblock.ProfileType
import dev.th7bo.sidequest.platform.skyblock.SkyBlockProfile
import dev.th7bo.sidequest.platform.skyblock.SubLocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What ends up on a public profile.
 *
 * Half of this file is about what does *not* come out. Every other outbound path in the mod goes to a friend
 * group that opted into a shared backend; this one goes to everybody who can see a Discord profile, and the
 * mistake it is possible to make here — leaking something through a field nobody was thinking about — is
 * both invisible in play and impossible to take back.
 *
 * The specific trap the image tests exist for: gating only the *text* on the island setting. The picture is
 * the island too, and a presence that says nothing and shows a photograph of the Dwarven Mines has disclosed
 * the island.
 */
class PresenceComposerTest {

    // -- when there should be no presence at all ------------------------------

    @Test
    fun `off Hypixel there is no presence`() {
        assertNull(PresenceComposer.compose(GameContext(), PartyState(), PresenceDisclosure()))
    }

    @Test
    fun `on Hypixel but not in SkyBlock says so without claiming more`() {
        val presence = PresenceComposer.compose(
            GameContext(isOnHypixel = true),
            PartyState(),
            PresenceDisclosure(),
        )
        assertNotNull(presence)
        assertEquals("In a Hypixel lobby", presence!!.details)
        assertNull(presence.state)
        assertEquals(PresenceComposer.LOGO, presence.largeImage)
    }

    // -- what it says ---------------------------------------------------------

    @Test
    fun `an island and an activity read as two lines`() {
        val presence = PresenceComposer.compose(mining(), PartyState(), PresenceDisclosure())!!

        assertEquals("Mining", presence.details)
        assertEquals("Dwarven Mines · Royal Mines", presence.state)
        assertEquals(PresenceComposer.islandAsset(Island.DWARVEN_MINES), presence.largeImage)
        assertEquals("Dwarven Mines", presence.largeText)
        assertEquals(PresenceComposer.activityAsset(Activity.MINING), presence.smallImage)
        assertEquals("Mining", presence.smallText)
    }

    /** The floor is the point of being in a dungeon, and `F7` is not something a profile reader parses. */
    @Test
    fun `a dungeon floor is spelled out`() {
        assertEquals("Entrance", PresenceComposer.floorLabel("E"))
        assertEquals("Floor 7", PresenceComposer.floorLabel("F7"))
        assertEquals("Master 3", PresenceComposer.floorLabel("M3"))
    }

    /** Hypixel adding a floor shape should read oddly rather than vanish. */
    @Test
    fun `an unrecognised floor tag passes through`() {
        assertEquals("Q9", PresenceComposer.floorLabel("Q9"))
        assertEquals("F", PresenceComposer.floorLabel("F"))
    }

    @Test
    fun `a dungeon says the floor rather than the island twice`() {
        val context = GameContext(
            isOnHypixel = true,
            isInSkyBlock = true,
            island = Island.CATACOMBS,
            dungeonFloor = "F7",
            activity = ActivityReading(Activity.DUNGEONS, ContextConfidence.CONFIRMED, "floor"),
        )
        val presence = PresenceComposer.compose(context, PartyState(), PresenceDisclosure())!!

        assertEquals("Dungeons · Floor 7", presence.details)
        assertEquals("Catacombs", presence.state)
    }

    /**
     * The island whose name is already in the first line.
     *
     * "Kuudra · Tier 4" over "Kuudra" is a second line spent repeating the first. Without a party there is
     * nothing else to say, so there should be no second line at all.
     */
    @Test
    fun `an island already named in the first line is not repeated`() {
        val context = GameContext(
            isOnHypixel = true,
            isInSkyBlock = true,
            island = Island.KUUDRA_ARENA,
            kuudraTier = 4,
        )
        val presence = PresenceComposer.compose(context, PartyState(), PresenceDisclosure())!!

        assertEquals("Kuudra · Tier 4", presence.details)
        assertNull(presence.state)
    }

    @Test
    fun `a party is counted`() {
        val presence = PresenceComposer.compose(mining(), party(4), PresenceDisclosure())!!
        assertEquals("Dwarven Mines · Royal Mines · Party of 4", presence.state)
    }

    /** A party of one is somebody alone. Hypixel disbands them, and announcing one would be odd. */
    @Test
    fun `a party of one is not a party`() {
        val presence = PresenceComposer.compose(mining(), party(1), PresenceDisclosure())!!
        assertFalse(presence.state!!.contains("Party"))
    }

    @Test
    fun `the elapsed clock is the session start in seconds`() {
        val presence = PresenceComposer.compose(mining(), PartyState(), PresenceDisclosure(), 1_700_000_000L)!!
        assertEquals(1_700_000_000L, presence.startedAtEpochSeconds)
    }

    // -- what it refuses to say ----------------------------------------------

    /**
     * The one that motivated the shape of [PresenceComposer.compose].
     *
     * Delete the `disclosure.showIsland` guard on `largeImage` and every other assertion in this file still
     * passes — the text is clean and the picture names the island anyway.
     */
    @Test
    fun `a hidden island does not leak through its picture`() {
        val presence = PresenceComposer.compose(
            mining(),
            PartyState(),
            PresenceDisclosure(showIsland = false),
        )!!

        assertEquals(PresenceComposer.LOGO, presence.largeImage)
        assertFalse(presence.largeText!!.contains("Dwarven"))
        assertNull(presence.state)
    }

    /** Same trap, the other image: the activity is a picture as well as a word. */
    @Test
    fun `a hidden activity does not leak through its picture`() {
        val presence = PresenceComposer.compose(
            mining(),
            PartyState(),
            PresenceDisclosure(showActivity = false),
        )!!

        assertEquals("SkyBlock", presence.details)
        assertNull(presence.smallImage)
        assertNull(presence.smallText)
    }

    /** The dungeon floor is the sharpest statement of activity there is, so the activity switch owns it. */
    @Test
    fun `a hidden activity hides the dungeon floor and the Kuudra tier`() {
        val dungeon = GameContext(
            isOnHypixel = true,
            isInSkyBlock = true,
            island = Island.CATACOMBS,
            dungeonFloor = "M5",
        )
        val kuudra = GameContext(
            isOnHypixel = true,
            isInSkyBlock = true,
            island = Island.KUUDRA_ARENA,
            kuudraTier = 5,
        )
        val quiet = PresenceDisclosure(showActivity = false)

        assertEquals("SkyBlock", PresenceComposer.compose(dungeon, PartyState(), quiet)!!.details)
        assertEquals("SkyBlock", PresenceComposer.compose(kuudra, PartyState(), quiet)!!.details)
    }

    @Test
    fun `the profile is off unless asked for`() {
        val withProfile = mining().copy(
            profile = SkyBlockProfile("Lemon"),
            profileType = ProfileType.IRONMAN,
        )

        val hidden = PresenceComposer.compose(withProfile, PartyState(), PresenceDisclosure())!!
        assertFalse(hidden.largeText!!.contains("Lemon"))

        val shown = PresenceComposer.compose(
            withProfile,
            PartyState(),
            PresenceDisclosure(showProfile = true),
        )!!
        assertEquals("Dwarven Mines · Lemon (Ironman)", shown.largeText)
    }

    /**
     * Nobody else's name, from any switch, in any field.
     *
     * Stated as a sweep over every combination rather than as one case, because the field a name would
     * appear in is the one nobody thought to check.
     */
    @Test
    fun `no combination of settings names another player`() {
        val friend = "Steve"
        val party = PartyState(
            members = listOf(PartyMember(name = friend), PartyMember(name = "Alex")),
            leader = friend,
        )

        for (island in listOf(true, false)) {
            for (activity in listOf(true, false)) {
                for (profile in listOf(true, false)) {
                    for (partyShown in listOf(true, false)) {
                        val presence = PresenceComposer.compose(
                            mining(),
                            party,
                            PresenceDisclosure(island, activity, profile, partyShown, showElapsed = true),
                        )!!
                        val everything = listOfNotNull(
                            presence.details,
                            presence.state,
                            presence.largeText,
                            presence.smallText,
                            presence.largeImage,
                            presence.smallImage,
                        ).joinToString(" ")
                        assertFalse(everything.contains(friend), "leaked a party member's name: $everything")
                        assertFalse(everything.contains("Alex"), "leaked a party member's name: $everything")
                    }
                }
            }
        }
    }

    /** Everything off still leaves something Discord will accept, and nothing it should not. */
    @Test
    fun `the minimal disclosure says only that you are playing`() {
        val presence = PresenceComposer.compose(mining(), party(3), PresenceDisclosure.Minimal)!!

        assertEquals("SkyBlock", presence.details)
        assertNull(presence.state)
        assertNull(presence.startedAtEpochSeconds)
        assertEquals(PresenceComposer.LOGO, presence.largeImage)
        assertNull(presence.smallImage)
    }

    /**
     * A guessed activity is not published.
     *
     * Detection is heuristics over widgets, and a guess is fine to draw on a HUD that redraws next frame. A
     * profile keeps whatever it was last told, so "Fishing" can sit over somebody's name for an hour.
     */
    @Test
    fun `an unreliable activity is not claimed`() {
        val guessing = mining().copy(
            activity = ActivityReading(Activity.FISHING, ContextConfidence.GUESSED, "a rod, maybe"),
        )
        val presence = PresenceComposer.compose(guessing, PartyState(), PresenceDisclosure())!!

        assertEquals("SkyBlock", presence.details)
        assertNull(presence.smallImage)
    }

    // -- what Discord will accept --------------------------------------------

    /** Discord rejects an over-long line outright, which loses the whole update rather than the tail. */
    @Test
    fun `a very long area name is truncated rather than sent`() {
        val long = mining().copy(subLocation = SubLocation("A".repeat(500)))
        val state = PresenceComposer.compose(long, PartyState(), PresenceDisclosure())!!.state!!
        assertTrue(state.length <= 128, "was ${state.length}")
    }

    /** Discord refuses a single-character line, which would take the whole presence down with it. */
    @Test
    fun `a one character line becomes no line`() {
        val terse = GameContext(isOnHypixel = true, isInSkyBlock = true, subLocation = SubLocation("X"))
        assertNull(PresenceComposer.compose(terse, PartyState(), PresenceDisclosure())!!.state)
    }

    @Test
    fun `resource pack glyphs do not escape into Discord text`() {
        val supplementaryGlyph = String(Character.toChars(0xF0000))
        val garden = GameContext(
            isOnHypixel = true,
            isInSkyBlock = true,
            island = Island.GARDEN,
            subLocation = SubLocation("\uE123   Plot 5 $supplementaryGlyph"),
        )

        assertEquals(
            "Garden · Plot 5",
            PresenceComposer.compose(garden, PartyState(), PresenceDisclosure())!!.state,
        )
    }

    @Test
    fun `an icon-only area does not leave a dangling separator`() {
        val garden = GameContext(
            isOnHypixel = true,
            isInSkyBlock = true,
            island = Island.GARDEN,
            subLocation = SubLocation("\uE123"),
        )

        assertEquals("Garden", PresenceComposer.compose(garden, PartyState(), PresenceDisclosure())!!.state)
    }

    /** Asset keys come from the stable ids, so renaming an enum constant cannot silently change a picture. */
    @Test
    fun `asset keys are built from the stable identifiers`() {
        assertEquals("island_mining_3", PresenceComposer.islandAsset(Island.DWARVEN_MINES))
        assertEquals("island_private_island", PresenceComposer.islandAsset(Island.PRIVATE_ISLAND))
        assertEquals("activity_mining", PresenceComposer.activityAsset(Activity.MINING))
    }

    // -- fixtures -------------------------------------------------------------

    private fun mining() = GameContext(
        isOnHypixel = true,
        isInSkyBlock = true,
        island = Island.DWARVEN_MINES,
        subLocation = SubLocation("Royal Mines"),
        activity = ActivityReading(Activity.MINING, ContextConfidence.CONFIRMED, "mining widget"),
        confidence = ContextConfidence.CONFIRMED,
    )

    private fun party(size: Int) = PartyState(members = (1..size).map { PartyMember(name = "Player$it") })
}

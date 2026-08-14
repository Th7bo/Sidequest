package dev.th7bo.sidequest.platform.core.presence

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The one thing [DiscordIpcTest] cannot prove: that a real Discord understands us.
 *
 * Everything else in this package is checked against a Discord made of bytes, which is the only way to have
 * tests that pass on a machine with nothing running. But a scripted pipe agrees with any self-consistent
 * protocol — it was written from the same understanding as the code — so a wrong frame layout would sail
 * through the whole suite and fail on every real machine.
 *
 * Skipped unless `SIDEQUEST_DISCORD_LIVE` is set, because it needs a Discord that this build server does
 * not have. Run it by hand after touching anything in [DiscordFrames] or [DiscordSockets]:
 *
 * ```
 * SIDEQUEST_DISCORD_LIVE=1 ./gradlew :platform-core:test --tests '*DiscordLiveTest*'
 * ```
 *
 * **It deliberately hands over an application id that cannot exist.** A rejection is a stronger result than
 * an acceptance here: Discord can only answer "invalid client id" if it received a well-formed frame,
 * decoded the little-endian length, and parsed the JSON inside — so the reply proves the wire format while
 * changing nothing about the user's actual presence.
 */
class DiscordLiveTest {

    @Test
    fun `a real Discord decodes our handshake and answers`() {
        assumeTrue(System.getenv(LIVE) != null, "set $LIVE to run against a running Discord")

        val pipe = try {
            DiscordSockets.open()
        } catch (e: DiscordIpcException) {
            assumeTrue(false, "no Discord is running: ${e.message}")
            return
        }

        pipe.use {
            DiscordFrames.write(it.output, DiscordOpcode.HANDSHAKE, """{"v":1,"client_id":"$NOT_AN_APPLICATION"}""")
            val frame = DiscordFrames.read(it.input)

            // Any parseable answer at all is the result being checked. Discord hangs up without a reply on a
            // frame it cannot decode, so reaching this line is the proof.
            assertNotNull(frame.payload)
            assertTrue(
                frame.payload.contains("READY") || frame.payload.contains("ERROR"),
                "Discord answered something unexpected: ${frame.payload}",
            )
        }
    }

    /**
     * The whole round trip, against a real Discord and a real application.
     *
     * The one thing no scripted pipe can establish: that Discord *accepts the activity payload*, rather than
     * merely that it can be encoded. A wrong field name, a timestamp in the wrong unit or an asset key in
     * the wrong shape all serialise perfectly and are all rejected or silently ignored by Discord.
     *
     * Needs the application id in the environment rather than a constant here, so this file cannot drift out
     * of step with the one in the settings:
     *
     * ```
     * SIDEQUEST_DISCORD_LIVE=1 SIDEQUEST_DISCORD_APP=<id> \
     *   ./gradlew :platform-core:test --tests '*DiscordLiveTest*' --rerun-tasks
     * ```
     *
     * **It briefly sets a real presence on the running account's profile**, then clears it — which is also
     * the point, since somebody watching the profile is the final check that the card looks right.
     */
    @Test
    fun `a real Discord accepts a real activity`() {
        assumeTrue(System.getenv(LIVE) != null, "set $LIVE to run against a running Discord")
        val application = System.getenv(APPLICATION)
        assumeTrue(!application.isNullOrBlank(), "set $APPLICATION to the Discord application id")

        val client = DiscordIpcClient(application!!, DiscordSockets::open)
        try {
            client.connect()
        } catch (e: DiscordIpcException) {
            assumeTrue(false, "could not connect: ${e.message}")
            return
        }

        client.use {
            it.setActivity(
                RichPresence(
                    details = "Mining",
                    state = "Dwarven Mines · Royal Mines",
                    startedAtEpochSeconds = System.currentTimeMillis() / 1000,
                    largeImage = PresenceComposer.islandAsset(dev.th7bo.sidequest.platform.skyblock.Island.DWARVEN_MINES),
                    largeText = "Dwarven Mines",
                    smallImage = PresenceComposer.activityAsset(dev.th7bo.sidequest.platform.skyblock.Activity.MINING),
                    smallText = "Mining",
                ),
            )

            val response = it.lastResponse
            assertNotNull(response, "Discord acknowledged nothing")
            // An accepted SET_ACTIVITY echoes the activity back. A rejected one carries an ERROR event, and
            // that is the failure this whole test exists to catch.
            assertFalse(response!!.contains("\"evt\":\"ERROR\""), "Discord rejected the activity: $response")

            // arRPC acknowledges before Vesktop has resolved the application and asset keys. Clearing in
            // that window races the older update: the clear lands first, then the completed image lookup
            // resurrects this test card. Let the renderer settle before taking it down.
            Thread.sleep(RENDERER_SETTLE_MILLIS)
            it.setActivity(null)
        }
    }

    private companion object {
        const val LIVE = "SIDEQUEST_DISCORD_LIVE"
        const val APPLICATION = "SIDEQUEST_DISCORD_APP"
        const val RENDERER_SETTLE_MILLIS = 5_000L

        /** All digits, so it is shaped like a snowflake, and far too small to be a real one. */
        const val NOT_AN_APPLICATION = "1"
    }
}

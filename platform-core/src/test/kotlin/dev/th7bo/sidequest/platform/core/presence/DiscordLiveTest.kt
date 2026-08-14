package dev.th7bo.sidequest.platform.core.presence

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

    private companion object {
        const val LIVE = "SIDEQUEST_DISCORD_LIVE"

        /** All digits, so it is shaped like a snowflake, and far too small to be a real one. */
        const val NOT_AN_APPLICATION = "1"
    }
}

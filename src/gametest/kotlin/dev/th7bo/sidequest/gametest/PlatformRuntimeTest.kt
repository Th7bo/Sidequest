package dev.th7bo.sidequest.gametest

import dev.th7bo.sidequest.Sidequest
import dev.th7bo.sidequest.platform.id.SqId
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Verifies the platform against a real client.
 *
 * The pure-JVM tests cover the event bus, the scheduler and the feature registry against
 * fakes, which is where the logic lives. What they cannot cover is the half this test
 * exists for: that the Minecraft adapter is actually wired to the game. A tick event that
 * fires in a test and not in the client is worth exactly nothing, and that gap is
 * invisible to every fake.
 */
class PlatformRuntimeTest : FabricClientGameTest {

    override fun runTest(context: ClientGameTestContext) {
        context.waitTicks(SETTLE_TICKS)

        val platform = Sidequest.platform
        val diagnostics = Sidequest.sessionDiagnostics

        // 1. The platform started and the feature is running.
        onClient(context) {
            val handle = platform.features[SqId.sidequest("dev.session_diagnostics")]
            checkNotNull(handle) { "The diagnostics feature was never registered" }
            check(handle.isEnabled) { "It did not start: ${handle.refusal}" }
        }

        // A world, so there is a state with no screen open. On the title screen — where a
        // test without one runs — a screen is open by definition, which is the answer the
        // first version of this test mistook for a bug in the tracker.
        context.worldBuilder().create().use {
            runInWorld(context, platform, diagnostics)
        }

        context.waitTicks(SETTLE_TICKS)
    }

    private fun runInWorld(
        context: ClientGameTestContext,
        platform: dev.th7bo.sidequest.platform.minecraft.SidequestPlatform,
        diagnostics: dev.th7bo.sidequest.features.SessionDiagnostics,
    ) {
        context.waitTicks(SETTLE_TICKS)

        // Joining the world is a lifecycle callback, so this proves the adapter's
        // connection hooks reach the bus and not only its tick hook.
        onClient(context) {
            check(diagnostics.joins > 0) { "No join event arrived when the world loaded" }
        }

        // 2. Client ticks reach the bus. This is the assertion that proves the adapter is
        //    connected at all — everything else in the platform is downstream of it.
        val before = diagnostics.ticks
        context.waitTicks(TICK_SAMPLE)
        onClient(context) {
            val elapsed = diagnostics.ticks - before
            check(elapsed >= TICK_SAMPLE / 2) {
                "Only $elapsed tick event(s) arrived in $TICK_SAMPLE ticks — the adapter is not wired"
            }
        }

        // 3. The feature's own repeating job runs on the scheduler's clock, not on ticks.
        onClient(context) {
            check(diagnostics.uptimeSeconds > 0) {
                "The repeating job never ran; the scheduler is not reaching the client thread"
            }
        }

        // 4. Its command registered, and is owned by it rather than floating loose.
        onClient(context) {
            val command = platform.commands["sqdiag"]
            checkNotNull(command) { "The feature's command was not registered" }
            check(command.owner.value == SqId.sidequest("dev.session_diagnostics")) {
                "The command is owned by ${command.owner}, which is not the feature that declared it"
            }
        }

        // 5. Sending a client message goes through the adapter without throwing. It is
        //    client-side, so nothing reaches the server.
        onClient(context) { diagnostics.report() }
        context.waitTicks(SETTLE_TICKS)
        context.takeScreenshot("platform_diagnostics_message")

        // 6. Screen tracking, which every "is it safe to interrupt" decision will read.
        onClient(context) { client ->
            check(!platform.client.isScreenOpen) { "No screen is open, but the tracker says one is" }
            client.setScreenAndShow(Sidequest.createConfigScreen())
        }
        context.waitTicks(SETTLE_TICKS)
        onClient(context) {
            check(platform.client.isScreenOpen) { "A screen is open, but the tracker says none is" }
        }

        context.setScreen { null }
        context.waitTicks(SETTLE_TICKS)
        onClient(context) {
            check(!platform.client.isScreenOpen) { "The screen closed, but the tracker still says one is open" }
        }

        // 7. Disabling really does undo everything. This is the guarantee the whole
        //    ownership design exists for, checked against the live registries.
        onClient(context) {
            val listenersBefore = platform.listenerCount()
            check(listenersBefore > 0) { "The feature registered no listeners at all" }

            platform.features.disable(SqId.sidequest("dev.session_diagnostics"))

            check(platform.commands["sqdiag"] == null) { "Its command outlived it" }
            check(platform.listenerCount() < listenersBefore) { "Its listeners outlived it" }
        }

        val afterDisable = diagnostics.ticks
        context.waitTicks(TICK_SAMPLE)
        onClient(context) {
            check(diagnostics.ticks == afterDisable) {
                "A disabled feature is still receiving tick events"
            }
        }

        // 8. And it comes back cleanly, because re-enabling is a supported operation
        //    rather than a thing that happens to work once.
        onClient(context) {
            check(platform.features.enable(SqId.sidequest("dev.session_diagnostics")) == null) {
                "The feature refused to start again"
            }
        }
        val afterReEnable = diagnostics.ticks
        context.waitTicks(TICK_SAMPLE)
        onClient(context) {
            check(diagnostics.ticks > afterReEnable) { "It did not resume after being re-enabled" }
        }
    }

    private fun onClient(
        context: ClientGameTestContext,
        action: (net.minecraft.client.Minecraft) -> Unit,
    ) {
        context.runOnClient<RuntimeException> { client -> action(client) }
    }

    private companion object {
        const val SETTLE_TICKS = 10

        /** Long enough that a missing tick hook is unambiguous rather than a timing fluke. */
        const val TICK_SAMPLE = 20
    }
}

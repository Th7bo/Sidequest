package dev.th7bo.sidequest.ui.minecraft.lifecycle

import com.mojang.blaze3d.platform.InputConstants
import dev.th7bo.sidequest.Sidequest
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW

/**
 * Registers the keybind that opens the configuration screen.
 *
 * Kept out of the screen classes: the screen should be openable from a keybind, a
 * command or another mod's menu, and none of those belong inside it.
 */
public object SidequestKeybinds {

    private val category: KeyMapping.Category = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath(Sidequest.MOD_ID, "main"),
    )

    /** Opens the configuration screen. Unbound by default so it cannot clash. */
    public val openConfig: KeyMapping = KeyMapping(
        "key.sidequest.open_config",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_UNKNOWN,
        category,
    )

    /** Opens the HUD editor. Also unbound by default. */
    public val openHudEditor: KeyMapping = KeyMapping(
        "key.sidequest.open_hud_editor",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_UNKNOWN,
        category,
    )

    /**
     * Opens the component gallery.
     *
     * A demonstration screen rather than a feature, but it has to be reachable to be one:
     * a gallery only a test can open demonstrates nothing to the person installing the mod.
     */
    public val openGallery: KeyMapping = KeyMapping(
        "key.sidequest.open_gallery",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_UNKNOWN,
        category,
    )

    /** Opens the stress screen, for checking responsiveness by hand. */
    public val openStressScreen: KeyMapping = KeyMapping(
        "key.sidequest.open_stress",
        InputConstants.Type.KEYSYM,
        GLFW.GLFW_KEY_UNKNOWN,
        category,
    )

    /**
     * Pings whatever the crosshair is on.
     *
     * The only one of these with a default binding, and it needs one: a ping typed as a command is not a
     * ping. Middle mouse is where every game that has this puts it, and it is free in vanilla — the pick-block
     * it normally does is creative-only, and this is a client for a server where creative does not exist.
     */
    public val ping: KeyMapping = KeyMapping(
        "key.sidequest.ping",
        InputConstants.Type.MOUSE,
        GLFW.GLFW_MOUSE_BUTTON_MIDDLE,
        category,
    )

    /**
     * Drains every queued press and reports whether there was one.
     *
     * Holding a key must open the screen once rather than every tick, and leaving presses
     * queued would fire again the moment the screen closes.
     */
    private fun consumeAll(mapping: KeyMapping): Boolean {
        var pressed = false
        while (mapping.consumeClick()) pressed = true
        return pressed
    }

    public fun register() {
        KeyMappingHelper.registerKeyMapping(openConfig)
        KeyMappingHelper.registerKeyMapping(openHudEditor)
        KeyMappingHelper.registerKeyMapping(openGallery)
        KeyMappingHelper.registerKeyMapping(openStressScreen)
        KeyMappingHelper.registerKeyMapping(ping)

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Minecraft only queues keybind presses while no screen is open, so no "is a
            // screen already showing" check is needed — and 26.2 no longer exposes one.
            if (consumeAll(openConfig)) {
                client.setScreenAndShow(Sidequest.createConfigScreen())
            }

            // Null in a menu, where the HUD layer does not exist yet and there is
            // nothing to arrange.
            if (consumeAll(openHudEditor)) {
                Sidequest.createHudEditorScreen()?.let { client.setScreenAndShow(it) }
            }

            if (consumeAll(openGallery)) {
                client.setScreenAndShow(Sidequest.createGalleryScreen())
            }

            if (consumeAll(openStressScreen)) {
                client.setScreenAndShow(Sidequest.createStressScreen())
            }

            // Drained like the rest, so holding the button pings once. The feature has its own cooldown as
            // well — that one is about not flooding the group, this one is about not sending twenty for one
            // press.
            if (consumeAll(ping)) {
                Sidequest.pingWhereLooking()
            }
        }
    }
}

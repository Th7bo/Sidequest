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

    public fun register() {
        KeyMappingHelper.registerKeyMapping(openConfig)

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // `consumeClick` drains the queued presses, so holding the key opens the
            // screen once rather than every tick. Minecraft only queues keybind presses
            // while no screen is open, so no "is a screen already showing" check is
            // needed — and 26.2 no longer exposes one.
            var pressed = false
            while (openConfig.consumeClick()) pressed = true

            if (pressed) {
                client.setScreenAndShow(Sidequest.createConfigScreen())
            }
        }
    }
}

package dev.th7bo.sidequest.mixins;

import dev.th7bo.sidequest.ui.minecraft.screen.TitleScreenBackground;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the main menu's panorama with Sidequest's nebula.
 *
 * The panorama itself is the injection point rather than the screen's render method, and that matters twice
 * over: drawing on top would still pay for rendering a spinning cube of textures every frame, and the nebula
 * would need to be fully opaque to hide it — which rules out ever seeing stars through the clouds.
 *
 * {@code extractPanorama} is declared on {@link Screen} rather than on the title screen, so this mixin sees
 * every screen with a panorama. That is deliberate rather than accidental: the decision about which screens
 * qualify belongs in one place, and {@link TitleScreenBackground} is it. This asks, and does nothing when the
 * answer is no.
 */
@Mixin(Screen.class)
public abstract class ScreenPanoramaMixin {

    @Inject(method = "extractPanorama", at = @At("HEAD"), cancellable = true)
    private void sidequest$paintNebula(GuiGraphicsExtractor graphics, float alpha, CallbackInfo info) {
        // Cancelled only when something was actually drawn. A refusal has to leave the frame exactly as it
        // would have been, or turning the feature off would leave the menu on a black void.
        if (TitleScreenBackground.paint((Screen) (Object) this, graphics)) {
            info.cancel();
        }
    }
}

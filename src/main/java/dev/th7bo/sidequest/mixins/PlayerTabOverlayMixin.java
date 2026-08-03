package dev.th7bo.sidequest.mixins;

import dev.th7bo.sidequest.cosmetic.LevelColour;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Recolours the SkyBlock level in the tab list.
 *
 * {@code getNameForDisplay} is the same shape of injection point as the nametag's, and for the same reason:
 * it returns the {@link Component} that will be drawn, it is spelled identically on 26.1.2 and 26.2, and
 * decorating its return value needs no knowledge of how the overlay lays anything out.
 *
 * The tab list on Hypixel is mostly not players — stat readouts, headers and counters share it — so the
 * decision about what counts as a level is deliberately not here. {@link LevelColour} only recognises a
 * bracketed number that leads the line, which is what a level does and what a stat does not.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void sidequest$colourLevel(CallbackInfoReturnable<Component> info) {
        Component original = info.getReturnValue();
        if (original == null) {
            return;
        }
        Component recoloured = LevelColour.forTabList(original);
        // Identity, not equality: the common case returns the very same instance, and setting the return
        // value to what it already is would be an allocation and a write for nothing, once per row per frame.
        if (recoloured != original) {
            info.setReturnValue(recoloured);
        }
    }
}

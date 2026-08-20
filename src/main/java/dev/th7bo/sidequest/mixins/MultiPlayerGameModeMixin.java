package dev.th7bo.sidequest.mixins;

import dev.th7bo.sidequest.platform.minecraft.BlocksBroken;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts blocks as the player breaks them.
 *
 * <p>{@code destroyBlock} rather than the click that started it: a click that finds nothing, or a block the
 * server refuses, is not a block broken, and a farming run counted from clicks would be inflated by every
 * swing that hit air. The return value is the game's own answer to "did that work".
 *
 * <p>This runs several times a tick while farming, so it does exactly one thing — see {@link BlocksBroken}.
 * Anything that has to decide what the number means does it on its own schedule.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {

    @Inject(method = "destroyBlock", at = @At("RETURN"))
    private void sidequest$countBrokenBlock(BlockPos position, CallbackInfoReturnable<Boolean> info) {
        if (info.getReturnValueZ()) {
            BlocksBroken.record();
        }
    }
}

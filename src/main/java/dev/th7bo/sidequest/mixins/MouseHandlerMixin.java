package dev.th7bo.sidequest.mixins;

import dev.th7bo.sidequest.platform.minecraft.OrbitalCameraState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives the mouse to the camera instead of to the player, while the orbital camera is on.
 *
 * <p>Injected at the head of {@code turnPlayer} and cancelling it, rather than zeroing the rotation further
 * in. That is the point of the design: the deltas are read here, before anything has been done to them, so
 * this composes with SkyHanni's mouse lock instead of racing it. SkyHanni wraps the
 * {@code LocalPlayer.turn} call <em>inside</em> this method and multiplies its arguments by zero — cancel
 * the method and that wrapper simply never runs, whichever mixin the loader happened to apply first. Two
 * mods reaching for the same call and depending on the order would be a bug that only appears on somebody
 * else's install.
 *
 * <p>The accumulators are cleared because vanilla clears them at the end of the method this is skipping.
 * Leaving them would pile a session's worth of movement into the first frame after the mode is turned off.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Shadow
    private double accumulatedDX;

    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void sidequest$orbitInsteadOfTurning(double partialTick, CallbackInfo info) {
        if (!OrbitalCameraState.INSTANCE.isActive()) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        OrbitalCameraState.onMouseMoved(this.accumulatedDX, this.accumulatedDY, player.getXRot());
        this.accumulatedDX = 0.0;
        this.accumulatedDY = 0.0;
        info.cancel();
    }
}

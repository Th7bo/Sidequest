package dev.th7bo.sidequest.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.th7bo.sidequest.platform.minecraft.AfkCameraState;
import dev.th7bo.sidequest.platform.minecraft.OrbitalCameraState;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Points the camera where the orbit says, rather than where the player is looking.
 *
 * <p>{@code setRotation} is the injection point, and everything else follows from it for free: the third
 * person offset, the wall collision that pulls the camera in, and the frustum are all computed <em>after</em>
 * the rotation inside {@code alignWithEntity}. Substituting the two floats there produces a real orbit
 * around the player rather than a view that clips through the terrain behind them.
 *
 * <p>The player's own rotation is untouched, which is the whole feature: the model keeps facing the crops,
 * the reach still goes where they are pointing, and only the view moves.
 *
 * <p>The AFK camera arrives through the same door, and second. Both cannot be on at once — the feature that
 * owns the shots refuses to start while the orbit is running — but the order is stated here anyway, because
 * the one that must win is the one the player is driving.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @WrapOperation(
        method = "alignWithEntity",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V")
    )
    private void sidequest$orbitAroundPlayer(Camera camera, float yaw, float pitch, Operation<Void> original) {
        if (OrbitalCameraState.INSTANCE.isActive()) {
            original.call(camera, OrbitalCameraState.cameraYaw(yaw), OrbitalCameraState.cameraPitch(pitch));
            return;
        }
        if (AfkCameraState.INSTANCE.isActive()) {
            // Advanced here, once, so the yaw and the pitch below come from one step of the reel rather than
            // from two reads that could fall either side of a cut.
            AfkCameraState.advance();
            original.call(camera, AfkCameraState.cameraYaw(yaw), AfkCameraState.cameraPitch());
            return;
        }
        original.call(camera, yaw, pitch);
    }
}

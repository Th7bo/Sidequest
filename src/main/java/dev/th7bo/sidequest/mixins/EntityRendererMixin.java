package dev.th7bo.sidequest.mixins;

import dev.th7bo.sidequest.cosmetic.NametagDecorator;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Puts cosmetics on a player's nametag.
 *
 * {@code getNameTag} is the injection point rather than the drawing code, and that is the whole reason this
 * mixin is three lines. It returns the {@link Component} that will be drawn, it is called once per entity per
 * frame, and it is spelled identically on 26.1.2 and 26.2 — so decorating its return value needs no knowledge
 * of poses, buffers or render states, and nothing here has to change when Mojang moves the rendering around.
 *
 * The decision about <em>what</em> to draw is not here. This asks {@link NametagDecorator}, which asks the
 * cosmetic service, which is where the viewer's settings and the wearer's visibility are decided. A mixin that
 * consulted those itself would be a second place they are enforced.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin<T extends Entity> {

    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
    private void sidequest$decorateNameTag(T entity, CallbackInfoReturnable<Component> info) {
        // Players only. Every entity in the world passes through here, and a named armour stand is not
        // somebody wearing a badge.
        if (!(entity instanceof Player player)) {
            return;
        }
        Component original = info.getReturnValue();
        if (original == null) {
            return;
        }
        Component decorated = NametagDecorator.decorate(player, original);
        if (decorated != original) {
            info.setReturnValue(decorated);
        }
    }
}

package com.fluxlogic.mixin.client;

import com.fluxlogic.FluxLogicClient;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Filters raw mouse-look input for jitter, before it becomes player rotation.
 *
 * <p>The mouse handler turns accumulated cursor movement into a call to
 * {@code Entity#turn(double yRot, double xRot)}. We intercept those two deltas
 * and run them through the Inertia! mouse filter (soft deadzone + optional
 * low-pass). Both stages are off by default, so aim is untouched unless the
 * player opts in via the config.
 *
 * <p>Like the camera hook, every injector is {@code require = 0}: if
 * {@code Entity#turn} were renamed in a future drop, this simply stops applying
 * and the game still launches.
 */
@Mixin(Entity.class)
public abstract class MouseInputMixin {

    @ModifyVariable(method = "turn(DD)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 0)
    private double fluxlogic$filterYaw(double yRot) {
        return FluxLogicClient.inertia().filterMouseDelta(yRot, true);
    }

    @ModifyVariable(method = "turn(DD)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 1, require = 0)
    private double fluxlogic$filterPitch(double xRot) {
        return FluxLogicClient.inertia().filterMouseDelta(xRot, false);
    }
}

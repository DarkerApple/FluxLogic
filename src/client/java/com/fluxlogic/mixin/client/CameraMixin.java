package com.fluxlogic.mixin.client;

import com.fluxlogic.FluxLogicClient;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * The one and only render-touching mixin in FluxLogic: it feeds the camera's
 * per-frame rotation (and vertical position) through the Inertia! smoother.
 *
 * <h2>Why this is safe to ship to a version I can't test</h2>
 * Every injector uses {@code require = 0}. If a future Minecraft drop renames
 * {@code Camera#setRotation} / {@code Camera#setPosition}, the injector simply
 * doesn't apply and camera smoothing turns itself off — the game still launches
 * and every other FluxLogic feature keeps working. There is no other engine
 * code we depend on.
 *
 * <h2>Verifying the targets (see BUILDING.md)</h2>
 * In Mojang mappings the targets have been stable for many versions:
 * <pre>
 *   protected void setRotation(float yaw, float pitch)
 *   protected void setPosition(double x, double y, double z)
 * </pre>
 * After importing 26.2 sources in your IDE, confirm those signatures; if they
 * changed, update the {@code method = } descriptors here — nothing else.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @ModifyVariable(method = "setRotation(FF)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 0)
    private float fluxlogic$smoothYaw(float yaw) {
        return FluxLogicClient.inertia().smoothYaw(yaw);
    }

    @ModifyVariable(method = "setRotation(FF)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 1, require = 0)
    private float fluxlogic$smoothPitch(float pitch) {
        return FluxLogicClient.inertia().smoothPitch(pitch);
    }

    @ModifyVariable(method = "setPosition(DDD)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 1, require = 0)
    private double fluxlogic$smoothStepY(double y) {
        return FluxLogicClient.inertia().smoothStepY(y);
    }
}

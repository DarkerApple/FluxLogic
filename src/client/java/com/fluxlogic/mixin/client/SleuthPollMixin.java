package com.fluxlogic.mixin.client;

import com.fluxlogic.FluxLogicClient;
import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the GLFW event poll ({@code RenderSystem#pollEvents}) for Stutter
 * Sleuth, so event-queue stalls (macOS NSEvent, IME, high-poll mice) are
 * measured directly instead of inferred. Static target → static handlers.
 * require=0 as always.
 */
@Mixin(RenderSystem.class)
public abstract class SleuthPollMixin {

    @Inject(method = "pollEvents", at = @At("HEAD"), require = 0)
    private static void fluxlogic$sleuthPollStart(CallbackInfo ci) {
        FluxLogicClient.sleuth().pollStart();
    }

    @Inject(method = "pollEvents", at = @At("RETURN"), require = 0)
    private static void fluxlogic$sleuthPollEnd(CallbackInfo ci) {
        FluxLogicClient.sleuth().pollEnd();
    }
}

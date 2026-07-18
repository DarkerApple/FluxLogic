package com.fluxlogic.mixin.client;

import com.fluxlogic.FluxLogicClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds Stutter Sleuth the per-frame boundaries: HEAD/RETURN of the game's
 * per-frame body ({@code Minecraft#runTick}). Name-only target + require=0 so
 * a future rename silently disables forensics rather than breaking the game.
 */
@Mixin(Minecraft.class)
public abstract class SleuthFrameMixin {

    @Inject(method = "runTick", at = @At("HEAD"), require = 0)
    private void fluxlogic$sleuthFrameStart(CallbackInfo ci) {
        FluxLogicClient.sleuth().frameStart();
    }

    @Inject(method = "runTick", at = @At("RETURN"), require = 0)
    private void fluxlogic$sleuthFrameEnd(CallbackInfo ci) {
        FluxLogicClient.sleuth().frameEnd();
    }
}

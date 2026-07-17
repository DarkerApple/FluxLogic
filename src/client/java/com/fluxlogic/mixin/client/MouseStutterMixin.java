package com.fluxlogic.mixin.client;

import com.fluxlogic.FluxLogicClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The "click-drag stutter" fix: coalesces the GLFW cursor-move event flood
 * from high-polling-rate mice so the full vanilla {@code onMove} body runs at
 * most a bounded number of times per frame, instead of once per hardware poll
 * (up to 8000×/s). Swallowed events lose nothing — deltas are derived from
 * absolute positions, so the next processed event folds them in exactly
 * (see {@link com.fluxlogic.input.MouseDeStutter}).
 *
 * <p>Two extra hooks mark the frame boundary (the moment the game actually
 * consumes accumulated mouse input), so the gate can guarantee "first event
 * per frame always processes". Both candidate method names are targeted with
 * {@code require = 0} — whichever exists in this drop applies; if Mojang
 * renames both, the time-based rate cap alone still carries the fix.
 *
 * <p>Per FluxLogic convention every injector is {@code require = 0}: a rename
 * in a future drop turns the feature off, never crashes the game.
 */
@Mixin(MouseHandler.class)
public abstract class MouseStutterMixin {

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void fluxlogic$coalesceMove(long window, double x, double y, CallbackInfo ci) {
        if (!FluxLogicClient.deStutter().shouldProcessMove()) {
            ci.cancel();
        }
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), require = 0)
    private void fluxlogic$frameBoundaryTurn(CallbackInfo ci) {
        FluxLogicClient.deStutter().onFrameBoundary();
    }

    @Inject(method = "handleAccumulatedMovement", at = @At("HEAD"), require = 0)
    private void fluxlogic$frameBoundaryAccum(CallbackInfo ci) {
        FluxLogicClient.deStutter().onFrameBoundary();
    }
}

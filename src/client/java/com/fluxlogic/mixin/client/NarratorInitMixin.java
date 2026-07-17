package com.fluxlogic.mixin.client;

import com.fluxlogic.workaround.NarratorMuzzle;
import com.mojang.text2speech.Narrator;
import net.minecraft.client.GameNarrator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Routes {@code GameNarrator}'s eager {@code Narrator.getNarrator()} call
 * through {@link NarratorMuzzle}, so the native TTS library can be kept from
 * loading at all (opt-in {@code workarounds.disableNarrator}).
 *
 * <p>{@code require = 0} as always: if the call site changes in a future
 * drop, the redirect doesn't apply and vanilla behaviour is untouched.
 */
@Mixin(GameNarrator.class)
public abstract class NarratorInitMixin {

    @Redirect(method = "<init>", require = 0, at = @At(value = "INVOKE",
            target = "Lcom/mojang/text2speech/Narrator;getNarrator()Lcom/mojang/text2speech/Narrator;"))
    private Narrator fluxlogic$maybeSkipNarratorInit() {
        return NarratorMuzzle.narratorForInit();
    }
}

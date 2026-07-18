package com.fluxlogic.gui;

import com.fluxlogic.config.ConfigManager;
import com.fluxlogic.config.FluxConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Minimal in-game settings screen (reachable via Mod Menu or the keybind):
 * one toggle for the de-stutter fix, plus a shortcut to the config file for
 * the rate-cap tweak.
 */
public final class FluxConfigScreen extends Screen {

    private final Screen parent;
    private final FluxConfig cfg;

    public FluxConfigScreen(Screen parent) {
        super(Component.literal("FluxLogic"));
        this.parent = parent;
        this.cfg = ConfigManager.get(); // edits are committed on Done
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 220;
        int x = cx - w / 2;
        int y = 60;
        int gap = 24;

        addRenderableWidget(CycleButton.onOffBuilder(cfg.input.stutterFix)
                .create(x, y, w, 20, Component.literal("Mouse De-Stutter (click-drag fix)"),
                        (btn, val) -> cfg.input.stutterFix = val));
        y += gap;

        // Applies at launch — needs a restart to take effect.
        addRenderableWidget(CycleButton.onOffBuilder(cfg.workarounds.disableNarrator)
                .create(x, y, w, 20, Component.literal("Disable Narrator/TTS lib (restart)"),
                        (btn, val) -> cfg.workarounds.disableNarrator = val));
        y += gap;

        addRenderableWidget(CycleButton.onOffBuilder(cfg.sleuth.enabled)
                .create(x, y, w, 20, Component.literal("Stutter Sleuth (hitch forensics)"),
                        (btn, val) -> cfg.sleuth.enabled = val));
        y += gap;

        addRenderableWidget(Button.builder(Component.literal("Open config file…"), b -> openConfig())
                .bounds(x, y, w, 20).build());
        y += gap + 6;

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
            ConfigManager.replace(cfg); // persist
            onClose();
        }).bounds(cx - 100, y, 200, 20).build());
    }

    private void openConfig() {
        try {
            Util.getPlatform().openUri(
                    FabricLoader.getInstance().getConfigDir().resolve("fluxlogic.json").toUri());
        } catch (Throwable t) {
            ConfigManager.LOG.warn("[FluxLogic] Could not open config file: {}", t.toString());
        }
    }

    // 26.x GUI rework: Screen#render became extractRenderState; text colours
    // are full ARGB (alpha required, or the text is invisible).
    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);
        g.centeredText(this.font,
                Component.literal("Rate cap lives in config/fluxlogic.json"),
                this.width / 2, this.height - 26, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            // 26.x: the current screen is owned by Minecraft#gui.
            this.minecraft.gui.setScreen(parent);
        }
    }
}

package com.fluxlogic.gui;

import com.fluxlogic.config.ConfigManager;
import com.fluxlogic.config.FluxConfig;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Compact in-game settings screen (reachable via Mod Menu). Exposes the
 * everyday toggles with friendly presets; the full granular config — every
 * preset value, colours, the essentials list — lives in
 * {@code config/fluxlogic.json}, openable straight from here.
 *
 * <p>Uses only stable vanilla widgets ({@link CycleButton}, {@link Button}) so
 * it survives version drift. Edits a working copy and commits on Done, so
 * cancelling discards changes.
 */
public final class FluxConfigScreen extends Screen {

    /** Friendly smoothing presets mapped to yaw/pitch half-lives (seconds). */
    private enum Smooth {
        OFF(0f, 0f, "Off"),
        SUBTLE(0.02f, 0.025f, "Subtle"),
        SMOOTH(0.035f, 0.045f, "Smooth"),
        HEAVY(0.07f, 0.09f, "Heavy");

        final float yaw, pitch;
        final String label;
        Smooth(float yaw, float pitch, String label) {
            this.yaw = yaw; this.pitch = pitch; this.label = label;
        }
        static Smooth nearest(float yawHalfLife) {
            Smooth best = OFF;
            float bestD = Float.MAX_VALUE;
            for (Smooth s : values()) {
                float d = Math.abs(s.yaw - yawHalfLife);
                if (d < bestD) { bestD = d; best = s; }
            }
            return best;
        }
    }

    private final Screen parent;
    private final FluxConfig cfg; // working copy

    public FluxConfigScreen(Screen parent) {
        super(Component.literal("FluxLogic"));
        this.parent = parent;
        this.cfg = ConfigManager.get(); // edit live; Done persists, Cancel reloads
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int w = 220;
        int x = cx - w / 2;
        int y = 40;
        int gap = 24;

        addRenderableWidget(CycleButton.<Smooth>builder(s -> Component.literal(s.label))
                .withValues(Smooth.values())
                .withInitialValue(Smooth.nearest(cfg.camera.yawHalfLife))
                .create(x, y, w, 20, Component.literal("Inertia! Camera Smoothing"),
                        (btn, val) -> {
                            cfg.camera.enabled = val != Smooth.OFF;
                            cfg.camera.yawHalfLife = val.yaw;
                            cfg.camera.pitchHalfLife = val.pitch;
                        }));
        y += gap;

        addRenderableWidget(CycleButton.onOffBuilder(cfg.camera.stairStepSmoothing)
                .create(x, y, w, 20, Component.literal("Stair-step Smoothing"),
                        (btn, val) -> cfg.camera.stairStepSmoothing = val));
        y += gap;

        addRenderableWidget(CycleButton.onOffBuilder(cfg.input.stutterFix)
                .create(x, y, w, 20, Component.literal("Mouse De-Stutter (click-drag fix)"),
                        (btn, val) -> cfg.input.stutterFix = val));
        y += gap;

        addRenderableWidget(CycleButton.onOffBuilder(cfg.presets.enabled)
                .create(x, y, w, 20, Component.literal("Adaptive Presets"),
                        (btn, val) -> cfg.presets.enabled = val));
        y += gap;

        addRenderableWidget(CycleButton.onOffBuilder(cfg.tactical.highlightHostiles)
                .create(x, y, w, 20, Component.literal("Highlight Hostiles (Combat)"),
                        (btn, val) -> cfg.tactical.highlightHostiles = val));
        y += gap;

        addRenderableWidget(CycleButton.onOffBuilder(cfg.tactical.highlightEssentials)
                .create(x, y, w, 20, Component.literal("Highlight Essentials"),
                        (btn, val) -> cfg.tactical.highlightEssentials = val));
        y += gap;

        addRenderableWidget(CycleButton.onOffBuilder(cfg.tactical.desaturateWorld)
                .create(x, y, w, 20, Component.literal("Desaturate World (Combat, experimental)"),
                        (btn, val) -> cfg.tactical.desaturateWorld = val));
        y += gap;

        addRenderableWidget(Button.builder(Component.literal("Open config file…"), b -> openConfig())
                .bounds(x, y, w, 20).build());
        y += gap + 6;

        addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
            ConfigManager.replace(cfg); // persist working copy
            onClose();
        }).bounds(cx - 100, y, 200, 20).build());
    }

    private void openConfig() {
        try {
            // openUri(URI) is the most stable cross-version entry point.
            Util.getPlatform().openUri(
                    FabricLoader.getInstance().getConfigDir().resolve("fluxlogic.json").toUri());
        } catch (Throwable t) {
            ConfigManager.LOG.warn("[FluxLogic] Could not open config file: {}", t.toString());
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        g.drawCenteredString(this.font,
                Component.literal("Full control lives in config/fluxlogic.json"),
                this.width / 2, this.height - 26, 0xA0A0A0);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}

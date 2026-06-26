package com.fluxlogic.presets;

import com.fluxlogic.combat.CombatTracker;
import com.fluxlogic.compat.ModCompat;
import com.fluxlogic.config.ConfigManager;
import com.fluxlogic.config.FluxConfig;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.player.LocalPlayer;

/**
 * The adaptive-preset engine. Each client tick it resolves the
 * {@link GameContext} (combat / exploration / idle) and nudges the game's video
 * options toward the matching {@link PerformancePreset}.
 *
 * <h2>"Presets you don't notice"</h2>
 * Two ideas keep the switching from being jarring:
 * <ol>
 *   <li><b>Cheap settings switch immediately, during the chaos.</b> Graphics
 *       mode, particles, clouds, shadows, view-bob and the FPS cap are applied
 *       the moment combat starts — exactly when you're least likely to notice a
 *       cosmetic drop, and most likely to benefit from the frames.</li>
 *   <li><b>Render/sim distance is debounced.</b> Changing render distance forces
 *       chunk re-meshing, which is the <em>most</em> noticeable thing you can do.
 *       So distance only commits after the context has held steady, and never
 *       more often than {@code MIN_DISTANCE_INTERVAL_MS}. Lowering (cheap) is
 *       allowed sooner than raising (expensive remesh).</li>
 * </ol>
 *
 * Nothing here replaces rendering — it only drives public game options, so it
 * is automatically compatible with Sodium / Iris / VulkanMod.
 */
public final class PresetManager {

    private static final long MIN_DISTANCE_INTERVAL_MS = 5000;

    private final CombatTracker combat = new CombatTracker();

    private GameContext context = GameContext.EXPLORATION;
    private long contextSinceMs = Long.MIN_VALUE;
    private GameContext appliedContext = null;
    private long lastDistanceChangeMs = Long.MIN_VALUE;

    // movement detection
    private double lastX = Double.NaN, lastY = Double.NaN, lastZ = Double.NaN;
    private long lastMovementMs = Long.MIN_VALUE;

    public CombatTracker combatTracker() {
        return combat;
    }

    public GameContext currentContext() {
        return context;
    }

    public void tick(Minecraft mc) {
        FluxConfig cfg = ConfigManager.get();
        if (!cfg.presets.enabled) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        long now = System.nanoTime() / 1_000_000L;

        // 1) combat signal (also caches nearby hostiles for TacticalVision)
        combat.sample(mc, cfg, now);

        // 2) movement signal
        if (!Double.isNaN(lastX)) {
            double moved2 = sq(player.getX() - lastX) + sq(player.getZ() - lastZ)
                    + sq(player.getY() - lastY);
            if (moved2 > 0.0009) { // ~3cm/tick threshold filters idle drift
                lastMovementMs = now;
            }
        }
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();

        // 3) resolve context with hysteresis
        GameContext resolved = GameContext.resolve(
                now, combat.lastSignalMs(), lastMovementMs,
                cfg.presets.combatHoldMs, cfg.presets.idleAfterMs);

        if (resolved != context) {
            context = resolved;
            contextSinceMs = now;
        }

        // 4) apply the matching preset
        PerformancePreset preset = presetFor(cfg, context);
        applyCheapSettings(mc, preset, cfg);
        applyDistanceSettings(mc, preset, now);
        appliedContext = context;
    }

    private PerformancePreset presetFor(FluxConfig cfg, GameContext ctx) {
        return switch (ctx) {
            case COMBAT -> cfg.presets.combat;
            case EXPLORATION -> cfg.presets.exploration;
            case IDLE -> cfg.presets.idle;
        };
    }

    /** Discrete / cheap options — applied immediately on a context change. */
    private void applyCheapSettings(Minecraft mc, PerformancePreset p, FluxConfig cfg) {
        if (appliedContext == context) {
            return; // already applied for this context
        }
        Options o = mc.options;

        if (p.graphics != null) {
            o.graphicsMode().set(switch (p.graphics) {
                case FAST -> GraphicsStatus.FAST;
                case FANCY -> GraphicsStatus.FANCY;
                case FABULOUS -> GraphicsStatus.FABULOUS;
            });
        }
        if (p.particles != null) {
            o.particles().set(switch (p.particles) {
                case MINIMAL -> ParticleStatus.MINIMAL;
                case DECREASED -> ParticleStatus.DECREASED;
                case ALL -> ParticleStatus.ALL;
            });
        }
        if (p.clouds != null) {
            o.cloudStatus().set(switch (p.clouds) {
                case OFF -> CloudStatus.OFF;
                case FAST -> CloudStatus.FAST;
                case FANCY -> CloudStatus.FANCY;
            });
        }
        if (p.entityShadows != null) {
            o.entityShadows().set(p.entityShadows);
        }
        if (p.viewBobbing != null) {
            o.bobView().set(p.viewBobbing);
        }
        if (p.entityDistancePercent != null) {
            o.entityDistanceScaling().set(p.entityDistancePercent / 100.0);
        }
        if (p.maxFps != null) {
            o.framerateLimit().set(p.maxFps == 0 ? 260 : p.maxFps);
        }
        if (p.suspendShaders != null && cfg.compat.deferToIris && ModCompat.hasIris()) {
            // suspendShaders == true  -> shaders OFF; false -> shaders ON
            IrisBridge.setShadersEnabled(!p.suspendShaders);
        }
    }

    /** Render/sim distance — expensive, so debounced and rate-limited. */
    private void applyDistanceSettings(Minecraft mc, PerformancePreset p, long now) {
        if (p.renderDistance == null && p.simulationDistance == null) {
            return;
        }
        Options o = mc.options;
        boolean lowering =
                (p.renderDistance != null && p.renderDistance < o.renderDistance().get());

        // Allow lowering after a short hold; raising waits the full interval.
        long stableMs = now - contextSinceMs;
        long requiredHold = lowering ? 750 : MIN_DISTANCE_INTERVAL_MS;
        if (stableMs < requiredHold) {
            return;
        }
        if (now - lastDistanceChangeMs < MIN_DISTANCE_INTERVAL_MS && !lowering) {
            return;
        }

        boolean changed = false;
        if (p.renderDistance != null && !p.renderDistance.equals(o.renderDistance().get())) {
            o.renderDistance().set(clampInt(p.renderDistance, 2, 64));
            changed = true;
        }
        if (p.simulationDistance != null && !p.simulationDistance.equals(o.simulationDistance().get())) {
            o.simulationDistance().set(clampInt(p.simulationDistance, 5, 32));
            changed = true;
        }
        if (changed) {
            lastDistanceChangeMs = now;
        }
    }

    private static int clampInt(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static double sq(double v) {
        return v * v;
    }
}

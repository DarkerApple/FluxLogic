package com.fluxlogic.camera;

import com.fluxlogic.config.ConfigManager;
import com.fluxlogic.config.FluxConfig;
import com.fluxlogic.perf.FastMath;

/**
 * "Inertia!" — the smooth-camera core. Holds the smoothed view state and turns
 * raw per-frame camera yaw/pitch/height into silky, jitter-filtered values.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>Frame-rate independent.</b> Smoothing uses a half-life in seconds and
 *       the real frame {@code dt}, so the feel is identical at 30 or 240 FPS
 *       (see {@link FastMath#smoothingFactor}).</li>
 *   <li><b>View-only, with a hard catch-up cap.</b> We smooth the rendered view,
 *       not the player's logical rotation, so hit registration is unaffected.
 *       {@code maxCatchUpDegreesPerTick} bounds how far the view may trail the
 *       real aim, so fast flicks never feel like input lag — the camera is
 *       allowed to "snap the rest of the way" once the gap exceeds the cap.</li>
 *   <li><b>Short-way yaw.</b> Yaw smoothing wraps through ±180° so spinning past
 *       north doesn't whip the camera the long way around.</li>
 *   <li><b>Stair-step easing.</b> Small sudden upward camera jumps (auto-stepping
 *       onto a slab/stair) are eased; large or continuous vertical motion
 *       (jumping, falling, elytra) passes straight through.</li>
 * </ul>
 *
 * A single instance lives on the client and is driven by {@code CameraMixin}.
 */
public final class InertiaController {

    private float smoothedYaw;
    private float smoothedPitch;
    private boolean rotInit;

    private double smoothedY;
    private double lastRawY = Double.NaN;
    private boolean yInit;

    private long lastNanos = 0L;

    /** Per-frame delta time in seconds, clamped to sane bounds. */
    private float computeDt() {
        long now = System.nanoTime();
        if (lastNanos == 0L) {
            lastNanos = now;
            return 1.0f / 60.0f;
        }
        float dt = (float) ((now - lastNanos) / 1_000_000_000.0);
        lastNanos = now;
        // Clamp to avoid huge jumps after a stall/pause.
        return FastMath.clamp(dt, 1.0f / 1000.0f, 1.0f / 10.0f);
    }

    /** Smooth the camera yaw toward {@code rawYaw} (degrees). */
    public float smoothYaw(float rawYaw) {
        FluxConfig.Camera c = ConfigManager.get().camera;
        if (!c.enabled || c.yawHalfLife <= 0f) {
            smoothedYaw = rawYaw;
            rotInit = true;
            return rawYaw;
        }
        if (!rotInit) {
            smoothedYaw = rawYaw;
            rotInit = true;
            return rawYaw;
        }
        float dt = computeDt();
        // shortest-path delta
        float delta = FastMath.wrapDegrees(rawYaw - smoothedYaw);
        float k = FastMath.smoothingFactor(c.yawHalfLife, dt);
        float step = delta * k;

        // hard catch-up cap (per ~tick): keep the view from trailing too far
        float cap = c.maxCatchUpDegreesPerTick * (dt * 20.0f);
        float maxGap = c.maxCatchUpDegreesPerTick * 2.0f;
        if (Math.abs(delta) > maxGap) {
            // gap too large (fast flick) — snap most of the way, smooth the rest
            step = delta - Math.signum(delta) * maxGap * 0.5f;
        } else if (Math.abs(step) > cap) {
            step = Math.signum(step) * cap;
        }

        smoothedYaw = wrap360(smoothedYaw + step);
        return smoothedYaw;
    }

    /** Smooth the camera pitch toward {@code rawPitch} (degrees, -90..90). */
    public float smoothPitch(float rawPitch) {
        FluxConfig.Camera c = ConfigManager.get().camera;
        if (!c.enabled || c.pitchHalfLife <= 0f) {
            smoothedPitch = rawPitch;
            return rawPitch;
        }
        if (!rotInit) {
            smoothedPitch = rawPitch;
            return rawPitch;
        }
        float dt = computeDt();
        float delta = rawPitch - smoothedPitch;
        float k = FastMath.smoothingFactor(c.pitchHalfLife, dt);
        float step = delta * k;

        float cap = c.maxCatchUpDegreesPerTick * (dt * 20.0f);
        if (Math.abs(delta) > c.maxCatchUpDegreesPerTick * 2.0f) {
            step = delta - Math.signum(delta) * c.maxCatchUpDegreesPerTick;
        } else if (Math.abs(step) > cap) {
            step = Math.signum(step) * cap;
        }
        smoothedPitch = FastMath.clamp(smoothedPitch + step, -90f, 90f);
        return smoothedPitch;
    }

    /**
     * Ease the camera's vertical position when auto-stepping up blocks.
     *
     * <p>Heuristic: a "step" is a small (~0.4–1.05 block) sudden upward jump in
     * one frame. Those get eased; everything else (jumping, falling, smooth
     * walking) is passed through untouched so we never add vertical lag.
     */
    public double smoothStepY(double rawY) {
        FluxConfig.Camera c = ConfigManager.get().camera;
        if (!c.enabled || !c.stairStepSmoothing || c.stairStepHalfLife <= 0f) {
            smoothedY = rawY;
            lastRawY = rawY;
            yInit = true;
            return rawY;
        }
        if (!yInit) {
            smoothedY = rawY;
            lastRawY = rawY;
            yInit = true;
            return rawY;
        }

        double frameDelta = rawY - lastRawY;
        lastRawY = rawY;

        boolean isStep = frameDelta > 0.05 && frameDelta < 1.05;
        if (!isStep && Math.abs(rawY - smoothedY) > 1.25) {
            // teleport / big motion — resync, no easing
            smoothedY = rawY;
            return rawY;
        }

        float dt = computeDt();
        float k = FastMath.smoothingFactor(c.stairStepHalfLife, dt);
        smoothedY = FastMath.lerp(smoothedY, rawY, k);
        return smoothedY;
    }

    private static float wrap360(float deg) {
        deg %= 360.0f;
        if (deg < 0.0f) deg += 360.0f;
        return deg;
    }
}

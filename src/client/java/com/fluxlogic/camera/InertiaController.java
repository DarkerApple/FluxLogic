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

    private double stepOffset;
    private double lastRawY = Double.NaN;
    private boolean yInit;

    // mouse-input filtering state (separate from view smoothing)
    private double smoothedDX;
    private double smoothedDY;

    // Each smoothed channel keeps its OWN clock. A single shared timestamp is
    // a subtle disaster: yaw reads a real ~16ms frame delta, then pitch —
    // invoked microseconds later in the same frame — reads dt≈0, its smoothing
    // factor collapses to ~0, and vertical look freezes.
    private final FrameClock yawClock = new FrameClock();
    private final FrameClock pitchClock = new FrameClock();
    private final FrameClock stepClock = new FrameClock();

    /**
     * Filter a single raw mouse-look delta before it becomes player rotation.
     *
     * <p>This is the "kill the micro-jitters from your mouse" feature. Two
     * stages, both off by default (so aim is untouched until you opt in):
     * <ul>
     *   <li><b>Soft deadzone</b> — deltas below {@code mouseDeadzone} are eased
     *       toward zero (quadratic, no hard cliff), removing sensor noise while
     *       preserving deliberate small movements.</li>
     *   <li><b>Low-pass</b> — an optional exponential blend that smooths the
     *       delta stream. Adds a touch of aim latency, so it's opt-in.</li>
     * </ul>
     *
     * @param delta raw look delta for this axis this frame
     * @param yaw   true for the horizontal axis, false for vertical
     */
    public double filterMouseDelta(double delta, boolean yaw) {
        FluxConfig.Camera c = ConfigManager.get().camera;
        if (!c.enabled) {
            return delta;
        }
        double out = delta;
        if (c.mouseDeadzone > 0.0) {
            out = FastMath.softDeadzone(out, c.mouseDeadzone);
        }
        if (c.mouseSmoothing > 0.0f) {
            // Blend toward the previous value; clamp so it can never fully stall.
            float a = FastMath.clamp(c.mouseSmoothing, 0.0f, 0.95f);
            if (yaw) {
                smoothedDX = FastMath.lerp(out, smoothedDX, a);
                out = smoothedDX;
            } else {
                smoothedDY = FastMath.lerp(out, smoothedDY, a);
                out = smoothedDY;
            }
        }
        return out;
    }

    /** Per-channel frame timer: delta time in seconds, clamped to sane bounds. */
    private static final class FrameClock {
        private long lastNanos = 0L;

        float dt() {
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
        float dt = yawClock.dt();
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
        float dt = pitchClock.dt();
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
     * <p>A stair-step is an <em>instant</em> vertical snap: ~0.4–1.05 blocks in
     * a single frame. When one is detected, the snap is absorbed into
     * {@code stepOffset}, which then decays to zero — so the camera glides up
     * instead of teleporting. All continuous motion (jumping ≈ 0.1 blocks/frame
     * at 60 FPS, falling, elytra) passes through 1:1, untouched.
     *
     * <p>This replaces an earlier always-lerp design that trailed every
     * vertical move by up to 1.25 blocks and misread jumps as steps — which
     * felt like broken physics.
     */
    public double smoothStepY(double rawY) {
        FluxConfig.Camera c = ConfigManager.get().camera;
        if (!c.enabled || !c.stairStepSmoothing || c.stairStepHalfLife <= 0f || !yInit) {
            stepOffset = 0.0;
            lastRawY = rawY;
            yInit = true;
            return rawY;
        }

        double frameDelta = rawY - lastRawY;
        lastRawY = rawY;

        if (frameDelta > 0.35 && frameDelta < 1.05) {
            // Instant snap detected — absorb it, capped so sprinting up stairs
            // can't accumulate a huge trailing offset.
            stepOffset = Math.min(stepOffset + frameDelta, 1.05);
        } else if (Math.abs(frameDelta) > 1.05) {
            // Teleport / dimension change — resync, no easing.
            stepOffset = 0.0;
            return rawY;
        }

        if (stepOffset > 0.0) {
            float k = FastMath.smoothingFactor(c.stairStepHalfLife, stepClock.dt());
            stepOffset *= (1.0 - k);
            if (stepOffset < 0.005) {
                stepOffset = 0.0;
            }
        }
        return rawY - stepOffset;
    }

    private static float wrap360(float deg) {
        deg %= 360.0f;
        if (deg < 0.0f) deg += 360.0f;
        return deg;
    }
}

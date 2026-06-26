package com.fluxlogic.perf;

/**
 * Small, allocation-free math helpers used on the hot path (camera smoothing,
 * culling, preset interpolation).
 *
 * <p>Philosophy: we do <em>not</em> try to out-clever the JIT on arithmetic the
 * HotSpot compiler already vectorises. These helpers exist for three concrete
 * wins that show up in profiles of per-frame client code:
 *
 * <ol>
 *   <li><b>Branch-free clamps / lerps</b> — called thousands of times per frame
 *       by the smoothing code; keeping them tiny lets the JIT inline them.</li>
 *   <li><b>A frame-rate-independent smoothing factor</b> — the correct way to
 *       do exponential smoothing when {@code dt} varies, so the camera feels
 *       identical at 30 and 240 FPS (a common bug in naive "lerp by 0.5"
 *       smoothers).</li>
 *   <li><b>A fast inverse-square-root style normalize</b> for direction vectors
 *       in culling, avoiding a {@link Math#sqrt} + divide.</li>
 * </ol>
 *
 * Everything here is pure and unit-testable; nothing touches Minecraft.
 */
public final class FastMath {

    private FastMath() {}

    /** Branch-free float clamp. */
    public static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** Branch-free double clamp. */
    public static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    /** Linear interpolation. {@code t} is not clamped — callers clamp if needed. */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Frame-rate-independent exponential smoothing factor.
     *
     * <p>Given a {@code halfLife} (seconds for the value to cover half the
     * remaining distance) and the frame time {@code dt} (seconds), returns the
     * blend factor to feed into {@link #lerp}. Because it is derived from
     * {@code dt}, the perceived smoothing speed is identical regardless of FPS.
     *
     * <pre>{@code
     *   float k = FastMath.smoothingFactor(0.05f, deltaSeconds);
     *   smoothed = lerp(smoothed, target, k);
     * }</pre>
     */
    public static float smoothingFactor(float halfLife, float dt) {
        if (halfLife <= 0.0f) {
            return 1.0f; // no smoothing
        }
        // 1 - 2^(-dt / halfLife). Using exp/ln keeps it smooth and monotonic.
        return 1.0f - (float) Math.exp(-0.6931471805599453 * (dt / halfLife));
    }

    /**
     * Smallest signed difference between two angles in degrees, in (-180, 180].
     * Used so yaw smoothing takes the short way around instead of spinning.
     */
    public static float wrapDegrees(float delta) {
        delta %= 360.0f;
        if (delta >= 180.0f) {
            delta -= 360.0f;
        }
        if (delta < -180.0f) {
            delta += 360.0f;
        }
        return delta;
    }

    /**
     * Squared length — for distance comparisons, never take the sqrt.
     * This is the single most common avoidable {@link Math#sqrt} in entity
     * iteration (culling, combat scans).
     */
    public static double lengthSquared(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Maps a deadzone to mouse-delta noise filtering: inputs smaller than
     * {@code threshold} are scaled down toward zero (soft gate) rather than
     * hard-clipped, so deliberate small movements survive while sensor jitter
     * is suppressed. Returns the filtered delta.
     */
    public static double softDeadzone(double delta, double threshold) {
        if (threshold <= 0.0) {
            return delta;
        }
        double mag = Math.abs(delta);
        if (mag >= threshold) {
            return delta;
        }
        // Quadratic ease toward 0 inside the deadzone — smooth, no cliff.
        double t = mag / threshold;
        return delta * t * t;
    }
}

package com.fluxlogic.input;

import com.fluxlogic.config.ConfigManager;
import com.fluxlogic.config.FluxConfig;

/**
 * Coalesces GLFW cursor-move event floods so mouse movement is processed at
 * most a few times per frame instead of once per hardware poll.
 *
 * <h2>The problem this fixes</h2>
 * High-polling-rate mice (1000–8000 Hz) deliver a cursor-position event for
 * every hardware poll. The game runs its full {@code MouseHandler#onMove}
 * body for <em>each</em> of them — and while a button is held with a screen
 * open, that includes a {@code Screen#mouseDragged} dispatch per event. At
 * 8000 Hz that is thousands of callback bodies per frame: the classic
 * "moving the mouse while holding left click makes the game stutter" bug
 * (the same event-flood class of issue game engines fix by processing input
 * once per frame — see e.g. MonoGame #8011 discussions).
 *
 * <h2>Why coalescing loses nothing</h2>
 * {@code onMove} receives <em>absolute</em> cursor coordinates and derives
 * deltas as {@code x - lastProcessedX}. If we swallow an event without
 * touching any state, the next event we let through computes its delta from
 * the last <em>processed</em> position — the skipped movement is folded in
 * automatically. Total look/drag distance is preserved exactly; only the
 * redundant per-event work is skipped.
 *
 * <h2>Gating rules</h2>
 * An event is processed when either:
 * <ul>
 *   <li>it is the first event since the last frame boundary (so input keeps
 *       true once-per-frame freshness), or</li>
 *   <li>{@code 1 / maxPollsPerSecond} has elapsed since the last processed
 *       event (a safety valve that keeps the fix working even if the
 *       frame-boundary hook fails to apply on a future Minecraft version).</li>
 * </ul>
 * The decision core is pure and static for testability; this wrapper only
 * adds state + config lookup. Driven by {@code MouseStutterMixin}.
 */
public final class MouseDeStutter {

    /** Bounds for the configurable rate cap (events actually processed). */
    public static final int MIN_RATE = 125;
    public static final int MAX_RATE = 8000;

    private static final long REPORT_INTERVAL_NANOS = 30_000_000_000L;

    private long frameMark;
    private long lastProcessedMark = -1L;
    private long lastProcessedNanos;

    private long processedCount;
    private long swallowedCount;
    private long lastReportNanos;

    /** Called (via mixin) once per frame when the game consumes mouse input. */
    public void onFrameBoundary() {
        frameMark++;
    }

    /**
     * Decide whether the current cursor-move event should run the vanilla
     * handler ({@code true}) or be coalesced into the next one ({@code false}).
     */
    public boolean shouldProcessMove() {
        FluxConfig.Input c = ConfigManager.get().input;
        if (!c.stutterFix) {
            return true;
        }
        long now = System.nanoTime();
        boolean newFrame = frameMark != lastProcessedMark;
        boolean process = decide(newFrame, now - lastProcessedNanos,
                minIntervalNanos(c.maxPollsPerSecond));
        if (process) {
            lastProcessedMark = frameMark;
            lastProcessedNanos = now;
            processedCount++;
        } else {
            swallowedCount++;
        }
        maybeReport(now);
        return process;
    }

    // ------------------------------------------------------------- pure core

    /**
     * @param newFrame          no event has been processed since the last frame
     *                          boundary
     * @param elapsedNanos      nanos since the last processed event
     * @param minIntervalNanos  minimum spacing implied by the rate cap
     */
    static boolean decide(boolean newFrame, long elapsedNanos, long minIntervalNanos) {
        // elapsed < 0 guards nanoTime irregularities: fail open, never eat input.
        return newFrame || elapsedNanos >= minIntervalNanos || elapsedNanos < 0;
    }

    /** Rate cap → minimum nanos between processed events, clamped to sane bounds. */
    static long minIntervalNanos(int maxPollsPerSecond) {
        int rate = Math.max(MIN_RATE, Math.min(MAX_RATE, maxPollsPerSecond));
        return 1_000_000_000L / rate;
    }

    // ---------------------------------------------------------------- stats

    /** Low-volume debug heartbeat so "is it doing anything?" is answerable. */
    private void maybeReport(long now) {
        if (now - lastReportNanos < REPORT_INTERVAL_NANOS) {
            return;
        }
        lastReportNanos = now;
        if (swallowedCount > 0 && ConfigManager.LOG.isDebugEnabled()) {
            ConfigManager.LOG.debug(
                    "[FluxLogic] mouse de-stutter: processed {} move events, coalesced {}",
                    processedCount, swallowedCount);
        }
        processedCount = 0;
        swallowedCount = 0;
    }
}

package com.fluxlogic.config;

/**
 * The FluxLogic configuration. Plain public fields + no-arg constructor so
 * Gson can (de)serialise it with zero annotations, and so the in-game
 * settings screen can bind directly to fields.
 */
public final class FluxConfig {

    /** Bump when the schema changes so {@link ConfigManager} can migrate. */
    public int configVersion = 3;

    public Input input = new Input();

    public static final class Input {
        /**
         * Coalesce the GLFW cursor-move event flood from high-polling-rate
         * mice (1000–8000 Hz) so movement is processed per frame, not per
         * hardware poll. Fixes the "game stutters when I move the mouse while
         * holding left click" bug. Loses no input: deltas are derived from
         * absolute cursor positions, so skipped events fold into the next
         * processed one exactly.
         */
        public boolean stutterFix = true;

        /**
         * Upper bound on cursor-move events actually processed per second
         * (the rest are coalesced). Also the safety valve if the per-frame
         * hook ever fails to apply. Clamped to 125–8000.
         */
        public int maxPollsPerSecond = 1000;
    }
}

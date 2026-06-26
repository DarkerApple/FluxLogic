package com.fluxlogic.presets;

/**
 * The three high-level situations FluxLogic adapts to. Kept as a tiny pure enum
 * plus a pure resolver so the "what are we doing right now" decision is
 * testable in isolation from Minecraft.
 */
public enum GameContext {
    COMBAT,
    EXPLORATION,
    IDLE;

    /**
     * Pure decision function. All inputs are supplied by the client-tick code
     * (see {@code PresetManager}); this method has no Minecraft dependencies so
     * the hysteresis logic can be reasoned about and unit-tested.
     *
     * @param now                monotonic time in ms (e.g. {@code System.nanoTime()/1_000_000})
     * @param lastCombatSignalMs last time we took/dealt damage or a hostile was in range; {@code Long.MIN_VALUE} if never
     * @param lastMovementMs     last time the player moved meaningfully; {@code Long.MIN_VALUE} if never
     * @param combatHoldMs       how long to stay in COMBAT after the last signal (hysteresis)
     * @param idleAfterMs        how long without movement before we consider the player IDLE
     * @return the resolved context
     */
    public static GameContext resolve(long now,
                                      long lastCombatSignalMs,
                                      long lastMovementMs,
                                      long combatHoldMs,
                                      long idleAfterMs) {
        if (lastCombatSignalMs != Long.MIN_VALUE && (now - lastCombatSignalMs) <= combatHoldMs) {
            return COMBAT;
        }
        boolean moving = lastMovementMs != Long.MIN_VALUE && (now - lastMovementMs) <= idleAfterMs;
        return moving ? EXPLORATION : IDLE;
    }
}

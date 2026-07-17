package com.fluxlogic.workaround;

import com.fluxlogic.config.ConfigManager;
import com.mojang.text2speech.Narrator;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * Opt-in workaround: skip initialising the native narrator/TTS library.
 *
 * <p>Vanilla constructs its narrator eagerly ({@code Narrator.getNarrator()}
 * runs at {@code GameNarrator} construction) even when the narrator option is
 * OFF. That call loads a native text-to-speech backend — on macOS it spins up
 * speech/CoreAudio infrastructure — and the library shipped with 26.2 is a
 * newer major version than 26.1.2's. For users chasing periodic stutter that
 * exists only on 26.2 regardless of graphics backend or JVM, this toggle
 * removes the TTS library from the equation entirely.
 *
 * <p>Off by default. When enabled, the in-game narrator simply reports
 * inactive — accessibility narration will not work until it's turned back off.
 *
 * <h2>Version resilience</h2>
 * The substitute narrator is built without assuming anything about the
 * closed-source library's API surface: first try a public {@code EMPTY}
 * constant via reflection, else a dynamic proxy that no-ops every method,
 * else give up and initialise the real narrator (feature silently inert).
 */
public final class NarratorMuzzle {

    private NarratorMuzzle() {}

    /** Called (via mixin) in place of {@code Narrator.getNarrator()}. */
    public static Narrator narratorForInit() {
        if (!ConfigManager.get().workarounds.disableNarrator) {
            return Narrator.getNarrator();
        }
        Narrator dummy = dummyNarrator();
        if (dummy == null) {
            ConfigManager.LOG.warn(
                    "[FluxLogic] Could not build a dummy narrator; initialising the real one.");
            return Narrator.getNarrator();
        }
        ConfigManager.LOG.info(
                "[FluxLogic] Narrator/TTS library init skipped (workarounds.disableNarrator).");
        return dummy;
    }

    private static Narrator dummyNarrator() {
        // Preferred: the library's own inert instance, if this version has one.
        try {
            Field f = Narrator.class.getField("EMPTY");
            Object v = f.get(null);
            if (v instanceof Narrator n) {
                return n;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        // Fallback: a proxy that no-ops every method (false/0/null returns),
        // valid for any shape the interface takes in any library version.
        try {
            return (Narrator) Proxy.newProxyInstance(
                    Narrator.class.getClassLoader(),
                    new Class<?>[]{Narrator.class},
                    (proxy, method, args) -> {
                        Class<?> r = method.getReturnType();
                        if (r == boolean.class) return false;
                        if (r == int.class) return 0;
                        if (r == long.class) return 0L;
                        if (r == float.class) return 0.0f;
                        if (r == double.class) return 0.0d;
                        if (r == byte.class) return (byte) 0;
                        if (r == short.class) return (short) 0;
                        if (r == char.class) return (char) 0;
                        return null;
                    });
        } catch (Throwable t) {
            return null;
        }
    }
}

package com.fluxlogic.combat;

import com.fluxlogic.compat.ModCompat;
import com.fluxlogic.config.ConfigManager;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Best-effort loader for the optional fullscreen "desaturate the background"
 * post-process effect.
 *
 * <p><b>This is the single most version- and renderer-sensitive feature in
 * FluxLogic, by design isolated here.</b> Minecraft's post-effect API has been
 * reshaped several times (the core-shader rewrites), and a Vulkan renderer
 * replaces it wholesale. So rather than hard-calling an API that might not
 * exist on a given drop, we:
 *
 * <ul>
 *   <li>bail out entirely if a Vulkan renderer owns the pipeline,</li>
 *   <li>resolve the post-effect entry point reflectively, trying the known
 *       method names — <em>including</em> the resource-id parameter type, which
 *       was repackaged in 26.x, so this class names no id class at all —</li>
 *   <li>degrade to a silent no-op (glow highlight still works) if none match.</li>
 * </ul>
 *
 * The shader assets live in {@code assets/fluxlogic/shaders/post/}. If a future
 * drop renames the API, only this class needs touching — and the rest of the
 * mod is unaffected.
 */
public final class PostEffectBridge {

    private static final String EFFECT_NAMESPACE = "fluxlogic";
    private static final String EFFECT_PATH = "shaders/post/desaturate.json";
    private static final String EFFECT_ID = EFFECT_NAMESPACE + ":" + EFFECT_PATH;

    private static Boolean supported; // null = unknown, lazily probed
    private static boolean enabled;
    private static boolean warned;

    private PostEffectBridge() {}

    public static void enable(Minecraft mc, float strength) {
        if (enabled) return;
        if (ModCompat.postShaderUnavailable()) {
            warnOnce("a Vulkan renderer is active; desaturation post-effect skipped (glow highlight still works)");
            return;
        }
        if (!load(mc)) {
            warnOnce("post-effect API not found on this Minecraft build; desaturation skipped (glow highlight still works)");
            return;
        }
        enabled = true;
    }

    public static void disable(Minecraft mc) {
        if (!enabled) return;
        clear(mc);
        enabled = false;
    }

    // --- reflection ---------------------------------------------------------

    private static boolean load(Minecraft mc) {
        if (Boolean.FALSE.equals(supported)) return false;
        Object renderer = mc.gameRenderer;
        // Method names that have, at various points, loaded a named post effect.
        for (String name : new String[]{"loadEffect", "setPostEffect", "loadPostChain", "setPostProcessor"}) {
            for (Method m : oneArgCandidates(renderer.getClass(), name)) {
                try {
                    Object arg = coerceId(m.getParameterTypes()[0]);
                    if (arg == null) continue;
                    m.setAccessible(true);
                    m.invoke(renderer, arg);
                    supported = true;
                    return true;
                } catch (Throwable ignored) {
                    // try next candidate
                }
            }
        }
        supported = false;
        return false;
    }

    private static void clear(Minecraft mc) {
        Object renderer = mc.gameRenderer;
        for (String name : new String[]{"shutdownEffect", "clearPostEffect", "clearPostProcessor"}) {
            try {
                Method m = renderer.getClass().getMethod(name);
                m.setAccessible(true);
                m.invoke(renderer);
                return;
            } catch (Throwable ignored) {
                // try next candidate
            }
        }
    }

    /** All single-argument methods with this name, walking the class hierarchy. */
    private static java.util.List<Method> oneArgCandidates(Class<?> cls, String name) {
        java.util.List<Method> out = new java.util.ArrayList<>();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 1) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * Build our effect id as whatever type the target method wants: a plain
     * String, or the game's resource-id class — whose name/package we never
     * reference, we just probe its own static factories.
     */
    private static Object coerceId(Class<?> paramType) {
        if (paramType == String.class) {
            return EFFECT_ID;
        }
        // Try the id class's static factories: of/parse/fromNamespaceAndPath/tryParse…
        for (Method m : paramType.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || !paramType.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            try {
                if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
                    m.setAccessible(true);
                    return m.invoke(null, EFFECT_NAMESPACE, EFFECT_PATH);
                }
                if (p.length == 1 && p[0] == String.class) {
                    m.setAccessible(true);
                    Object v = m.invoke(null, EFFECT_ID);
                    if (v != null) return v;
                }
            } catch (Throwable ignored) {
                // try next factory
            }
        }
        return null;
    }

    private static void warnOnce(String msg) {
        if (!warned) {
            warned = true;
            ConfigManager.LOG.info("[FluxLogic] {}", msg);
        }
    }
}

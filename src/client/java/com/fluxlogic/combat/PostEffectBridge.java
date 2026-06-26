package com.fluxlogic.combat;

import com.fluxlogic.compat.ModCompat;
import com.fluxlogic.config.ConfigManager;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;

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
 *       method names, and</li>
 *   <li>degrade to a silent no-op (glow highlight still works) if none match.</li>
 * </ul>
 *
 * The shader assets live in {@code assets/fluxlogic/shaders/post/}. If a future
 * drop renames the API, only this class needs touching — and the rest of the
 * mod is unaffected.
 */
public final class PostEffectBridge {

    private static final ResourceLocation EFFECT =
            ResourceLocation.fromNamespaceAndPath("fluxlogic", "shaders/post/desaturate.json");

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
            try {
                Method m = findMethod(renderer.getClass(), name, ResourceLocation.class);
                if (m != null) {
                    m.setAccessible(true);
                    m.invoke(renderer, EFFECT);
                    supported = true;
                    return true;
                }
                Method ms = findMethod(renderer.getClass(), name, String.class);
                if (ms != null) {
                    ms.setAccessible(true);
                    ms.invoke(renderer, EFFECT.toString());
                    supported = true;
                    return true;
                }
            } catch (Throwable ignored) {
                // try next candidate
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

    private static Method findMethod(Class<?> cls, String name, Class<?> arg) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, arg);
            } catch (NoSuchMethodException ignored) {
                // walk up
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

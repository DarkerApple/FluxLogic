package com.fluxlogic.presets;

import com.fluxlogic.config.ConfigManager;

import java.lang.reflect.Method;

/**
 * Reflective bridge to Iris's public API so FluxLogic can suspend/resume
 * shaders during combat <em>without</em> a compile-time dependency on Iris.
 *
 * <p>If Iris isn't installed, every call is a silent no-op. We resolve the
 * method once and cache it. This is the polite way to integrate: we route
 * through Iris's own toggle instead of poking its internals.
 *
 * <p>Target API (stable across recent Iris): {@code
 * net.irisshaders.iris.api.v0.IrisApi.getInstance().setShadersEnabledConfig(boolean)}.
 */
final class IrisBridge {

    private static boolean resolved;
    private static Object irisInstance;
    private static Method setEnabled;

    private IrisBridge() {}

    static synchronized void setShadersEnabled(boolean enabled) {
        if (!resolved) {
            resolve();
        }
        if (irisInstance == null || setEnabled == null) {
            return;
        }
        try {
            setEnabled.invoke(irisInstance, enabled);
        } catch (Throwable t) {
            ConfigManager.LOG.debug("[FluxLogic] Iris toggle failed: {}", t.toString());
        }
    }

    private static void resolve() {
        resolved = true;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Method getInstance = api.getMethod("getInstance");
            irisInstance = getInstance.invoke(null);
            // setShadersEnabledConfig(boolean) is the public, persisted toggle.
            setEnabled = api.getMethod("setShadersEnabledConfig", boolean.class);
        } catch (Throwable t) {
            // Iris absent or API changed — stay a no-op.
            irisInstance = null;
            setEnabled = null;
        }
    }
}

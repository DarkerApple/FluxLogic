package com.fluxlogic.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Detects neighbouring mods so FluxLogic can <em>defer</em> to whoever already
 * owns a subsystem instead of fighting them. This is the whole compatibility
 * strategy in one class.
 *
 * <p>Why this matters (from the design notes): renderer-replacement mods
 * (Sodium, VulkanMod) crash if two of them fight over the pipeline. FluxLogic
 * is deliberately <b>not</b> a renderer. But some of its lighter features
 * overlap with what those mods do better — so when they're present, we yield
 * that slice and keep the rest.
 *
 * <p>Results are cached: mod presence can't change after launch.
 */
public final class ModCompat {

    private ModCompat() {}

    private static boolean detected;
    private static boolean sodium;
    private static boolean iris;
    private static boolean vulkanRenderer;
    private static boolean modMenu;

    public static void detect() {
        FabricLoader loader = FabricLoader.getInstance();
        sodium = loader.isModLoaded("sodium");
        iris = loader.isModLoaded("iris") || loader.isModLoaded("oculus");
        // Mojang's own Vulkan toggle isn't a mod; this catches the community
        // renderer (VulkanMod) so we can disable our OpenGL-only post effect.
        vulkanRenderer = loader.isModLoaded("vulkanmod");
        modMenu = loader.isModLoaded("modmenu");
        detected = true;
    }

    private static void ensure() {
        if (!detected) {
            detect();
        }
    }

    public static boolean hasSodium() { ensure(); return sodium; }
    public static boolean hasIris() { ensure(); return iris; }
    public static boolean hasVulkanRenderer() { ensure(); return vulkanRenderer; }
    public static boolean hasModMenu() { ensure(); return modMenu; }

    /**
     * True when FluxLogic should leave entity/chunk render culling to a
     * dedicated renderer mod. We still keep our cheap particle culling and all
     * non-render features.
     */
    public static boolean shouldDeferRenderCulling() {
        ensure();
        return sodium || vulkanRenderer;
    }

    /**
     * True when the OpenGL post-process desaturation shader cannot be used
     * (a Vulkan renderer is active). We fall back to the glow-only highlight,
     * which is renderer-agnostic.
     */
    public static boolean postShaderUnavailable() {
        ensure();
        return vulkanRenderer;
    }

    public static String summary() {
        ensure();
        return "Sodium=" + sodium + " Iris=" + iris
                + " VulkanRenderer=" + vulkanRenderer + " ModMenu=" + modMenu;
    }
}

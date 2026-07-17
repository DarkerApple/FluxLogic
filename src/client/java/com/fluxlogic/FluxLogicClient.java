package com.fluxlogic;

import com.fluxlogic.camera.InertiaController;
import com.fluxlogic.combat.TacticalVision;
import com.fluxlogic.input.MouseDeStutter;
import com.fluxlogic.compat.ModCompat;
import com.fluxlogic.config.ConfigManager;
import com.fluxlogic.gui.FluxConfigScreen;
import com.fluxlogic.presets.GameContext;
import com.fluxlogic.presets.PresetManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * FluxLogic client entrypoint.
 *
 * <p>FluxLogic is a client-side <em>coordination</em> layer, not a renderer:
 * it smooths the camera (Inertia!), adapts video settings to what you're doing
 * (combat-aware presets), and adds tactical mob highlighting — all on top of
 * public game APIs, so it stacks cleanly with Sodium / Iris / VulkanMod instead
 * of competing with them.
 *
 * <p>Static accessors expose the long-lived managers to the (tiny) mixin layer.
 */
public final class FluxLogicClient implements ClientModInitializer {

    public static final String MOD_ID = "fluxlogic";

    private static final InertiaController INERTIA = new InertiaController();
    private static final MouseDeStutter DE_STUTTER = new MouseDeStutter();
    private static final PresetManager PRESETS = new PresetManager();
    private static final TacticalVision TACTICAL = new TacticalVision();

    private static KeyMapping openConfigKey;

    public static InertiaController inertia() {
        return INERTIA;
    }

    public static MouseDeStutter deStutter() {
        return DE_STUTTER;
    }

    @Override
    public void onInitializeClient() {
        ConfigManager.load();
        ModCompat.detect();
        ConfigManager.LOG.info("[FluxLogic] Initialised. Neighbours: {}", ModCompat.summary());

        // Unbound by default — set a key in Controls, or use Mod Menu if present.
        // 26.x: categories are registered objects (lang key "key.category.<ns>.<path>").
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.fluxlogic.open_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(Minecraft mc) {
        // Open the settings screen on keypress (works even at the main menu).
        // 26.x: the current screen moved behind Minecraft#gui.
        while (openConfigKey != null && openConfigKey.consumeClick()) {
            mc.gui.setScreen(new FluxConfigScreen(mc.gui.screen()));
        }

        if (mc.player == null || mc.level == null) {
            return;
        }
        // Don't run game logic while a screen has the game paused in singleplayer.
        if (mc.isPaused()) {
            return;
        }

        PRESETS.tick(mc);
        TACTICAL.update(mc, PRESETS.currentContext() == GameContext.COMBAT);
    }
}

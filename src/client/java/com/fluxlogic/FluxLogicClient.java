package com.fluxlogic;

import com.fluxlogic.config.ConfigManager;
import com.fluxlogic.gui.FluxConfigScreen;
import com.fluxlogic.input.MouseDeStutter;
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
 * <p>FluxLogic does exactly one thing: it fixes the "game stutters when I
 * move the mouse while holding a button" bug caused by high-polling-rate mice
 * flooding the GLFW event queue. See {@link MouseDeStutter} for the how.
 */
public final class FluxLogicClient implements ClientModInitializer {

    public static final String MOD_ID = "fluxlogic";

    private static final MouseDeStutter DE_STUTTER = new MouseDeStutter();

    private static KeyMapping openConfigKey;

    public static MouseDeStutter deStutter() {
        return DE_STUTTER;
    }

    @Override
    public void onInitializeClient() {
        ConfigManager.load();
        ConfigManager.LOG.info("[FluxLogic] Initialised. Mouse de-stutter: {}.",
                ConfigManager.get().input.stutterFix ? "on" : "off");

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
        while (openConfigKey != null && openConfigKey.consumeClick()) {
            mc.gui.setScreen(new FluxConfigScreen(mc.gui.screen()));
        }
    }
}

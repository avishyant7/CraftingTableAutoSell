package com.example.craftingtableautosell;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Entry point for the client-only "Crafting Table Auto Sell" mod.
 *
 * <p>This class only wires up the keybinding, the per-tick pump, and the chat/system
 * message listeners. All of the actual sell logic and inventory-safety machinery lives
 * in {@link AutoSellManager}.
 */
public final class CraftingTableAutoSellClient implements ClientModInitializer {

    public static final String MOD_ID = "craftingtableautosell";

    private KeyMapping toggleKey;
    private final AutoSellManager manager = new AutoSellManager();

    @Override
    public void onInitializeClient() {
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.craftingtableautosell.toggle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSPACE,
                "category.craftingtableautosell"
        ));

        // Single, centralized per-tick pump. Everything the manager does -
        // scanning, splitting, selling, waiting - happens from here.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                manager.toggle(client);
            }
            manager.tick(client);
        });

        // Normal chat messages (most auction-house / shop plugins reply here).
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            manager.onServerMessage(message);
            return true; // never swallow the message, just observe it
        });

        // Some servers use system messages / action-bar style messages instead.
        ClientReceiveMessageEvents.ALLOW_SYSTEM.register(message -> {
            manager.onServerMessage(message);
            return true;
        });
    }
}

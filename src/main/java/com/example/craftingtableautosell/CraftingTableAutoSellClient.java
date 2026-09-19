package com.example.craftingtableautosell;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class CraftingTableAutoSellClient implements ClientModInitializer {

    public static final String MOD_ID = "craftingtableautosell";

    private KeyMapping toggleKey;
    private final AutoSellManager manager = new AutoSellManager();

    @Override
    public void onInitializeClient() {

        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main")
        );

        toggleKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "key.craftingtableautosell.toggle",
                        InputConstants.Type.KEYSYM,
                        GLFW.GLFW_KEY_BACKSPACE,
                        category
                )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {

            while (toggleKey.consumeClick()) {
                manager.toggle(client);
            }

            manager.tick(client);
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register(
                (message, overlay) -> {
                    manager.onServerMessage(message);
                    return true;
                }
        );
    }
}

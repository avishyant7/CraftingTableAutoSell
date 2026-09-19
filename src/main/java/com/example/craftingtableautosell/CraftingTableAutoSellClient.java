package com.example.craftingtableautosell;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class CraftingTableAutoSellClient implements ClientModInitializer {

    public static final String MOD_ID = "craftingtableautosell";

    private static AutoSellManager manager;

    private static KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        manager = new AutoSellManager();

        Identifier categoryId =
                Identifier.fromNamespaceAndPath(
                        MOD_ID,
                        "main"
                );

        KeyMapping.Category category =
                KeyMapping.Category.register(categoryId);

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

        ClientCommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess) -> {
                    dispatcher.register(
                            ClientCommandManager.literal(
                                    "craftautosellnew"
                            ).then(
                                    ClientCommandManager.argument(
                                            "price",
                                            IntegerArgumentType.integer(1)
                                    ).executes(context -> {
                                        int price =
                                                IntegerArgumentType.getInteger(
                                                        context,
                                                        "price"
                                                );

                                        manager.setSellPrice(
                                                price,
                                                Minecraft.getInstance()
                                        );

                                        return 1;
                                    })
                            )
                    );

                    dispatcher.register(
                            ClientCommandManager.literal(
                                    "craftautostopnew"
                            ).executes(context -> {
                                manager.resetSellPrice(
                                        Minecraft.getInstance()
                                );

                                return 1;
                            })
                    );
                }
        );
    }
}

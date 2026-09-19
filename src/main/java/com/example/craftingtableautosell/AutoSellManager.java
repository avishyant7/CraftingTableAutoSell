```java
package com.example.craftingtableautosell;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AutoSellManager {

    private static final int DEFAULT_SELL_PRICE = 999;

    private static final String TOO_MANY_ITEMS_TRIGGER =
            "you have too many items listed";

    private static final int TOO_MANY_ITEMS_WAIT_TICKS = 8 * 20;
    private static final int SALE_TIMEOUT_TICKS = 6 * 20;
    private static final int POST_SALE_COOLDOWN_TICKS = 10;
    private static final int WARN_MESSAGE_COOLDOWN_TICKS = 60;

    private static final Identifier BELL_USE_SOUND_ID =
            Identifier.withDefaultNamespace("block.bell.use");

    private enum State {
        IDLE,
        AWAITING_SALE_RESULT,
        TOO_MANY_ITEMS_WAIT,
        POST_SALE_COOLDOWN,
        STOPPED_EMERGENCY
    }

    private State state = State.IDLE;

    private boolean enabled = false;

    private int sellPrice = DEFAULT_SELL_PRICE;

    private int waitTicks = 0;
    private int lockedHotbarSlot = -1;
    private int warnCooldown = 0;

    private final List<Integer> pendingBellTicks = new ArrayList<>();

    public void toggle(Minecraft client) {
        enabled = !enabled;
        state = State.IDLE;
        waitTicks = 0;
        lockedHotbarSlot = -1;

        if (enabled) {
            sendClientMessage(
                    client,
                    "Crafting Table Auto Sell: ON",
                    ChatFormatting.GREEN
            );
        } else {
            sendClientMessage(
                    client,
                    "Crafting Table Auto Sell: OFF",
                    ChatFormatting.RED
            );
        }
    }

    public void tick(Minecraft client) {
        processBellQueue(client);

        if (!enabled) {
            return;
        }

        if (client.player == null || client.player.connection == null) {
            return;
        }

        switch (state) {
            case IDLE -> tryStartCycle(client);
            case AWAITING_SALE_RESULT -> handleAwaitingSaleResult(client);
            case TOO_MANY_ITEMS_WAIT -> handleTooManyItemsWait(client);
            case POST_SALE_COOLDOWN -> handlePostSaleCooldown();
            case STOPPED_EMERGENCY -> {
            }
        }
    }

    public void onServerMessage(Component message) {
        if (!enabled) {
            return;
        }

        String plain = message.getString()
                .toLowerCase(Locale.ROOT)
                .replaceAll("§.", "");

        if (plain.contains(TOO_MANY_ITEMS_TRIGGER)) {
            beginTooManyItemsWait();
        }
    }

    public void setSellPrice(int price, Minecraft client) {
        if (price <= 0) {
            sendClientMessage(
                    client,
                    "Price must be greater than 0.",
                    ChatFormatting.RED
            );
            return;
        }

        sellPrice = price;

        sendClientMessage(
                client,
                "Crafting Table Auto Sell price set to $" + price,
                ChatFormatting.GREEN
        );
    }

    public int getSellPrice() {
        return sellPrice;
    }

    public void resetSellPrice(Minecraft client) {
        sellPrice = DEFAULT_SELL_PRICE;

        sendClientMessage(
                client,
                "Crafting Table Auto Sell price reset to $" + DEFAULT_SELL_PRICE,
                ChatFormatting.YELLOW
        );
    }

    private void tryStartCycle(Minecraft client) {
        LocalPlayer player = client.player;

        if (client.gui.screen() != null) {
            return;
        }

        InventoryMenu menu = player.inventoryMenu;

        if (!menu.getCarried().isEmpty()) {
            return;
        }

        Inventory inventory = player.getInventory();

        int craftingTableCount = countCraftingTables(inventory);

        if (craftingTableCount <= 0) {
            stopBecauseInventoryEmpty(client);
            return;
        }

        int selectedHotbarSlot = inventory.getSelectedSlot();
        int targetScreenSlot = hotbarScreenSlot(selectedHotbarSlot);

        int sourceInvIndex = findCraftingTableSlot(inventory);

        if (sourceInvIndex < 0) {
            return;
        }

        int sourceScreenSlot =
                inventoryIndexToScreenSlot(sourceInvIndex);

        ItemStack sourceStack =
                menu.getSlot(sourceScreenSlot).getItem();

        if (sourceStack.isEmpty()
                || sourceStack.getItem() != Items.CRAFTING_TABLE) {
            return;
        }

        boolean prepared;

        if (sourceScreenSlot == targetScreenSlot) {
            prepared = prepareWhenSourceIsHandSlot(
                    client,
                    menu,
                    sourceScreenSlot,
                    sourceStack.getCount()
            );
        } else if (sourceStack.getCount() == 1) {
            prepared = swapIntoHand(
                    client,
                    sourceScreenSlot,
                    selectedHotbarSlot
            );
        } else {
            prepared = splitOneThenSwapIntoHand(
                    client,
                    menu,
                    sourceScreenSlot,
                    selectedHotbarSlot
            );
        }

        if (!prepared) {
            warnCannotSplit(client);
            return;
        }

        lockedHotbarSlot = selectedHotbarSlot;

        if (!verifyReadyToSell(
                menu,
                inventory,
                selectedHotbarSlot
        )) {
            lockedHotbarSlot = -1;
            return;
        }

        sendSellCommand(client);
    }

    private boolean prepareWhenSourceIsHandSlot(
            Minecraft client,
            InventoryMenu menu,
            int slot,
            int count
    ) {
        if (count == 1) {
            return true;
        }

        int scratch = findEmptySlot(menu, slot, -1);

        if (scratch < 0) {
            return false;
        }

        click(
                client,
                slot,
                0,
                ContainerInput.PICKUP
        );

        ItemStack cursor = menu.getCarried();

        if (cursor.isEmpty()
                || cursor.getItem() != Items.CRAFTING_TABLE
                || cursor.getCount() != count) {
            return false;
        }

        click(
                client,
                slot,
                1,
                ContainerInput.PICKUP
        );

        ItemStack handSlotNow =
                menu.getSlot(slot).getItem();

        if (handSlotNow.isEmpty()
                || handSlotNow.getItem() != Items.CRAFTING_TABLE
                || handSlotNow.getCount() != 1) {
            return false;
        }

        click(
                client,
                scratch,
                0,
                ContainerInput.PICKUP
        );

        return menu.getCarried().isEmpty();
    }

    private boolean swapIntoHand(
            Minecraft client,
            int sourceSlot,
            int hotbarIndex
    ) {
        click(
                client,
                sourceSlot,
                hotbarIndex,
                ContainerInput.SWAP
        );

        return true;
    }

    private boolean splitOneThenSwapIntoHand(
            Minecraft client,
            InventoryMenu menu,
            int sourceSlot,
            int hotbarIndex
    ) {
        int scratch = findEmptySlot(
                menu,
                sourceSlot,
                hotbarScreenSlot(hotbarIndex)
        );

        if (scratch < 0) {
            return false;
        }

        int originalCount =
                menu.getSlot(sourceSlot)
                        .getItem()
                        .getCount();

        click(
                client,
                sourceSlot,
                0,
                ContainerInput.PICKUP
        );

        ItemStack cursorAfterPickup =
                menu.getCarried();

        if (cursorAfterPickup.isEmpty()
                || cursorAfterPickup.getItem() != Items.CRAFTING_TABLE
                || cursorAfterPickup.getCount() != originalCount) {
            return false;
        }

        click(
                client,
                scratch,
                1,
                ContainerInput.PICKUP
        );

        ItemStack scratchStack =
                menu.getSlot(scratch).getItem();

        if (scratchStack.isEmpty()
                || scratchStack.getItem() != Items.CRAFTING_TABLE
                || scratchStack.getCount() != 1) {
            return false;
        }

        click(
                client,
                sourceSlot,
                0,
                ContainerInput.PICKUP
        );

        if (!menu.getCarried().isEmpty()) {
            return false;
        }

        int expectedRemainder = originalCount - 1;

        if (expectedRemainder > 0) {
            ItemStack sourceAfter =
                    menu.getSlot(sourceSlot).getItem();

            if (sourceAfter.isEmpty()
                    || sourceAfter.getItem() != Items.CRAFTING_TABLE
                    || sourceAfter.getCount() != expectedRemainder) {
                return false;
            }
        }

        click(
                client,
                scratch,
                hotbarIndex,
                ContainerInput.SWAP
        );

        return true;
    }

    private boolean verifyReadyToSell(
            InventoryMenu menu,
            Inventory inventory,
            int hotbarIndex
    ) {
        if (!menu.getCarried().isEmpty()) {
            return false;
        }

        ItemStack mainHand =
                inventory.getItem(hotbarIndex);

        return !mainHand.isEmpty()
                && mainHand.getItem() == Items.CRAFTING_TABLE
                && mainHand.getCount() == 1;
    }

    private void sendSellCommand(Minecraft client) {
        if (client.player == null
                || client.player.connection == null) {
            return;
        }

        client.player.connection.sendCommand(
                "ah sell " + sellPrice
        );

        state = State.AWAITING_SALE_RESULT;
        waitTicks = SALE_TIMEOUT_TICKS;
    }

    private void handleAwaitingSaleResult(Minecraft client) {
        LocalPlayer player = client.player;

        if (player == null) {
            emergencyStop(
                    client,
                    "player disappeared mid-sale"
            );
            return;
        }

        ItemStack mainHand =
                player.getInventory()
                        .getItem(lockedHotbarSlot);

        boolean handNoLongerHoldsCraftingTable =
                mainHand.isEmpty()
                        || mainHand.getItem() != Items.CRAFTING_TABLE;

        if (handNoLongerHoldsCraftingTable) {
            state = State.POST_SALE_COOLDOWN;
            waitTicks = POST_SALE_COOLDOWN_TICKS;
            return;
        }

        if (mainHand.getCount() > 1) {
            emergencyStop(
                    client,
                    "main hand unexpectedly holds more than 1 Crafting Table"
            );
            return;
        }

        waitTicks--;

        if (waitTicks <= 0) {
            emergencyStop(
                    client,
                    "no confirmation the sale went through in time"
            );
        }
    }

    private void beginTooManyItemsWait() {
        if (state == State.AWAITING_SALE_RESULT) {
            state = State.TOO_MANY_ITEMS_WAIT;
            waitTicks = TOO_MANY_ITEMS_WAIT_TICKS;
        }
    }

    private void handleTooManyItemsWait(Minecraft client) {
        waitTicks--;

        if (waitTicks > 0) {
            return;
        }

        LocalPlayer player = client.player;

        if (player == null) {
            return;
        }

        ItemStack mainHand =
                player.getInventory()
                        .getItem(lockedHotbarSlot);

        if (mainHand.isEmpty()
                || mainHand.getItem() != Items.CRAFTING_TABLE
                || mainHand.getCount() != 1) {
            state = State.IDLE;
            lockedHotbarSlot = -1;
            return;
        }

        sendSellCommand(client);
    }

    private void handlePostSaleCooldown() {
        waitTicks--;

        if (waitTicks <= 0) {
            state = State.IDLE;
            lockedHotbarSlot = -1;
        }
    }

    private void stopBecauseInventoryEmpty(
            Minecraft client
    ) {
        enabled = false;
        state = State.IDLE;
        lockedHotbarSlot = -1;

        sendClientMessage(
                client,
                "Crafting Table Auto Sell: OFF (no Crafting Tables left)",
                ChatFormatting.YELLOW
        );

        ringBellFourTimes();
    }

    private void emergencyStop(
            Minecraft client,
            String reason
    ) {
        enabled = false;
        state = State.STOPPED_EMERGENCY;

        sendClientMessage(
                client,
                "Crafting Table Auto Sell: OFF (safety stop - "
                        + reason + ")",
                ChatFormatting.RED
        );
    }

    private void ringBellFourTimes() {
        pendingBellTicks.clear();

        for (int i = 0; i < 4; i++) {
            pendingBellTicks.add(i * 10);
        }
    }

    private void processBellQueue(Minecraft client) {
        if (pendingBellTicks.isEmpty()
                || client.player == null) {
            return;
        }

        List<Integer> remaining = new ArrayList<>();

        for (int ticksLeft : pendingBellTicks) {
            if (ticksLeft <= 0) {
                playBellSound(client);
            } else {
                remaining.add(ticksLeft - 1);
            }
        }

        pendingBellTicks.clear();
        pendingBellTicks.addAll(remaining);
    }

    private void playBellSound(Minecraft client) {
        LocalPlayer player = client.player;

        if (player == null) {
            return;
        }

        SoundEvent sound =
                BuiltInRegistries.SOUND_EVENT
                        .getValue(BELL_USE_SOUND_ID);

        if (sound != null) {
            player.playSound(
                    sound,
                    1.0f,
                    1.0f
            );
        }
    }

    private void click(
            Minecraft client,
            int slot,
            int button,
            ContainerInput action
    ) {
        client.gameMode.handleContainerInput(
                client.player.inventoryMenu.containerId,
                slot,
                button,
                action,
                client.player
        );
    }

    private int countCraftingTables(
            Inventory inventory
    ) {
        int total = 0;

        for (int i = 0; i < 36; i++) {
            ItemStack stack =
                    inventory.getItem(i);

            if (!stack.isEmpty()
                    && stack.getItem() == Items.CRAFTING_TABLE) {
                total += stack.getCount();
            }
        }

        return total;
    }

    private int findCraftingTableSlot(
            Inventory inventory
    ) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack =
                    inventory.getItem(i);

            if (!stack.isEmpty()
                    && stack.getItem() == Items.CRAFTING_TABLE
                    && stack.getCount() == 1) {
                return i;
            }
        }

        for (int i = 0; i < 36; i++) {
            ItemStack stack =
                    inventory.getItem(i);

            if (!stack.isEmpty()
                    && stack.getItem() == Items.CRAFTING_TABLE) {
                return i;
            }
        }

        return -1;
    }

    private int inventoryIndexToScreenSlot(
            int inventoryIndex
    ) {
        if (inventoryIndex < 9) {
            return hotbarScreenSlot(inventoryIndex);
        }

        return inventoryIndex;
    }

    private int hotbarScreenSlot(
            int hotbarIndex
    ) {
        return 36 + hotbarIndex;
    }

    private int findEmptySlot(
            InventoryMenu menu,
            int exclude1,
            int exclude2
    ) {
        for (int slot = 9; slot <= 35; slot++) {
            if (slot == exclude1 || slot == exclude2) {
                continue;
            }

            if (menu.getSlot(slot)
                    .getItem()
                    .isEmpty()) {
                return slot;
            }
        }

        for (int slot = 36; slot <= 44; slot++) {
            if (slot == exclude1 || slot == exclude2) {
                continue;
            }

            if (menu.getSlot(slot)
                    .getItem()
                    .isEmpty()) {
                return slot;
            }
        }

        return -1;
    }

    private void warnCannotSplit(
            Minecraft client
    ) {
        if (warnCooldown > 0) {
            warnCooldown--;
            return;
        }

        warnCooldown = WARN_MESSAGE_COOLDOWN_TICKS;

        sendClientMessage(
                client,
                "Crafting Table Auto Sell: waiting for a free inventory slot to split safely...",
                ChatFormatting.GOLD
        );
    }

    private void sendClientMessage(
            Minecraft client,
            String message,
            ChatFormatting color
    ) {
        if (client.player == null) {
            return;
        }

        client.player.sendSystemMessage(
                Component.literal(message)
                        .withStyle(color)
        );
    }
}
```

You will also need the **`CraftingTableAutoSellClient.java`** update for `/craftautosellnew <price>` itself. If you want, I can send that whole file next

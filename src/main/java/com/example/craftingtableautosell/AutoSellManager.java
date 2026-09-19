package com.example.craftingtableautosell;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Core state machine that safely auto-sells Crafting Tables, exactly one at a time,
 * using real inventory slot-click packets rather than local item-stack edits.
 *
 * <p>Design goals, in priority order:
 * <ol>
 *     <li>Never let more than 1 Crafting Table reach the main hand / be sold.</li>
 *     <li>Never touch, drop, or overwrite any item that isn't a Crafting Table.</li>
 *     <li>If a safe split can't be proven, do nothing and wait rather than guess.</li>
 * </ol>
 */
public final class AutoSellManager {

    private static final String SELL_COMMAND = "ah sell 999";
    private static final String TOO_MANY_ITEMS_TRIGGER = "you have too many items listed";

    /** How long to wait after a "too many items listed" message before retrying. */
    private static final int TOO_MANY_ITEMS_WAIT_TICKS = 8 * 20;

    /** Max ticks to wait for a sale to visibly clear the held stack before treating it as failed. */
    private static final int SALE_TIMEOUT_TICKS = 6 * 20;

    /** Small courtesy delay after a confirmed sale before starting the next one. */
    private static final int POST_SALE_COOLDOWN_TICKS = 10;

    /** Minimum gap between repeated "can't find a safe slot" chat warnings. */
    private static final int WARN_MESSAGE_COOLDOWN_TICKS = 60;

    /** Resource location of the vanilla bell "use" sound, looked up rather than referenced
     *  by field name so this keeps working even if the exact SoundEvents constant name
     *  differs slightly between mapping revisions. */
    private static final ResourceLocation BELL_USE_SOUND_ID = ResourceLocation.withDefaultNamespace("block.bell.use");

    private enum State {
        IDLE,
        AWAITING_SALE_RESULT,
        TOO_MANY_ITEMS_WAIT,
        POST_SALE_COOLDOWN,
        STOPPED_EMERGENCY
    }

    private State state = State.IDLE;
    private boolean enabled = false;

    private int waitTicks = 0;
    /** The hotbar slot (0-8) that currently holds the single Crafting Table we're selling. */
    private int lockedHotbarSlot = -1;
    private int warnCooldown = 0;

    /** Ticks remaining for each scheduled bell ring; processed every tick regardless of enabled state. */
    private final List<Integer> pendingBellTicks = new ArrayList<>();

    // -----------------------------------------------------------------
    // Public control
    // -----------------------------------------------------------------

    public void toggle(Minecraft client) {
        enabled = !enabled;
        state = State.IDLE;
        waitTicks = 0;
        lockedHotbarSlot = -1;

        if (enabled) {
            sendClientMessage(client, "Crafting Table Auto Sell: ON", ChatFormatting.GREEN);
        } else {
            sendClientMessage(client, "Crafting Table Auto Sell: OFF", ChatFormatting.RED);
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
                // Do nothing further until the player re-toggles the mod.
            }
        }
    }

    public void onServerMessage(Component message) {
        if (!enabled) {
            return;
        }
        String plain = message.getString().toLowerCase(Locale.ROOT).replaceAll("§.", "");
        if (plain.contains(TOO_MANY_ITEMS_TRIGGER)) {
            beginTooManyItemsWait();
        }
    }

    // -----------------------------------------------------------------
    // Cycle start / inventory scan
    // -----------------------------------------------------------------

    private void tryStartCycle(Minecraft client) {
        LocalPlayer player = client.player;

        if (client.screen != null) {
            // Don't fight the player for control of an open screen (their own
            // inventory, a chest, the auction UI, etc.) - just wait.
            return;
        }

        InventoryMenu menu = player.inventoryMenu;
        if (!menu.getCarried().isEmpty()) {
            // Something is already on the cursor. Never touch it - wait until it's clear.
            return;
        }

        Inventory inventory = player.getInventory();
        int craftingTableCount = countCraftingTables(inventory);
        if (craftingTableCount <= 0) {
            stopBecauseInventoryEmpty(client);
            return;
        }

        int selectedHotbarSlot = inventory.selected; // 0-8
        int targetScreenSlot = hotbarScreenSlot(selectedHotbarSlot);

        int sourceInvIndex = findCraftingTableSlot(inventory);
        if (sourceInvIndex < 0) {
            // Shouldn't happen given craftingTableCount > 0, but never guess.
            return;
        }
        int sourceScreenSlot = inventoryIndexToScreenSlot(sourceInvIndex);

        ItemStack sourceStack = menu.getSlot(sourceScreenSlot).getItem();
        if (sourceStack.isEmpty() || sourceStack.getItem() != Items.CRAFTING_TABLE) {
            return; // Stale read (inventory changed between scan and click) - retry next tick.
        }

        boolean prepared;
        if (sourceScreenSlot == targetScreenSlot) {
            prepared = prepareWhenSourceIsHandSlot(client, menu, sourceScreenSlot, sourceStack.getCount());
        } else if (sourceStack.getCount() == 1) {
            prepared = swapIntoHand(client, sourceScreenSlot, selectedHotbarSlot);
        } else {
            prepared = splitOneThenSwapIntoHand(client, menu, sourceScreenSlot, selectedHotbarSlot);
        }

        if (!prepared) {
            warnCannotSplit(client);
            return;
        }

        lockedHotbarSlot = selectedHotbarSlot;

        if (!verifyReadyToSell(menu, inventory, selectedHotbarSlot)) {
            // Final state isn't exactly what we require. Do not sell.
            // Next tick will re-scan from scratch.
            lockedHotbarSlot = -1;
            return;
        }

        sendSellCommand(client);
    }

    // -----------------------------------------------------------------
    // Slot manipulation primitives - all real click-slot packets, never
    // direct ItemStack/inventory edits.
    // -----------------------------------------------------------------

    /** Case: the Crafting Table stack sitting in the target hand slot itself has more than one item. */
    private boolean prepareWhenSourceIsHandSlot(Minecraft client, InventoryMenu menu, int slot, int count) {
        if (count == 1) {
            return true; // Already exactly one in hand - nothing to do.
        }
        int scratch = findEmptySlot(menu, slot, -1);
        if (scratch < 0) {
            return false;
        }

        // 1) Pick up the whole stack onto the cursor.
        click(client, slot, 0, ClickType.PICKUP);
        ItemStack cursor = menu.getCarried();
        if (cursor.isEmpty() || cursor.getItem() != Items.CRAFTING_TABLE || cursor.getCount() != count) {
            return false;
        }

        // 2) Right-click the same (now empty) slot: places exactly 1 back from the cursor.
        click(client, slot, 1, ClickType.PICKUP);
        ItemStack handSlotNow = menu.getSlot(slot).getItem();
        if (handSlotNow.isEmpty() || handSlotNow.getItem() != Items.CRAFTING_TABLE || handSlotNow.getCount() != 1) {
            return false;
        }

        // 3) Dump the remainder (count - 1) into the scratch slot.
        click(client, scratch, 0, ClickType.PICKUP);
        return menu.getCarried().isEmpty();
    }

    /** Case: source already has exactly one Crafting Table - just needs to move into hand. */
    private boolean swapIntoHand(Minecraft client, int sourceSlot, int hotbarIndex) {
        // ClickType.SWAP with button = hotbar index atomically swaps the hovered slot's
        // contents with that hotbar slot, in a single packet, without touching the cursor.
        click(client, sourceSlot, hotbarIndex, ClickType.SWAP);
        return true;
    }

    /** Case: source has more than one Crafting Table - split exactly one off via a scratch slot. */
    private boolean splitOneThenSwapIntoHand(Minecraft client, InventoryMenu menu, int sourceSlot, int hotbarIndex) {
        int scratch = findEmptySlot(menu, sourceSlot, hotbarScreenSlot(hotbarIndex));
        if (scratch < 0) {
            return false;
        }

        int originalCount = menu.getSlot(sourceSlot).getItem().getCount();

        // 1) Pick up the whole source stack onto the cursor.
        click(client, sourceSlot, 0, ClickType.PICKUP);
        ItemStack cursorAfterPickup = menu.getCarried();
        if (cursorAfterPickup.isEmpty() || cursorAfterPickup.getItem() != Items.CRAFTING_TABLE
                || cursorAfterPickup.getCount() != originalCount) {
            return false;
        }

        // 2) Right-click the empty scratch slot: places exactly 1 from the cursor.
        click(client, scratch, 1, ClickType.PICKUP);
        ItemStack scratchStack = menu.getSlot(scratch).getItem();
        if (scratchStack.isEmpty() || scratchStack.getItem() != Items.CRAFTING_TABLE || scratchStack.getCount() != 1) {
            return false;
        }

        // 3) Put the remainder back where it came from.
        click(client, sourceSlot, 0, ClickType.PICKUP);
        if (!menu.getCarried().isEmpty()) {
            return false;
        }
        int expectedRemainder = originalCount - 1;
        if (expectedRemainder > 0) {
            ItemStack sourceAfter = menu.getSlot(sourceSlot).getItem();
            if (sourceAfter.isEmpty() || sourceAfter.getItem() != Items.CRAFTING_TABLE
                    || sourceAfter.getCount() != expectedRemainder) {
                return false;
            }
        }

        // 4) Swap the single Crafting Table from the scratch slot into the target hand slot.
        click(client, scratch, hotbarIndex, ClickType.SWAP);
        return true;
    }

    private boolean verifyReadyToSell(InventoryMenu menu, Inventory inventory, int hotbarIndex) {
        if (!menu.getCarried().isEmpty()) {
            return false;
        }
        ItemStack mainHand = inventory.getItem(hotbarIndex);
        return !mainHand.isEmpty() && mainHand.getItem() == Items.CRAFTING_TABLE && mainHand.getCount() == 1;
    }

    // -----------------------------------------------------------------
    // Selling / server response handling
    // -----------------------------------------------------------------

    private void sendSellCommand(Minecraft client) {
        if (client.player == null || client.player.connection == null) {
            return;
        }
        client.player.connection.sendCommand(SELL_COMMAND);
        state = State.AWAITING_SALE_RESULT;
        waitTicks = SALE_TIMEOUT_TICKS;
    }

    private void handleAwaitingSaleResult(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            emergencyStop(client, "player disappeared mid-sale");
            return;
        }

        ItemStack mainHand = player.getInventory().getItem(lockedHotbarSlot);
        boolean handNoLongerHoldsCraftingTable = mainHand.isEmpty() || mainHand.getItem() != Items.CRAFTING_TABLE;

        if (handNoLongerHoldsCraftingTable) {
            // The single Crafting Table is gone from the hand slot - treat it as a
            // successful sale and move on after a short cooldown.
            state = State.POST_SALE_COOLDOWN;
            waitTicks = POST_SALE_COOLDOWN_TICKS;
            return;
        }

        if (mainHand.getCount() > 1) {
            // Should be impossible given how we prepare the hand slot, but if it
            // ever happens, stop immediately rather than risk an oversell.
            emergencyStop(client, "main hand unexpectedly holds more than 1 Crafting Table");
            return;
        }

        waitTicks--;
        if (waitTicks <= 0) {
            emergencyStop(client, "no confirmation the sale went through in time");
        }
    }

    private void beginTooManyItemsWait() {
        // Only meaningful while a sale is actually in flight.
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
        ItemStack mainHand = player.getInventory().getItem(lockedHotbarSlot);
        if (mainHand.isEmpty() || mainHand.getItem() != Items.CRAFTING_TABLE || mainHand.getCount() != 1) {
            // We can no longer be sure what's in hand - start a fresh cycle rather than guess.
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

    // -----------------------------------------------------------------
    // Stop conditions
    // -----------------------------------------------------------------

    private void stopBecauseInventoryEmpty(Minecraft client) {
        enabled = false;
        state = State.IDLE;
        lockedHotbarSlot = -1;
        sendClientMessage(client, "Crafting Table Auto Sell: OFF (no Crafting Tables left)", ChatFormatting.YELLOW);
        ringBellFourTimes();
    }

    private void emergencyStop(Minecraft client, String reason) {
        enabled = false;
        state = State.STOPPED_EMERGENCY;
        sendClientMessage(client, "Crafting Table Auto Sell: OFF (safety stop - " + reason + ")", ChatFormatting.RED);
    }

    private void ringBellFourTimes() {
        pendingBellTicks.clear();
        for (int i = 0; i < 4; i++) {
            pendingBellTicks.add(i * 10); // roughly half a second apart
        }
    }

    private void processBellQueue(Minecraft client) {
        if (pendingBellTicks.isEmpty() || client.player == null) {
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
        SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(BELL_USE_SOUND_ID);
        if (sound != null) {
            player.playSound(sound, 1.0f, 1.0f);
        } else {
            // Fallback in case the registry lookup ever comes back empty; the exact
            // constant name below may need adjusting for future mapping revisions.
            player.playSound(SoundEvents.BELL_BLOCK_USE, 1.0f, 1.0f);
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private void click(Minecraft client, int slot, int button, ClickType action) {
        client.gameMode.handleInventoryMouseClick(
                client.player.inventoryMenu.containerId,
                slot,
                button,
                action,
                client.player
        );
    }

    private int countCraftingTables(Inventory inventory) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.CRAFTING_TABLE) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int findCraftingTableSlot(Inventory inventory) {
        // Prefer a slot that already holds exactly 1 - cheapest, safest case.
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.CRAFTING_TABLE && stack.getCount() == 1) {
                return i;
            }
        }
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == Items.CRAFTING_TABLE) {
                return i;
            }
        }
        return -1;
    }

    /** Converts an Inventory index (0-8 hotbar, 9-35 main storage) into an InventoryMenu slot id. */
    private int inventoryIndexToScreenSlot(int inventoryIndex) {
        if (inventoryIndex < 9) {
            return hotbarScreenSlot(inventoryIndex);
        }
        return inventoryIndex; // Main storage slots share the same numbering in InventoryMenu.
    }

    private int hotbarScreenSlot(int hotbarIndex) {
        return 36 + hotbarIndex;
    }

    /**
     * Finds an empty InventoryMenu slot to use as scratch space, excluding the given slot
     * id(s) (pass -1 to skip an exclusion). Searches main storage (9-35) before the hotbar
     * (36-44), and never considers armor, offhand, or crafting-grid slots.
     */
    private int findEmptySlot(InventoryMenu menu, int exclude1, int exclude2) {
        for (int slot = 9; slot <= 35; slot++) {
            if (slot == exclude1 || slot == exclude2) continue;
            if (menu.getSlot(slot).getItem().isEmpty()) {
                return slot;
            }
        }
        for (int slot = 36; slot <= 44; slot++) {
            if (slot == exclude1 || slot == exclude2) continue;
            if (menu.getSlot(slot).getItem().isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private void warnCannotSplit(Minecraft client) {
        if (warnCooldown > 0) {
            warnCooldown--;
            return;
        }
        warnCooldown = WARN_MESSAGE_COOLDOWN_TICKS;
        sendClientMessage(client,
                "Crafting Table Auto Sell: waiting for a free inventory slot to split safely...",
                ChatFormatting.GOLD);
    }

    private void sendClientMessage(Minecraft client, String message, ChatFormatting color) {
        if (client.player == null) {
            return;
        }
        // displayClientMessage(..., false) shows a purely local chat line that is
        // never sent to or echoed by the server.
        client.player.displayClientMessage(Component.literal(message).withStyle(color), false);
    }
}

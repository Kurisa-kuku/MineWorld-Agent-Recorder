package com.kurisa.mwarecorder.event;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns raw container slot changes into semantic events.
 *
 * <p>A tick only sees "slot 3 lost two items" and "slot 0 gained one item"; this class gives
 * that a meaning for the dataset:</p>
 * <ul>
 *   <li>{@code item_craft} - a crafting result was taken out of a crafting grid;</li>
 *   <li>{@code item_smelt} - a furnace / smoker / blast furnace produced output;</li>
 *   <li>{@code container_change} - any other slot change inside the container.</li>
 * </ul>
 *
 * <p>Only runs on the logical server side, where the authoritative slot contents live.</p>
 */
public final class ContainerEventBuilder {

    private ContainerEventBuilder() {
    }

    /* =====================================================================================
     * Entry point
     * ===================================================================================== */

    /** Classifies a diff set for the menu the player currently has open. */
    public static void emit(ServerPlayer player, AbstractContainerMenu menu, List<SlotDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return;
        }

        if (menu instanceof CraftingMenu) {
            classifyCrafting(player, menu, diffs, "minecraft:crafting_table");
            return;
        }

        if (menu instanceof InventoryMenu) {
            // The player inventory only records its 2x2 crafting grid; ordinary item moves
            // are already visible in state.jsonl and would only add noise.
            classifyPlayerCrafting(player, menu, diffs);
            return;
        }

        if (menu instanceof FurnaceMenu) {
            classifySmelting(player, menu, diffs, "minecraft:furnace");
            return;
        }

        if (menu instanceof SmokerMenu) {
            classifySmelting(player, menu, diffs, "minecraft:smoker");
            return;
        }

        if (menu instanceof BlastFurnaceMenu) {
            classifySmelting(player, menu, diffs, "minecraft:blast_furnace");
            return;
        }

        // Chests, barrels, hoppers, brewing stands, anvils, ...
        emitContainerChange(player, menu, diffs);
    }

    /* =====================================================================================
     * Crafting (crafting table, 3x3)
     *
     * Slot layout:
     *   0        output
     *   1..9     input grid
     *   10..45   player inventory
     *
     * Semantics:
     *   - output slot gains: the recipe preview appeared, no craft happened yet;
     *   - output slot loses: the player took the result, this is the actual craft.
     * ===================================================================================== */

    private static void classifyCrafting(
            ServerPlayer player,
            AbstractContainerMenu menu,
            List<SlotDiff> diffs,
            String station) {

        SlotDiff outputDiff = findDiff(diffs, 0);

        List<SlotDiff> inputLosses = new ArrayList<>();
        for (SlotDiff diff : diffs) {
            if (diff.slot() >= 1 && diff.slot() <= 9 && diff.isLoss()) {
                inputLosses.add(diff);
            }
        }

        if (outputDiff != null && outputDiff.isLoss()) {
            int craftCount = -outputDiff.delta();
            String outputType = BuiltInRegistries.ITEM
                    .getKey(outputDiff.before().getItem())
                    .toString();

            emitCraftEvent(player, station, outputType, craftCount, inputLosses);

            // Changes outside the craft itself, for example putting leftover ingredients back.
            List<SlotDiff> others = new ArrayList<>();
            for (SlotDiff diff : diffs) {
                if (diff != outputDiff && !inputLosses.contains(diff)) {
                    others.add(diff);
                }
            }
            if (!others.isEmpty()) {
                emitContainerChange(player, menu, others);
            }
        } else {
            // Preview only, or plain input shuffling.
            emitContainerChange(player, menu, diffs);
        }
    }

    /* =====================================================================================
     * Crafting (player inventory, 2x2)
     *
     * Slot layout:
     *   0        output
     *   1..4     input grid
     *   5..8     armour
     *   9..44    inventory
     * ===================================================================================== */

    private static void classifyPlayerCrafting(
            ServerPlayer player,
            AbstractContainerMenu menu,
            List<SlotDiff> diffs) {

        SlotDiff outputDiff = findDiff(diffs, 0);

        List<SlotDiff> inputLosses = new ArrayList<>();
        for (SlotDiff diff : diffs) {
            if (diff.slot() >= 1 && diff.slot() <= 4 && diff.isLoss()) {
                inputLosses.add(diff);
            }
        }

        if (outputDiff != null && outputDiff.isLoss()) {
            int craftCount = -outputDiff.delta();
            String outputType = BuiltInRegistries.ITEM
                    .getKey(outputDiff.before().getItem())
                    .toString();

            emitCraftEvent(player, "minecraft:player", outputType, craftCount, inputLosses);

            List<SlotDiff> others = new ArrayList<>();
            for (SlotDiff diff : diffs) {
                if (diff != outputDiff && !inputLosses.contains(diff)) {
                    others.add(diff);
                }
            }
            if (!others.isEmpty()) {
                emitContainerChange(player, menu, others);
            }
        }
        // Ordinary inventory movement is not recorded here.
    }

    /* =====================================================================================
     * Smelting
     *
     * Slot layout:
     *   0        input
     *   1        fuel
     *   2        output
     *   3..38    player inventory
     * ===================================================================================== */

    private static void classifySmelting(
            ServerPlayer player,
            AbstractContainerMenu menu,
            List<SlotDiff> diffs,
            String station) {

        SlotDiff outputDiff = findDiff(diffs, 2);

        if (outputDiff != null && outputDiff.isGain()) {
            List<SlotDiff> inputDiffs = new ArrayList<>();
            SlotDiff inputDiff = findDiff(diffs, 0);
            if (inputDiff != null && inputDiff.isLoss()) {
                inputDiffs.add(inputDiff);
            }

            emitSmeltEvent(player, station, outputDiff, inputDiffs);

            List<SlotDiff> otherDiffs = new ArrayList<>();
            for (SlotDiff diff : diffs) {
                if (diff != outputDiff && !inputDiffs.contains(diff)) {
                    otherDiffs.add(diff);
                }
            }

            if (!otherDiffs.isEmpty()) {
                emitContainerChange(player, menu, otherDiffs);
            }
        } else {
            emitContainerChange(player, menu, diffs);
        }
    }

    /* =====================================================================================
     * Event construction
     * ===================================================================================== */

    private static void emitCraftEvent(
            ServerPlayer player,
            String station,
            String outputType,
            int outputCount,
            List<SlotDiff> inputDiffs) {

        JsonObject event = new JsonObject();
        event.addProperty("type", "item_craft");
        event.addProperty("station", station);

        JsonArray outputs = new JsonArray();
        JsonObject output = new JsonObject();
        output.addProperty("type", outputType);
        output.addProperty("count", outputCount);
        outputs.add(output);
        event.add("outputs", outputs);

        JsonArray inputs = new JsonArray();
        for (SlotDiff diff : inputDiffs) {
            JsonObject input = new JsonObject();
            input.addProperty(
                    "type",
                    BuiltInRegistries.ITEM.getKey(diff.before().getItem()).toString());
            input.addProperty("count", -diff.delta());
            inputs.add(input);
        }
        event.add("inputs", inputs);

        EventRecorder.queue(event, player.level().getGameTime());
    }

    private static void emitSmeltEvent(
            ServerPlayer player,
            String station,
            SlotDiff outputDiff,
            List<SlotDiff> inputDiffs) {

        JsonObject event = new JsonObject();
        event.addProperty("type", "item_smelt");
        event.addProperty("station", station);

        JsonArray outputs = new JsonArray();
        JsonObject output = new JsonObject();
        output.addProperty(
                "type",
                BuiltInRegistries.ITEM.getKey(outputDiff.after().getItem()).toString());
        output.addProperty("count", outputDiff.afterCount());
        outputs.add(output);
        event.add("outputs", outputs);

        JsonArray inputs = new JsonArray();
        for (SlotDiff diff : inputDiffs) {
            JsonObject input = new JsonObject();
            input.addProperty(
                    "type",
                    BuiltInRegistries.ITEM.getKey(diff.before().getItem()).toString());
            input.addProperty("count", -diff.delta());
            inputs.add(input);
        }
        event.add("inputs", inputs);

        EventRecorder.queue(event, player.level().getGameTime());
    }

    private static void emitContainerChange(
            ServerPlayer player,
            AbstractContainerMenu menu,
            List<SlotDiff> diffs) {

        if (diffs.isEmpty()) {
            return;
        }

        JsonObject event = new JsonObject();
        event.addProperty("type", "container_change");

        MenuType<?> type = menu.getType();
        if (type != null) {
            ResourceLocation key = BuiltInRegistries.MENU.getKey(type);
            if (key != null) {
                event.addProperty("container_type", key.toString());
            }
        }

        JsonArray changes = new JsonArray();

        // The last 36 slots of every menu belong to the player inventory.
        int containerSlotCount = menu.slots.size() - 36;

        for (SlotDiff diff : diffs) {
            if (diff.slot() >= containerSlotCount) {
                continue;
            }

            JsonObject change = new JsonObject();
            change.addProperty("slot", diff.slot());

            if (!diff.before().isEmpty()) {
                JsonObject before = new JsonObject();
                before.addProperty(
                        "type",
                        BuiltInRegistries.ITEM.getKey(diff.before().getItem()).toString());
                before.addProperty("count", diff.beforeCount());
                change.add("before", before);
            }

            if (!diff.after().isEmpty()) {
                JsonObject after = new JsonObject();
                after.addProperty(
                        "type",
                        BuiltInRegistries.ITEM.getKey(diff.after().getItem()).toString());
                after.addProperty("count", diff.afterCount());
                change.add("after", after);
            }

            changes.add(change);
        }

        if (changes.isEmpty()) {
            return;
        }

        event.add("changes", changes);
        EventRecorder.queue(event, player.level().getGameTime());
    }

    /* =====================================================================================
     * Helpers
     * ===================================================================================== */

    private static SlotDiff findDiff(List<SlotDiff> diffs, int slot) {
        for (SlotDiff diff : diffs) {
            if (diff.slot() == slot) {
                return diff;
            }
        }
        return null;
    }
}

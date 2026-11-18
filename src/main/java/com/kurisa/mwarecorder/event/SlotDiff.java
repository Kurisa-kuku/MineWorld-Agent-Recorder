package com.kurisa.mwarecorder.event;

import net.minecraft.world.item.ItemStack;

/**
 * One slot change inside a container menu.
 *
 * @param slot   index of the slot in the menu
 * @param before contents before the tick (a copy)
 * @param after  contents after the tick (a copy)
 */
public record SlotDiff(int slot, ItemStack before, ItemStack after) {

    public int beforeCount() {
        return before.getCount();
    }

    public int afterCount() {
        return after.getCount();
    }

    /** Positive when the slot gained items, negative when it lost items. */
    public int delta() {
        return afterCount() - beforeCount();
    }

    public boolean isGain() {
        return delta() > 0;
    }

    public boolean isLoss() {
        return delta() < 0;
    }
}

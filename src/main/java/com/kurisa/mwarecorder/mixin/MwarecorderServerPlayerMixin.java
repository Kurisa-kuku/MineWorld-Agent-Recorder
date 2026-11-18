package com.kurisa.mwarecorder.mixin;

import com.kurisa.mwarecorder.event.ContainerEventBuilder;
import com.kurisa.mwarecorder.event.SlotDiff;
import com.kurisa.mwarecorder.recorder.EpisodeManager;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects container slot changes on the logical server.
 *
 * <p>At the end of every {@code ServerPlayer} tick the current menu contents are compared with
 * the snapshot from the previous tick. When the menu instance itself changed (a container was
 * opened or closed) the snapshot is simply replaced, so switching screens does not produce a
 * flood of phony diffs.</p>
 *
 * <p>This mixin lives in the common mixin set: on a dedicated server no episode is ever
 * active, so the diff loop returns immediately.</p>
 */
@Mixin(ServerPlayer.class)
public class MwarecorderServerPlayerMixin {

    /** Slot contents observed on the previous tick. */
    @Unique
    private List<ItemStack> mwarecorder$lastSlots;

    /** Menu instance observed on the previous tick. */
    @Unique
    private AbstractContainerMenu mwarecorder$lastMenu;

    @Inject(method = "tick", at = @At("TAIL"))
    private void mwarecorder$captureContainerChanges(CallbackInfo callback) {
        ServerPlayer self = (ServerPlayer) (Object) this;

        if (!EpisodeManager.isActive()) {
            mwarecorder$lastSlots = null;
            mwarecorder$lastMenu = null;
            return;
        }

        AbstractContainerMenu menu = self.containerMenu;

        if (menu == null) {
            mwarecorder$lastSlots = null;
            mwarecorder$lastMenu = null;
            return;
        }

        // A different menu means the player opened or closed a container; start from a fresh
        // snapshot instead of diffing across two unrelated inventories.
        if (mwarecorder$lastMenu != menu) {
            mwarecorder$lastMenu = menu;
            mwarecorder$lastSlots = snapshot(menu);
            return;
        }

        List<ItemStack> current = snapshot(menu);

        if (mwarecorder$lastSlots != null) {
            List<SlotDiff> diffs = computeDiff(mwarecorder$lastSlots, current);
            if (!diffs.isEmpty()) {
                ContainerEventBuilder.emit(self, menu, diffs);
            }
        }

        mwarecorder$lastSlots = current;
    }

    /* =====================================================================================
     * Helpers
     * ===================================================================================== */

    @Unique
    private static List<ItemStack> snapshot(AbstractContainerMenu menu) {
        int size = menu.slots.size();
        List<ItemStack> result = new ArrayList<>(size);

        for (int i = 0; i < size; i++) {
            Slot slot = menu.slots.get(i);
            result.add(slot.getItem().copy());
        }

        return result;
    }

    @Unique
    private static List<SlotDiff> computeDiff(
            List<ItemStack> before, List<ItemStack> after) {

        int size = Math.min(before.size(), after.size());
        List<SlotDiff> diffs = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            ItemStack previous = before.get(i);
            ItemStack current = after.get(i);

            if (ItemStack.matches(previous, current)
                    && previous.getCount() == current.getCount()) {
                continue;
            }

            diffs.add(new SlotDiff(i, previous, current));
        }

        return diffs;
    }
}

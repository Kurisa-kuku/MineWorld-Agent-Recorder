package com.kurisa.mwarecorder.mixin;

import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.event.EventRecorder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records item pickups.
 *
 * <p>{@code ItemEntity.playerTouch} runs on the server. A successful pick-up removes the item
 * entity, which is used here as the success signal - the count is captured before the removal
 * because the stack is already empty afterwards.</p>
 *
 * <p>Schema:</p>
 * <pre>
 * {
 *   "type": "item_pickup",
 *   "player": { "uuid": "..." },
 *   "item":   { "type": "minecraft:dirt", "count": 1 },
 *   "entity": { "id": 496, "x": .., "y": .., "z": .. }
 * }
 * </pre>
 */
@Mixin(ItemEntity.class)
public class MwarecorderItemEntityMixin {

    @Unique
    private int mwarecorder$beforeCount;

    @Unique
    private String mwarecorder$itemId;

    /* =====================================================================================
     * Pickup - start
     * ===================================================================================== */

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void mwarecorder$pickupStart(Player player, CallbackInfo callback) {
        ItemEntity self = (ItemEntity) (Object) this;

        // playerTouch() performs the actual pickup on the logical server only.
        if (self.level().isClientSide()) {
            return;
        }

        ItemStack stack = self.getItem();

        mwarecorder$beforeCount = stack.getCount();
        mwarecorder$itemId = stack.isEmpty()
                ? null
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /* =====================================================================================
     * Pickup - end
     * ===================================================================================== */

    @Inject(method = "playerTouch", at = @At("TAIL"))
    private void mwarecorder$pickupEnd(Player player, CallbackInfo callback) {
        ItemEntity self = (ItemEntity) (Object) this;

        if (self.level().isClientSide() || mwarecorder$itemId == null) {
            return;
        }

        // A full pick-up calls discard(), so removal means the pickup succeeded.
        if (!self.isRemoved()) {
            return;
        }

        JsonObject event = new JsonObject();
        event.addProperty("type", "item_pickup");

        JsonObject playerJson = new JsonObject();
        playerJson.addProperty("uuid", player.getUUID().toString());
        event.add("player", playerJson);

        JsonObject itemJson = new JsonObject();
        itemJson.addProperty("type", mwarecorder$itemId);
        itemJson.addProperty("count", mwarecorder$beforeCount);
        event.add("item", itemJson);

        JsonObject entityJson = new JsonObject();
        entityJson.addProperty("id", self.getId());
        entityJson.addProperty("x", self.getX());
        entityJson.addProperty("y", self.getY());
        entityJson.addProperty("z", self.getZ());
        event.add("entity", entityJson);

        EventRecorder.queue(event, self.level().getGameTime());
    }
}

package com.kurisa.mwarecorder.mixin;

import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.event.EventRecorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records block and entity interactions performed by the local player.
 *
 * <p>{@code MultiPlayerGameMode} is the single place where the client sends interaction
 * packets, so hooking it gives exact, client-authoritative semantics: an event is only emitted
 * when the interaction actually succeeded.</p>
 *
 * <p>Events produced here:</p>
 * <ul>
 *   <li>{@code block_place} - right click placed a different block than before;</li>
 *   <li>{@code block_interact} - right click used a block without replacing it (doors, chests,
 *       buttons, ...);</li>
 *   <li>{@code item_use} - an item was used successfully (eating, drinking, shooting, ...);</li>
 *   <li>{@code entity_attack} - left click on an entity;</li>
 *   <li>{@code entity_interact} - right click on an entity (trading, shearing, mounting, ...).</li>
 * </ul>
 */
@Mixin(MultiPlayerGameMode.class)
public class MwarecorderGameModeMixin {

    /* =====================================================================================
     * State captured at the start of a block interaction
     * ===================================================================================== */

    /** Position where a new block would be placed. */
    @Unique
    private BlockPos mwarecorder$placePos;

    /** State of {@link #mwarecorder$placePos} before the interaction. */
    @Unique
    private BlockState mwarecorder$oldState;

    /** Position of the block that was clicked. */
    @Unique
    private BlockPos mwarecorder$clickedPos;

    /** Item stack in hand before {@code useItem} consumed part of it. */
    @Unique
    private ItemStack mwarecorder$usedItemStack;

    /* =====================================================================================
     * Block interaction - start
     * ===================================================================================== */

    @Inject(method = "useItemOn", at = @At("HEAD"))
    private void mwarecorder$captureBlockInteractionStart(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> callback) {

        Minecraft minecraft = Minecraft.getInstance();

        if (player != minecraft.player || minecraft.level == null) {
            return;
        }

        // The clicked block itself, used for block_interact.
        mwarecorder$clickedPos = hitResult.getBlockPos();

        // The position a placed block would occupy, used for block_place.
        mwarecorder$placePos = hitResult.getBlockPos().relative(hitResult.getDirection());
        mwarecorder$oldState = minecraft.level.getBlockState(mwarecorder$placePos);
    }

    /* =====================================================================================
     * Block interaction - end
     *
     * If the block type at the placement position changed, the interaction placed a block.
     * Otherwise it interacted with the clicked block (opening a door, a chest, ...).
     * ===================================================================================== */

    @Inject(method = "useItemOn", at = @At("RETURN"))
    private void mwarecorder$captureBlockInteractionEnd(
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            CallbackInfoReturnable<InteractionResult> callback) {

        Minecraft minecraft = Minecraft.getInstance();

        if (player != minecraft.player
                || minecraft.level == null
                || mwarecorder$placePos == null
                || mwarecorder$oldState == null
                || mwarecorder$clickedPos == null) {
            return;
        }

        InteractionResult result = callback.getReturnValue();

        if (result == null || !result.consumesAction()) {
            clearBlockInteractionState();
            return;
        }

        BlockState newPlaceState = minecraft.level.getBlockState(mwarecorder$placePos);
        boolean typeChanged = !newPlaceState.getBlock().equals(mwarecorder$oldState.getBlock());

        JsonObject event = new JsonObject();

        if (typeChanged) {
            event.addProperty("type", "block_place");
            EventRecorder.addBlockInfo(
                    event,
                    mwarecorder$placePos.getX(),
                    mwarecorder$placePos.getY(),
                    mwarecorder$placePos.getZ(),
                    BuiltInRegistries.BLOCK.getKey(newPlaceState.getBlock()).toString());
        } else {
            event.addProperty("type", "block_interact");

            BlockState clickedState = minecraft.level.getBlockState(mwarecorder$clickedPos);
            EventRecorder.addBlockInfo(
                    event,
                    mwarecorder$clickedPos.getX(),
                    mwarecorder$clickedPos.getY(),
                    mwarecorder$clickedPos.getZ(),
                    BuiltInRegistries.BLOCK.getKey(clickedState.getBlock()).toString());

            EventRecorder.addItemInfo(event, player.getItemInHand(hand));
            event.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
        }

        EventRecorder.queue(event, minecraft.level.getGameTime());
        clearBlockInteractionState();
    }

    @Unique
    private void clearBlockInteractionState() {
        mwarecorder$placePos = null;
        mwarecorder$oldState = null;
        mwarecorder$clickedPos = null;
    }

    /* =====================================================================================
     * Item use
     *
     * The stack is copied at HEAD because using an item may consume it before RETURN.
     * ===================================================================================== */

    @Inject(method = "useItem", at = @At("HEAD"))
    private void mwarecorder$captureItemUseStart(
            net.minecraft.world.entity.player.Player player,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> callback) {

        Minecraft minecraft = Minecraft.getInstance();

        if (player != minecraft.player) {
            mwarecorder$usedItemStack = null;
            return;
        }

        mwarecorder$usedItemStack = player.getItemInHand(hand).copy();
    }

    @Inject(method = "useItem", at = @At("RETURN"))
    private void mwarecorder$captureItemUseEnd(
            net.minecraft.world.entity.player.Player player,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> callback) {

        Minecraft minecraft = Minecraft.getInstance();

        if (player != minecraft.player || minecraft.level == null) {
            mwarecorder$usedItemStack = null;
            return;
        }

        InteractionResult result = callback.getReturnValue();

        if (result == null
                || !result.consumesAction()
                || mwarecorder$usedItemStack == null
                || mwarecorder$usedItemStack.isEmpty()) {
            mwarecorder$usedItemStack = null;
            return;
        }

        JsonObject event = new JsonObject();
        event.addProperty("type", "item_use");
        event.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
        EventRecorder.addItemInfo(event, mwarecorder$usedItemStack);

        EventRecorder.queue(event, minecraft.level.getGameTime());
        mwarecorder$usedItemStack = null;
    }

    /* =====================================================================================
     * Entity attack
     * ===================================================================================== */

    @Inject(method = "attack", at = @At("HEAD"))
    private void mwarecorder$captureEntityAttack(
            net.minecraft.world.entity.player.Player player,
            Entity target,
            CallbackInfo callback) {

        Minecraft minecraft = Minecraft.getInstance();

        if (player != minecraft.player || minecraft.level == null) {
            return;
        }

        JsonObject event = new JsonObject();
        event.addProperty("type", "entity_attack");

        JsonObject attacker = new JsonObject();
        attacker.addProperty("uuid", player.getUUID().toString());
        event.add("attacker", attacker);

        EventRecorder.addEntityInfo(event, target);
        EventRecorder.queue(event, minecraft.level.getGameTime());
    }

    /* =====================================================================================
     * Entity interaction (right click on an entity)
     * ===================================================================================== */

    @Inject(method = "interact", at = @At("RETURN"))
    private void mwarecorder$captureEntityInteract(
            net.minecraft.world.entity.player.Player player,
            Entity target,
            InteractionHand hand,
            CallbackInfoReturnable<InteractionResult> callback) {

        Minecraft minecraft = Minecraft.getInstance();

        if (player != minecraft.player || minecraft.level == null) {
            return;
        }

        InteractionResult result = callback.getReturnValue();
        if (result == null || !result.consumesAction()) {
            return;
        }

        JsonObject event = new JsonObject();
        event.addProperty("type", "entity_interact");
        event.addProperty("hand", hand == InteractionHand.MAIN_HAND ? "main" : "off");
        EventRecorder.addEntityInfo(event, target);
        EventRecorder.queue(event, minecraft.level.getGameTime());
    }
}

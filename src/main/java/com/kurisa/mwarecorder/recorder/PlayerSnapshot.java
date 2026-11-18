package com.kurisa.mwarecorder.recorder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.config.MwarecorderConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the per-tick observation of the local player.
 *
 * <p>The snapshot is the "state" half of the dataset: what the agent could perceive at a
 * given tick. It intentionally favours compact, numerically stable fields over a full world
 * dump, because it is written twenty times per second.</p>
 *
 * <p>Schema (fields may be omitted when empty):</p>
 * <pre>
 * {
 *   "position":        { "x":, "y":, "z": },
 *   "rotation":        { "yaw":, "pitch": },
 *   "look_direction":  { "x":, "y":, "z": },
 *   "velocity":        { "x":, "y":, "z": },
 *   "health": 20.0, "max_health": 20.0, "absorption": 0.0,
 *   "food": 20, "saturation": 5.0, "air": 300, "max_air": 300,
 *   "xp_level": 0, "xp_progress": 0.0,
 *   "sprinting": false, "sneaking": false, "on_ground": true,
 *   "swimming": false, "in_water": false, "on_fire": false, "flying": false,
 *   "portal_cooldown": 0,
 *   "selected_slot": 0,
 *   "held_item":    { "type":, "count":, "damage": },
 *   "offhand_item": { "type":, "count":, "damage": },
 *   "inventory":    [ { "slot":, "type":, "count": }, ... ],   // optional
 *   "looking_at":   { "kind": "block" | "entity", ... },
 *   "nearby_entities": [ ... ],
 *   "environment":  { "biome":, "block_light":, "sky_light":, "day_time":,
 *                     "raining":, "thundering":, "rain_level":,
 *                     "difficulty":, "game_mode": },              // optional
 *   "dimension": "minecraft:overworld",
 *   "dimension_scale": 1.0
 * }
 * </pre>
 */
public final class PlayerSnapshot {

    private PlayerSnapshot() {
    }

    /* =====================================================================================
     * Entry point
     * ===================================================================================== */

    /** Captures one observation for the current client tick. */
    public static JsonObject capture(Minecraft minecraft) {
        JsonObject json = new JsonObject();

        if (minecraft.player == null || minecraft.level == null) {
            return json;
        }

        MwarecorderConfig cfg = MwarecorderConfig.get();
        LocalPlayer player = minecraft.player;

        addTransform(json, player);
        addVitals(json, player);
        addEnvironment(json, minecraft, player, cfg.includeEnvironment);
        addHeldItems(json, player);

        if (cfg.includeInventory) {
            addInventory(json, player);
        }

        addLookingAt(json, player, cfg.lookReach);
        addNearbyEntities(json, player, cfg.nearbyRadius, cfg.nearbyMax);

        json.addProperty("dimension", minecraft.level.dimension().location().toString());
        json.addProperty("dimension_scale", minecraft.level.dimensionType().coordinateScale());

        return json;
    }

    /* =====================================================================================
     * Transform
     * ===================================================================================== */

    private static void addTransform(JsonObject json, LocalPlayer player) {
        JsonObject position = new JsonObject();
        position.addProperty("x", player.getX());
        position.addProperty("y", player.getY());
        position.addProperty("z", player.getZ());
        json.add("position", position);

        JsonObject rotation = new JsonObject();
        rotation.addProperty("yaw", player.getYRot());
        rotation.addProperty("pitch", player.getXRot());
        json.add("rotation", rotation);

        Vec3 look = player.getViewVector(1.0F);
        JsonObject lookDirection = new JsonObject();
        lookDirection.addProperty("x", look.x);
        lookDirection.addProperty("y", look.y);
        lookDirection.addProperty("z", look.z);
        json.add("look_direction", lookDirection);

        Vec3 velocity = player.getDeltaMovement();
        JsonObject velocityJson = new JsonObject();
        velocityJson.addProperty("x", velocity.x);
        velocityJson.addProperty("y", velocity.y);
        velocityJson.addProperty("z", velocity.z);
        json.add("velocity", velocityJson);
    }

    /* =====================================================================================
     * Vitals and movement flags
     * ===================================================================================== */

    private static void addVitals(JsonObject json, LocalPlayer player) {
        json.addProperty("health", player.getHealth());
        json.addProperty("max_health", player.getMaxHealth());
        json.addProperty("absorption", player.getAbsorptionAmount());

        json.addProperty("food", player.getFoodData().getFoodLevel());
        json.addProperty("saturation", player.getFoodData().getSaturationLevel());

        json.addProperty("air", player.getAirSupply());
        json.addProperty("max_air", player.getMaxAirSupply());

        json.addProperty("xp_level", player.experienceLevel);
        json.addProperty("xp_progress", player.experienceProgress);

        json.addProperty("sprinting", player.isSprinting());
        json.addProperty("sneaking", player.isShiftKeyDown());
        json.addProperty("on_ground", player.onGround());
        json.addProperty("swimming", player.isSwimming());
        json.addProperty("in_water", player.isInWater());
        json.addProperty("on_fire", player.isOnFire());
        json.addProperty("flying", player.getAbilities().flying);
        json.addProperty("fall_distance", player.fallDistance);
        json.addProperty("portal_cooldown", player.getPortalCooldown());

        json.addProperty("selected_slot", player.getInventory().getSelectedSlot());
    }

    /* =====================================================================================
     * Environment
     * ===================================================================================== */

    private static void addEnvironment(
            JsonObject json,
            Minecraft minecraft,
            LocalPlayer player,
            boolean enabled) {

        if (!enabled) {
            return;
        }

        JsonObject environment = new JsonObject();

        BlockPos pos = player.blockPosition();
        BlockPos eyePos = BlockPos.containing(player.getEyePosition());

        environment.addProperty("biome", biomeId(minecraft, pos));
        environment.addProperty("block_light", minecraft.level.getBrightness(LightLayer.BLOCK, pos));
        environment.addProperty("sky_light", minecraft.level.getBrightness(LightLayer.SKY, pos));
        environment.addProperty("eye_block_light", minecraft.level.getBrightness(LightLayer.BLOCK, eyePos));

        environment.addProperty("day_time", minecraft.level.getDayTime());
        environment.addProperty("raining", minecraft.level.isRaining());
        environment.addProperty("thundering", minecraft.level.isThundering());
        environment.addProperty("rain_level", minecraft.level.getRainLevel(1.0F));

        environment.addProperty("difficulty", minecraft.level.getDifficulty().getKey());
        if (minecraft.gameMode != null) {
            environment.addProperty("game_mode", minecraft.gameMode.getPlayerMode().getName());
        }

        json.add("environment", environment);
    }

    private static String biomeId(Minecraft minecraft, BlockPos pos) {
        try {
            return minecraft.level
                    .getBiome(pos)
                    .unwrapKey()
                    .map(ResourceKey::location)
                    .map(Object::toString)
                    .orElse("unknown");
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /* =====================================================================================
     * Items
     * ===================================================================================== */

    private static void addHeldItems(JsonObject json, LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (!main.isEmpty()) {
            json.add("held_item", itemToJson(main));
        }

        ItemStack off = player.getOffhandItem();
        if (!off.isEmpty()) {
            json.add("offhand_item", itemToJson(off));
        }
    }

    /** Optional full inventory dump; off by default because it is large. */
    private static void addInventory(JsonObject json, LocalPlayer player) {
        JsonArray inventory = new JsonArray();
        int size = player.getInventory().getContainerSize();

        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject entry = itemToJson(stack);
            entry.addProperty("slot", slot);
            inventory.add(entry);
        }

        if (!inventory.isEmpty()) {
            json.add("inventory", inventory);
        }
    }

    private static JsonObject itemToJson(ItemStack stack) {
        JsonObject item = new JsonObject();
        item.addProperty("type", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount());

        if (stack.isDamageableItem()) {
            item.addProperty("damage", stack.getDamageValue());
            item.addProperty("max_damage", stack.getMaxDamage());
        }

        return item;
    }

    /* =====================================================================================
     * Looking at
     * ===================================================================================== */

    private static void addLookingAt(JsonObject json, LocalPlayer player, double reach) {
        Vec3 eyePos = player.getEyePosition(1.0F);
        Vec3 lookVec = player.getViewVector(1.0F);
        Vec3 endPos = eyePos.add(lookVec.scale(reach));

        BlockHitResult blockHit = player.level().clip(new ClipContext(
                eyePos,
                endPos,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));

        AABB searchBox = new AABB(eyePos, endPos).inflate(1.0D);
        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                player,
                eyePos,
                endPos,
                searchBox,
                entity -> !entity.isSpectator() && entity.isPickable() && entity != player,
                reach * reach);

        double blockDistance = blockHit.getType() == HitResult.Type.MISS
                ? Double.MAX_VALUE
                : blockHit.getLocation().distanceToSqr(eyePos);

        double entityDistance = entityHit == null
                ? Double.MAX_VALUE
                : entityHit.getLocation().distanceToSqr(eyePos);

        if (blockDistance < entityDistance && blockHit.getType() != HitResult.Type.MISS) {
            json.add("looking_at", blockHitToJson(player, blockHit, eyePos));
        } else if (entityHit != null) {
            json.add("looking_at", entityLookToJson(entityHit, eyePos, player));
        }
    }

    private static JsonObject blockHitToJson(
            LocalPlayer player, BlockHitResult hit, Vec3 eyePos) {

        JsonObject lookAt = new JsonObject();
        lookAt.addProperty("kind", "block");

        BlockPos hitPos = hit.getBlockPos();
        JsonObject block = new JsonObject();
        block.addProperty("x", hitPos.getX());
        block.addProperty("y", hitPos.getY());
        block.addProperty("z", hitPos.getZ());
        block.addProperty(
                "type",
                BuiltInRegistries.BLOCK
                        .getKey(player.level().getBlockState(hitPos).getBlock())
                        .toString());

        lookAt.add("block", block);
        lookAt.addProperty("distance", Math.sqrt(hit.getLocation().distanceToSqr(eyePos)));
        lookAt.addProperty("hit_face", hit.getDirection().getName());

        return lookAt;
    }

    private static JsonObject entityLookToJson(
            EntityHitResult hit, Vec3 eyePos, LocalPlayer player) {

        JsonObject lookAt = new JsonObject();
        lookAt.addProperty("kind", "entity");
        lookAt.add(
                "entity",
                entityToJson(hit.getEntity(), player, true));
        lookAt.addProperty("distance", Math.sqrt(hit.getLocation().distanceToSqr(eyePos)));

        return lookAt;
    }

    /* =====================================================================================
     * Nearby entities
     * ===================================================================================== */

    private static void addNearbyEntities(
            JsonObject json,
            LocalPlayer player,
            double radius,
            int maxCount) {

        if (maxCount <= 0) {
            return;
        }

        AABB area = player.getBoundingBox().inflate(radius);

        List<Entity> entities = player.level().getEntities(
                player,
                area,
                entity -> entity != player && !entity.isSpectator());

        if (entities.isEmpty()) {
            return;
        }

        // Nearest first; the dataset keeps a fixed number of slots per tick.
        List<Entity> sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparingDouble(player::distanceToSqr));

        int limit = Math.min(maxCount, sorted.size());

        JsonArray array = new JsonArray();
        for (int i = 0; i < limit; i++) {
            array.add(entityToJson(sorted.get(i), player, false));
        }

        json.add("nearby_entities", array);
    }

    /* =====================================================================================
     * Entity serialisation
     * ===================================================================================== */

    /**
     * Serialises an entity.
     *
     * @param full {@code true} for the "looking at" target (includes the UUID), {@code false}
     *             for the nearby-entity list where the entity id is enough within an episode
     */
    private static JsonObject entityToJson(Entity entity, LocalPlayer player, boolean full) {
        JsonObject json = new JsonObject();

        json.addProperty("id", entity.getId());
        json.addProperty("type", entityTypeId(entity));

        if (full) {
            json.addProperty("uuid", entity.getUUID().toString());
        }

        // Absolute position: needed by the area filter and useful to reconstruct the scene.
        JsonObject position = new JsonObject();
        position.addProperty("x", entity.getX());
        position.addProperty("y", entity.getY());
        position.addProperty("z", entity.getZ());
        json.add("position", position);

        JsonObject relative = new JsonObject();
        relative.addProperty("x", entity.getX() - player.getX());
        relative.addProperty("y", entity.getY() - player.getY());
        relative.addProperty("z", entity.getZ() - player.getZ());
        json.add("relative_position", relative);

        json.addProperty("distance", Math.sqrt(player.distanceToSqr(entity)));
        json.addProperty("yaw", entity.getYRot());
        json.addProperty("pitch", entity.getXRot());

        if (entity instanceof ItemEntity itemEntity) {
            ItemStack stack = itemEntity.getItem();
            if (!stack.isEmpty()) {
                json.addProperty("item_type", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                json.addProperty("item_count", stack.getCount());
            }
        }

        if (entity instanceof Projectile projectile) {
            Entity owner = projectile.getOwner();
            if (owner != null) {
                json.addProperty("owner_uuid", owner.getUUID().toString());
                json.addProperty("owner_type", entityTypeId(owner));
            }
        }

        if (entity instanceof AbstractArrow arrow) {
            json.addProperty("is_critical", arrow.isCritArrow());
        }

        if (entity instanceof LivingEntity living) {
            json.addProperty("health", living.getHealth());
            json.addProperty("max_health", living.getMaxHealth());
            json.addProperty("is_alive", living.isAlive());

            if (full) {
                json.addProperty("hurt_time", living.hurtTime);
            }
        }

        if (entity instanceof Player otherPlayer) {
            json.addProperty("is_player", true);
            if (full) {
                json.addProperty("name", otherPlayer.getGameProfile().getName());
                json.addProperty("sneaking", otherPlayer.isShiftKeyDown());
                json.addProperty("sprinting", otherPlayer.isSprinting());
            }
        }

        return json;
    }

    private static String entityTypeId(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? "unknown" : key.toString();
    }
}

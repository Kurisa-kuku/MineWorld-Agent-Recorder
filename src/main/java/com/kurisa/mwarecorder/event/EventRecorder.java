package com.kurisa.mwarecorder.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.export.JsonExporter;
import com.kurisa.mwarecorder.recorder.DimensionTracker;
import com.kurisa.mwarecorder.recorder.EpisodeManager;
import com.kurisa.mwarecorder.recorder.RecordingStats;
import com.kurisa.mwarecorder.recorder.TickRecorder;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects discrete game events and writes them with the tick they belong to.
 *
 * <p>Events are produced from two threads: client hooks (block interactions, container
 * screens) and the integrated server hooks (damage, death, item pickup). To keep the write
 * path single-threaded, producers only enqueue an immutable JSON object plus the game time,
 * and the client tick drains the queue at a well defined point.</p>
 *
 * <p>Filtering happens in two stages:</p>
 * <ol>
 *   <li>on enqueue, the event category is checked (cheap configuration lookups only, safe from
 *       the server thread);</li>
 *   <li>on flush, the positional area filter is applied on the client thread, where the local
 *       player position can be read safely.</li>
 * </ol>
 */
public final class EventRecorder {

    private static final ConcurrentLinkedQueue<PendingEvent> QUEUE = new ConcurrentLinkedQueue<>();

    /**
     * Hard cap on queued events. The queue only grows while recording is suspended (paused
     * game, world loading, settings screen), but a very long pause must not consume memory.
     */
    private static final int MAX_QUEUED_EVENTS = 4096;

    private EventRecorder() {
    }

    private record PendingEvent(JsonObject event, long worldTime) {
    }

    /* =====================================================================================
     * Producing
     * ===================================================================================== */

    /**
     * Enqueues an event.
     *
     * <p>Safe to call from any thread. Events are dropped when no episode is active or when
     * their category is disabled.</p>
     *
     * @param event     event payload; the {@code type} field decides the category
     * @param worldTime game time at the moment the event happened
     */
    public static void queue(JsonObject event, long worldTime) {
        if (event == null || !EpisodeManager.isActive()) {
            return;
        }

        JsonElement typeElement = event.get("type");
        String type = typeElement == null || typeElement.isJsonNull()
                ? null
                : typeElement.getAsString();

        if (!EventCategory.of(type).isEnabled()) {
            return;
        }

        // Drop the oldest event when the backlog grows too large; keeping the newest events is
        // more useful for the dataset than keeping the oldest ones.
        if (QUEUE.size() >= MAX_QUEUED_EVENTS) {
            if (QUEUE.poll() != null) {
                RecordingStats.onEventDropped();
            }
        }

        QUEUE.add(new PendingEvent(event, worldTime));
    }

    /** Convenience for events without additional payload. */
    public static void queue(String type, long worldTime) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        queue(event, worldTime);
    }

    /* =====================================================================================
     * Consuming
     * ===================================================================================== */

    /** Called once per client tick after the state and action writers ran. */
    public static void flush(Minecraft minecraft) {
        if (!EpisodeManager.isActive()) {
            QUEUE.clear();
            return;
        }

        // While paused the queue is kept: it may hold the pause boundary itself and the
        // events that arrived during world loading. They are written when recording resumes.
        if (!EpisodeManager.isRecording()) {
            return;
        }

        writeQueued(minecraft);
    }

    /**
     * Drains the queue even if recording is suspended; used when the episode stops.
     */
    public static void flushFinal() {
        writeQueued(Minecraft.getInstance());
    }

    private static void writeQueued(Minecraft minecraft) {
        long sampleTick = TickRecorder.getSampleTick();
        if (sampleTick <= 0) {
            return;
        }

        String dimension = currentDimension(minecraft);

        PendingEvent pending;
        while ((pending = QUEUE.poll()) != null) {
            JsonObject event = pending.event();

            if (!EventFilter.accept(event)) {
                continue;
            }

            event.addProperty("sample_tick", sampleTick);
            event.addProperty("world_time", pending.worldTime());
            if (dimension != null && !dimension.isBlank()) {
                event.addProperty("dimension", dimension);
            }

            JsonExporter.writeEvent(event);

            JsonElement type = event.get("type");
            RecordingStats.onEvent(
                    type == null || type.isJsonNull() ? "unknown" : type.getAsString(),
                    sampleTick);
        }
    }

    private static String currentDimension(Minecraft minecraft) {
        if (minecraft != null && minecraft.level != null) {
            return minecraft.level.dimension().location().toString();
        }
        return DimensionTracker.getCurrentDimension();
    }

    /** Drops every queued event; called when an episode starts. */
    public static void clear() {
        QUEUE.clear();
    }

    /* =====================================================================================
     * Building blocks for event payloads
     * ===================================================================================== */

    /**
     * Adds an {@code entity} block:
     * <pre>
     * "entity": { "id": 361, "uuid": "...", "type": "minecraft:pig",
     *             "position": { "x":, "y":, "z": }, "dimension": "..." }
     * </pre>
     */
    public static void addEntityInfo(JsonObject json, Entity entity) {
        if (entity == null) {
            return;
        }

        JsonObject entityJson = new JsonObject();
        entityJson.addProperty("id", entity.getId());
        entityJson.addProperty("uuid", entity.getUUID().toString());
        entityJson.addProperty("type", entityTypeId(entity));

        JsonObject position = new JsonObject();
        position.addProperty("x", entity.getX());
        position.addProperty("y", entity.getY());
        position.addProperty("z", entity.getZ());
        entityJson.add("position", position);

        entityJson.addProperty("dimension", entity.level().dimension().location().toString());

        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            entityJson.addProperty("health", living.getHealth());
            entityJson.addProperty("max_health", living.getMaxHealth());
        }

        json.add("entity", entityJson);
    }

    /** Adds an {@code item} block. */
    public static void addItemInfo(JsonObject json, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        JsonObject itemJson = new JsonObject();
        itemJson.addProperty("type", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        itemJson.addProperty("count", stack.getCount());
        json.add("item", itemJson);
    }

    /** Adds a {@code block} block. */
    public static void addBlockInfo(
            JsonObject json, int x, int y, int z, String blockType) {

        JsonObject blockJson = new JsonObject();
        blockJson.addProperty("x", x);
        blockJson.addProperty("y", y);
        blockJson.addProperty("z", z);
        blockJson.addProperty("type", blockType);
        json.add("block", blockJson);
    }

    /** Adds a {@code position} block. */
    public static void addPositionInfo(JsonObject json, double x, double y, double z) {
        JsonObject positionJson = new JsonObject();
        positionJson.addProperty("x", x);
        positionJson.addProperty("y", y);
        positionJson.addProperty("z", z);
        json.add("position", positionJson);
    }

    /**
     * Adds a full {@code damage} block for a hurt event:
     * <pre>
     * "damage": { "base":, "taken":, "blocked":, "source":,
     *             "attacker_uuid":, "direct_entity_uuid": }
     * </pre>
     */
    public static void addDamageInfo(
            JsonObject json,
            float baseDamage,
            float damageTaken,
            boolean blocked,
            String damageSource,
            String attackerUuid,
            String directEntityUuid) {

        JsonObject damageJson = new JsonObject();
        damageJson.addProperty("base", baseDamage);
        damageJson.addProperty("taken", damageTaken);
        damageJson.addProperty("blocked", blocked);
        damageJson.addProperty("source", damageSource);

        if (attackerUuid != null) {
            damageJson.addProperty("attacker_uuid", attackerUuid);
        }
        if (directEntityUuid != null) {
            damageJson.addProperty("direct_entity_uuid", directEntityUuid);
        }

        json.add("damage", damageJson);
    }

    /** Adds a minimal {@code damage} block used by death events. */
    public static void addDamageSource(
            JsonObject json,
            String damageSource,
            String attackerUuid,
            String directEntityUuid) {

        JsonObject damageJson = new JsonObject();
        damageJson.addProperty("source", damageSource);

        if (attackerUuid != null) {
            damageJson.addProperty("attacker_uuid", attackerUuid);
        }
        if (directEntityUuid != null) {
            damageJson.addProperty("direct_entity_uuid", directEntityUuid);
        }

        json.add("damage", damageJson);
    }

    private static String entityTypeId(Entity entity) {
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? "unknown" : key.toString();
    }
}

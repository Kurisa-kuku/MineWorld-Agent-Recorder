package com.kurisa.mwarecorder.event;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.recorder.RecordingStats;

import net.minecraft.client.Minecraft;

/**
 * Positional filter for recorded events.
 *
 * <p>The player can restrict event recording to a square centred on the player (for example
 * {@code 16x16} or {@code 32x32} blocks). This keeps far-away background simulation, such as a
 * drowning squid fifty blocks below the surface, out of the dataset while the local game play
 * stays fully described.</p>
 *
 * <p>The filter is evaluated on the client thread right before an event is flushed, because
 * events may be queued from the integrated server thread and reading the local player from
 * there would be a data race.</p>
 */
public final class EventFilter {

    private EventFilter() {
    }

    /**
     * Whether the event passes the configured area filter.
     *
     * <p>Events without a world position (for example crafting or container changes) always
     * pass: they happen at the player by definition.</p>
     */
    public static boolean isInsideArea(JsonObject event) {
        MwarecorderConfig cfg = MwarecorderConfig.get();
        int area = cfg.eventAreaSize;

        if (area <= 0) {
            return true;
        }

        EventCategory category = EventCategory.of(string(event, "type"));
        if (!category.isPositional()) {
            return true;
        }

        double[] position = positionOf(event);
        if (position == null) {
            return true;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return true;
        }

        // Chebyshev distance gives a square footprint, which is what the "N x N blocks"
        // setting promises to the player.
        double half = cfg.eventAreaHalfBlocks();
        double dx = Math.abs(position[0] - minecraft.player.getX());
        double dz = Math.abs(position[2] - minecraft.player.getZ());

        return dx <= half && dz <= half;
    }

    /**
     * Extracts the world position an event refers to.
     *
     * <p>Supported layouts:</p>
     * <ul>
     *   <li>{@code "block": {"x":..,"y":..,"z":..}}</li>
     *   <li>{@code "entity": {"x":..,"y":..,"z":..}} (only when the entity block has absolute
     *       coordinates; nearby-entity snapshots use relative ones)</li>
     *   <li>{@code "position": {"x":..,"y":..,"z":..}}</li>
     * </ul>
     *
     * @return {@code {x, y, z}} or {@code null} when the event is not positional
     */
    public static double[] positionOf(JsonObject event) {
        double[] fromBlock = readPosition(event.get("block"));
        if (fromBlock != null) {
            return fromBlock;
        }

        JsonElement entity = event.get("entity");
        double[] fromEntity = readPosition(entity);
        if (fromEntity != null) {
            return fromEntity;
        }

        // Combat events nest the absolute position inside the entity block.
        if (entity != null && entity.isJsonObject()) {
            double[] nested = readPosition(entity.getAsJsonObject().get("position"));
            if (nested != null) {
                return nested;
            }
        }

        return readPosition(event.get("position"));
    }

    private static double[] readPosition(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        JsonElement x = object.get("x");
        JsonElement y = object.get("y");
        JsonElement z = object.get("z");
        if (x == null || y == null || z == null) {
            return null;
        }
        try {
            return new double[] {x.getAsDouble(), y.getAsDouble(), z.getAsDouble()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Applies the filter and records a statistic when the event is dropped.
     *
     * @return {@code true} when the event should be written
     */
    public static boolean accept(JsonObject event) {
        if (isInsideArea(event)) {
            return true;
        }
        RecordingStats.onEventFiltered();
        return false;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }
}

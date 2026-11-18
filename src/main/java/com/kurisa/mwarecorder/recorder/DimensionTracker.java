package com.kurisa.mwarecorder.recorder;

import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.event.EventRecorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Follows the dimension the player is currently in and records every transition.
 *
 * <p>Walking through a Nether portal replaces the client level, scales the coordinate system
 * by a factor of eight and briefly shows a loading screen. Without an explicit marker the
 * resulting jump in the state stream would look like a teleport, and a world model could not
 * tell that the background actually changed.</p>
 *
 * <p>For each transition the tracker emits a {@code dimension_change} event that contains:</p>
 * <pre>
 * {
 *   "type": "dimension_change",
 *   "from": "minecraft:overworld",
 *   "to": "minecraft:the_nether",
 *   "from_position": { "x": .., "y": .., "z": .. },
 *   "to_position":   { "x": .., "y": .., "z": .. },
 *   "from_coordinate_scale": 1.0,
 *   "to_coordinate_scale": 8.0,
 *   "from_overworld_equivalent": { "x": .., "z": .. },
 *   "to_overworld_equivalent":   { "x": .., "z": .. },
 *   "segment": 1
 * }
 * </pre>
 *
 * <p>The tracker runs while the episode is active, even if recording is currently suspended,
 * because the level can be replaced during the loading screen. The event is queued and lands
 * on the first tick that is actually recorded after the transition.</p>
 */
public final class DimensionTracker {

    private static String lastDimension;
    private static double lastCoordinateScale = 1.0;
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static boolean hasLastPosition;
    private static int segment;

    private DimensionTracker() {
    }

    /** Called once per client tick, after the key handling and before the event flush. */
    public static void tick(Minecraft minecraft) {
        if (!EpisodeManager.isActive()) {
            reset();
            return;
        }
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        String current = minecraft.level.dimension().location().toString();
        double coordinateScale = minecraft.level.dimensionType().coordinateScale();

        if (lastDimension == null) {
            // First tick of the episode: remember the starting dimension but do not emit a
            // transition, because start_dimension is already stored in metadata.json.
            lastDimension = current;
            lastCoordinateScale = coordinateScale;
            RecordingStats.onDimension(current);
            remember(minecraft.player);
            return;
        }

        if (!current.equals(lastDimension)) {
            emitTransition(minecraft, current, coordinateScale);
            lastDimension = current;
            lastCoordinateScale = coordinateScale;
            segment++;
            RecordingStats.onDimension(current);
        }

        remember(minecraft.player);
    }

    private static void emitTransition(
            Minecraft minecraft, String toDimension, double toScale) {

        if (MwarecorderConfig.get().eventDimension && hasLastPosition) {
            JsonObject event = new JsonObject();
            event.addProperty("type", "dimension_change");
            event.addProperty("from", lastDimension);
            event.addProperty("to", toDimension);

            event.add("from_position", position(lastX, lastY, lastZ));
            event.add("to_position", position(
                    minecraft.player.getX(),
                    minecraft.player.getY(),
                    minecraft.player.getZ()));

            event.addProperty("from_coordinate_scale", lastCoordinateScale);
            event.addProperty("to_coordinate_scale", toScale);

            // Overworld-equivalent coordinates make portal travel comparable across
            // dimensions; the Nether is compressed by a factor of eight.
            event.add("from_overworld_equivalent", overworldEquivalent(lastX, lastZ, lastCoordinateScale));
            event.add("to_overworld_equivalent", overworldEquivalent(
                    minecraft.player.getX(),
                    minecraft.player.getZ(),
                    toScale));

            event.addProperty("segment", segment + 1);

            EventRecorder.queue(event, minecraft.level.getGameTime());
        }
    }

    private static JsonObject position(double x, double y, double z) {
        JsonObject json = new JsonObject();
        json.addProperty("x", x);
        json.addProperty("y", y);
        json.addProperty("z", z);
        return json;
    }

    private static JsonObject overworldEquivalent(double x, double z, double scale) {
        JsonObject json = new JsonObject();
        json.addProperty("x", x * scale);
        json.addProperty("z", z * scale);
        return json;
    }

    private static void remember(LocalPlayer player) {
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        hasLastPosition = true;
    }

    /** Clears the tracked state; called when an episode starts or stops. */
    public static void reset() {
        lastDimension = null;
        lastCoordinateScale = 1.0;
        lastX = 0.0;
        lastY = 0.0;
        lastZ = 0.0;
        hasLastPosition = false;
        segment = 0;
    }

    /** Dimension id observed on the previous tick, or {@code null} before the first tick. */
    public static String getCurrentDimension() {
        return lastDimension;
    }

    /** Number of transitions seen during the current episode. */
    public static int getSegment() {
        return segment;
    }
}

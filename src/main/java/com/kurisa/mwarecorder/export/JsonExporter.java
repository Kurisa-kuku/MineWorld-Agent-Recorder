package com.kurisa.mwarecorder.export;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.recorder.RecordingStats;
import com.kurisa.mwarecorder.recorder.TickRecorder;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Writes every data stream of an episode to disk.
 *
 * <p>An episode directory contains:</p>
 * <pre>
 *   metadata.json   static description (versions, world, capture settings)
 *   state.jsonl     one player observation per recorded tick
 *   action.jsonl    one input snapshot per recorded tick
 *   event.jsonl     discrete game events, keyed by sample_tick
 *   frames.jsonl    frame id to sample_tick / file mapping
 *   summary.json    counters and dimensions, written when the episode stops
 *   frames/         JPEG frames referenced by frames.jsonl
 * </pre>
 *
 * <p>All files use UTF-8 and JSON Lines (one compact JSON object per line), which is fast to
 * append and friendly to streaming loaders. The writer is used from the client thread and,
 * for the frame index, from the encoder thread; every entry point is therefore
 * {@code synchronized}.</p>
 */
public final class JsonExporter {

    /** Current on-disk format version. */
    public static final String FORMAT_VERSION = "2.0";

    private static final Gson GSON = new GsonBuilder().create();

    /** Flush the text streams at least every N sample ticks. */
    private static final long FLUSH_INTERVAL_TICKS = 100L;

    private static BufferedWriter stateWriter;
    private static BufferedWriter actionWriter;
    private static BufferedWriter eventWriter;
    private static BufferedWriter frameIndexWriter;

    private static Path outputRootDirectory;
    private static Path episodeDirectory;
    private static String episodeIdentifier = "";

    private static Instant episodeStartTime;
    private static long lastFlushTick;
    private static String stopReason = "unknown";

    private JsonExporter() {
    }

    /* =====================================================================================
     * Lifecycle
     * ===================================================================================== */

    /**
     * Creates the episode directory and opens every output stream.
     *
     * @return {@code true} when recording can start; {@code false} when the directory or a
     *         file could not be created (the caller must not mark the episode as active)
     */
    public static synchronized boolean init(Path gameDirectory, String episodeId) {
        closeQuietly();

        MwarecorderConfig cfg = MwarecorderConfig.get();
        episodeIdentifier = episodeId == null || episodeId.isBlank() ? "episode" : episodeId;

        try {
            outputRootDirectory = cfg.resolveOutputRoot(gameDirectory);
            Files.createDirectories(outputRootDirectory);

            episodeDirectory = createUniqueEpisodeDirectory(outputRootDirectory);
            Files.createDirectories(episodeDirectory.resolve("frames"));

            stateWriter = openWriter(episodeDirectory.resolve("state.jsonl"));
            actionWriter = openWriter(episodeDirectory.resolve("action.jsonl"));
            eventWriter = openWriter(episodeDirectory.resolve("event.jsonl"));
            frameIndexWriter = openWriter(episodeDirectory.resolve("frames.jsonl"));

            episodeStartTime = Instant.now();
            lastFlushTick = 0L;
            stopReason = "unknown";

            if (cfg.writeMetadata) {
                writeMetadata();
            }

            System.out.println("[Mwarecorder] Episode directory: " + episodeDirectory);
            return true;
        } catch (IOException | RuntimeException e) {
            System.err.println("[Mwarecorder] Failed to initialise the exporter: " + e.getMessage());
            e.printStackTrace();
            closeQuietly();
            episodeDirectory = null;
            return false;
        }
    }

    /**
     * Creates {@code episode_<id>}, appending {@code _2}, {@code _3}, ... when a directory of
     * the same name already exists. This avoids appending to an earlier recording when two
     * episodes start within the same second.
     */
    private static Path createUniqueEpisodeDirectory(Path root) throws IOException {
        String baseName = "episode_" + episodeIdentifier;
        Path candidate = root.resolve(baseName);

        int suffix = 2;
        while (Files.exists(candidate.resolve("metadata.json"))
                || Files.exists(candidate.resolve("state.jsonl"))) {
            candidate = root.resolve(baseName + "_" + suffix);
            suffix++;
            if (suffix > 1000) {
                throw new IOException("Unable to find a free episode directory name in " + root);
            }
        }

        Files.createDirectories(candidate);
        return candidate;
    }

    private static BufferedWriter openWriter(Path path) throws IOException {
        Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        return new BufferedWriter(writer, 1 << 16);
    }

    /* =====================================================================================
     * Metadata
     * ===================================================================================== */

    private static void writeMetadata() throws IOException {
        MwarecorderConfig cfg = MwarecorderConfig.get();
        Minecraft minecraft = Minecraft.getInstance();

        JsonObject metadata = new JsonObject();
        metadata.addProperty("episode_id", episodeIdentifier);
        metadata.addProperty("mod", "mwarecorder");
        metadata.addProperty("format_version", FORMAT_VERSION);
        metadata.addProperty("schema_version", MwarecorderConfig.CONFIG_VERSION);
        metadata.addProperty("start_time", episodeStartTime.toString());
        metadata.addProperty("episode_directory", episodeDirectory.toString());

        metadata.addProperty("minecraft_version", SharedConstants.getCurrentVersion().id());
        metadata.addProperty("java_version", System.getProperty("java.version", "unknown"));
        metadata.addProperty("os", System.getProperty("os.name", "unknown"));
        metadata.addProperty("os_arch", System.getProperty("os.arch", "unknown"));

        FabricLoader.getInstance().getModContainer("fabricloader").ifPresent(container ->
                metadata.addProperty(
                        "fabric_loader_version",
                        container.getMetadata().getVersion().getFriendlyString()));

        metadata.add("mods", collectMods());

        if (minecraft.player != null) {
            metadata.addProperty("player_name", minecraft.player.getGameProfile().getName());
            metadata.addProperty("player_uuid", minecraft.player.getUUID().toString());
        }

        if (minecraft.getWindow() != null) {
            metadata.addProperty("window_width", minecraft.getWindow().getWidth());
            metadata.addProperty("window_height", minecraft.getWindow().getHeight());
            metadata.addProperty("gui_scale", minecraft.getWindow().getGuiScale());
        }

        metadata.addProperty(
                "start_dimension",
                minecraft.level != null
                        ? minecraft.level.dimension().location().toString()
                        : "unknown");

        writeWorldInfo(metadata, minecraft);
        metadata.add("capture_config", captureConfig(cfg));
        metadata.add("data_files", dataFileList());

        writeJson(episodeDirectory.resolve("metadata.json"), metadata);
    }

    private static JsonArray collectMods() {
        JsonArray mods = new JsonArray();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", mod.getMetadata().getId());
            entry.addProperty("name", mod.getMetadata().getName());
            entry.addProperty("version", mod.getMetadata().getVersion().getFriendlyString());
            mods.add(entry);
        }
        return mods;
    }

    private static void writeWorldInfo(JsonObject metadata, Minecraft minecraft) {
        if (minecraft.hasSingleplayerServer()) {
            metadata.addProperty("world_type", "singleplayer");
            var server = minecraft.getSingleplayerServer();
            if (server != null) {
                metadata.addProperty("world_seed", server.overworld().getSeed());
                var levelData = server.getWorldData();
                metadata.addProperty("world_name", levelData.getLevelName());
                metadata.addProperty("hardcore", levelData.isHardcore());
                metadata.addProperty("difficulty", levelData.getDifficulty().getKey());
                metadata.addProperty("game_mode", levelData.getGameType().getName());
            }
        } else if (minecraft.getCurrentServer() != null) {
            var serverData = minecraft.getCurrentServer();
            metadata.addProperty("world_type", "multiplayer");
            metadata.addProperty("server_name", serverData.name);
            metadata.addProperty("server_address", serverData.ip);
        } else {
            metadata.addProperty("world_type", "unknown");
        }
    }

    /** Full configuration snapshot, so a dataset can be reproduced later. */
    private static JsonObject captureConfig(MwarecorderConfig cfg) {
        JsonObject capture = new JsonObject();

        capture.addProperty("frame_capture_enabled", cfg.frameCaptureEnabled);
        capture.addProperty("every_n_frames", cfg.everyNFrames);
        capture.addProperty("jpeg_quality", cfg.jpegQuality);
        capture.addProperty("target_width", cfg.targetWidth);
        capture.addProperty("target_height", cfg.targetHeight);
        capture.addProperty("max_pending_frames", cfg.maxPendingFrames);

        capture.addProperty("state_capture_enabled", cfg.stateCaptureEnabled);
        capture.addProperty("nearby_radius", cfg.nearbyRadius);
        capture.addProperty("nearby_max", cfg.nearbyMax);
        capture.addProperty("look_reach", cfg.lookReach);
        capture.addProperty("include_inventory", cfg.includeInventory);
        capture.addProperty("include_environment", cfg.includeEnvironment);

        capture.addProperty("action_capture_enabled", cfg.actionCaptureEnabled);
        capture.addProperty("record_keyboard", cfg.recordKeyboard);
        capture.addProperty("record_mouse", cfg.recordMouse);

        capture.addProperty("event_capture_enabled", cfg.eventCaptureEnabled);
        capture.addProperty("event_area_size", cfg.eventAreaSize);
        capture.addProperty("event_block", cfg.eventBlock);
        capture.addProperty("event_entity", cfg.eventEntity);
        capture.addProperty("event_container", cfg.eventContainer);
        capture.addProperty("event_item", cfg.eventItem);
        capture.addProperty("event_session", cfg.eventSession);
        capture.addProperty("event_dimension", cfg.eventDimension);

        capture.addProperty("hud_enabled", cfg.hudEnabled);
        capture.addProperty("output_directory", cfg.outputDirectory);
        capture.addProperty("resolved_output_directory", String.valueOf(outputRootDirectory));

        return capture;
    }

    private static JsonArray dataFileList() {
        JsonArray files = new JsonArray();
        files.add("state.jsonl");
        files.add("action.jsonl");
        files.add("event.jsonl");
        files.add("frames.jsonl");
        if (MwarecorderConfig.get().writeMetadata) {
            files.add("metadata.json");
        }
        files.add("summary.json");
        return files;
    }

    /* =====================================================================================
     * Data streams
     * ===================================================================================== */

    /**
     * Writes one player observation.
     *
     * @param sampleTick episode-wide tick counter
     * @param worldTime  game time of the current dimension
     * @param playerState observation built by {@code PlayerSnapshot}
     */
    public static synchronized void writeState(
            long sampleTick, long worldTime, JsonObject playerState) {

        if (stateWriter == null) {
            return;
        }

        JsonObject record = new JsonObject();
        record.addProperty("sample_tick", sampleTick);
        record.addProperty("world_time", worldTime);

        JsonElement dimension = playerState == null ? null : playerState.get("dimension");
        if (dimension != null && !dimension.isJsonNull()) {
            record.addProperty("dimension", dimension.getAsString());
        }

        if (playerState != null) {
            record.add("player", playerState);
        }

        writeLine(stateWriter, record, "state");
        RecordingStats.onState();
    }

    /** Writes one input snapshot. */
    public static synchronized void writeAction(JsonObject action) {
        if (actionWriter == null || action == null) {
            return;
        }
        writeLine(actionWriter, action, "action");
        RecordingStats.onAction();
    }

    /** Writes one discrete event. */
    public static synchronized void writeEvent(JsonObject event) {
        if (eventWriter == null || event == null) {
            return;
        }
        writeLine(eventWriter, event, "event");
    }

    /**
     * Writes the mapping between a JPEG frame and the tick it belongs to.
     *
     * @param frameId    strictly increasing frame number starting at 1
     * @param sampleTick tick counter at capture time
     * @param worldTime  game time of the current dimension
     * @param filename   file name inside {@code frames/}
     * @param dimension  dimension id at capture time
     */
    public static synchronized void writeFrameIndex(
            int frameId,
            int sampleTick,
            long worldTime,
            String filename,
            String dimension) {

        if (frameIndexWriter == null) {
            return;
        }

        JsonObject record = new JsonObject();
        record.addProperty("frame_id", frameId);
        record.addProperty("sample_tick", sampleTick);
        record.addProperty("world_time", worldTime);
        record.addProperty("file", filename);
        if (dimension != null && !dimension.isBlank()) {
            record.addProperty("dimension", dimension);
        }

        writeLine(frameIndexWriter, record, "frame index");
        RecordingStats.onFrame();
    }

    private static void writeLine(BufferedWriter writer, JsonObject json, String label) {
        try {
            writer.write(json.toString());
            writer.newLine();
        } catch (IOException e) {
            System.err.println("[Mwarecorder] Failed to write " + label + ": " + e.getMessage());
        }
    }

    /* =====================================================================================
     * Flushing and closing
     * ===================================================================================== */

    /** Flushes the text streams when the flush interval has elapsed. */
    public static synchronized void flushIfDue() {
        long tick = TickRecorder.getSampleTick();
        if (tick - lastFlushTick < FLUSH_INTERVAL_TICKS) {
            return;
        }
        lastFlushTick = tick;
        flushAll();
    }

    /** Flushes every text stream. */
    public static synchronized void flushAll() {
        flushQuietly(stateWriter);
        flushQuietly(actionWriter);
        flushQuietly(eventWriter);
        flushQuietly(frameIndexWriter);
    }

    /**
     * Flushes and closes all streams, then writes {@code summary.json}.
     *
     * @param reason human readable reason, stored in the summary
     */
    public static synchronized void close(String reason) {
        if (episodeDirectory == null) {
            return;
        }

        stopReason = reason == null ? "unknown" : reason;

        stateWriter = closeWriter(stateWriter);
        actionWriter = closeWriter(actionWriter);
        eventWriter = closeWriter(eventWriter);
        frameIndexWriter = closeWriter(frameIndexWriter);

        try {
            writeSummary();
        } catch (IOException e) {
            System.err.println("[Mwarecorder] Failed to write summary: " + e.getMessage());
        }

        System.out.println("[Mwarecorder] Episode closed: " + episodeDirectory);
        episodeDirectory = null;
    }

    /** Closes any open stream without writing a summary; used on failed initialisation. */
    public static synchronized void closeQuietly() {
        stateWriter = closeWriter(stateWriter);
        actionWriter = closeWriter(actionWriter);
        eventWriter = closeWriter(eventWriter);
        frameIndexWriter = closeWriter(frameIndexWriter);
    }

    private static BufferedWriter closeWriter(BufferedWriter writer) {
        if (writer == null) {
            return null;
        }
        try {
            writer.flush();
        } catch (IOException ignored) {
            // The data is lost either way; closing below is still required.
        }
        try {
            writer.close();
        } catch (IOException ignored) {
            // Nothing sensible can be done while shutting down.
        }
        return null;
    }

    private static void flushQuietly(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            System.err.println("[Mwarecorder] Failed to flush: " + e.getMessage());
        }
    }

    /* =====================================================================================
     * Summary
     * ===================================================================================== */

    private static void writeSummary() throws IOException {
        if (episodeDirectory == null) {
            return;
        }

        JsonObject summary = new JsonObject();
        summary.addProperty("episode_id", episodeIdentifier);
        summary.addProperty("episode_directory", episodeDirectory.toString());
        summary.addProperty(
                "start_time",
                episodeStartTime != null ? episodeStartTime.toString() : "unknown");

        Instant endTime = Instant.now();
        summary.addProperty("end_time", endTime.toString());
        summary.addProperty("stop_reason", stopReason);

        if (episodeStartTime != null) {
            summary.addProperty(
                    "duration_seconds",
                    Math.max(0L, Duration.between(episodeStartTime, endTime).getSeconds()));
        }

        summary.addProperty("total_state_records", RecordingStats.states());
        summary.addProperty("total_action_records", RecordingStats.actions());
        summary.addProperty("total_event_records", RecordingStats.events());
        summary.addProperty("total_frames", RecordingStats.frames());
        summary.addProperty("total_filtered_events", RecordingStats.filteredEvents());
        summary.addProperty("total_dropped_events", RecordingStats.droppedEvents());
        summary.addProperty("total_dropped_frames", RecordingStats.droppedFrames());

        JsonArray dimensions = new JsonArray();
        for (String dimension : RecordingStats.dimensionsVisited()) {
            dimensions.add(dimension);
        }
        summary.add("dimensions_visited", dimensions);

        writeJson(episodeDirectory.resolve("summary.json"), summary);
    }

    private static void writeJson(Path path, JsonObject json) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
    }

    /* =====================================================================================
     * Accessors
     * ===================================================================================== */

    /** Directory of the active episode, or {@code null} when idle. */
    public static synchronized Path getEpisodeDirectory() {
        return episodeDirectory;
    }

    /** Root directory that contains all episodes. */
    public static synchronized Path getOutputRootDirectory() {
        return outputRootDirectory;
    }
}

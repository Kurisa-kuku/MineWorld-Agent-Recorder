package com.kurisa.mwarecorder.recorder;

import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.event.EventRecorder;
import com.kurisa.mwarecorder.export.JsonExporter;
import com.kurisa.mwarecorder.input.ActionRecorder;
import com.kurisa.mwarecorder.input.MwarecorderKeys;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the lifetime of one episode.
 *
 * <p>An episode starts when the player presses the recording key and ends when the key is
 * pressed again, the player leaves the world, or the game shuts down. While an episode is
 * active the mod distinguishes two states:</p>
 *
 * <ul>
 *   <li><b>active</b> - the episode exists and its files are open;</li>
 *   <li><b>recording</b> - data is actually written. It is false while the game is paused,
 *       while a world is loading, and while the settings screen is open.</li>
 * </ul>
 *
 * <p>A paused episode keeps writing into the same files after it resumes, so a trip through
 * a Nether portal - which briefly unloads the client level - stays inside one episode.</p>
 */
public final class EpisodeManager {

    private static final DateTimeFormatter ID_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static String episodeId = "";
    private static boolean active = false;

    private EpisodeManager() {
    }

    /* =====================================================================================
     * Lifecycle
     * ===================================================================================== */

    /**
     * Starts a new episode.
     *
     * @return {@code true} when recording started, {@code false} when it was rejected
     *         (already recording, no world loaded, or the output directory is not writable)
     */
    public static boolean start(Minecraft minecraft) {
        if (active) {
            return false;
        }
        if (minecraft.player == null || minecraft.level == null) {
            System.out.println("[Mwarecorder] Cannot start: no world loaded.");
            return false;
        }

        MwarecorderConfig cfg = MwarecorderConfig.get();

        if (!cfg.anyCaptureEnabled()) {
            System.out.println("[Mwarecorder] Cannot start: every capture stream is disabled.");
            return false;
        }

        String id = LocalDateTime.now().format(ID_FORMAT);

        if (!JsonExporter.init(minecraft.gameDirectory.toPath(), id)) {
            return false;
        }

        episodeId = id;
        RecordingStats.reset(id);

        ActionRecorder.reset();
        EventRecorder.clear();
        TickRecorder.reset();
        ContainerTracker.reset();
        DimensionTracker.reset();

        String dimension = minecraft.level.dimension().location().toString();
        RecordingStats.onDimension(dimension);

        if (cfg.frameCaptureEnabled) {
            FrameCapture.start(
                    JsonExporter.getEpisodeDirectory().toFile(),
                    cfg.everyNFrames,
                    cfg.jpegQuality,
                    cfg.targetWidth,
                    cfg.targetHeight,
                    cfg.maxPendingFrames);
        } else {
            System.out.println("[Mwarecorder] Frame capture is disabled by the configuration.");
        }

        active = true;

        queueSessionEvent("session_start", minecraft, dimension);

        System.out.println("[Mwarecorder] Recording started: episode " + id);
        return true;
    }

    /**
     * Stops the active episode.
     *
     * @param reason short machine readable reason stored in {@code summary.json}
     * @return {@code true} when an episode was stopped
     */
    public static boolean stop(String reason) {
        if (!active) {
            return false;
        }

        active = false;

        // Drain queued events while the writer is still open. The final flush ignores the
        // "recording" gate because the episode is ending by definition.
        EventRecorder.flushFinal();

        // The frame encoder must finish before the frame index file is closed.
        FrameCapture.stop();

        JsonExporter.close(reason == null ? "manual" : reason);
        RecordingStats.markStopped();

        ContainerTracker.reset();
        DimensionTracker.reset();

        System.out.println("[Mwarecorder] Recording stopped (" + reason + ").");
        return true;
    }

    /** Stops the episode without a Minecraft reference; used by the shutdown hook. */
    public static void stop() {
        stop("shutdown");
    }

    /* =====================================================================================
     * State queries
     * ===================================================================================== */

    public static boolean isActive() {
        return active;
    }

    /**
     * Whether data may be written in this tick.
     *
     * <p>Unlike {@link #isActive()} this returns {@code false} while the game is paused, while
     * a world is loading and while the settings screen is open.</p>
     */
    public static boolean isRecording() {
        if (!active) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return false;
        }
        if (minecraft.isPaused()) {
            return false;
        }
        if (minecraft.screen instanceof PauseScreen) {
            return false;
        }
        if (minecraft.screen instanceof ReceivingLevelScreen) {
            return false;
        }
        // Settings screens suspend recording so configuration time is not part of the data.
        if (minecraft.screen instanceof PausesRecording) {
            return false;
        }

        return true;
    }

    /** Current dimension segment index (0 before the first transition). */
    public static int getSegment() {
        return DimensionTracker.getSegment();
    }

    public static String getEpisodeId() {
        return episodeId;
    }

    /** Directory of the active episode, or {@code null} when idle. */
    public static Path getEpisodeDirectory() {
        return JsonExporter.getEpisodeDirectory();
    }

    /* =====================================================================================
     * Reporting
     * ===================================================================================== */

    /** Multi-line status report used by the {@code /mwarecorder status} command. */
    public static List<String> statusReport() {
        List<String> lines = new ArrayList<>();

        if (!active) {
            lines.add("Mwarecorder: idle (no active episode)");
            lines.add("Output root: " + MwarecorderConfig.get().resolveOutputRoot());
            lines.add("Toggle key: " + MwarecorderKeys.toggleKeyName());
            return lines;
        }

        lines.add("Mwarecorder: episode " + episodeId
                + (isRecording() ? " [recording]" : " [paused]"));
        lines.add("Elapsed: " + RecordingStats.elapsedText());
        lines.add("Frames: " + RecordingStats.frames()
                + " (dropped " + RecordingStats.droppedFrames() + ")");
        lines.add("States: " + RecordingStats.states()
                + ", actions: " + RecordingStats.actions()
                + ", events: " + RecordingStats.events()
                + " (filtered " + RecordingStats.filteredEvents()
                + ", dropped " + RecordingStats.droppedEvents() + ")");
        lines.add("Dimension: " + RecordingStats.currentDimension());
        lines.add("Segments: " + (getSegment() + 1)
                + ", visited: " + String.join(", ", RecordingStats.dimensionsVisited()));

        if (!RecordingStats.lastEventType().isEmpty()) {
            lines.add("Last event: " + RecordingStats.lastEventType()
                    + " at tick " + RecordingStats.lastEventTick());
        }

        lines.add("Directory: " + JsonExporter.getEpisodeDirectory());
        return lines;
    }

    private static void queueSessionEvent(String type, Minecraft minecraft, String dimension) {
        if (!MwarecorderConfig.get().eventSession) {
            return;
        }

        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("episode_id", episodeId);
        event.addProperty("dimension", dimension);
        event.addProperty("output_directory", String.valueOf(JsonExporter.getOutputRootDirectory()));
        event.addProperty("area_size", MwarecorderConfig.get().eventAreaSize);
        event.addProperty("frames_enabled", MwarecorderConfig.get().frameCaptureEnabled);
        event.addProperty("states_enabled", MwarecorderConfig.get().stateCaptureEnabled);
        event.addProperty("actions_enabled", MwarecorderConfig.get().actionCaptureEnabled);

        EventRecorder.queue(event, minecraft.level.getGameTime());
    }
}

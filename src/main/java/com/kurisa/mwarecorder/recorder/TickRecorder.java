package com.kurisa.mwarecorder.recorder;

import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.export.JsonExporter;

import net.minecraft.client.Minecraft;

/**
 * Advances the episode-wide tick counter and writes the player observation for the tick.
 *
 * <p>The counter is intentionally independent of the state stream: frames, events and actions
 * all reference {@code sample_tick}, so it must keep advancing even when the player disables
 * state recording. A tick is only counted while {@link EpisodeManager#isRecording()} is true,
 * which means paused time does not create gaps.</p>
 */
public final class TickRecorder {

    private static long sampleTick;

    private TickRecorder() {
    }

    /** Called once per client tick, after input handling and before the event flush. */
    public static void record(Minecraft minecraft) {
        if (!EpisodeManager.isRecording()) {
            return;
        }
        if (minecraft.player == null) {
            return;
        }

        sampleTick++;

        long worldTime = minecraft.level != null ? minecraft.level.getGameTime() : -1L;

        if (MwarecorderConfig.get().stateCaptureEnabled) {
            JsonExporter.writeState(sampleTick, worldTime, PlayerSnapshot.capture(minecraft));
        }

        JsonExporter.flushIfDue();
    }

    /** Episode-wide tick counter, used to align every data stream. */
    public static long getSampleTick() {
        return sampleTick;
    }

    /** Resets the counter; called when an episode starts. */
    public static void reset() {
        sampleTick = 0L;
    }
}

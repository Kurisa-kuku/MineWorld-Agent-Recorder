package com.kurisa.mwarecorder.input;

import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.export.JsonExporter;
import com.kurisa.mwarecorder.recorder.EpisodeManager;
import com.kurisa.mwarecorder.recorder.TickRecorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Writes one input snapshot per recorded tick.
 *
 * <p>The action stream is the "what the agent did" half of the dataset. Keyboard state is read
 * from the user's own key mappings, so remapped controls are recorded correctly. Mouse
 * movement is accumulated between ticks by {@link MouseInputRecorder} and consumed here, which
 * turns raw pointer deltas into a per-tick displacement.</p>
 */
public final class ActionRecorder {

    private ActionRecorder() {
    }

    /** Called once per client tick. */
    public static void record(Minecraft minecraft) {
        MwarecorderConfig cfg = MwarecorderConfig.get();

        if (!cfg.actionCaptureEnabled || !EpisodeManager.isRecording()
                || minecraft.player == null) {
            // Drain the accumulated mouse delta so the first recorded tick does not contain
            // movement that happened while the episode was paused.
            MouseInputRecorder.consume();
            return;
        }

        long sampleTick = TickRecorder.getSampleTick();
        if (sampleTick <= 0) {
            MouseInputRecorder.consume();
            return;
        }

        Options options = minecraft.options;

        JsonObject json = new JsonObject();
        json.addProperty("sample_tick", sampleTick);

        if (cfg.recordKeyboard) {
            JsonObject keys = new JsonObject();
            keys.addProperty("forward", options.keyUp.isDown());
            keys.addProperty("back", options.keyDown.isDown());
            keys.addProperty("left", options.keyLeft.isDown());
            keys.addProperty("right", options.keyRight.isDown());
            keys.addProperty("jump", options.keyJump.isDown());
            keys.addProperty("sneak", options.keyShift.isDown());
            keys.addProperty("sprint", options.keySprint.isDown());
            json.add("keys", keys);
        }

        if (cfg.recordMouse) {
            double[] delta = MouseInputRecorder.consume();

            JsonObject mouse = new JsonObject();
            mouse.addProperty("dx", delta[0]);
            mouse.addProperty("dy", delta[1]);
            mouse.addProperty("left", MouseInputRecorder.isLeftDown());
            mouse.addProperty("middle", MouseInputRecorder.isMiddleDown());
            mouse.addProperty("right", MouseInputRecorder.isRightDown());
            json.add("mouse", mouse);
        }

        JsonExporter.writeAction(json);
    }

    /** Resets accumulated input; called when an episode starts. */
    public static void reset() {
        MouseInputRecorder.reset();
    }
}

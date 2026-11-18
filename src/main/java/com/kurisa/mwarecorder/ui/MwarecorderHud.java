package com.kurisa.mwarecorder.ui;

import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.input.MwarecorderKeys;
import com.kurisa.mwarecorder.recorder.DimensionTracker;
import com.kurisa.mwarecorder.recorder.EpisodeManager;
import com.kurisa.mwarecorder.recorder.RecordingStats;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording overlay drawn on top of the HUD.
 *
 * <p>The overlay is the player's main feedback channel while an episode runs: it shows whether
 * data is being written, for how long, how much has been captured and where the player
 * currently is. It is intentionally cheap - a handful of strings per frame - and can be turned
 * off or restyled from the settings screen.</p>
 */
public final class MwarecorderHud {

    private static final ResourceLocation ELEMENT_ID =
            ResourceLocation.fromNamespaceAndPath("mwarecorder", "recording_overlay");

    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 3;

    private static final int COLOR_RECORDING = 0xFFFF5555;
    private static final int COLOR_PAUSED = 0xFFFFCC55;
    private static final int COLOR_TEXT = 0xFFE0E0E0;
    private static final int COLOR_DIM = 0xFF9A9A9A;
    private static final int COLOR_BACKGROUND = 0x90101010;

    private MwarecorderHud() {
    }

    /** Registers the overlay; called once from the client initialiser. */
    public static void register() {
        HudElementRegistry.addLast(ELEMENT_ID, MwarecorderHud::render);
    }

    /** Called by Fabric once per frame while the HUD is visible. */
    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        MwarecorderConfig cfg = MwarecorderConfig.get();

        if (!cfg.hudEnabled || !EpisodeManager.isActive()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.font == null) {
            return;
        }

        boolean recording = EpisodeManager.isRecording();

        List<Line> lines = new ArrayList<>(6);

        lines.add(new Line(recording ? "REC" : "PAUSED",
                recording ? COLOR_RECORDING : COLOR_PAUSED, true));

        if (cfg.hudTimer) {
            lines.add(new Line(
                    "time " + RecordingStats.elapsedText()
                            + "   frame " + RecordingStats.frames()
                            + " (drop " + RecordingStats.droppedFrames() + ")",
                    COLOR_TEXT));
        }

        if (cfg.hudCounters) {
            lines.add(new Line(
                    "state " + RecordingStats.states()
                            + "   act " + RecordingStats.actions()
                            + "   event " + RecordingStats.events()
                            + " (filt " + RecordingStats.filteredEvents() + ")",
                    COLOR_TEXT));
        }

        if (cfg.hudDimension) {
            String dimension = RecordingStats.currentDimension();
            if (dimension == null || dimension.isBlank()) {
                dimension = DimensionTracker.getCurrentDimension();
            }
            lines.add(new Line("dim " + shortDimension(dimension), COLOR_DIM));
        }

        if (cfg.hudPosition && minecraft.player != null) {
            lines.add(new Line(String.format(
                    "x %.1f  y %.1f  z %.1f",
                    minecraft.player.getX(),
                    minecraft.player.getY(),
                    minecraft.player.getZ()), COLOR_DIM));
        }

        if (!recording) {
            lines.add(new Line(
                    "press " + MwarecorderKeys.toggleKeyName() + " to continue",
                    COLOR_DIM));
        }

        int x = cfg.hudOffsetX;
        int y = cfg.hudOffsetY;

        if (cfg.hudBackground) {
            int width = 0;
            for (Line line : lines) {
                width = Math.max(width, minecraft.font.width(line.text()));
            }
            graphics.fill(
                    x - PADDING,
                    y - PADDING,
                    x + width + PADDING,
                    y + lines.size() * LINE_HEIGHT + PADDING - 1,
                    COLOR_BACKGROUND);
        }

        int lineY = y;
        for (Line line : lines) {
            if (line.marker()) {
                // Filled square in front of the REC label; blinks while recording.
                boolean visible = !recording
                        || (System.currentTimeMillis() / 500L) % 2L == 0L;
                if (visible) {
                    graphics.fill(x, lineY + 2, x + 4, lineY + 6, line.color());
                }
                graphics.drawString(
                        minecraft.font, line.text(), x + 7, lineY, line.color(), true);
            } else {
                graphics.drawString(
                        minecraft.font, line.text(), x, lineY, line.color(), true);
            }
            lineY += LINE_HEIGHT;
        }
    }

    /** Shortens {@code minecraft:the_nether} to {@code the_nether} for the overlay. */
    private static String shortDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "unknown";
        }
        int colon = dimension.indexOf(':');
        return colon >= 0 ? dimension.substring(colon + 1) : dimension;
    }

    /** One overlay line. */
    private record Line(String text, int color, boolean marker) {

        private Line(String text, int color) {
            this(text, color, false);
        }
    }
}

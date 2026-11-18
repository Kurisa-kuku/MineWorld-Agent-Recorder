package com.kurisa.mwarecorder;

import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.command.MwarecorderClientCommand;
import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.event.EventRecorder;
import com.kurisa.mwarecorder.input.ActionRecorder;
import com.kurisa.mwarecorder.input.MwarecorderKeys;
import com.kurisa.mwarecorder.recorder.ContainerTracker;
import com.kurisa.mwarecorder.recorder.DimensionTracker;
import com.kurisa.mwarecorder.recorder.EpisodeManager;
import com.kurisa.mwarecorder.recorder.FrameCapture;
import com.kurisa.mwarecorder.recorder.TickRecorder;
import com.kurisa.mwarecorder.ui.MwarecorderConfigScreen;
import com.kurisa.mwarecorder.ui.MwarecorderHud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Client initialisation: input, per-tick capture, rendering hooks and the user interface.
 *
 * <p>The client owns the episode lifecycle because the player runs the recorder locally. Every
 * client tick runs the same pipeline in a fixed order:</p>
 * <ol>
 *   <li>key handling (start / stop / open settings);</li>
 *   <li>session pause / resume markers;</li>
 *   <li>state and action recording (advances {@code sample_tick});</li>
 *   <li>container screen tracking;</li>
 *   <li>dimension tracking;</li>
 *   <li>event flush.</li>
 * </ol>
 */
public class MwarecorderClient implements ClientModInitializer {

    /** Recording state observed on the previous tick, used for pause/resume markers. */
    private static boolean wasRecording;

    /** Whether an episode was active on the previous tick. */
    private static boolean wasActive;

    @Override
    public void onInitializeClient() {
        System.out.println("[Mwarecorder] Client initialising...");

        MwarecorderKeys.register();
        MwarecorderHud.register();
        MwarecorderClientCommand.register();

        registerBlockBreakEvent();
        registerClientTick();
        registerFrameCapture();
        registerDisconnectHandler();
        registerShutdownHook();
    }

    /* =====================================================================================
     * Block break
     * ===================================================================================== */

    private void registerBlockBreakEvent() {
        ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
            if (!EpisodeManager.isRecording()) {
                return;
            }

            JsonObject event = new JsonObject();
            event.addProperty("type", "block_break");

            EventRecorder.addBlockInfo(
                    event,
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(state.getBlock())
                            .toString());

            EventRecorder.queue(event, level.getGameTime());
        });
    }

    /* =====================================================================================
     * Client tick
     * ===================================================================================== */

    private void registerClientTick() {
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> {
            handleToggleKey(minecraft);
            handleSettingsKey(minecraft);
            updateSessionMarkers(minecraft);

            TickRecorder.record(minecraft);
            ActionRecorder.record(minecraft);
            ContainerTracker.tick(minecraft);
            DimensionTracker.tick(minecraft);
            EventRecorder.flush(minecraft);
        });
    }

    private void handleToggleKey(Minecraft minecraft) {
        if (MwarecorderKeys.toggleRecording == null) {
            return;
        }

        while (MwarecorderKeys.toggleRecording.consumeClick()) {
            if (EpisodeManager.isActive()) {
                String episodeId = EpisodeManager.getEpisodeId();
                Path directory = EpisodeManager.getEpisodeDirectory();

                EpisodeManager.stop("manual");

                feedback(minecraft, Component.translatable(
                        "mwarecorder.chat.stopped", episodeId));
                if (directory != null) {
                    feedback(minecraft, Component.literal(directory.toString())
                            .withStyle(ChatFormatting.GRAY));
                }
            } else if (minecraft.player == null || minecraft.level == null) {
                feedback(minecraft, Component.translatable("mwarecorder.chat.cannot_start"));
            } else if (EpisodeManager.start(minecraft)) {
                feedback(minecraft, Component.translatable(
                        "mwarecorder.chat.started", EpisodeManager.getEpisodeId()));

                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(
                            Component.translatable(
                                    "mwarecorder.chat.started_hint",
                                    MwarecorderKeys.toggleKeyName(),
                                    MwarecorderKeys.settingsKeyName()),
                            true);
                }
            } else {
                feedback(minecraft, Component.translatable("mwarecorder.chat.start_failed")
                        .withStyle(ChatFormatting.RED));
            }
        }
    }

    private void handleSettingsKey(Minecraft minecraft) {
        if (MwarecorderKeys.openSettings == null) {
            return;
        }

        while (MwarecorderKeys.openSettings.consumeClick()) {
            if (minecraft.screen instanceof MwarecorderConfigScreen) {
                continue;
            }
            minecraft.setScreen(new MwarecorderConfigScreen(minecraft.screen));
        }
    }

    /**
     * Emits {@code session_pause} / {@code session_resume} whenever the recording state flips
     * while an episode is active, so the dataset can exclude paused stretches.
     *
     * <p>The first tick after an episode becomes active only initialises the tracked state:
     * the start itself is already reported by {@code session_start}, and an episode may be
     * started by the key, by a command or by an automation hook.</p>
     */
    private void updateSessionMarkers(Minecraft minecraft) {
        boolean active = EpisodeManager.isActive();

        if (!active) {
            wasActive = false;
            wasRecording = false;
            return;
        }

        boolean nowRecording = EpisodeManager.isRecording();

        if (!wasActive) {
            wasActive = true;
            wasRecording = nowRecording;
            return;
        }

        if (nowRecording != wasRecording) {
            long worldTime = minecraft.level != null ? minecraft.level.getGameTime() : -1L;
            EventRecorder.queue(nowRecording ? "session_resume" : "session_pause", worldTime);
        }

        wasRecording = nowRecording;
    }

    /* =====================================================================================
     * Rendering
     * ===================================================================================== */

    private void registerFrameCapture() {
        WorldRenderEvents.LAST.register(context -> FrameCapture.onFrameRendered());
    }

    /* =====================================================================================
     * Shutdown paths
     * ===================================================================================== */

    private void registerDisconnectHandler() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, minecraft) -> {
            if (EpisodeManager.isActive()
                    && MwarecorderConfig.get().autoStopOnDisconnect) {
                EpisodeManager.stop("disconnect");
            }
        });
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                EpisodeManager.stop("shutdown");
            } catch (Throwable throwable) {
                // The JVM is already shutting down; log and continue.
                System.err.println("[Mwarecorder] Shutdown flush failed: " + throwable.getMessage());
            }
        }, "Mwarecorder-Shutdown"));
    }

    /* =====================================================================================
     * Helpers
     * ===================================================================================== */

    private static void feedback(Minecraft minecraft, Component message) {
        if (!MwarecorderConfig.get().chatFeedback || minecraft.player == null) {
            return;
        }
        minecraft.player.displayClientMessage(message, false);
    }
}

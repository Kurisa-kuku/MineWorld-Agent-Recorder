package com.kurisa.mwarecorder.command;

import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.recorder.EpisodeManager;
import com.kurisa.mwarecorder.recorder.RecordingStats;
import com.kurisa.mwarecorder.ui.MwarecorderConfigScreen;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/**
 * Registers the client-side {@code /mwarecorder} command.
 *
 * <p>The command mirrors what the keys and the settings screen do, which is convenient while
 * scripting a recording session or while checking the state of a running episode:</p>
 *
 * <pre>
 *   /mwarecorder start          start an episode
 *   /mwarecorder stop           stop the current episode
 *   /mwarecorder status         show counters, dimension and output path
 *   /mwarecorder config         open the settings screen
 *   /mwarecorder path           copy the episode directory to the clipboard
 *   /mwarecorder area &lt;size&gt;    set the event area (0 = unlimited)
 *   /mwarecorder hud            toggle the recording overlay
 * </pre>
 */
public final class MwarecorderClientCommand {

    private MwarecorderClientCommand() {
    }

    /** Registers the command tree. */
    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("mwarecorder")
                        .then(ClientCommandManager.literal("start")
                                .executes(context -> start(context.getSource())))
                        .then(ClientCommandManager.literal("stop")
                                .executes(context -> stop(context.getSource())))
                        .then(ClientCommandManager.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(ClientCommandManager.literal("config")
                                .executes(context -> openConfig(context.getSource())))
                        .then(ClientCommandManager.literal("path")
                                .executes(context -> copyPath(context.getSource())))
                        .then(ClientCommandManager.literal("hud")
                                .executes(context -> toggleHud(context.getSource())))
                        .then(ClientCommandManager.literal("area")
                                .then(ClientCommandManager
                                        .argument("size", IntegerArgumentType.integer(0, 256))
                                        .executes(context -> setArea(
                                                context.getSource(),
                                                IntegerArgumentType.getInteger(context, "size")))))));
    }

    /* =====================================================================================
     * Command bodies
     * ===================================================================================== */

    private static int start(FabricClientCommandSource source) {
        Minecraft minecraft = source.getClient();

        if (EpisodeManager.isActive()) {
            source.sendError(Component.translatable(
                    "mwarecorder.chat.already_active", EpisodeManager.getEpisodeId()));
            return 0;
        }

        if (!EpisodeManager.start(minecraft)) {
            source.sendError(Component.translatable("mwarecorder.chat.cannot_start"));
            return 0;
        }

        source.sendFeedback(Component.translatable(
                "mwarecorder.chat.started", EpisodeManager.getEpisodeId()));
        return 1;
    }

    private static int stop(FabricClientCommandSource source) {
        if (!EpisodeManager.isActive()) {
            source.sendError(Component.translatable("mwarecorder.chat.not_active"));
            return 0;
        }

        EpisodeManager.stop("command");
        source.sendFeedback(Component.translatable(
                "mwarecorder.chat.stopped", EpisodeManager.getEpisodeId()));
        return 1;
    }

    private static int status(FabricClientCommandSource source) {
        for (String line : EpisodeManager.statusReport()) {
            source.sendFeedback(Component.literal(line));
        }
        return 1;
    }

    private static int openConfig(FabricClientCommandSource source) {
        Minecraft minecraft = source.getClient();
        minecraft.setScreen(new MwarecorderConfigScreen(minecraft.screen));
        return 1;
    }

    private static int copyPath(FabricClientCommandSource source) {
        Minecraft minecraft = source.getClient();
        Path directory = EpisodeManager.getEpisodeDirectory();

        if (directory == null) {
            directory = MwarecorderConfig.get().resolveOutputRoot();
        }

        minecraft.keyboardHandler.setClipboard(directory.toString());
        source.sendFeedback(Component.translatable(
                "mwarecorder.chat.path_copied", directory.toString()));
        return 1;
    }

    private static int toggleHud(FabricClientCommandSource source) {
        MwarecorderConfig cfg = MwarecorderConfig.get();
        cfg.hudEnabled = !cfg.hudEnabled;
        MwarecorderConfig.save();

        source.sendFeedback(Component.translatable(
                cfg.hudEnabled ? "mwarecorder.chat.hud_on" : "mwarecorder.chat.hud_off"));
        return 1;
    }

    private static int setArea(FabricClientCommandSource source, int size) {
        MwarecorderConfig cfg = MwarecorderConfig.get();
        cfg.eventAreaSize = size;
        cfg.normalize();
        MwarecorderConfig.save();

        source.sendFeedback(Component.translatable(
                "mwarecorder.chat.area_changed",
                cfg.eventAreaSize <= 0 ? "unlimited" : cfg.eventAreaSize + "x" + cfg.eventAreaSize));
        source.sendFeedback(Component.literal(
                "filtered events so far: " + RecordingStats.filteredEvents()));
        return 1;
    }
}

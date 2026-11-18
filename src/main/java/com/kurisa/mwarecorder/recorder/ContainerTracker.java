package com.kurisa.mwarecorder.recorder;

import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.event.EventRecorder;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;

/**
 * Detects when the player opens or closes a container screen.
 *
 * <p>The client tick compares {@code minecraft.screen} with the previous tick:</p>
 * <pre>
 *   null      -> container    container_open
 *   container -> null         container_close
 *   container A -> container B close(A) + open(B)
 * </pre>
 *
 * <p>The state is kept while recording is suspended, so closing a crafting table with ESC
 * still produces the matching {@code container_close} on the next recorded tick.</p>
 */
public final class ContainerTracker {

    /** Container type observed on the previous tick; {@code null} when no screen is open. */
    private static String lastContainerType;

    private ContainerTracker() {
    }

    /** Called at the end of every client tick. */
    public static void tick(Minecraft minecraft) {
        if (!EpisodeManager.isActive()) {
            lastContainerType = null;
            return;
        }

        // Skip paused ticks but keep the previous value: the close event still has to be
        // emitted once recording resumes.
        if (!EpisodeManager.isRecording()) {
            return;
        }

        String current = containerTypeOf(minecraft.screen);

        if (lastContainerType == null && current != null) {
            queueEvent("container_open", current, minecraft);
        } else if (lastContainerType != null && current == null) {
            queueEvent("container_close", lastContainerType, minecraft);
        } else if (lastContainerType != null && !lastContainerType.equals(current)) {
            // Direct switch from one container to another.
            queueEvent("container_close", lastContainerType, minecraft);
            queueEvent("container_open", current, minecraft);
        }

        lastContainerType = current;
    }

    /** Clears the tracked state; called when an episode starts or stops. */
    public static void reset() {
        lastContainerType = null;
    }

    /* =====================================================================================
     * Helpers
     * ===================================================================================== */

    private static void queueEvent(String type, String containerType, Minecraft minecraft) {
        JsonObject event = new JsonObject();
        event.addProperty("type", type);
        event.addProperty("container_type", containerType);

        EventRecorder.queue(
                event,
                minecraft.level != null ? minecraft.level.getGameTime() : -1L);
    }

    /**
     * Maps a screen to its container id.
     *
     * @return the container id, or {@code null} when the screen is not a container
     */
    private static String containerTypeOf(Screen screen) {
        if (screen == null) {
            return null;
        }
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return null;
        }

        // InventoryMenu is constructed with a null MenuType in vanilla, so it needs a special
        // case.
        if (screen instanceof InventoryScreen) {
            return "minecraft:player_inventory";
        }

        try {
            MenuType<?> type = containerScreen.getMenu().getType();
            if (type != null) {
                ResourceLocation key = BuiltInRegistries.MENU.getKey(type);
                if (key != null) {
                    return key.toString();
                }
            }
        } catch (RuntimeException ignored) {
            // Modded menus may not be registered; fall back to the class name below.
        }

        return "unknown:" + screen.getClass().getSimpleName();
    }
}

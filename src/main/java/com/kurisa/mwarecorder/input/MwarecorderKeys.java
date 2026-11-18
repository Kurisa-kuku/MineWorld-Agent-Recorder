package com.kurisa.mwarecorder.input;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import org.lwjgl.glfw.GLFW;

/**
 * Key bindings of the mod.
 *
 * <p>Two bindings are registered in the standard "Mwarecorder" category, so they can also be
 * changed from Minecraft's own controls screen:</p>
 * <ul>
 *   <li>{@code key.mwarecorder.toggle_recording} - start / stop an episode (default K);</li>
 *   <li>{@code key.mwarecorder.open_settings} - open the recorder settings (default O).</li>
 * </ul>
 */
public final class MwarecorderKeys {

    /** Translation key of the key binding category. */
    public static final String CATEGORY = "category.mwarecorder";

    /** Starts and stops an episode. */
    public static KeyMapping toggleRecording;

    /** Opens the recorder settings screen. */
    public static KeyMapping openSettings;

    private MwarecorderKeys() {
    }

    /** Registers both bindings; called from the client initialiser. */
    public static void register() {
        toggleRecording = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mwarecorder.toggle_recording",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                CATEGORY));

        openSettings = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mwarecorder.open_settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                CATEGORY));
    }

    /**
     * Rebinds a mapping and releases the key from any other mapping that used it, mirroring
     * the behaviour of the vanilla controls screen.
     */
    public static void bind(KeyMapping mapping, InputConstants.Key key) {
        if (mapping == null || key == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        String identifier = key.getName();

        for (KeyMapping other : minecraft.options.keyMappings) {
            if (other != mapping && identifier.equals(other.saveString())) {
                other.setKey(InputConstants.UNKNOWN);
            }
        }

        mapping.setKey(key);
        KeyMapping.resetMapping();
    }

    /** Human readable toggle key, for chat messages and the settings screen. */
    public static String toggleKeyName() {
        return toggleRecording == null
                ? "unbound"
                : toggleRecording.getTranslatedKeyMessage().getString();
    }

    /** Human readable settings key. */
    public static String settingsKeyName() {
        return openSettings == null
                ? "unbound"
                : openSettings.getTranslatedKeyMessage().getString();
    }
}

package com.kurisa.mwarecorder.mixin;

import com.kurisa.mwarecorder.ui.MwarecorderConfigScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "MW Recorder" button to the pause menu.
 *
 * <p>The button sits in the top-right corner so it never collides with the vanilla menu
 * layout, and opens the same settings screen as the dedicated key.</p>
 */
@Mixin(PauseScreen.class)
public abstract class MwarecorderPauseScreenMixin extends Screen {

    /** Required by the compiler; the real constructor belongs to {@link PauseScreen}. */
    protected MwarecorderPauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void mwarecorder$addSettingsButton(CallbackInfo callback) {
        Button button = Button.builder(
                        Component.translatable("mwarecorder.gui.pause_button"),
                        pressed -> Minecraft.getInstance()
                                .setScreen(new MwarecorderConfigScreen(this)))
                .bounds(this.width - 112, 8, 104, 18)
                .build();

        this.addRenderableWidget(button);
    }
}

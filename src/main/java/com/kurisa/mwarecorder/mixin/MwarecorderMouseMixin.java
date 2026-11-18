package com.kurisa.mwarecorder.mixin;

import com.kurisa.mwarecorder.input.MouseInputRecorder;

import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds raw GLFW mouse callbacks into {@link MouseInputRecorder}.
 *
 * <p>Both hooks are client-only. Movement is accumulated rather than written immediately,
 * because the dataset is tick-aligned.</p>
 */
@Mixin(MouseHandler.class)
public class MwarecorderMouseMixin {

    @Inject(method = "onMove", at = @At("HEAD"))
    private void mwarecorder$captureMouseMovement(
            long window, double x, double y, CallbackInfo callback) {

        MouseInputRecorder.onMouseMove(x, y);
    }

    @Inject(method = "onPress", at = @At("TAIL"))
    private void mwarecorder$captureMouseButton(
            long window, int button, int action, int modifiers, CallbackInfo callback) {

        MouseInputRecorder.onMouseButton(button, action);
    }
}

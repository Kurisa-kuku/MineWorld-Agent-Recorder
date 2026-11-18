package com.kurisa.mwarecorder.input;

/**
 * Accumulates raw mouse input between client ticks.
 *
 * <p>Minecraft reports mouse movement and button presses through GLFW callbacks, which fire
 * several times per tick. The recorder sums the movement and mirrors the button state so
 * {@link ActionRecorder} can write one compact snapshot per tick.</p>
 *
 * <p>All methods are synchronised because the callbacks run on the render thread while the
 * tick writer reads the values from the game thread.</p>
 */
public final class MouseInputRecorder {

    private static boolean left;
    private static boolean middle;
    private static boolean right;

    private static double accumulatedDx;
    private static double accumulatedDy;

    private static double lastX;
    private static double lastY;
    private static boolean initialised;

    private MouseInputRecorder() {
    }

    /** Called for every cursor move; the first call only initialises the reference point. */
    public static synchronized void onMouseMove(double x, double y) {
        if (!initialised) {
            lastX = x;
            lastY = y;
            initialised = true;
            return;
        }

        accumulatedDx += x - lastX;
        accumulatedDy += y - lastY;

        lastX = x;
        lastY = y;
    }

    /** Called for every mouse button event; {@code action} is a GLFW press/release value. */
    public static synchronized void onMouseButton(int button, int action) {
        boolean pressed = action != 0;

        switch (button) {
            case 0 -> left = pressed;
            case 1 -> right = pressed;
            case 2 -> middle = pressed;
            default -> {
                // Extra buttons are not recorded.
            }
        }
    }

    public static synchronized boolean isLeftDown() {
        return left;
    }

    public static synchronized boolean isMiddleDown() {
        return middle;
    }

    public static synchronized boolean isRightDown() {
        return right;
    }

    /** Clears all accumulated state. */
    public static synchronized void reset() {
        accumulatedDx = 0.0;
        accumulatedDy = 0.0;
        initialised = false;
        lastX = 0.0;
        lastY = 0.0;
    }

    /**
     * Returns the movement accumulated since the previous call and resets it.
     *
     * @return {@code {dx, dy}}
     */
    public static synchronized double[] consume() {
        double dx = accumulatedDx;
        double dy = accumulatedDy;

        accumulatedDx = 0.0;
        accumulatedDy = 0.0;

        return new double[] {dx, dy};
    }
}

package com.kurisa.mwarecorder.ui;

import com.mojang.blaze3d.platform.InputConstants;

import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.input.MwarecorderKeys;
import com.kurisa.mwarecorder.recorder.PausesRecording;

import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * In-game settings screen for the recorder.
 *
 * <p>The screen edits the live {@link MwarecorderConfig} singleton directly. A snapshot is
 * taken when the screen opens so <em>Cancel</em> (or ESC) can roll everything back, while
 * <em>Done</em> persists the values to {@code config/mwarecorder.properties} and saves the key
 * bindings into {@code options.txt}.</p>
 *
 * <p>Settings are grouped into six tabs so the layout fits even at the smallest supported GUI
 * scale:</p>
 * <ol>
 *   <li>Video - frame capture, resolution, frame rate divisor, JPEG quality;</li>
 *   <li>State - player observation and nearby-entity limits;</li>
 *   <li>Input - keyboard and mouse action capture;</li>
 *   <li>Events - area filter and per-category switches;</li>
 *   <li>Output - save path and the recording overlay;</li>
 *   <li>Keys - recording key, settings key, interaction options, resets.</li>
 * </ol>
 */
public final class MwarecorderConfigScreen extends Screen implements PausesRecording {

    /* =====================================================================================
     * Layout constants
     * ===================================================================================== */

    private static final int ROW_HEIGHT = 20;
    private static final int CONTROL_HEIGHT = 18;
    private static final int LABEL_COLUMN = 168;
    private static final int CONTENT_TOP = 46;
    private static final int TAB_HEIGHT = 18;

    private static final int TAB_VIDEO = 0;
    private static final int TAB_STATE = 1;
    private static final int TAB_INPUT = 2;
    private static final int TAB_EVENTS = 3;
    private static final int TAB_OUTPUT = 4;
    private static final int TAB_KEYS = 5;
    private static final int TAB_COUNT = 6;

    /* =====================================================================================
     * State
     * ===================================================================================== */

    private final Screen parent;
    private final MwarecorderConfig snapshot;
    private final Map<KeyMapping, String> originalBindings = new LinkedHashMap<>();
    private final List<Label> labels = new ArrayList<>();

    private int tab = TAB_VIDEO;
    private int panelLeft;
    private int panelWidth;

    private EditBox outputField;
    private Button toggleKeyButton;
    private Button settingsKeyButton;
    private KeyMapping listening;

    private String statusText = "";
    private int statusColor = 0xFFA0A0A0;

    /**
     * @param parent screen to return to, or {@code null} for the main menu
     */
    public MwarecorderConfigScreen(Screen parent) {
        super(Component.translatable("mwarecorder.gui.title"));
        this.parent = parent;
        this.snapshot = MwarecorderConfig.get().copy();

        rememberBinding(MwarecorderKeys.toggleRecording);
        rememberBinding(MwarecorderKeys.openSettings);
    }

    private void rememberBinding(KeyMapping mapping) {
        if (mapping != null) {
            originalBindings.put(mapping, mapping.saveString());
        }
    }

    /* =====================================================================================
     * Lifecycle
     * ===================================================================================== */

    @Override
    protected void init() {
        super.init();

        this.panelWidth = Math.min(430, this.width - 16);
        this.panelLeft = (this.width - this.panelWidth) / 2;

        // Widgets are recreated on every rebuild; drop the references to the old instances.
        this.labels.clear();
        this.outputField = null;
        this.toggleKeyButton = null;
        this.settingsKeyButton = null;

        buildTabs();

        switch (this.tab) {
            case TAB_VIDEO -> buildVideoTab();
            case TAB_STATE -> buildStateTab();
            case TAB_INPUT -> buildInputTab();
            case TAB_EVENTS -> buildEventsTab();
            case TAB_OUTPUT -> buildOutputTab();
            case TAB_KEYS -> buildKeysTab();
            default -> buildVideoTab();
        }

        buildFooter();
        refreshKeyButtons();
    }

    @Override
    public void onClose() {
        cancel();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    /* =====================================================================================
     * Tabs
     * ===================================================================================== */

    private void buildTabs() {
        String[] keys = {
                "mwarecorder.gui.tab.video",
                "mwarecorder.gui.tab.state",
                "mwarecorder.gui.tab.input",
                "mwarecorder.gui.tab.events",
                "mwarecorder.gui.tab.output",
                "mwarecorder.gui.tab.keys"
        };

        int gap = 2;
        int tabWidth = (this.panelWidth - gap * (TAB_COUNT - 1)) / TAB_COUNT;
        int y = 22;

        for (int index = 0; index < TAB_COUNT; index++) {
            final int target = index;
            Component message = Component.translatable(keys[index]);
            if (index == this.tab) {
                message = message.copy().withStyle(ChatFormatting.YELLOW);
            }

            Button button = Button.builder(message, pressed -> {
                if (this.tab != target) {
                    this.tab = target;
                    this.statusText = "";
                    rebuildWidgets();
                }
            }).bounds(this.panelLeft + index * (tabWidth + gap), y, tabWidth, TAB_HEIGHT).build();

            addRenderableWidget(button);
        }
    }

    /* =====================================================================================
     * Video tab
     * ===================================================================================== */

    private void buildVideoTab() {
        MwarecorderConfig cfg = MwarecorderConfig.get();

        addToggle(0, "mwarecorder.gui.frames.enabled",
                () -> cfg.frameCaptureEnabled,
                value -> cfg.frameCaptureEnabled = value);

        List<Resolution> resolutions = new ArrayList<>();
        for (int[] choice : MwarecorderConfig.RESOLUTION_CHOICES) {
            resolutions.add(new Resolution(choice[0], choice[1]));
        }

        addChoice(1, "mwarecorder.gui.frames.resolution",
                resolutions,
                () -> new Resolution(cfg.targetWidth, cfg.targetHeight),
                choice -> {
                    cfg.targetWidth = choice.width();
                    cfg.targetHeight = choice.height();
                },
                resolution -> resolution.width() <= 0
                        ? Component.translatable("mwarecorder.gui.resolution.native")
                        : Component.literal(resolution.width() + " x " + resolution.height()));

        addSlider(2, "mwarecorder.gui.frames.every_n",
                1, 10, 1,
                () -> cfg.everyNFrames,
                value -> cfg.everyNFrames = (int) Math.round(value),
                value -> Component.literal(String.valueOf((int) Math.round(value))));

        addSlider(3, "mwarecorder.gui.frames.quality",
                0.3, 1.0, 0.05,
                () -> cfg.jpegQuality,
                value -> cfg.jpegQuality = (float) value,
                value -> Component.literal(String.format("%.2f", value)));

        addSlider(4, "mwarecorder.gui.frames.max_pending",
                8, 256, 8,
                () -> cfg.maxPendingFrames,
                value -> cfg.maxPendingFrames = (int) Math.round(value),
                value -> Component.literal(String.valueOf((int) Math.round(value))));
    }

    /* =====================================================================================
     * State tab
     * ===================================================================================== */

    private void buildStateTab() {
        MwarecorderConfig cfg = MwarecorderConfig.get();

        addToggle(0, "mwarecorder.gui.states.enabled",
                () -> cfg.stateCaptureEnabled,
                value -> cfg.stateCaptureEnabled = value);

        addSlider(1, "mwarecorder.gui.states.radius",
                4, 64, 1,
                () -> cfg.nearbyRadius,
                value -> cfg.nearbyRadius = value,
                value -> Component.literal((int) Math.round(value) + " m"));

        addSlider(2, "mwarecorder.gui.states.max",
                0, 96, 4,
                () -> cfg.nearbyMax,
                value -> cfg.nearbyMax = (int) Math.round(value),
                value -> Component.literal(String.valueOf((int) Math.round(value))));

        addSlider(3, "mwarecorder.gui.states.look_reach",
                2, 32, 1,
                () -> cfg.lookReach,
                value -> cfg.lookReach = value,
                value -> Component.literal((int) Math.round(value) + " m"));

        addToggle(4, "mwarecorder.gui.states.inventory",
                () -> cfg.includeInventory,
                value -> cfg.includeInventory = value);

        addToggle(5, "mwarecorder.gui.states.environment",
                () -> cfg.includeEnvironment,
                value -> cfg.includeEnvironment = value);
    }

    /* =====================================================================================
     * Input tab
     * ===================================================================================== */

    private void buildInputTab() {
        MwarecorderConfig cfg = MwarecorderConfig.get();

        addToggle(0, "mwarecorder.gui.actions.enabled",
                () -> cfg.actionCaptureEnabled,
                value -> cfg.actionCaptureEnabled = value);

        addToggle(1, "mwarecorder.gui.actions.keyboard",
                () -> cfg.recordKeyboard,
                value -> cfg.recordKeyboard = value);

        addToggle(2, "mwarecorder.gui.actions.mouse",
                () -> cfg.recordMouse,
                value -> cfg.recordMouse = value);
    }

    /* =====================================================================================
     * Events tab
     * ===================================================================================== */

    private void buildEventsTab() {
        MwarecorderConfig cfg = MwarecorderConfig.get();

        addToggle(0, "mwarecorder.gui.events.enabled",
                () -> cfg.eventCaptureEnabled,
                value -> cfg.eventCaptureEnabled = value);

        List<Integer> areas = new ArrayList<>();
        for (int choice : MwarecorderConfig.AREA_SIZE_CHOICES) {
            areas.add(choice);
        }

        addChoice(1, "mwarecorder.gui.events.area",
                areas,
                () -> cfg.eventAreaSize,
                value -> cfg.eventAreaSize = value,
                size -> size <= 0
                        ? Component.translatable("mwarecorder.gui.area.unlimited")
                        : Component.literal(size + " x " + size));

        addToggle(2, "mwarecorder.gui.events.block",
                () -> cfg.eventBlock, value -> cfg.eventBlock = value);
        addToggle(3, "mwarecorder.gui.events.entity",
                () -> cfg.eventEntity, value -> cfg.eventEntity = value);
        addToggle(4, "mwarecorder.gui.events.container",
                () -> cfg.eventContainer, value -> cfg.eventContainer = value);
        addToggle(5, "mwarecorder.gui.events.item",
                () -> cfg.eventItem, value -> cfg.eventItem = value);
        addToggle(6, "mwarecorder.gui.events.session",
                () -> cfg.eventSession, value -> cfg.eventSession = value);
        addToggle(7, "mwarecorder.gui.events.dimension",
                () -> cfg.eventDimension, value -> cfg.eventDimension = value);
    }

    /* =====================================================================================
     * Output tab
     * ===================================================================================== */

    private void buildOutputTab() {
        MwarecorderConfig cfg = MwarecorderConfig.get();

        label(0, "mwarecorder.gui.output.directory");
        this.outputField = new EditBox(
                this.font,
                controlX(),
                rowY(0),
                controlWidth(),
                CONTROL_HEIGHT,
                Component.translatable("mwarecorder.gui.output.directory"));
        this.outputField.setMaxLength(260);
        this.outputField.setValue(cfg.outputDirectory);
        this.outputField.setResponder(text -> cfg.outputDirectory = text.trim());
        addRenderableWidget(this.outputField);

        int half = (controlWidth() - 4) / 2;

        button(controlX(), rowY(1), half, CONTROL_HEIGHT,
                Component.translatable("mwarecorder.gui.output.open_folder"),
                pressed -> openOutputFolder());

        button(controlX() + half + 4, rowY(1), half, CONTROL_HEIGHT,
                Component.translatable("mwarecorder.gui.output.copy_path"),
                pressed -> copyOutputPath());

        addToggle(2, "mwarecorder.gui.output.hud_enabled",
                () -> cfg.hudEnabled, value -> cfg.hudEnabled = value);
        addToggle(3, "mwarecorder.gui.output.hud_background",
                () -> cfg.hudBackground, value -> cfg.hudBackground = value);
        addToggle(4, "mwarecorder.gui.output.hud_timer",
                () -> cfg.hudTimer, value -> cfg.hudTimer = value);
        addToggle(5, "mwarecorder.gui.output.hud_counters",
                () -> cfg.hudCounters, value -> cfg.hudCounters = value);
        addToggle(6, "mwarecorder.gui.output.hud_dimension",
                () -> cfg.hudDimension, value -> cfg.hudDimension = value);
        addToggle(7, "mwarecorder.gui.output.hud_position",
                () -> cfg.hudPosition, value -> cfg.hudPosition = value);
    }

    /* =====================================================================================
     * Keys tab
     * ===================================================================================== */

    private void buildKeysTab() {
        MwarecorderConfig cfg = MwarecorderConfig.get();

        this.toggleKeyButton = keyRow(0, "mwarecorder.gui.keys.toggle",
                MwarecorderKeys.toggleRecording);
        this.settingsKeyButton = keyRow(1, "mwarecorder.gui.keys.settings",
                MwarecorderKeys.openSettings);

        addToggle(2, "mwarecorder.gui.keys.chat_feedback",
                () -> cfg.chatFeedback, value -> cfg.chatFeedback = value);
        addToggle(3, "mwarecorder.gui.keys.auto_stop",
                () -> cfg.autoStopOnDisconnect, value -> cfg.autoStopOnDisconnect = value);

        int half = (controlWidth() - 4) / 2;

        button(controlX(), rowY(4), half, CONTROL_HEIGHT,
                Component.translatable("mwarecorder.gui.keys.reset_keys"),
                pressed -> resetKeyBindings());

        button(controlX() + half + 4, rowY(4), half, CONTROL_HEIGHT,
                Component.translatable("mwarecorder.gui.keys.reset_all"),
                pressed -> resetAllSettings());
    }

    private Button keyRow(int row, String labelKey, KeyMapping mapping) {
        label(row, labelKey);

        Button button = Button.builder(keyText(mapping), pressed -> beginListening(mapping))
                .bounds(controlX(), rowY(row), controlWidth(), CONTROL_HEIGHT)
                .build();

        addRenderableWidget(button);
        return button;
    }

    /* =====================================================================================
     * Footer
     * ===================================================================================== */

    private void buildFooter() {
        int buttonWidth = 100;
        int y = this.height - 26;
        int center = this.width / 2;

        button(center - buttonWidth - 2, y, buttonWidth, CONTROL_HEIGHT,
                Component.translatable("mwarecorder.gui.done"),
                pressed -> done());

        button(center + 2, y, buttonWidth, CONTROL_HEIGHT,
                Component.translatable("mwarecorder.gui.cancel"),
                pressed -> cancel());
    }

    /* =====================================================================================
     * Widget helpers
     * ===================================================================================== */

    private int rowY(int row) {
        return CONTENT_TOP + row * ROW_HEIGHT;
    }

    private int controlX() {
        return this.panelLeft + LABEL_COLUMN;
    }

    private int controlWidth() {
        return this.panelWidth - LABEL_COLUMN - 4;
    }

    private void label(int row, String translationKey) {
        this.labels.add(new Label(Component.translatable(translationKey), rowY(row) + 5));
    }

    private Button button(int x, int y, int width, int height, Component message,
            Button.OnPress onPress) {
        Button button = Button.builder(message, onPress).bounds(x, y, width, height).build();
        addRenderableWidget(button);
        return button;
    }

    private void addToggle(int row, String labelKey, BooleanSupplier getter,
            Consumer<Boolean> setter) {
        label(row, labelKey);

        Button button = Button.builder(toggleText(getter.getAsBoolean()), pressed -> {
            setter.accept(!getter.getAsBoolean());
            pressed.setMessage(toggleText(getter.getAsBoolean()));
        }).bounds(controlX(), rowY(row), controlWidth(), CONTROL_HEIGHT).build();

        addRenderableWidget(button);
    }

    private <T> void addChoice(int row, String labelKey, List<T> choices,
            Supplier<T> getter, Consumer<T> setter,
            Function<T, Component> formatter) {
        label(row, labelKey);

        Button button = Button.builder(formatter.apply(getter.get()), pressed -> {
            T current = getter.get();
            int index = indexOf(choices, current);
            T next = choices.get((index + 1) % choices.size());
            setter.accept(next);
            pressed.setMessage(formatter.apply(next));
        }).bounds(controlX(), rowY(row), controlWidth(), CONTROL_HEIGHT).build();

        addRenderableWidget(button);
    }

    private static <T> int indexOf(List<T> choices, T current) {
        for (int index = 0; index < choices.size(); index++) {
            if (choices.get(index).equals(current)) {
                return index;
            }
        }
        return 0;
    }

    private void addSlider(int row, String labelKey, double min, double max, double step,
            DoubleSupplier getter, DoubleConsumer setter, Function<Double, Component> formatter) {
        label(row, labelKey);

        ValueSlider slider = new ValueSlider(
                controlX(), rowY(row), controlWidth(), CONTROL_HEIGHT,
                min, max, step, getter.getAsDouble(), formatter, setter);

        addRenderableWidget(slider);
    }

    private static Component toggleText(boolean enabled) {
        return enabled
                ? Component.translatable("mwarecorder.gui.on").withStyle(ChatFormatting.GREEN)
                : Component.translatable("mwarecorder.gui.off").withStyle(ChatFormatting.RED);
    }

    /* =====================================================================================
     * Key binding
     * ===================================================================================== */

    private void beginListening(KeyMapping mapping) {
        if (mapping == null) {
            return;
        }
        this.listening = mapping;
        setStatus(Component.translatable("mwarecorder.gui.status.listening"), 0xFFFFAA00);
        refreshKeyButtons();
    }

    private Component keyText(KeyMapping mapping) {
        if (mapping == null) {
            return Component.literal("-");
        }
        if (mapping == this.listening) {
            return Component.translatable("mwarecorder.gui.status.listening")
                    .copy()
                    .withStyle(ChatFormatting.YELLOW);
        }
        return Component.literal("[ " + mapping.getTranslatedKeyMessage().getString() + " ]");
    }

    private void refreshKeyButtons() {
        if (this.toggleKeyButton != null) {
            this.toggleKeyButton.setMessage(keyText(MwarecorderKeys.toggleRecording));
        }
        if (this.settingsKeyButton != null) {
            this.settingsKeyButton.setMessage(keyText(MwarecorderKeys.openSettings));
        }
    }

    private void resetKeyBindings() {
        MwarecorderKeys.bind(MwarecorderKeys.toggleRecording, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K));
        MwarecorderKeys.bind(MwarecorderKeys.openSettings, InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_O));
        refreshKeyButtons();
        setStatus(Component.translatable("mwarecorder.gui.status.keys_reset"), 0xFF55FF55);
    }

    private void restoreOriginalBindings() {
        originalBindings.forEach((mapping, saved) -> {
            try {
                mapping.setKey(InputConstants.getKey(saved));
            } catch (RuntimeException ignored) {
                // The stored identifier is unknown on this platform; keep the current key.
            }
        });
        KeyMapping.resetMapping();
    }

    /* =====================================================================================
     * Input handling
     * ===================================================================================== */

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.listening != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                this.listening = null;
                setStatus(Component.empty(), 0xFFA0A0A0);
                refreshKeyButtons();
                return true;
            }

            MwarecorderKeys.bind(this.listening, InputConstants.getKey(keyCode, scanCode));
            this.listening = null;
            setStatus(Component.translatable("mwarecorder.gui.status.key_changed"), 0xFF55FF55);
            refreshKeyButtons();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.listening != null) {
            if (button >= 0 && button <= 2) {
                MwarecorderKeys.bind(
                        this.listening,
                        InputConstants.Type.MOUSE.getOrCreate(button));
                this.listening = null;
                setStatus(Component.translatable("mwarecorder.gui.status.key_changed"), 0xFF55FF55);
                refreshKeyButtons();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /* =====================================================================================
     * Actions
     * ===================================================================================== */

    private void done() {
        MwarecorderConfig cfg = MwarecorderConfig.get();
        cfg.normalize();
        MwarecorderConfig.save();

        if (this.minecraft != null) {
            this.minecraft.options.save();
            this.minecraft.setScreen(this.parent);
        }
    }

    private void cancel() {
        MwarecorderConfig.get().assign(this.snapshot);
        restoreOriginalBindings();

        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    private void resetAllSettings() {
        MwarecorderConfig.get().resetToDefaults();
        rebuildWidgets();
        setStatus(Component.translatable("mwarecorder.gui.status.defaults"), 0xFF55FF55);
    }

    /** Creates the configured directory and opens it in the operating system. */
    private void openOutputFolder() {
        try {
            Path root = MwarecorderConfig.get().resolveOutputRoot();
            Files.createDirectories(root);
            Util.getPlatform().openPath(root);
            setStatus(Component.translatable(
                    "mwarecorder.gui.status.folder_opened", root.toString()), 0xFF55FF55);
        } catch (IOException | RuntimeException e) {
            setStatus(Component.translatable(
                    "mwarecorder.gui.status.invalid_path", String.valueOf(e.getMessage())), 0xFFFF5555);
        }
    }

    private void copyOutputPath() {
        if (this.minecraft == null) {
            return;
        }
        Path root = MwarecorderConfig.get().resolveOutputRoot();
        this.minecraft.keyboardHandler.setClipboard(root.toString());
        setStatus(Component.translatable("mwarecorder.gui.status.path_copied"), 0xFF55FF55);
    }

    private void setStatus(Component message, int color) {
        this.statusText = message.getString();
        this.statusColor = color;
    }

    /* =====================================================================================
     * Rendering
     * ===================================================================================== */

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(this.font, this.title, this.panelLeft, 8, 0xFFFFFFFF, true);

        for (Label label : this.labels) {
            graphics.drawString(
                    this.font, label.text(), this.panelLeft + 4, label.y(),
                    0xFFD0D0D0, false);
        }

        if (!this.statusText.isEmpty()) {
            // The status line shares the title row; it is clipped to the space the title
            // leaves so it can never overlap the controls on short screens.
            int available = this.panelWidth - this.font.width(this.title) - 12;
            String text = this.statusText;

            if (this.font.width(text) > available) {
                if (available < this.font.width("...")) {
                    text = "";
                } else {
                    while (!text.isEmpty()
                            && this.font.width(text + "...") > available) {
                        text = text.substring(0, text.length() - 1);
                    }
                    text = text + "...";
                }
            }

            if (!text.isEmpty()) {
                graphics.drawString(
                        this.font, text,
                        this.panelLeft + this.panelWidth - this.font.width(text),
                        8, this.statusColor, false);
            }
        }

        // Descriptive hint only when the window is tall enough not to overlap the rows.
        if (this.height >= 300) {
            Component hint = Component.translatable("mwarecorder.gui.hint");
            graphics.drawString(
                    this.font, hint,
                    this.panelLeft, this.height - 52,
                    0xFF808080, false);
        }
    }

    /* =====================================================================================
     * Small value types
     * ===================================================================================== */

    private record Label(Component text, int y) {
    }

    /** One of the resolution presets. */
    private record Resolution(int width, int height) {
    }

    /** Slider that snaps to a step and formats its own label. */
    private static final class ValueSlider extends AbstractSliderButton {

        private final double min;
        private final double max;
        private final double step;
        private final Function<Double, Component> formatter;
        private final DoubleConsumer onChange;

        private ValueSlider(int x, int y, int width, int height,
                double min, double max, double step, double current,
                Function<Double, Component> formatter, DoubleConsumer onChange) {
            super(x, y, width, height, Component.empty(),
                    (current - min) / Math.max(1.0E-6, max - min));
            this.min = min;
            this.max = max;
            this.step = step;
            this.formatter = formatter;
            this.onChange = onChange;
            updateMessage();
        }

        private double currentValue() {
            double raw = this.min + this.value * (this.max - this.min);
            if (this.step <= 0.0) {
                return raw;
            }
            double snapped = Math.round((raw - this.min) / this.step) * this.step + this.min;
            return Math.max(this.min, Math.min(this.max, snapped));
        }

        @Override
        protected void updateMessage() {
            setMessage(this.formatter.apply(currentValue()));
        }

        @Override
        protected void applyValue() {
            this.onChange.accept(currentValue());
        }
    }
}

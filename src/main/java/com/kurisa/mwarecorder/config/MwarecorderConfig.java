package com.kurisa.mwarecorder.config;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Global configuration of the Mwarecorder mod.
 *
 * <p>The configuration is a plain {@code .properties} file located at
 * {@code <game_dir>/config/mwarecorder.properties}. It is created on first launch, loaded on
 * every launch, and rewritten by {@link #save()} whenever the player changes something in the
 * in-game settings screen.</p>
 *
 * <p>The class is a mutable singleton so the settings screen can edit the live object and then
 * either save it or roll back to a {@link #copy() snapshot}. All values are sanitised by
 * {@link #normalize()} before they are used, so a hand-edited file can never break recording.</p>
 */
public final class MwarecorderConfig {

    /** File name inside the Fabric config directory. */
    public static final String FILE_NAME = "mwarecorder.properties";

    /** Schema version written into the file; bumped when semantics change. */
    public static final int CONFIG_VERSION = 2;

    /** Default relative output directory. */
    public static final String DEFAULT_OUTPUT_DIRECTORY = "mwarecorder_data";

    /** Valid area sizes offered by the settings screen (0 means "unlimited"). */
    public static final int[] AREA_SIZE_CHOICES = {0, 16, 32, 48, 64, 96, 128};

    /** Valid frame resolutions offered by the settings screen (0,0 means "native"). */
    public static final int[][] RESOLUTION_CHOICES = {
            {0, 0},
            {1920, 1080},
            {1280, 720},
            {960, 540},
            {640, 360},
            {480, 270},
            {320, 180}
    };

    private static volatile MwarecorderConfig instance = new MwarecorderConfig();

    /* =====================================================================================
     * Output
     * ===================================================================================== */

    /**
     * Directory that receives the recorded episodes. Absolute paths are used verbatim,
     * relative paths are resolved against the game directory, and a leading {@code ~} is
     * expanded to the user home directory.
     */
    public String outputDirectory = DEFAULT_OUTPUT_DIRECTORY;

    /** Whether {@code metadata.json} is written for every episode. */
    public boolean writeMetadata = true;

    /* =====================================================================================
     * Frame capture
     * ===================================================================================== */

    /** Master switch for JPEG frame capture. */
    public boolean frameCaptureEnabled = true;

    /** Capture one frame every N rendered frames (1 = every frame). */
    public int everyNFrames = 2;

    /** JPEG compression quality in the range [0.1, 1.0]. */
    public float jpegQuality = 0.85f;

    /** Target frame width; {@code 0} keeps the native size. */
    public int targetWidth = 640;

    /** Target frame height; {@code 0} keeps the native size. */
    public int targetHeight = 360;

    /** Maximum number of frames waiting for the encoder before new captures are dropped. */
    public int maxPendingFrames = 64;

    /* =====================================================================================
     * Player state capture
     * ===================================================================================== */

    /** Master switch for the per-tick player state stream. */
    public boolean stateCaptureEnabled = true;

    /** Radius (in blocks) used when scanning nearby entities. */
    public double nearbyRadius = 16.0;

    /** Maximum number of nearby entities written per tick. */
    public int nearbyMax = 32;

    /** Maximum distance (in blocks) used by the "looking at" ray cast. */
    public double lookReach = 8.0;

    /** Whether the full 36-slot inventory is written every tick (heavy, off by default). */
    public boolean includeInventory = false;

    /** Whether environment details (biome, light, weather) are written every tick. */
    public boolean includeEnvironment = true;

    /* =====================================================================================
     * Action capture
     * ===================================================================================== */

    /** Master switch for the per-tick action stream. */
    public boolean actionCaptureEnabled = true;

    /** Record the seven movement keys. */
    public boolean recordKeyboard = true;

    /** Record mouse movement deltas and buttons. */
    public boolean recordMouse = true;

    /* =====================================================================================
     * Event capture
     * ===================================================================================== */

    /** Master switch for the event stream. */
    public boolean eventCaptureEnabled = true;

    /**
     * Side length in blocks of the square, centred on the player, inside which positional
     * events are kept. {@code 0} disables the filter and records events everywhere.
     */
    public int eventAreaSize = 32;

    /** Block break / place / interact events. */
    public boolean eventBlock = true;

    /** Entity events: hurt, death, attack, interaction and respawn. */
    public boolean eventEntity = true;

    /** Container open / close / slot change and crafting / smelting events. */
    public boolean eventContainer = true;

    /** Item use, crafting, smelting and pickup events. */
    public boolean eventItem = true;

    /** Session pause / resume / start markers. */
    public boolean eventSession = true;

    /** Dimension change markers. */
    public boolean eventDimension = true;

    /* =====================================================================================
     * Heads-up display
     * ===================================================================================== */

    /** Whether the recording overlay is drawn at all. */
    public boolean hudEnabled = true;

    /** Whether the overlay draws a translucent background box. */
    public boolean hudBackground = true;

    /** Show the elapsed recording time. */
    public boolean hudTimer = true;

    /** Show frame / state / action / event counters. */
    public boolean hudCounters = true;

    /** Show the current dimension. */
    public boolean hudDimension = true;

    /** Show the player coordinates. */
    public boolean hudPosition = true;

    /** Horizontal offset of the overlay in scaled GUI pixels. */
    public int hudOffsetX = 4;

    /** Vertical offset of the overlay in scaled GUI pixels. */
    public int hudOffsetY = 4;

    /* =====================================================================================
     * Interaction
     * ===================================================================================== */

    /** Print hints and result summaries in the chat. */
    public boolean chatFeedback = true;

    /** Stop the episode automatically when the player leaves the world. */
    public boolean autoStopOnDisconnect = true;

    private MwarecorderConfig() {
    }

    /* =====================================================================================
     * Access
     * ===================================================================================== */

    public static MwarecorderConfig get() {
        return instance;
    }

    /** Path of the configuration file. */
    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /* =====================================================================================
     * Copy / restore
     * ===================================================================================== */

    /** Returns a deep copy; used to implement "Cancel" in the settings screen. */
    public MwarecorderConfig copy() {
        MwarecorderConfig copy = new MwarecorderConfig();
        copy.assign(this);
        return copy;
    }

    /** Copies every field from {@code other} into this instance. */
    public void assign(MwarecorderConfig other) {
        if (other == null || other == this) {
            return;
        }
        outputDirectory = other.outputDirectory;
        writeMetadata = other.writeMetadata;

        frameCaptureEnabled = other.frameCaptureEnabled;
        everyNFrames = other.everyNFrames;
        jpegQuality = other.jpegQuality;
        targetWidth = other.targetWidth;
        targetHeight = other.targetHeight;
        maxPendingFrames = other.maxPendingFrames;

        stateCaptureEnabled = other.stateCaptureEnabled;
        nearbyRadius = other.nearbyRadius;
        nearbyMax = other.nearbyMax;
        lookReach = other.lookReach;
        includeInventory = other.includeInventory;
        includeEnvironment = other.includeEnvironment;

        actionCaptureEnabled = other.actionCaptureEnabled;
        recordKeyboard = other.recordKeyboard;
        recordMouse = other.recordMouse;

        eventCaptureEnabled = other.eventCaptureEnabled;
        eventAreaSize = other.eventAreaSize;
        eventBlock = other.eventBlock;
        eventEntity = other.eventEntity;
        eventContainer = other.eventContainer;
        eventItem = other.eventItem;
        eventSession = other.eventSession;
        eventDimension = other.eventDimension;

        hudEnabled = other.hudEnabled;
        hudBackground = other.hudBackground;
        hudTimer = other.hudTimer;
        hudCounters = other.hudCounters;
        hudDimension = other.hudDimension;
        hudPosition = other.hudPosition;
        hudOffsetX = other.hudOffsetX;
        hudOffsetY = other.hudOffsetY;

        chatFeedback = other.chatFeedback;
        autoStopOnDisconnect = other.autoStopOnDisconnect;
    }

    /** Restores every field to its factory default. */
    public void resetToDefaults() {
        assign(new MwarecorderConfig());
    }

    /* =====================================================================================
     * Load
     * ===================================================================================== */

    /** Loads the configuration file and publishes the resulting singleton. */
    public static void load() {
        Path path = configPath();
        Properties props = new Properties();

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                System.err.println("[Mwarecorder] Failed to read config: " + e.getMessage());
            }
        }

        MwarecorderConfig loaded = new MwarecorderConfig();

        loaded.outputDirectory = getString(
                props, "output.directory", "data.output_directory", DEFAULT_OUTPUT_DIRECTORY);
        loaded.writeMetadata = getBoolean(props, "output.write_metadata", true);

        loaded.frameCaptureEnabled = getBoolean(
                props, "frames.enabled", "frame_capture.enabled", true);
        loaded.everyNFrames = getInt(
                props, "frames.every_n_frames", "frame_capture.every_n_frames", 2);
        loaded.jpegQuality = getFloat(
                props, "frames.jpeg_quality", "frame_capture.jpeg_quality", 0.85f);
        loaded.targetWidth = getInt(
                props, "frames.width", "frame_capture.target_width", 640);
        loaded.targetHeight = getInt(
                props, "frames.height", "frame_capture.target_height", 360);
        loaded.maxPendingFrames = getInt(props, "frames.max_pending", 64);

        loaded.stateCaptureEnabled = getBoolean(props, "states.enabled", true);
        loaded.nearbyRadius = getDouble(props, "states.nearby_radius", 16.0);
        loaded.nearbyMax = getInt(props, "states.nearby_max", 32);
        loaded.lookReach = getDouble(props, "states.look_reach", 8.0);
        loaded.includeInventory = getBoolean(props, "states.include_inventory", false);
        loaded.includeEnvironment = getBoolean(props, "states.include_environment", true);

        loaded.actionCaptureEnabled = getBoolean(props, "actions.enabled", true);
        loaded.recordKeyboard = getBoolean(props, "actions.keyboard", true);
        loaded.recordMouse = getBoolean(props, "actions.mouse", true);

        loaded.eventCaptureEnabled = getBoolean(props, "events.enabled", true);
        loaded.eventAreaSize = getInt(props, "events.area_size", 32);
        loaded.eventBlock = getBoolean(props, "events.block", true);
        loaded.eventEntity = getBoolean(props, "events.entity", "events.combat", true);
        loaded.eventContainer = getBoolean(props, "events.container", true);
        loaded.eventItem = getBoolean(props, "events.item", true);
        loaded.eventSession = getBoolean(props, "events.session", true);
        loaded.eventDimension = getBoolean(props, "events.dimension", true);

        loaded.hudEnabled = getBoolean(props, "hud.enabled", true);
        loaded.hudBackground = getBoolean(props, "hud.background", true);
        loaded.hudTimer = getBoolean(props, "hud.timer", true);
        loaded.hudCounters = getBoolean(props, "hud.counters", true);
        loaded.hudDimension = getBoolean(props, "hud.dimension", true);
        loaded.hudPosition = getBoolean(props, "hud.position", true);
        loaded.hudOffsetX = getInt(props, "hud.offset_x", 4);
        loaded.hudOffsetY = getInt(props, "hud.offset_y", 4);

        loaded.chatFeedback = getBoolean(props, "ui.chat_feedback", true);
        loaded.autoStopOnDisconnect = getBoolean(props, "ui.auto_stop_on_disconnect", true);

        loaded.normalize();
        instance = loaded;

        // Always rewrite the file so newly added keys become visible to the user.
        save();

        System.out.println("[Mwarecorder] Config loaded from " + path);
    }

    /* =====================================================================================
     * Save
     * ===================================================================================== */

    /** Sanitises and writes the current configuration. */
    public static void save() {
        MwarecorderConfig cfg = instance;
        cfg.normalize();

        List<Line> lines = new ArrayList<>();

        lines.add(Line.comment(""));
        lines.add(Line.comment("Mwarecorder configuration (schema version " + CONFIG_VERSION + ")"));
        lines.add(Line.comment(""));
        lines.add(Line.comment("This file is read at startup and rewritten by the in-game"));
        lines.add(Line.comment("settings screen (default key: O). Values are clamped to safe"));
        lines.add(Line.comment("ranges, so a hand-edited file can never break recording."));
        lines.add(Line.comment(""));

        lines.add(Line.comment("---------------------------------------------"));
        lines.add(Line.comment("Output"));
        lines.add(Line.comment("---------------------------------------------"));
        lines.add(Line.comment("Absolute paths are used as-is, relative paths are resolved"));
        lines.add(Line.comment("against the game directory. Forward slashes are recommended."));
        lines.add(new Line("output.directory", cfg.outputDirectory));
        lines.add(new Line("output.write_metadata", cfg.writeMetadata));
        lines.add(Line.comment(""));

        lines.add(Line.comment("---------------------------------------------"));
        lines.add(Line.comment("Frame capture (video frames as JPEG)"));
        lines.add(Line.comment("---------------------------------------------"));
        lines.add(Line.comment("every_n_frames: capture one frame every N rendered frames."));
        lines.add(Line.comment("width / height : 0 keeps the native window resolution."));
        lines.add(Line.comment("max_pending    : frames queued for the encoder before new ones"));
        lines.add(Line.comment("                 are dropped (protects memory on slow disks)."));
        lines.add(new Line("frames.enabled", cfg.frameCaptureEnabled));
        lines.add(new Line("frames.every_n_frames", cfg.everyNFrames));
        lines.add(new Line("frames.jpeg_quality", cfg.jpegQuality));
        lines.add(new Line("frames.width", cfg.targetWidth));
        lines.add(new Line("frames.height", cfg.targetHeight));
        lines.add(new Line("frames.max_pending", cfg.maxPendingFrames));
        lines.add(Line.comment(""));

        lines.add(Line.comment("---------------------------------------------"));
        lines.add(Line.comment("Player state capture (one record per tick)"));
        lines.add(Line.comment("---------------------------------------------"));
        lines.add(new Line("states.enabled", cfg.stateCaptureEnabled));
        lines.add(new Line("states.nearby_radius", cfg.nearbyRadius));
        lines.add(new Line("states.nearby_max", cfg.nearbyMax));
        lines.add(new Line("states.look_reach", cfg.lookReach));
        lines.add(new Line("states.include_inventory", cfg.includeInventory));
        lines.add(new Line("states.include_environment", cfg.includeEnvironment));
        lines.add(Line.comment(""));

        lines.add(Line.comment("---------------------------------------------"));
        lines.add(Line.comment("Action capture (keys and mouse, one record per tick)"));
        lines.add(Line.comment("---------------------------------------------"));
        lines.add(new Line("actions.enabled", cfg.actionCaptureEnabled));
        lines.add(new Line("actions.keyboard", cfg.recordKeyboard));
        lines.add(new Line("actions.mouse", cfg.recordMouse));
        lines.add(Line.comment(""));

        lines.add(Line.comment("---------------------------------------------"));
        lines.add(Line.comment("Event capture"));
        lines.add(Line.comment("---------------------------------------------"));
        lines.add(Line.comment("area_size: side length of the square, centred on the player,"));
        lines.add(Line.comment("           inside which positional events are recorded."));
        lines.add(Line.comment("           0 = no limit (record the whole dimension)."));
        lines.add(new Line("events.enabled", cfg.eventCaptureEnabled));
        lines.add(new Line("events.area_size", cfg.eventAreaSize));
        lines.add(new Line("events.block", cfg.eventBlock));
        lines.add(new Line("events.entity", cfg.eventEntity));
        lines.add(new Line("events.container", cfg.eventContainer));
        lines.add(new Line("events.item", cfg.eventItem));
        lines.add(new Line("events.session", cfg.eventSession));
        lines.add(new Line("events.dimension", cfg.eventDimension));
        lines.add(Line.comment(""));

        lines.add(Line.comment("---------------------------------------------"));
        lines.add(Line.comment("Recording overlay (HUD)"));
        lines.add(Line.comment("---------------------------------------------"));
        lines.add(new Line("hud.enabled", cfg.hudEnabled));
        lines.add(new Line("hud.background", cfg.hudBackground));
        lines.add(new Line("hud.timer", cfg.hudTimer));
        lines.add(new Line("hud.counters", cfg.hudCounters));
        lines.add(new Line("hud.dimension", cfg.hudDimension));
        lines.add(new Line("hud.position", cfg.hudPosition));
        lines.add(new Line("hud.offset_x", cfg.hudOffsetX));
        lines.add(new Line("hud.offset_y", cfg.hudOffsetY));
        lines.add(Line.comment(""));

        lines.add(Line.comment("---------------------------------------------"));
        lines.add(Line.comment("Interaction"));
        lines.add(Line.comment("---------------------------------------------"));
        lines.add(new Line("ui.chat_feedback", cfg.chatFeedback));
        lines.add(new Line("ui.auto_stop_on_disconnect", cfg.autoStopOnDisconnect));
        lines.add(new Line("config.version", CONFIG_VERSION));

        StringBuilder text = new StringBuilder();
        for (Line line : lines) {
            text.append(line.render()).append('\n');
        }

        try {
            Path path = configPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[Mwarecorder] Failed to save config: " + e.getMessage());
        }
    }

    /* =====================================================================================
     * Derived values
     * ===================================================================================== */

    /** Whether at least one data stream is enabled. */
    public boolean anyCaptureEnabled() {
        return frameCaptureEnabled
                || stateCaptureEnabled
                || actionCaptureEnabled
                || eventCaptureEnabled;
    }

    /** Half side length of the event area in blocks ({@code 0} when unlimited). */
    public int eventAreaHalfBlocks() {
        return eventAreaSize <= 0 ? 0 : Math.max(1, eventAreaSize / 2);
    }

    /** Resolves the configured output directory against the game directory. */
    public Path resolveOutputRoot(Path gameDirectory) {
        String raw = outputDirectory == null ? "" : outputDirectory.trim();
        if (raw.isEmpty()) {
            raw = DEFAULT_OUTPUT_DIRECTORY;
        }

        if (raw.startsWith("~")) {
            raw = System.getProperty("user.home", ".") + raw.substring(1);
        }

        try {
            Path path = Path.of(raw);
            return path.isAbsolute()
                    ? path.normalize()
                    : gameDirectory.resolve(path).normalize();
        } catch (RuntimeException e) {
            // Invalid path (for example an illegal character on Windows).
            return gameDirectory.resolve(DEFAULT_OUTPUT_DIRECTORY).normalize();
        }
    }

    /** Resolves the configured output directory against the running game directory. */
    public Path resolveOutputRoot() {
        return resolveOutputRoot(FabricLoader.getInstance().getGameDir());
    }

    /* =====================================================================================
     * Validation
     * ===================================================================================== */

    /** Clamps every value into its supported range. */
    public void normalize() {
        String dir = outputDirectory == null ? "" : outputDirectory.trim();
        outputDirectory = dir.isEmpty() ? DEFAULT_OUTPUT_DIRECTORY : dir;

        everyNFrames = clamp(everyNFrames, 1, 20);
        jpegQuality = clamp(jpegQuality, 0.1f, 1.0f);
        maxPendingFrames = clamp(maxPendingFrames, 1, 512);

        // A resolution only makes sense when both dimensions are positive.
        if (targetWidth <= 0 || targetHeight <= 0) {
            targetWidth = 0;
            targetHeight = 0;
        } else {
            targetWidth = clamp(targetWidth, 16, 7680);
            targetHeight = clamp(targetHeight, 16, 4320);
        }

        nearbyRadius = clamp(nearbyRadius, 4.0, 128.0);
        nearbyMax = clamp(nearbyMax, 0, 128);
        lookReach = clamp(lookReach, 1.0, 64.0);

        if (eventAreaSize < 0) {
            eventAreaSize = 0;
        }
        if (eventAreaSize > 256) {
            eventAreaSize = 256;
        }
        if (eventAreaSize > 0 && eventAreaSize % 2 != 0) {
            eventAreaSize += 1;
        }

        hudOffsetX = clamp(hudOffsetX, 0, 4096);
        hudOffsetY = clamp(hudOffsetY, 0, 4096);
    }

    /* =====================================================================================
     * Property helpers
     * ===================================================================================== */

    private static String getString(
            Properties props, String key, String legacyKey, String def) {
        String value = props.getProperty(key);
        if (value == null && legacyKey != null) {
            value = props.getProperty(legacyKey);
        }
        return value == null ? def : value.trim();
    }

    private static boolean getBoolean(Properties props, String key, boolean def) {
        return getBoolean(props, key, null, def);
    }

    private static boolean getBoolean(
            Properties props, String key, String legacyKey, boolean def) {
        String value = getString(props, key, legacyKey, null);
        return value == null ? def : Boolean.parseBoolean(value);
    }

    private static int getInt(Properties props, String key, int def) {
        return getInt(props, key, null, def);
    }

    private static int getInt(
            Properties props, String key, String legacyKey, int def) {
        String value = getString(props, key, legacyKey, null);
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double getDouble(Properties props, String key, double def) {
        String value = getString(props, key, null, null);
        if (value == null) {
            return def;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float getFloat(
            Properties props, String key, String legacyKey, float def) {
        String value = getString(props, key, legacyKey, null);
        if (value == null) {
            return def;
        }
        try {
            return Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /* =====================================================================================
     * File rendering
     * ===================================================================================== */

    /** One rendered line of the properties file. */
    private static final class Line {

        private final String key;
        private final Object value;
        private final String comment;

        private Line(String key, Object value) {
            this.key = key;
            this.value = value;
            this.comment = null;
        }

        private Line(String comment) {
            this.key = null;
            this.value = null;
            this.comment = comment;
        }

        static Line comment(String text) {
            return new Line(text);
        }

        String render() {
            if (comment != null) {
                return comment.isEmpty() ? "#" : "# " + comment;
            }
            return key + "=" + escape(String.valueOf(value));
        }

        /**
         * Escapes a value for the {@code .properties} format. Windows backslashes are
         * normalised to forward slashes first so paths stay readable and portable.
         */
        private static String escape(String raw) {
            if (raw == null) {
                return "";
            }
            String value = raw.replace('\\', '/');
            StringBuilder out = new StringBuilder(value.length() + 8);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '=' , ':', '#', '!' -> out.append('\\').append(c);
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> out.append(c);
                }
            }
            return out.toString();
        }
    }
}

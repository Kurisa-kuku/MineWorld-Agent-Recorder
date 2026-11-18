package com.kurisa.mwarecorder.recorder;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live counters for the current episode.
 *
 * <p>Recording threads only increment atomic counters, while the HUD and the summary writer
 * read them without locking. Keeping the counters in one place avoids scattering statistics
 * through the recorders and lets the overlay show a consistent picture.</p>
 */
public final class RecordingStats {

    private static final AtomicLong STATES = new AtomicLong();
    private static final AtomicLong ACTIONS = new AtomicLong();
    private static final AtomicLong EVENTS = new AtomicLong();
    private static final AtomicLong FILTERED_EVENTS = new AtomicLong();
    private static final AtomicLong DROPPED_EVENTS = new AtomicLong();
    private static final AtomicLong FRAMES = new AtomicLong();
    private static final AtomicLong DROPPED_FRAMES = new AtomicLong();

    private static final Set<String> DIMENSIONS = Collections.synchronizedSet(new LinkedHashSet<>());

    private static volatile String episodeId = "";
    private static volatile String currentDimension = "";
    private static volatile String lastEventType = "";
    private static volatile long lastEventTick = -1L;
    private static volatile Instant startedAt = null;
    private static volatile Instant stoppedAt = null;

    private RecordingStats() {
    }

    /** Resets every counter for a new episode. */
    public static void reset(String id) {
        episodeId = id == null ? "" : id;
        currentDimension = "";
        lastEventType = "";
        lastEventTick = -1L;
        startedAt = Instant.now();
        stoppedAt = null;
        STATES.set(0);
        ACTIONS.set(0);
        EVENTS.set(0);
        FILTERED_EVENTS.set(0);
        DROPPED_EVENTS.set(0);
        FRAMES.set(0);
        DROPPED_FRAMES.set(0);
        DIMENSIONS.clear();
    }

    /** Freezes the elapsed-time calculation when the episode ends. */
    public static void markStopped() {
        if (stoppedAt == null) {
            stoppedAt = Instant.now();
        }
    }

    public static void onState() {
        STATES.incrementAndGet();
    }

    public static void onAction() {
        ACTIONS.incrementAndGet();
    }

    public static void onEvent(String type, long sampleTick) {
        EVENTS.incrementAndGet();
        lastEventType = type == null ? "" : type;
        lastEventTick = sampleTick;
    }

    public static void onEventFiltered() {
        FILTERED_EVENTS.incrementAndGet();
    }

    /** Counts an event that was discarded because the queue was full. */
    public static void onEventDropped() {
        DROPPED_EVENTS.incrementAndGet();
    }

    public static void onFrame() {
        FRAMES.incrementAndGet();
    }

    public static void onFrameDropped() {
        DROPPED_FRAMES.incrementAndGet();
    }

    /** Records a dimension seen during the episode. */
    public static void onDimension(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return;
        }
        currentDimension = dimension;
        DIMENSIONS.add(dimension);
    }

    /* =====================================================================================
     * Readers
     * ===================================================================================== */

    public static String episodeId() {
        return episodeId;
    }

    public static String currentDimension() {
        return currentDimension;
    }

    public static String lastEventType() {
        return lastEventType;
    }

    public static long lastEventTick() {
        return lastEventTick;
    }

    public static long states() {
        return STATES.get();
    }

    public static long actions() {
        return ACTIONS.get();
    }

    public static long events() {
        return EVENTS.get();
    }

    public static long filteredEvents() {
        return FILTERED_EVENTS.get();
    }

    public static long droppedEvents() {
        return DROPPED_EVENTS.get();
    }

    public static long frames() {
        return FRAMES.get();
    }

    public static long droppedFrames() {
        return DROPPED_FRAMES.get();
    }

    /** Immutable view of the dimensions visited so far. */
    public static Set<String> dimensionsVisited() {
        synchronized (DIMENSIONS) {
            return new LinkedHashSet<>(DIMENSIONS);
        }
    }

    /** Elapsed milliseconds since the episode started; frozen after the episode stops. */
    public static long elapsedMillis() {
        Instant start = startedAt;
        if (start == null) {
            return 0L;
        }
        Instant end = stoppedAt != null ? stoppedAt : Instant.now();
        return Math.max(0L, Duration.between(start, end).toMillis());
    }

    /** Elapsed time formatted as {@code mm:ss} (or {@code hh:mm:ss} after one hour). */
    public static String elapsedText() {
        long totalSeconds = elapsedMillis() / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours > 0
                ? String.format("%d:%02d:%02d", hours, minutes, seconds)
                : String.format("%02d:%02d", minutes, seconds);
    }
}

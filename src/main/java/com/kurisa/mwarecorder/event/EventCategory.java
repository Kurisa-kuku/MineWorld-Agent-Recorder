package com.kurisa.mwarecorder.event;

import com.kurisa.mwarecorder.config.MwarecorderConfig;

/**
 * Coarse classification of recorded events.
 *
 * <p>Every event type emitted by the mod belongs to exactly one category. The category decides
 * whether the event is recorded at all (the player can toggle categories in the settings
 * screen) and whether the positional area filter applies.</p>
 */
public enum EventCategory {

    /** Block break, block place and block interaction. */
    BLOCK,

    /** Entities: hurt, death, attack, interaction and respawn. */
    ENTITY,

    /** Container open / close / slot change, crafting and smelting. */
    CONTAINER,

    /** Item use and item pickup. */
    ITEM,

    /** Session start / pause / resume markers. */
    SESSION,

    /** Dimension transitions. */
    DIMENSION,

    /** Anything not covered above; always recorded when events are enabled. */
    OTHER;

    /**
     * Maps a serialised event type to its category.
     *
     * @param type the value of the {@code type} field of the event
     * @return the matching category, never {@code null}
     */
    public static EventCategory of(String type) {
        if (type == null) {
            return OTHER;
        }
        return switch (type) {
            case "block_break", "block_place", "block_interact" -> BLOCK;
            case "entity_hurt", "death", "entity_attack", "entity_interact", "respawn" -> ENTITY;
            case "container_open", "container_close", "container_change",
                 "item_craft", "item_smelt" -> CONTAINER;
            case "item_use", "item_pickup" -> ITEM;
            case "session_start", "session_pause", "session_resume", "session_stop" -> SESSION;
            case "dimension_change" -> DIMENSION;
            default -> OTHER;
        };
    }

    /**
     * Whether this category is enabled in the current configuration.
     *
     * <p>This method must stay free of client-only classes because events can be queued from
     * the integrated server thread.</p>
     */
    public boolean isEnabled() {
        MwarecorderConfig cfg = MwarecorderConfig.get();
        if (!cfg.eventCaptureEnabled) {
            return false;
        }
        return switch (this) {
            case BLOCK -> cfg.eventBlock;
            case ENTITY -> cfg.eventEntity;
            case CONTAINER -> cfg.eventContainer;
            case ITEM -> cfg.eventItem;
            case SESSION -> cfg.eventSession;
            case DIMENSION -> cfg.eventDimension;
            case OTHER -> true;
        };
    }

    /**
     * Whether events of this category carry a world position and should be filtered by the
     * configured recording area. Session and dimension markers are timeline metadata, so they
     * are always kept.
     */
    public boolean isPositional() {
        return this == BLOCK || this == ENTITY || this == CONTAINER || this == ITEM;
    }
}

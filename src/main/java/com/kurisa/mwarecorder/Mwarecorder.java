package com.kurisa.mwarecorder;

import com.google.gson.JsonObject;

import com.kurisa.mwarecorder.config.MwarecorderConfig;
import com.kurisa.mwarecorder.event.EventRecorder;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;

import net.minecraft.world.damagesource.DamageSource;

/**
 * Common (client and server) initialisation.
 *
 * <p>This entry point registers the game events that can only be observed authoritatively on
 * the logical server: damage, death and respawn. In single-player the integrated server shares
 * the JVM with the client, so these handlers feed the same event queue the client flushes.</p>
 *
 * <p>On a dedicated server {@code EpisodeManager.isActive()} is always false, so every handler
 * queues nothing and the mod stays completely inert.</p>
 */
public class Mwarecorder implements ModInitializer {

    @Override
    public void onInitialize() {
        // The configuration is loaded once, before any recorder can read it.
        MwarecorderConfig.load();

        System.out.println("[Mwarecorder] Common initialising...");

        registerEntityDamageEvent();
        registerEntityDeathEvent();
        registerPlayerRespawnEvent();
    }

    /* =====================================================================================
     * Entity hurt
     *
     * Emitted when a living entity actually received damage.
     * ===================================================================================== */

    private void registerEntityDamageEvent() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register(
                (entity, damageSource, baseDamage, damageTaken, blocked) -> {
                    JsonObject event = new JsonObject();
                    event.addProperty("type", "entity_hurt");

                    EventRecorder.addEntityInfo(event, entity);
                    EventRecorder.addDamageInfo(
                            event,
                            baseDamage,
                            damageTaken,
                            blocked,
                            damageSource.getMsgId(),
                            entityUuid(damageSource.getEntity()),
                            entityUuid(damageSource.getDirectEntity()));

                    EventRecorder.queue(event, entity.level().getGameTime());
                });
    }

    /* =====================================================================================
     * Entity death
     *
     * Emitted for every living entity, including the player.
     * ===================================================================================== */

    private void registerEntityDeathEvent() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            JsonObject event = new JsonObject();
            event.addProperty("type", "death");

            EventRecorder.addEntityInfo(event, entity);
            EventRecorder.addDamageSource(
                    event,
                    damageSource.getMsgId(),
                    entityUuid(damageSource.getEntity()),
                    entityUuid(damageSource.getDirectEntity()));

            EventRecorder.queue(event, entity.level().getGameTime());
        });
    }

    /* =====================================================================================
     * Player respawn
     * ===================================================================================== */

    private void registerPlayerRespawnEvent() {
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            JsonObject event = new JsonObject();
            event.addProperty("type", "respawn");

            JsonObject player = new JsonObject();
            player.addProperty("uuid", newPlayer.getUUID().toString());
            player.addProperty("alive", alive);
            event.add("player", player);

            EventRecorder.addPositionInfo(
                    event,
                    newPlayer.getX(),
                    newPlayer.getY(),
                    newPlayer.getZ());

            event.addProperty(
                    "dimension",
                    newPlayer.level().dimension().location().toString());

            EventRecorder.queue(event, newPlayer.level().getGameTime());
        });
    }

    /* =====================================================================================
     * Helpers
     * ===================================================================================== */

    /** UUID of an entity, or {@code null} when the damage source has no entity. */
    private static String entityUuid(net.minecraft.world.entity.Entity entity) {
        return entity == null ? null : entity.getUUID().toString();
    }
}

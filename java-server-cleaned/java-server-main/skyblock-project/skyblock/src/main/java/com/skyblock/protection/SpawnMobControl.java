package com.skyblock.protection;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.WaterMob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Logger;

/**
 * SpawnMobControl
 *
 * Prevents any mob (hostile or passive) from existing within MOB_FREE_RADIUS
 * blocks of spawn (0, 0) in the overworld.
 *
 * Two layers of protection:
 *   1. Cancel the CreatureSpawnEvent before the entity exists in the world.
 *   2. A periodic sweep (every 5 seconds) that removes any mobs that
 *      somehow slipped through (e.g. from chunk loading, plugins, etc.).
 *
 * Players are never affected.
 */
public class SpawnMobControl implements Listener {

    private static final double MOB_FREE_RADIUS = 2000.0;
    private static final double MOB_FREE_RADIUS_SQ = MOB_FREE_RADIUS * MOB_FREE_RADIUS;

    /** Ticks between sweep passes (5 seconds = 100 ticks) */
    private static final long SWEEP_INTERVAL_TICKS = 100L;

    private final JavaPlugin plugin;
    private final Logger logger;

    public SpawnMobControl(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;

        startSweepTask();

        logger.info("[SpawnMobControl] Mob-free zone active: "
                + (int) MOB_FREE_RADIUS + " blocks around spawn.");
    }

    // ─── Distance helper (2D, XZ plane only) ─────────────────────────────────

    private static boolean isInMobFreeZone(Location loc) {
        // Only apply in the overworld
        if (loc.getWorld() == null) return false;
        String envName = loc.getWorld().getEnvironment().name();
        if (!"NORMAL".equals(envName)) return false;

        double dx = loc.getX();
        double dz = loc.getZ();
        return (dx * dx + dz * dz) <= MOB_FREE_RADIUS_SQ;
    }

    private static boolean isMob(Entity entity) {
        // Exclude players — never touch them
        if (entity instanceof Player) return false;
        // Target all living non-player entities: monsters, animals, ambient, water mobs, etc.
        return entity instanceof LivingEntity;
    }

    // ─── Event: block spawn outright ──────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        // Don't block NPC / Citizens / Sentinels — they register as CUSTOM
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;

        Location loc = event.getLocation();
        if (!isInMobFreeZone(loc)) return;

        event.setCancelled(true);
    }

    // ─── Periodic sweep: remove any stragglers ────────────────────────────────

    private void startSweepTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (plugin.getServer().getWorlds().isEmpty()) return;

                org.bukkit.World overworld = plugin.getServer().getWorlds().stream()
                        .filter(w -> w.getEnvironment() == org.bukkit.World.Environment.NORMAL)
                        .findFirst()
                        .orElse(null);

                if (overworld == null) return;

                int removed = 0;
                for (Entity entity : overworld.getEntities()) {
                    if (!isMob(entity)) continue;
                    if (!isInMobFreeZone(entity.getLocation())) continue;
                    entity.remove();
                    removed++;
                }

                if (removed > 0) {
                    logger.info("[SpawnMobControl] Sweep removed " + removed
                            + " mob(s) inside " + (int) MOB_FREE_RADIUS + "-block spawn zone.");
                }
            }
        }.runTaskTimer(plugin, SWEEP_INTERVAL_TICKS, SWEEP_INTERVAL_TICKS);
    }
}

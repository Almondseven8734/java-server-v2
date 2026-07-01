package com.skyblock.dungeon.floor;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

/**
 * Drives the dungeon's weekly reset cycle: every player still inside
 * gets ejected to spawn (with their items - this is a scheduled
 * server reset, not a death, so no item loss applies), the dungeon
 * world is deleted and recreated from scratch with a fresh
 * StoneBufferGenerator, and DungeonFloorManager.resetAll() clears all
 * in-memory floor/graph/boss state to match.
 *
 * Call start() once during plugin onEnable; call cancel() in
 * onDisable to avoid a dangling task across reloads.
 */
public final class DungeonResetScheduler {

    /** One week, expressed in ticks (20 ticks/sec * 60 * 60 * 24 * 7). */
    public static final long RESET_INTERVAL_TICKS = 20L * 60 * 60 * 24 * 7;

    private final JavaPlugin plugin;
    private final DungeonFloorManager floorManager;
    private final String dungeonWorldName;
    private final ChunkGeneratorFactory generatorFactory;
    private final java.util.function.Supplier<org.bukkit.Location> spawnLocationSupplier;
    private final Logger logger;

    private int taskId = -1;

    /**
     * Builds the world's chunk generator fresh each reset (a new
     * StoneBufferGenerator instance bound to Floor 1's origin) - supplied
     * as a factory rather than a single instance since ChunkGenerator
     * state shouldn't be reused across a world delete/recreate cycle.
     */
    @FunctionalInterface
    public interface ChunkGeneratorFactory {
        ChunkGenerator create();
    }

    public DungeonResetScheduler(JavaPlugin plugin, DungeonFloorManager floorManager, String dungeonWorldName,
                                  ChunkGeneratorFactory generatorFactory,
                                  java.util.function.Supplier<org.bukkit.Location> spawnLocationSupplier,
                                  Logger logger) {
        this.plugin = plugin;
        this.floorManager = floorManager;
        this.dungeonWorldName = dungeonWorldName;
        this.generatorFactory = generatorFactory;
        this.spawnLocationSupplier = spawnLocationSupplier;
        this.logger = logger;
    }

    /** Schedules the recurring weekly reset. Safe to call once at plugin startup. */
    public void start() {
        if (taskId != -1) {
            return; // already scheduled
        }
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin, this::performReset, RESET_INTERVAL_TICKS, RESET_INTERVAL_TICKS
        );
        logger.info("[Dungeon] Weekly reset scheduled (every " + RESET_INTERVAL_TICKS + " ticks).");
    }

    /** Cancels the scheduled reset task, e.g. on plugin disable. */
    public void cancel() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    /** Forces an immediate reset, e.g. via an admin command, outside the normal weekly schedule. */
    public void performReset() {
        logger.info("[Dungeon] Starting weekly dungeon reset...");

        World oldWorld = floorManager.dungeonWorld();
        org.bukkit.Location spawnLoc = spawnLocationSupplier.get();

        // Eject every player currently inside the dungeon world to spawn,
        // keeping their items - this is a scheduled reset, not a death.
        for (Player player : oldWorld.getPlayers()) {
            player.teleport(spawnLoc);
            player.sendMessage("§6The dungeon is resetting for the week - you've been returned to spawn.");
        }

        floorManager.resetAll();

        String worldName = oldWorld.getName();
        boolean unloaded = Bukkit.unloadWorld(oldWorld, false);
        if (!unloaded) {
            logger.warning("[Dungeon] Failed to unload dungeon world '" + worldName + "' - reset aborted, "
                    + "in-memory floor state was already cleared but the old world is still active.");
            return;
        }

        java.io.File worldFolder = new java.io.File(Bukkit.getWorldContainer(), worldName);
        deleteRecursively(worldFolder);

        World freshWorld = new WorldCreator(worldName)
                .generator(generatorFactory.create())
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .createWorld();

        if (freshWorld == null) {
            logger.severe("[Dungeon] Failed to recreate dungeon world '" + worldName + "' after reset!");
            return;
        }

        logger.info("[Dungeon] Weekly dungeon reset complete - world '" + worldName + "' regenerated.");
    }

    private void deleteRecursively(java.io.File file) {
        if (!file.exists()) {
            return;
        }
        java.io.File[] children = file.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}

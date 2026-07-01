package com.skyblock.dungeon.floor;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;
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
     * Fired with the brand-new World instance once a reset finishes
     * recreating it. DungeonFloorManager.dungeonWorld is repointed
     * automatically before this fires (see performReset), but every
     * other component that cached a Location/World tied to the old
     * dungeon world (the entrance, the portal bounds, the block
     * protection listener) needs the same treatment, and only the
     * caller (SkyblockPlugin) knows about all of them - hence the
     * callback rather than this class reaching into unrelated systems.
     */
    private Consumer<World> onWorldRecreated = w -> { };

    /**
     * Fired once per player as they're ejected to spawn during a reset,
     * right after their teleport. performReset() only ever moved the
     * player's physical body - it never touched their persisted
     * DungeonPlayerState (isInsideDungeon flag + last coordinates). If a
     * player logs out any time before walking through the portal again,
     * DungeonJoinQuitListener trusts that stale persisted state and force-
     * teleports them straight back into the dungeon world at their old
     * coordinates on rejoin - coordinates that, post-reset, sit inside
     * solid unstructured stone. Wire this to clear + persist each
     * ejected player's dungeon state the same way the portal exit does.
     */
    private Consumer<Player> onPlayerEjected = p -> { };

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

    /**
     * Registers a callback invoked with the freshly recreated dungeon
     * World right after a reset completes. Set this once during plugin
     * wiring, before start() or any manual performReset() call (e.g.
     * via /dungeon reset).
     */
    public void setOnWorldRecreated(Consumer<World> onWorldRecreated) {
        this.onWorldRecreated = onWorldRecreated != null ? onWorldRecreated : (w -> { });
    }

    /** Registers a callback fired for each player ejected to spawn during a reset. */
    public void setOnPlayerEjected(Consumer<Player> onPlayerEjected) {
        this.onPlayerEjected = onPlayerEjected != null ? onPlayerEjected : (p -> { });
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

    /**
     * Forces an immediate reset, e.g. via an admin command, outside the
     * normal weekly schedule.
     *
     * The actual unload/delete/recreate is deferred one tick after the
     * ejection teleports: Bukkit.unloadWorld() refuses to unload a world
     * that still has players in it, and a same-tick check right after
     * calling player.teleport() across worlds isn't guaranteed to see
     * the transfer as fully complete yet. Without the delay, unloadWorld
     * can spuriously return false, silently aborting the whole reset
     * (world never deleted, floor state already wiped) - which looks
     * exactly like "the reset didn't work" from the console/logs and,
     * on the next server restart, the untouched old world simply
     * reloads as if nothing happened.
     */
    public void performReset() {
        logger.info("[Dungeon] Starting weekly dungeon reset...");

        World oldWorld = floorManager.dungeonWorld();
        org.bukkit.Location spawnLoc = spawnLocationSupplier.get();

        // Eject every player currently inside the dungeon world to spawn,
        // keeping their items - this is a scheduled reset, not a death.
        for (Player player : oldWorld.getPlayers()) {
            player.teleport(spawnLoc);
            player.sendMessage("§6The dungeon is resetting for the week - you've been returned to spawn.");
            onPlayerEjected.accept(player);
        }

        floorManager.resetAll();

        Bukkit.getScheduler().runTask(plugin, () -> finishReset(oldWorld));
    }

    /** How many extra attempts to give a stubborn file/directory before giving up on it. */
    private static final int DELETE_RETRY_ATTEMPTS = 5;

    /** Backoff between delete retries - region files can stay open briefly after unloadWorld() returns. */
    private static final long DELETE_RETRY_DELAY_MS = 100L;

    private void finishReset(World oldWorld) {
        String worldName = oldWorld.getName();
        boolean unloaded = Bukkit.unloadWorld(oldWorld, false);
        if (!unloaded) {
            logger.warning("[Dungeon] Failed to unload dungeon world '" + worldName + "' - reset aborted, "
                    + "in-memory floor state was already cleared but the old world is still active. "
                    + "This usually means a player (or entity holding a reference) is still in the world - "
                    + "check for anyone who reconnected mid-reset.");
            return;
        }

        java.io.File container = Bukkit.getWorldContainer();
        java.io.File worldFolder = new java.io.File(container, worldName);

        if (worldFolder.exists()) {
            // unloadWorld() returning true does NOT guarantee every region
            // file's handle is actually released yet, especially for a
            // heavily-explored area with many files (the small, always
            // force-repainted hub folder was never a real test of this -
            // it looks "reset" regardless because DungeonHubBuilder
            // overwrites it unconditionally). Deleting in place and then
            // immediately recreating under the same name gambles on that
            // timing - if any file is still locked, WorldCreator silently
            // loads the stale leftovers instead of generating fresh.
            //
            // Renaming sidesteps the race entirely: a rename doesn't
            // require the OS to have released open file handles, so it
            // succeeds instantly, freeing the "dungeon" name for a
            // guaranteed-empty recreation. The actual bytes of the old
            // world get deleted from the renamed-away folder in the
            // background, at leisure - success or failure there can no
            // longer affect the live dungeon.
            java.io.File trashFolder = new java.io.File(container, worldName + "_old_" + System.currentTimeMillis());
            boolean renamed = worldFolder.renameTo(trashFolder);

            if (!renamed) {
                logger.warning("[Dungeon] Could not rename old dungeon world folder aside (unexpected - check "
                        + "permissions or whether the world container is on a filesystem that supports atomic "
                        + "rename). Falling back to deleting it in place before recreating.");
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean deleted = deleteRecursively(worldFolder);
                    if (!deleted || worldFolder.exists()) {
                        logger.severe("[Dungeon] Failed to fully delete old dungeon world folder '" + worldName
                                + "' after " + DELETE_RETRY_ATTEMPTS + " retries per file - aborting recreation "
                                + "rather than silently loading the stale world. In-memory floor state was "
                                + "already reset; a manual server restart or manual folder delete followed by "
                                + "/dungeon reset will be needed.");
                        return;
                    }
                    Bukkit.getScheduler().runTask(plugin, () -> recreateWorld(worldName));
                });
                return;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                if (!deleteRecursively(trashFolder)) {
                    logger.warning("[Dungeon] Old dungeon data at '" + trashFolder.getPath() + "' could not be "
                            + "fully cleaned up in the background. Functionally harmless - the live dungeon is "
                            + "unaffected since it's already running under a fresh folder - but delete it "
                            + "manually next time you're on the box to reclaim disk space.");
                }
            });
        }

        // The name is free (either it never existed, or was just renamed
        // away above) - safe to recreate immediately, same tick, no
        // waiting on anything.
        recreateWorld(worldName);
    }

    private void recreateWorld(String worldName) {
        World freshWorld = new WorldCreator(worldName)
                .generator(generatorFactory.create())
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .createWorld();

        if (freshWorld == null) {
            logger.severe("[Dungeon] Failed to recreate dungeon world '" + worldName + "' after reset!");
            return;
        }

        // Repoint the floor manager at the new World instance before
        // anything else can touch it - onWorldRecreated below may
        // immediately rebuild the hub, and generation could start
        // firing the moment a player reconnects.
        floorManager.setDungeonWorld(freshWorld);
        onWorldRecreated.accept(freshWorld);

        logger.info("[Dungeon] Weekly dungeon reset complete - world '" + worldName + "' regenerated.");
    }

    /**
     * Deletes a file or directory tree, retrying each individual delete a
     * few times with a short backoff to ride out files that are still
     * briefly locked by async chunk-save I/O right after unloadWorld().
     * Returns true only if everything under (and including) {@code file}
     * was actually removed - never assume success just because no
     * exception was thrown.
     */
    private boolean deleteRecursively(java.io.File file) {
        if (!file.exists()) {
            return true;
        }

        boolean allChildrenDeleted = true;
        java.io.File[] children = file.listFiles();
        if (children != null) {
            for (java.io.File child : children) {
                allChildrenDeleted &= deleteRecursively(child);
            }
        }

        if (!allChildrenDeleted) {
            // Don't even try to delete this directory - it still has
            // stubborn children in it, so file.delete() would just fail.
            return false;
        }

        if (file.delete()) {
            return true;
        }

        for (int attempt = 0; attempt < DELETE_RETRY_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(DELETE_RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (file.delete()) {
                return true;
            }
        }

        logger.warning("[Dungeon] Could not delete '" + file.getPath() + "' after " + DELETE_RETRY_ATTEMPTS
                + " retries - still locked or in use.");
        return false;
    }
}

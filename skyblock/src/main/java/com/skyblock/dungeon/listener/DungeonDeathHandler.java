package com.skyblock.dungeon.listener;

import com.skyblock.dungeon.floor.DungeonPlayerState;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Handles the death penalty for players who die inside the dungeon:
 * items are stripped (the real stakes per design) and the player is
 * ejected straight to server spawn, distinct from walking out via the
 * Floor 0 portal (which preserves items).
 *
 * Looks up per-player dungeon state via a supplied accessor function
 * rather than owning a state map directly, so this listener stays
 * decoupled from however the floor manager chooses to store state.
 */
public final class DungeonDeathHandler implements Listener {

    private final JavaPlugin plugin;
    private final Function<UUID, DungeonPlayerState> stateLookup;
    private final Location spawnLocation;
    private final Logger logger;

    public DungeonDeathHandler(JavaPlugin plugin,
                                Function<UUID, DungeonPlayerState> stateLookup,
                                Location spawnLocation,
                                Logger logger) {
        this.plugin = plugin;
        this.stateLookup = stateLookup;
        this.spawnLocation = spawnLocation;
        this.logger = logger;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        DungeonPlayerState state = stateLookup.apply(player.getUniqueId());

        if (state == null || !state.isInsideDungeon()) {
            return; // death happened outside the dungeon, not our concern
        }

        // Strip items - the actual death penalty. Bukkit already clears
        // the inventory on death by default in survival, but we make it
        // explicit and unconditional here regardless of keepInventory
        // gamerule state, since dungeon death penalty must always apply.
        event.getDrops().clear();
        event.setKeepInventory(false);
        event.setDroppedExp(0);

        state.clearDungeonState();

        // Defer the actual teleport to spawn until after respawn completes
        // (teleporting mid-death-event is unreliable across Paper versions).
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.teleport(spawnLocation);
                logger.info("[Dungeon] " + player.getName() + " died inside the dungeon, ejected to spawn and items stripped.");
            }
        });
    }
}

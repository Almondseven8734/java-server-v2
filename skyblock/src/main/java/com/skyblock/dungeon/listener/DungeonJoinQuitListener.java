package com.skyblock.dungeon.listener;

import com.skyblock.dungeon.floor.DungeonPlayerState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Implements the relog-resume mechanic: logging out simply freezes a
 * player's saved position (no vulnerable body left in the world, per
 * design), and logging back in teleports them back to that exact
 * floor/position rather than dumping them at Floor 0 or spawn.
 *
 * Players who were NOT inside the dungeon when they quit are left
 * completely untouched on join - this listener only acts on dungeon
 * state.
 */
public final class DungeonJoinQuitListener implements Listener {

    private final Function<UUID, DungeonPlayerState> stateLookup;
    private final BiConsumer<UUID, DungeonPlayerState> statePersist;
    private final Function<Integer, org.bukkit.World> worldForFloor;

    /**
     * @param stateLookup    resolves a player's current DungeonPlayerState (may create a default if none exists yet)
     * @param statePersist   called on quit to persist the latest state to disk/storage
     * @param worldForFloor  resolves which Bukkit World a given floor number lives in
     *                       (e.g. all floors might share one dungeon world, or be split across several)
     */
    public DungeonJoinQuitListener(Function<UUID, DungeonPlayerState> stateLookup,
                                    BiConsumer<UUID, DungeonPlayerState> statePersist,
                                    Function<Integer, org.bukkit.World> worldForFloor) {
        this.stateLookup = stateLookup;
        this.statePersist = statePersist;
        this.worldForFloor = worldForFloor;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        DungeonPlayerState state = stateLookup.apply(player.getUniqueId());

        if (state == null || !state.isInsideDungeon()) {
            return; // wasn't inside the dungeon at logout, nothing to resume
        }

        org.bukkit.World world = worldForFloor.apply(state.getCurrentFloor());
        if (world == null) {
            return; // floor/world not loaded yet - resume will need to be retried once it is
        }

        org.bukkit.Location resumeLocation = new org.bukkit.Location(
            world, state.getX(), state.getY(), state.getZ(), state.getYaw(), state.getPitch()
        );

        // Defer to next tick: teleporting during PlayerJoinEvent itself
        // is unreliable since the player entity isn't always fully
        // initialized on this exact tick across Paper versions.
        org.bukkit.Bukkit.getScheduler().runTask(
            org.bukkit.Bukkit.getPluginManager().getPlugin("Skyblock"),
            () -> {
                if (player.isOnline()) {
                    player.teleport(resumeLocation);
                    player.sendMessage("§7Welcome back - you've resumed inside the dungeon on Floor " + state.getCurrentFloor() + ".");
                }
            }
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        DungeonPlayerState state = stateLookup.apply(player.getUniqueId());

        if (state == null || !state.isInsideDungeon()) {
            return;
        }

        // Freeze the player's last known position before they fully
        // disconnect, so the join handler can restore it exactly.
        state.savePosition(
            player.getLocation().getX(),
            player.getLocation().getY(),
            player.getLocation().getZ(),
            player.getLocation().getYaw(),
            player.getLocation().getPitch()
        );

        statePersist.accept(player.getUniqueId(), state);
    }
}

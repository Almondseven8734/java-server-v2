package com.skyblock.dungeon.listener;

import com.skyblock.dungeon.floor.DungeonFloorManager;
import com.skyblock.dungeon.floor.DungeonPlayerState;
import com.skyblock.dungeon.gen.DungeonRoom;
import com.skyblock.dungeon.spawn.DungeonBossRoomTrigger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.UUID;
import java.util.function.Function;

/**
 * The missing link between "a player moved" and "the dungeon
 * generates near them": every move event for a player currently
 * inside the dungeon and on a real floor (not Floor 0, which never
 * generates) is forwarded into DungeonFloorManager.onPlayerFrontier
 * and DungeonBossRoomTrigger.onPlayerPosition.
 *
 * Throttled to whole-block movement only (PlayerMoveEvent fires on
 * sub-block head/camera movement too, which would otherwise call into
 * the generation planner far more often than needed).
 */
public final class DungeonFrontierListener implements Listener {

    private final Function<UUID, DungeonPlayerState> stateLookup;
    private final DungeonFloorManager floorManager;
    private final DungeonBossRoomTrigger bossRoomTrigger;

    public DungeonFrontierListener(Function<UUID, DungeonPlayerState> stateLookup,
                                    DungeonFloorManager floorManager,
                                    DungeonBossRoomTrigger bossRoomTrigger) {
        this.stateLookup = stateLookup;
        this.floorManager = floorManager;
        this.bossRoomTrigger = bossRoomTrigger;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to == null) {
            return;
        }
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) {
            return; // only whole-block XZ movement triggers generation checks
        }

        Player player = event.getPlayer();
        DungeonPlayerState state = stateLookup.apply(player.getUniqueId());
        if (state == null || !state.isInsideDungeon()) {
            return;
        }

        int floorNumber = state.getCurrentFloor();
        if (floorNumber < 1) {
            return; // Floor 0 is the static entrance hub - it never generates
        }

        floorManager.onPlayerFrontier(floorNumber, to.getX(), to.getZ());

        DungeonRoom bossRoom = floorManager.getBossRoom(floorNumber);
        if (bossRoom != null) {
            int floorBottomY = floorManager.floorBounds().floorBottomY(floorNumber);
            bossRoomTrigger.onPlayerPosition(floorManager.dungeonWorld(), floorNumber, floorBottomY,
                    bossRoom, to.getX(), to.getZ());
        }
    }
}

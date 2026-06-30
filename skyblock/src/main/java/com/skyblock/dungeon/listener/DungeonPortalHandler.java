package com.skyblock.dungeon.listener;

import com.skyblock.dungeon.floor.DungeonPlayerState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.function.Function;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Handles the Floor 0 portal: walking through it is the only
 * non-death way out of the dungeon, and unlike death, it preserves
 * the player's items.
 *
 * The portal region itself is a simple axis-aligned bounding box for
 * now (min/max corners) - swap for a more elaborate trigger volume
 * later if needed without changing the listener's public surface.
 */
public final class DungeonPortalHandler implements Listener {

    private final Function<UUID, DungeonPlayerState> stateLookup;
    private final Location spawnLocation;
    private final Logger logger;

    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;

    public DungeonPortalHandler(Function<UUID, DungeonPlayerState> stateLookup,
                                 Location spawnLocation,
                                 Location portalCorner1,
                                 Location portalCorner2,
                                 Logger logger) {
        this.stateLookup = stateLookup;
        this.spawnLocation = spawnLocation;
        this.logger = logger;

        this.minX = Math.min(portalCorner1.getX(), portalCorner2.getX());
        this.minY = Math.min(portalCorner1.getY(), portalCorner2.getY());
        this.minZ = Math.min(portalCorner1.getZ(), portalCorner2.getZ());
        this.maxX = Math.max(portalCorner1.getX(), portalCorner2.getX());
        this.maxY = Math.max(portalCorner1.getY(), portalCorner2.getY());
        this.maxZ = Math.max(portalCorner1.getZ(), portalCorner2.getZ());
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }

        Player player = event.getPlayer();
        DungeonPlayerState state = stateLookup.apply(player.getUniqueId());

        if (state == null || !state.isInsideDungeon()) {
            return; // not currently inside the dungeon, portal trigger doesn't apply
        }

        if (!isInsidePortalRegion(event.getTo())) {
            return;
        }

        // Item-preserving exit: clear dungeon state and teleport to
        // spawn, but unlike death, do NOT touch inventory.
        state.clearDungeonState();
        player.teleport(spawnLocation);
        logger.info("[Dungeon] " + player.getName() + " walked out through the Floor 0 portal, items preserved.");
    }

    private boolean isInsidePortalRegion(Location loc) {
        return loc.getX() >= minX && loc.getX() <= maxX
            && loc.getY() >= minY && loc.getY() <= maxY
            && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }
}

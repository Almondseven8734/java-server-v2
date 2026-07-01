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
 *
 * The dungeon world is deleted and recreated as a brand-new World
 * object on every DungeonResetScheduler reset, which also moves the
 * physical portal (the hub gets rebuilt from scratch). Call
 * updatePortalBounds(...) with the fresh world/corners right after a
 * reset, or this listener keeps comparing against stale, unloaded
 * Location data and the portal effectively stops working.
 */
public final class DungeonPortalHandler implements Listener {

    private final Function<UUID, DungeonPlayerState> stateLookup;
    private final Location spawnLocation;
    private final Logger logger;
    private org.bukkit.World dungeonWorld;

    private double minX;
    private double minY;
    private double minZ;
    private double maxX;
    private double maxY;
    private double maxZ;

    public DungeonPortalHandler(Function<UUID, DungeonPlayerState> stateLookup,
                                 Location spawnLocation,
                                 Location portalCorner1,
                                 Location portalCorner2,
                                 Logger logger) {
        this.stateLookup = stateLookup;
        this.spawnLocation = spawnLocation;
        this.logger = logger;
        updatePortalBounds(portalCorner1, portalCorner2);
    }

    /** Repoints the portal's world and bounding box at freshly (re)built hub corners after a reset. */
    public void updatePortalBounds(Location portalCorner1, Location portalCorner2) {
        this.dungeonWorld = portalCorner1.getWorld();

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

        // Same dimension-identity guard as DungeonFrontierListener: only
        // act on the portal region if the player is actually in the
        // dungeon world, not just carrying a stale flag.
        if (!player.getWorld().equals(dungeonWorld)) {
            state.clearDungeonState();
            return;
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

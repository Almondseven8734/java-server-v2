package com.skyblock.dungeon.spawn;

import com.skyblock.dungeon.gen.DungeonRoom;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.Random;

/**
 * Finds an actually-carved-open XZ column within a DungeonRoom's
 * footprint. DungeonRoom's radiusX/radiusZ describe a bounding box used
 * for graph bookkeeping (staircase spacing, boss-trigger containment,
 * etc.), not the real carved shape - DungeonRoomPlanner carves rooms as
 * noise-driven caves that only open roughly 35-40% of that box to air.
 * Anything that blindly places an entity or block at a random offset
 * within the box (or dead-center) will very often land inside solid
 * stone. This class checks the actual world blocks before returning a
 * spot, so callers only ever get a real, standable location.
 */
public final class DungeonSpawnLocator {

    private DungeonSpawnLocator() {
    }

    /**
     * @param world      the dungeon world
     * @param room       the room to search within
     * @param groundY    the Y an entity/block should stand ON - i.e.
     *                   FloorBounds.walkableFloorY(floorNumber), the first
     *                   carvable cave-band layer, NOT floorBottomY+1
     * @param random     RNG for the initial randomized attempts
     * @param maxAttempts number of random tries before falling back to a
     *                   full deterministic scan of the room's footprint
     * @return {x, z} of a verified open+standable column, or null if the
     *         room has no such column yet (e.g. noise happened to leave
     *         this particular footprint entirely solid)
     */
    public static int[] findOpenColumn(World world, DungeonRoom room, int groundY, Random random, int maxAttempts) {
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            int dx = random.nextInt(room.radiusX() * 2 + 1) - room.radiusX();
            int dz = random.nextInt(room.radiusZ() * 2 + 1) - room.radiusZ();
            int x = room.centerX() + dx;
            int z = room.centerZ() + dz;
            if (isOpenStandableColumn(world, x, groundY, z)) {
                return new int[]{x, z};
            }
        }

        // Random attempts unlucky (or room mostly solid) - fall back to an
        // exhaustive scan of the footprint before giving up entirely.
        for (int dx = -room.radiusX(); dx <= room.radiusX(); dx++) {
            for (int dz = -room.radiusZ(); dz <= room.radiusZ(); dz++) {
                int x = room.centerX() + dx;
                int z = room.centerZ() + dz;
                if (isOpenStandableColumn(world, x, groundY, z)) {
                    return new int[]{x, z};
                }
            }
        }

        return null;
    }

    /** True if groundY-1 is solid ground and groundY/groundY+1 are open air to stand/spawn in. */
    private static boolean isOpenStandableColumn(World world, int x, int groundY, int z) {
        Material below = world.getBlockAt(x, groundY - 1, z).getType();
        if (!below.isSolid()) {
            return false;
        }
        Material feet = world.getBlockAt(x, groundY, z).getType();
        Material head = world.getBlockAt(x, groundY + 1, z).getType();
        return feet == Material.AIR && head == Material.AIR;
    }
}

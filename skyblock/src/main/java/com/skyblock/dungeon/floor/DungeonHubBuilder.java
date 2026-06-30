package com.skyblock.dungeon.floor;

import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Builds the physical Floor 0 structure: a small fixed, non-generating
 * entrance/hub room with a portal block volume back to spawn.
 *
 * This is plain block placement, not part of the procedural generator -
 * Floor 0 never generates or changes, per design ("Floor 0 isn't a
 * vertical floor, it's an entrance room"). Call buildHub() once, right
 * after the dungeon world is first created (it's idempotent-safe to
 * call again, it'll just overwrite the same blocks identically).
 *
 * The hub is positioned FLOOR_0_TO_FLOOR_1_OFFSET blocks east
 * (+X) of Floor 1's origin point, so walking out of the hub through its
 * west wall heads straight toward Floor 1's origin. All coordinates are
 * derived from Floor 1's origin passed into buildHub() rather than
 * hardcoded, so moving Floor 1's origin automatically moves the hub
 * with it.
 */
public final class DungeonHubBuilder {

    private static final int HUB_FLOOR_Y = 100;

    private static final int HUB_RADIUS_X = 8;
    private static final int HUB_RADIUS_Z = 8;
    private static final int HUB_HEIGHT = 5;

    private DungeonHubBuilder() {
    }

    /** Hub center sits FLOOR_0_TO_FLOOR_1_OFFSET blocks east (+X) of Floor 1's origin, same Z. */
    private static int hubCenterX(int floor1OriginX) {
        return floor1OriginX + FloorBounds.FLOOR_0_TO_FLOOR_1_OFFSET;
    }

    private static int hubCenterZ(int floor1OriginZ) {
        return floor1OriginZ;
    }

    /** Builds the Floor 0 room: floor, walls, ceiling, and a portal block volume on the far wall. */
    public static void buildHub(World world, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);

        // Floor.
        for (int x = -HUB_RADIUS_X; x <= HUB_RADIUS_X; x++) {
            for (int z = -HUB_RADIUS_Z; z <= HUB_RADIUS_Z; z++) {
                world.getBlockAt(hubCenterX + x, HUB_FLOOR_Y, hubCenterZ + z).setType(Material.SMOOTH_STONE);
            }
        }

        // Hollow interior air + walls/ceiling.
        for (int x = -HUB_RADIUS_X; x <= HUB_RADIUS_X; x++) {
            for (int z = -HUB_RADIUS_Z; z <= HUB_RADIUS_Z; z++) {
                boolean edge = Math.abs(x) == HUB_RADIUS_X || Math.abs(z) == HUB_RADIUS_Z;
                for (int y = 1; y <= HUB_HEIGHT; y++) {
                    Material material = (edge || y == HUB_HEIGHT) ? Material.STONE_BRICKS : Material.AIR;
                    world.getBlockAt(hubCenterX + x, HUB_FLOOR_Y + y, hubCenterZ + z).setType(material);
                }
            }
        }

        // Portal volume: a 3x3 plane on the south wall (-Z side), distinct
        // from the west-wall staircase. Decorative marker, not real portal
        // physics - matches DungeonPortalHandler's portalCorner1/2 box.
        int portalX = hubCenterX;
        int portalZBase = hubCenterZ - HUB_RADIUS_Z + 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 1; dy <= 3; dy++) {
                Material material = (dx == 0) ? Material.GLASS : Material.SEA_LANTERN;
                world.getBlockAt(portalX + dx, HUB_FLOOR_Y + dy, portalZBase).setType(material);
            }
        }

        // Staircase down to Floor 1's entrance, on the west wall (-X side),
        // since the hub sits east of Floor 1's origin - walking out this
        // exit heads straight toward Floor 1.
        world.getBlockAt(hubCenterX - HUB_RADIUS_X + 1, HUB_FLOOR_Y, hubCenterZ).setType(Material.AIR);
        for (int y = HUB_FLOOR_Y; y >= HUB_FLOOR_Y - 3; y--) {
            world.getBlockAt(hubCenterX - HUB_RADIUS_X + 1, y, hubCenterZ).setType(Material.LADDER);
        }
    }

    /** The location players should be teleported to on /dungeon - just inside the hub room. */
    public static Location entranceLocation(World world, int floor1OriginX, int floor1OriginZ) {
        return new Location(world, hubCenterX(floor1OriginX), HUB_FLOOR_Y + 1, hubCenterZ(floor1OriginZ));
    }

    /** Corner 1 of the portal trigger volume, matching the visual marker built in buildHub(). */
    public static Location portalCorner1(World world, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        return new Location(world, hubCenterX - 1, HUB_FLOOR_Y + 1, hubCenterZ - HUB_RADIUS_Z + 2);
    }

    /** Corner 2 of the portal trigger volume, matching the visual marker built in buildHub(). */
    public static Location portalCorner2(World world, int floor1OriginX, int floor1OriginZ) {
        int hubCenterX = hubCenterX(floor1OriginX);
        int hubCenterZ = hubCenterZ(floor1OriginZ);
        return new Location(world, hubCenterX + 1, HUB_FLOOR_Y + 3, hubCenterZ - HUB_RADIUS_Z + 1);
    }
}

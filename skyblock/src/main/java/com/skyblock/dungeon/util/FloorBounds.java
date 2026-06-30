package com.skyblock.dungeon.util;

/**
 * Pure math utility for dungeon floor geometry:
 *   - the horizontal generation radius leash (2000 blocks from a floor's origin)
 *   - vertical floor slot layout (20 blocks tall + 1 block border between floors)
 *
 * No Bukkit/Paper dependencies on purpose — this is unit-testable in isolation.
 */
public final class FloorBounds {

    /** Horizontal cap: terrain on a floor never generates beyond this distance from its origin. */
    public static final int GENERATION_RADIUS = 2000;

    /** Height of a single floor's playable vertical space. */
    public static final int FLOOR_HEIGHT = 20;

    /** Solid, impassable border separating each floor from its neighbors. */
    public static final int FLOOR_BORDER = 1;

    /** Combined vertical footprint of one floor slot (floor + its bottom border). */
    public static final int FLOOR_SLOT_HEIGHT = FLOOR_HEIGHT + FLOOR_BORDER;

    /** Floor 0 (the entrance hub) is offset this far horizontally from Floor 1's origin. */
    public static final int FLOOR_0_TO_FLOOR_1_OFFSET = 2000;

    /** Minimum separation required between two staircases generated on the same floor. */
    public static final int MIN_STAIRCASE_SEPARATION = 40;

    private final int worldMinY;
    private final int worldMaxY;

    public FloorBounds(int worldMinY, int worldMaxY) {
        if (worldMaxY <= worldMinY) {
            throw new IllegalArgumentException("worldMaxY must be greater than worldMinY");
        }
        this.worldMinY = worldMinY;
        this.worldMaxY = worldMaxY;
    }

    /** Convenience constructor for a standard Paper 1.21.x world (-64 to 320). */
    public static FloorBounds standardWorld() {
        return new FloorBounds(-64, 320);
    }

    /**
     * Maximum number of floors that fit between worldMinY and worldMaxY,
     * given each floor needs FLOOR_SLOT_HEIGHT of vertical space.
     */
    public int maxFloorCount() {
        int totalHeight = worldMaxY - worldMinY;
        return totalHeight / FLOOR_SLOT_HEIGHT;
    }

    /**
     * The Y coordinate of the floor of floor N's walkable space (1-indexed floors).
     * Floor 1 starts at worldMaxY and floors stack downward.
     */
    public int floorBottomY(int floorNumber) {
        if (floorNumber < 1) {
            throw new IllegalArgumentException("floorNumber must be >= 1");
        }
        return worldMaxY - (floorNumber * FLOOR_SLOT_HEIGHT);
    }

    /** The Y coordinate of the top of floor N's walkable space. */
    public int floorTopY(int floorNumber) {
        return floorBottomY(floorNumber) + FLOOR_HEIGHT;
    }

    /**
     * Whether a given world Y coordinate falls within floor N's walkable
     * vertical band (excluding its border).
     */
    public boolean isWithinFloor(int floorNumber, int y) {
        return y >= floorBottomY(floorNumber) && y < floorTopY(floorNumber);
    }

    /**
     * Resolves which floor number's walkable band a given world Y falls
     * into, or -1 if it falls in a border/outside every floor (e.g. in
     * Floor 0's hub, or in the gap between floors). Scans up to
     * maxFloorCount() - cheap enough to call per block event since this
     * runs on the rare occasions a chest is closed/broken, not per tick.
     */
    public int floorForY(int y) {
        int max = maxFloorCount();
        for (int floor = 1; floor <= max; floor++) {
            if (isWithinFloor(floor, y)) {
                return floor;
            }
        }
        return -1;
    }

    /**
     * Whether a horizontal point is within the 2000-block generation leash
     * of a floor's origin. Uses flat (X/Z) Chebyshev-free Euclidean distance.
     */
    public boolean isWithinGenerationRadius(double originX, double originZ, double x, double z) {
        double dx = x - originX;
        double dz = z - originZ;
        return (dx * dx + dz * dz) <= ((double) GENERATION_RADIUS * GENERATION_RADIUS);
    }

    /**
     * Clamps a target point so it never exceeds the generation radius from
     * the floor's origin - useful when a generation frontier wants to expand
     * past the leash and needs to be pulled back to the boundary instead.
     */
    public double[] clampToRadius(double originX, double originZ, double x, double z) {
        double dx = x - originX;
        double dz = z - originZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= GENERATION_RADIUS) {
            return new double[]{x, z};
        }
        double scale = GENERATION_RADIUS / dist;
        return new double[]{originX + dx * scale, originZ + dz * scale};
    }

    public int worldMinY() {
        return worldMinY;
    }

    public int worldMaxY() {
        return worldMaxY;
    }
}

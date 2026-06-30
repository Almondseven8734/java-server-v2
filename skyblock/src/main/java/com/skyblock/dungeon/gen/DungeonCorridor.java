package com.skyblock.dungeon.gen;

import java.util.List;
import java.util.UUID;

/**
 * An edge in a floor's room/corridor graph: a carved path between two
 * rooms, expressed as an ordered list of XZ waypoints (the corridor's
 * centerline). The planner walks these waypoints when carving so the
 * corridor can wander naturally instead of being a straight pipe.
 */
public final class DungeonCorridor {

    private final UUID id;
    private final UUID fromRoomId;
    private final UUID toRoomId;
    private final List<int[]> waypoints; // each entry: {x, z}
    private final int width;

    private boolean carved = false;

    public DungeonCorridor(UUID id, UUID fromRoomId, UUID toRoomId, List<int[]> waypoints, int width) {
        if (waypoints == null || waypoints.size() < 2) {
            throw new IllegalArgumentException("Corridor needs at least 2 waypoints (start and end)");
        }
        this.id = id;
        this.fromRoomId = fromRoomId;
        this.toRoomId = toRoomId;
        this.waypoints = waypoints;
        this.width = Math.max(1, width);
    }

    public UUID id() { return id; }
    public UUID fromRoomId() { return fromRoomId; }
    public UUID toRoomId() { return toRoomId; }
    public List<int[]> waypoints() { return waypoints; }
    public int width() { return width; }

    public boolean isCarved() { return carved; }
    public void markCarved() { this.carved = true; }
}

package com.skyblock.dungeon.gen;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A single node in a floor's room/corridor graph.
 *
 * Rooms are axis-aligned boxes in floor-local horizontal space (X/Z);
 * vertical extent always matches the floor's walkable band, supplied
 * by FloorBounds at carve time, so rooms here only need an X/Z footprint.
 */
public final class DungeonRoom {

    public enum Type {
        NORMAL,
        BUFFER,      // landing room directly beneath a staircase from the floor above
        BOSS,
        CHEST
    }

    private final UUID id;
    private final int centerX;
    private final int centerZ;
    private final int radiusX;
    private final int radiusZ;
    private final Type type;
    private final Set<UUID> connectedRoomIds = new LinkedHashSet<>();

    private boolean carved = false;
    private boolean looted = false;
    private boolean cleared = false;

    public DungeonRoom(UUID id, int centerX, int centerZ, int radiusX, int radiusZ, Type type) {
        if (radiusX <= 0 || radiusZ <= 0) {
            throw new IllegalArgumentException("Room radii must be positive");
        }
        this.id = id;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radiusX = radiusX;
        this.radiusZ = radiusZ;
        this.type = type;
    }

    public boolean containsXZ(int x, int z) {
        return Math.abs(x - centerX) <= radiusX && Math.abs(z - centerZ) <= radiusZ;
    }

    /** Euclidean distance from this room's center to a point, in the XZ plane. */
    public double distanceTo(int x, int z) {
        double dx = x - centerX;
        double dz = z - centerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public void connectTo(UUID otherRoomId) {
        connectedRoomIds.add(otherRoomId);
    }

    public UUID id() { return id; }
    public int centerX() { return centerX; }
    public int centerZ() { return centerZ; }
    public int radiusX() { return radiusX; }
    public int radiusZ() { return radiusZ; }
    public Type type() { return type; }
    public Set<UUID> connectedRoomIds() { return connectedRoomIds; }

    public boolean isCarved() { return carved; }
    public void markCarved() { this.carved = true; }

    public boolean isLooted() { return looted; }
    public void markLooted() { this.looted = true; }

    public boolean isCleared() { return cleared; }
    public void markCleared() { this.cleared = true; }
}

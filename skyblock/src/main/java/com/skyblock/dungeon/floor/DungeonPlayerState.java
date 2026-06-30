package com.skyblock.dungeon.floor;

import java.util.UUID;

/**
 * Per-player state while inside the dungeon: which floor they're on
 * and their last known position (so logging out and back in resumes
 * exactly where they left off, per design - no vulnerable offline
 * body, just a frozen position).
 *
 * This is a plain data holder; persistence (disk/db) is handled by
 * whatever storage class wraps it (mirrors the pattern used by
 * IslandStorage / AhStorage elsewhere in this codebase).
 */
public final class DungeonPlayerState {

    private final UUID playerId;
    private int currentFloor;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private boolean insideDungeon;

    public DungeonPlayerState(UUID playerId) {
        this.playerId = playerId;
        this.currentFloor = 0; // Floor 0 = entrance hub
        this.insideDungeon = false;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public int getCurrentFloor() {
        return currentFloor;
    }

    public void setCurrentFloor(int currentFloor) {
        this.currentFloor = currentFloor;
    }

    public boolean isInsideDungeon() {
        return insideDungeon;
    }

    public void setInsideDungeon(boolean insideDungeon) {
        this.insideDungeon = insideDungeon;
    }

    public void savePosition(double x, double y, double z, float yaw, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    /**
     * Resets this player's dungeon state, used after they die (ejected
     * to spawn) or walk out through the Floor 0 portal.
     */
    public void clearDungeonState() {
        this.currentFloor = 0;
        this.insideDungeon = false;
    }
}

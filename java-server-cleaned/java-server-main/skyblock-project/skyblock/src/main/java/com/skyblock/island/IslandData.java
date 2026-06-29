package com.skyblock.island;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a Skyblock island.
 * Mirrors the JS island data structure stored in world dynamic properties.
 *
 * Grid layout: 50x50, origin at (5000, 5000), spacing 1000 blocks.
 * Slot-based: id = "slot_N", slot = grid index.
 */
public class IslandData {

    /** Unique island ID, e.g. "slot_0" */
    public String id;

    /** Grid slot index */
    public int slot;

    /** Center X coordinate */
    public double centerX;

    /** Center Z coordinate */
    public double centerZ;

    /** Display name (no spaces, max 20 chars) */
    public String name;

    /** UUID string of the owner */
    public String ownerId;

    /** Display name of the owner */
    public String ownerName;

    /** Ordered oldest-first; index 0 = oldest member */
    public List<Member> members = new ArrayList<>();

    /** Public warps on this island */
    public List<Warp> pwarps = new ArrayList<>();

    /** Maximum allowed members (default 4) */
    public int maxMembers = 4;

    /** Home teleport coordinates */
    public double homeX;
    public double homeY;
    public double homeZ;

    /** Creation timestamp (ms since epoch) */
    public long createdAt;

    /** Island border radius in blocks (25, 50, 100, or 200). Default 25. */
    public int borderRadius = 25;

    /** Island flags. Key = flag name, Value = enabled/disabled. */
    public Map<String, Boolean> flags = new java.util.HashMap<>(Map.of(
            "mobSpawning",    true,
            "daylightCycle",  true,
            "pvp",            false,
            "weather",        true
    ));

    // ─── Nested types ─────────────────────────────────────────────────────────

    public static class Member {
        public String id;
        public String name;
        /** "Owner", "Admin", "Co-Owner", "Moderator", or "Member" */
        public String role;
        public long joinedAt;

        public Member() {}

        public Member(String id, String name, String role, long joinedAt) {
            this.id       = id;
            this.name     = name;
            this.role     = role;
            this.joinedAt = joinedAt;
        }
    }

    public static class Warp {
        public String name;
        public double x, y, z;
        public String creatorId;
        public String creatorName;

        public Warp() {}

        public Warp(String name, double x, double y, double z,
                          String creatorId, String creatorName) {
            this.name        = name;
            this.x           = x;
            this.y           = y;
            this.z           = z;
            this.creatorId   = creatorId;
            this.creatorName = creatorName;
        }
    }

    public static class PwarpData extends Warp {
        public PwarpData() {}
        public PwarpData(String name, double x, double y, double z,
                         String creatorId, String creatorName) {
            super(name, x, y, z, creatorId, creatorName);
        }
    }
}

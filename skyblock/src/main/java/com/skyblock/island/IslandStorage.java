package com.skyblock.island;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Skyblock Island Storage System
 *
 * Translated from island_storage.js.
 * Uses JSON file persistence on the server's data folder instead of
 * world dynamic properties (which are a Bedrock-scripting concept).
 *
 * All public methods are thread-safe via synchronized blocks on the
 * internal island list.
 *
 * Grid constants (kept identical to JS):
 *   GRID_ORIGIN_X = 5000, GRID_ORIGIN_Z = 5000
 *   ISLAND_SPACING = 1000, GRID_WIDTH = 50
 */
public class IslandStorage {

    // ─── Grid constants ────────────────────────────────────────────────────────
    public static final double GRID_ORIGIN_X  = 5000;
    public static final double GRID_ORIGIN_Z  = 5000;
    public static final double ISLAND_SPACING = 1000;
    public static final int    GRID_WIDTH     = 50;

    // ─── Persistence ───────────────────────────────────────────────────────────
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<IslandData>>() {}.getType();

    private final Path dataFile;
    private final Logger logger;

    /** In-memory cache of all islands */
    private List<IslandData> islands = new ArrayList<>();

    // ─── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param dataFolder   Plugin / extension data directory (must exist).
     * @param logger       Logger for error output.
     */
    public IslandStorage(File dataFolder, Logger logger) {
        this.dataFile = dataFolder.toPath().resolve("islands.json");
        this.logger   = logger;
        load();
        logger.info("[Island Storage] Loaded " + islands.size() + " island(s) from disk.");
    }

    // ─── Persistence helpers ───────────────────────────────────────────────────

    private synchronized void load() {
        if (!Files.exists(dataFile)) {
            islands = new ArrayList<>();
            return;
        }
        try (Reader r = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            List<IslandData> loaded = GSON.fromJson(r, LIST_TYPE);
            islands = loaded != null ? loaded : new ArrayList<>();
        } catch (IOException e) {
            logger.severe("[Island Storage] Failed to load islands.json: " + e.getMessage());
            islands = new ArrayList<>();
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            try (Writer w = Files.newBufferedWriter(dataFile, StandardCharsets.UTF_8)) {
                GSON.toJson(islands, w);
            }
        } catch (IOException e) {
            logger.severe("[Island Storage] Failed to save islands.json: " + e.getMessage());
        }
    }

    // ─── ISLAND CRUD ──────────────────────────────────────────────────────────

    /** Returns a defensive copy of all islands. */
    public synchronized List<IslandData> getAllIslands() {
        return new ArrayList<>(islands);
    }

    public synchronized IslandData getIslandById(String islandId) {
        return islands.stream()
                .filter(i -> i.id.equals(islandId))
                .findFirst().orElse(null);
    }

    public synchronized IslandData getIslandByPlayerId(String playerId) {
        return islands.stream()
                .filter(i -> i.members.stream().anyMatch(m -> m.id.equals(playerId)))
                .findFirst().orElse(null);
    }

    public synchronized IslandData getIslandByName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return islands.stream()
                .filter(i -> i.name.toLowerCase(Locale.ROOT).equals(lower))
                .findFirst().orElse(null);
    }

    /**
     * Finds the island whose center is at (centerX, centerZ). Used for admin
     * recovery/assignment when an island exists on disk/in-world but its
     * owner link needs to be (re)established manually.
     */
    public synchronized IslandData getIslandByCoords(double centerX, double centerZ) {
        return islands.stream()
                .filter(i -> i.centerX == centerX && i.centerZ == centerZ)
                .findFirst().orElse(null);
    }

    /**
     * Assigns (or reassigns) ownership of an island to the given player,
     * replacing any existing owner-role member entry. If the player is
     * already a member with a different role, they are promoted to Owner.
     * Persists immediately.
     */
    public synchronized IslandData assignOwner(String islandId, String playerId, String playerName) {
        IslandData island = getIslandById(islandId);
        if (island == null) return null;

        island.ownerId   = playerId;
        island.ownerName = playerName;

        boolean found = false;
        for (IslandData.Member m : island.members) {
            if (m.id.equals(playerId)) {
                m.role = "Owner";
                m.name = playerName;
                found = true;
            } else if ("Owner".equals(m.role)) {
                m.role = "Co-Owner";
            }
        }
        if (!found) {
            island.members.add(0, new IslandData.Member(playerId, playerName, "Owner", System.currentTimeMillis()));
        }

        save();
        return island;
    }

    /** Backward-compat: finds island by player ID (member lookup). */
    public synchronized IslandData getIsland(String playerId) {
        return getIslandByPlayerId(playerId);
    }

    public synchronized IslandData updateIslandName(String islandId, String newName) {
        return updateIsland(islandId, Map.of("name", newName));
    }

    public synchronized IslandData clearPwarps(String islandId) {
        return updateIsland(islandId, Map.of("pwarps", new ArrayList<IslandData.Warp>()));
    }

    public synchronized IslandData updateIslandHome(String islandId, double homeX, double homeY, double homeZ) {
        return updateIsland(islandId, Map.of("homeX", homeX, "homeY", homeY, "homeZ", homeZ));
    }

    /**
     * Creates a new island for the given owner and persists it.
     * Mirrors createIsland() in island_storage.js.
     */
    public synchronized IslandData createIsland(String ownerId, String ownerName) {
        int slot = allocateSlot();
        double[] coords = slotToCoords(slot);

        IslandData island = new IslandData();
        island.id         = "slot_" + slot;
        island.slot       = slot;
        island.centerX    = coords[0];
        island.centerZ    = coords[1];
        island.name       = ownerName;
        island.ownerId    = ownerId;
        island.ownerName  = ownerName;
        island.maxMembers = 4;
        island.homeX      = coords[0];
        island.homeY      = 65;
        island.homeZ      = coords[1];
        island.createdAt  = System.currentTimeMillis();

        IslandData.Member ownerEntry = new IslandData.Member(
                ownerId, ownerName, "Owner", System.currentTimeMillis());
        island.members.add(ownerEntry);

        islands.add(island);
        save();
        return island;
    }

    public synchronized boolean deleteIsland(String islandId) {
        boolean removed = islands.removeIf(i -> i.id.equals(islandId));
        if (removed) save();
        return removed;
    }

    /**
     * Applies a map of field updates to an island and persists.
     * Returns the updated island or null if not found.
     *
     * Keys are field names mirroring JS object keys:
     * "homeX", "homeY", "homeZ", "name", "ownerId", "ownerName",
     * "maxMembers", "pwarps" (List<Warp>)
     */
    public synchronized IslandData updateIsland(String islandId, Map<String, Object> updates) {
        IslandData island = getIslandById(islandId);
        if (island == null) return null;
        applyUpdates(island, updates);
        save();
        return island;
    }

    @SuppressWarnings("unchecked")
    private void applyUpdates(IslandData island, Map<String, Object> updates) {
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            switch (e.getKey()) {
                case "name"         -> island.name         = (String)  e.getValue();
                case "ownerId"      -> island.ownerId      = (String)  e.getValue();
                case "ownerName"    -> island.ownerName    = (String)  e.getValue();
                case "homeX"        -> island.homeX        = toDouble(e.getValue());
                case "homeY"        -> island.homeY        = toDouble(e.getValue());
                case "homeZ"        -> island.homeZ        = toDouble(e.getValue());
                case "maxMembers"   -> island.maxMembers   = ((Number) e.getValue()).intValue();
                case "pwarps"       -> island.pwarps       = (List<IslandData.Warp>) e.getValue();
                case "borderRadius" -> island.borderRadius = ((Number) e.getValue()).intValue();
                case "flags"        -> island.flags        = (java.util.Map<String,Boolean>) e.getValue();
            }
        }
    }

    /** Set a single island flag (e.g. "pvp", "mobSpawning"). */
    public synchronized IslandData setIslandFlag(String islandId, String flag, boolean value) {
        IslandData island = getIslandById(islandId);
        if (island == null) return null;
        if (island.flags == null) island.flags = new java.util.HashMap<>();
        island.flags.put(flag, value);
        save();
        return island;
    }

    /** Update the island border radius. */
    public synchronized IslandData setBorderRadius(String islandId, int radius) {
        IslandData island = getIslandById(islandId);
        if (island == null) return null;
        island.borderRadius = radius;
        save();
        return island;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        return Double.parseDouble(o.toString());
    }

    // ─── MEMBER MANAGEMENT ────────────────────────────────────────────────────

    public synchronized IslandData addMember(String islandId, String playerId,
                                             String playerName, String role) {
        IslandData island = getIslandById(islandId);
        if (island == null) return null;
        island.members.add(new IslandData.Member(
                playerId, playerName, role, System.currentTimeMillis()));
        save();
        return island;
    }

    /**
     * Result of removeMember: holds the updated island and whether it was deleted.
     */
    public static class RemoveResult {
        public final IslandData island;
        public final boolean deleted;
        RemoveResult(IslandData island, boolean deleted) {
            this.island  = island;
            this.deleted = deleted;
        }
    }

    /**
     * Removes a player from their island.
     * Mirrors removeMember() in island_storage.js:
     * - Deletes pwarps created by the player.
     * - Deletes the island if no members remain.
     * - Auto-promotes oldest remaining member to Owner if owner left.
     */
    public synchronized RemoveResult removeMember(String islandId, String playerId) {
        IslandData island = getIslandById(islandId);
        if (island == null) return null;

        // Remove the player from members
        island.members.removeIf(m -> m.id.equals(playerId));

        // Remove pwarps created by this player
        island.pwarps.removeIf(p -> p.creatorId.equals(playerId));

        // If no members remain, delete island
        if (island.members.isEmpty()) {
            deleteIsland(islandId);
            return new RemoveResult(island, true);
        }

        // If owner left, promote oldest remaining member
        boolean hasOwner = island.members.stream().anyMatch(m -> "Owner".equals(m.role));
        if (!hasOwner) {
            IslandData.Member oldest = island.members.get(0);
            oldest.role      = "Owner";
            island.ownerId   = oldest.id;
            island.ownerName = oldest.name;
        }

        save();
        return new RemoveResult(island, false);
    }

    public synchronized IslandData setMemberRole(String islandId, String playerId, String newRole) {
        IslandData island = getIslandById(islandId);
        if (island == null) return null;

        IslandData.Member member = island.members.stream()
                .filter(m -> m.id.equals(playerId)).findFirst().orElse(null);
        if (member == null) return null;

        // Promoting to Owner: demote current owner first
        if ("Owner".equals(newRole)) {
            island.members.stream()
                    .filter(m -> "Owner".equals(m.role))
                    .forEach(m -> m.role = "Admin");
            island.ownerId   = playerId;
            island.ownerName = member.name;
        }

        member.role = newRole;
        save();
        return island;
    }

    public synchronized String getMemberRole(IslandData island, String playerId) {
        if (island == null) return null;
        return island.members.stream()
                .filter(m -> m.id.equals(playerId))
                .map(m -> m.role)
                .findFirst().orElse(null);
    }

    // ─── PWARP MANAGEMENT ─────────────────────────────────────────────────────

    public synchronized IslandData addPwarp(String islandId, String warpName,
                                            double x, double y, double z,
                                            String creatorId, String creatorName) {
        IslandData island = getIslandById(islandId);
        if (island == null) return null;

        // Remove old warp with same name (case-insensitive)
        String lower = warpName.toLowerCase(Locale.ROOT);
        island.pwarps.removeIf(p -> p.name.toLowerCase(Locale.ROOT).equals(lower));
        island.pwarps.add(new IslandData.Warp(warpName, x, y, z, creatorId, creatorName));

        save();
        return island;
    }

    public synchronized IslandData removePwarp(String islandId, String warpName) {
        IslandData island = getIslandById(islandId);
        if (island == null) return null;
        String lower = warpName.toLowerCase(Locale.ROOT);
        island.pwarps.removeIf(p -> p.name.toLowerCase(Locale.ROOT).equals(lower));
        save();
        return island;
    }

    public synchronized IslandData.Warp getPwarp(String islandId, String warpName) {
        IslandData island = getIslandById(islandId);
        if (island == null) return null;
        String lower = warpName.toLowerCase(Locale.ROOT);
        return island.pwarps.stream()
                .filter(p -> p.name.toLowerCase(Locale.ROOT).equals(lower))
                .findFirst().orElse(null);
    }

    // ─── SLOT / GRID SYSTEM ───────────────────────────────────────────────────

    /**
     * Converts a slot index to world coordinates.
     * Returns double[]{x, z}.
     */
    public static double[] slotToCoords(int slot) {
        int col = slot % GRID_WIDTH;
        int row = slot / GRID_WIDTH;
        return new double[]{
            GRID_ORIGIN_X + col * ISLAND_SPACING,
            GRID_ORIGIN_Z + row * ISLAND_SPACING
        };
    }

    private synchronized int allocateSlot() {
        Set<Integer> used = new HashSet<>();
        for (IslandData i : islands) used.add(i.slot);
        int slot = 0;
        while (used.contains(slot)) slot++;
        return slot;
    }
}

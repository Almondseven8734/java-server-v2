package com.skyblock.dungeon.floor;

import java.io.*;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Owns every player's DungeonPlayerState in memory, and persists it to
 * a flat file on disk so dungeon position/floor survives a server
 * restart (not just a relog while the server stays up - PlayerJoinEvent
 * only fires after a restart too, and per design a player should
 * resume exactly where they left off regardless of why they were
 * offline).
 *
 * File format is intentionally simple (one CSV-ish line per player) to
 * avoid pulling in a JSON dependency this project doesn't already use
 * elsewhere; swap for a real serializer later if the schema grows.
 *
 * Mirrors the constructor pattern used by IslandStorage/AhStorage
 * elsewhere in this codebase (dataFolder + Logger).
 */
public final class DungeonPlayerStateStorage {

    private final File storageFile;
    private final Logger logger;
    private final Map<UUID, DungeonPlayerState> states = new ConcurrentHashMap<>();

    public DungeonPlayerStateStorage(File dataFolder, Logger logger) {
        this.logger = logger;
        File dungeonFolder = new File(dataFolder, "dungeon");
        if (!dungeonFolder.exists()) {
            dungeonFolder.mkdirs();
        }
        this.storageFile = new File(dungeonFolder, "player_states.csv");
        load();
    }

    /**
     * Resolves a player's state, creating a fresh (not-in-dungeon)
     * entry if none exists yet. Safe to pass directly as the
     * Function<UUID, DungeonPlayerState> every dungeon listener expects.
     */
    public DungeonPlayerState get(UUID playerId) {
        return states.computeIfAbsent(playerId, DungeonPlayerState::new);
    }

    /**
     * Persists one player's state to disk immediately. Safe to pass
     * directly as the BiConsumer<UUID, DungeonPlayerState> DungeonJoinQuitListener expects.
     */
    public void persist(UUID playerId, DungeonPlayerState state) {
        states.put(playerId, state);
        saveAll();
    }

    private void load() {
        if (!storageFile.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(storageFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                if (parts.length != 8) continue;

                UUID id = UUID.fromString(parts[0]);
                DungeonPlayerState state = new DungeonPlayerState(id);
                state.setCurrentFloor(Integer.parseInt(parts[1]));
                state.setInsideDungeon(Boolean.parseBoolean(parts[2]));
                state.savePosition(
                        Double.parseDouble(parts[3]),
                        Double.parseDouble(parts[4]),
                        Double.parseDouble(parts[5]),
                        Float.parseFloat(parts[6]),
                        Float.parseFloat(parts[7])
                );
                states.put(id, state);
            }
        } catch (IOException e) {
            logger.warning("[Dungeon] Failed to load player state file: " + e.getMessage());
        }
    }

    private synchronized void saveAll() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(storageFile))) {
            for (DungeonPlayerState state : states.values()) {
                writer.write(String.join(",",
                        state.getPlayerId().toString(),
                        String.valueOf(state.getCurrentFloor()),
                        String.valueOf(state.isInsideDungeon()),
                        String.valueOf(state.getX()),
                        String.valueOf(state.getY()),
                        String.valueOf(state.getZ()),
                        String.valueOf(state.getYaw()),
                        String.valueOf(state.getPitch())
                ));
                writer.newLine();
            }
        } catch (IOException e) {
            logger.warning("[Dungeon] Failed to save player state file: " + e.getMessage());
        }
    }
}

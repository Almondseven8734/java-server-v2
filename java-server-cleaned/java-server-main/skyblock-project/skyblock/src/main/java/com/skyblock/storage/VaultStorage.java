package com.skyblock.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Player Vault Storage
 *
 * Persists each player's vault contents to a single JSON file:
 *   player_vaults.json
 *
 * Each vault's contents are stored as a Base64-encoded blob (see
 * VaultSystem's serialize()/deserialize() helpers, which use Bukkit's own
 * object stream so item meta, enchantments, lore, and PersistentDataContainer
 * tags — e.g. crate-key/talisman markers — survive a save/load cycle exactly,
 * the same as everything else in this plugin's economy).
 *
 * Layout: Map<playerUUID-as-string, String[MAX_VAULTS]>
 *   — each array slot is a Base64 blob, or null if that vault is empty/unused.
 */
public class VaultStorage {

    /** Players are limited to 2 private vaults: vault 0 and vault 1. */
    public static final int MAX_VAULTS = 2;

    /** Slots per vault — identical capacity to a vanilla ender chest. */
    public static final int VAULT_SIZE = 27;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type DATA_TYPE = new TypeToken<Map<String, String[]>>() {}.getType();

    private final Path   dataFile;
    private final Logger logger;

    /** Map<playerUUID-as-string, String[MAX_VAULTS]> of Base64-encoded vault contents. */
    private final Map<String, String[]> data = new HashMap<>();

    public VaultStorage(File dataFolder, Logger logger) {
        this.dataFile = dataFolder.toPath().resolve("player_vaults.json");
        this.logger   = logger;
        load();
        logger.info("[Vault Storage] Loaded " + data.size() + " player vault record(s).");
    }

    /** Returns the raw Base64 blob for a vault, or null if it's empty/never been used. */
    public synchronized String getRaw(UUID playerId, int vaultIndex) {
        String[] vaults = data.get(playerId.toString());
        if (vaults == null || vaultIndex < 0 || vaultIndex >= vaults.length) return null;
        return vaults[vaultIndex];
    }

    /** Saves the raw Base64 blob for a vault and persists to disk immediately. */
    public synchronized void setRaw(UUID playerId, int vaultIndex, String base64) {
        if (vaultIndex < 0 || vaultIndex >= MAX_VAULTS) return;
        String[] vaults = data.computeIfAbsent(playerId.toString(), k -> new String[MAX_VAULTS]);
        vaults[vaultIndex] = base64;
        save();
    }

    // =========================================================================
    // PERSISTENCE
    // =========================================================================

    private void load() {
        if (!Files.exists(dataFile)) return;
        try (Reader r = Files.newBufferedReader(dataFile, StandardCharsets.UTF_8)) {
            Map<String, String[]> loaded = GSON.fromJson(r, DATA_TYPE);
            if (loaded != null) data.putAll(loaded);
        } catch (IOException e) {
            logger.severe("[Vault Storage] Failed to load " + dataFile.getFileName() + ": " + e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(dataFile.getParent());
            Files.write(dataFile, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.severe("[Vault Storage] Failed to save " + dataFile.getFileName() + ": " + e.getMessage());
        }
    }
}

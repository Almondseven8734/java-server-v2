package com.skyblock.ah;

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
 * Auction House Storage
 *
 * Translated from ah_storage.js.
 *
 * Bedrock used world dynamic properties. Java uses JSON files in the
 * plugin data folder:
 *   ah_listings.json       — all active listings
 *   ah_collections.json    — unclaimed items per player
 *
 * All public methods are thread-safe via synchronized blocks.
 */
public class AhStorage {

    // ─── Data models (mirror JS listing / collectionItem shapes) ─────────────

    public static class Listing {
        public String id;
        public String sellerId;
        public String sellerName;
        public AhItemData item;
        public int price;
        public long timestamp;
    }

    public static class CollectionEntry {
        public String id;
        public AhItemData item;
        public long timestamp;
    }

    /** Serialized form of an ItemStack (mirrors serializeItem in ah_system.js). */
    public static class AhItemData {
        public String typeId;
        public int    amount;
        public String nameTag;
        public List<String> lore = new ArrayList<>();
        public List<EnchantData> enchantments = new ArrayList<>();
    }

    public static class EnchantData {
        public String type;
        public int    level;
    }

    // ─── State ────────────────────────────────────────────────────────────────

    private final List<Listing>                        allListings   = new ArrayList<>();
    /** Map<playerId, List<CollectionEntry>> */
    private final Map<String, List<CollectionEntry>>   collections   = new HashMap<>();

    private final Path    listingsFile;
    private final Path    collectionsFile;
    private final Logger  logger;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ─── Constructor ──────────────────────────────────────────────────────────

    public AhStorage(File dataFolder, Logger logger) {
        this.listingsFile    = dataFolder.toPath().resolve("ah_listings.json");
        this.collectionsFile = dataFolder.toPath().resolve("ah_collections.json");
        this.logger          = logger;
        load();
        logger.info("[AH Storage] Storage system loaded! "
                + allListings.size() + " listing(s).");
    }

    // ─── Listing API (mirrors ah_storage.js exports) ──────────────────────────

    public synchronized List<Listing> getAllListings() {
        return new ArrayList<>(allListings);
    }

    public synchronized Listing getListing(String listingId) {
        return allListings.stream()
                .filter(l -> listingId.equals(l.id))
                .findFirst()
                .orElse(null);
    }

    public synchronized Listing createListing(String sellerId, String sellerName,
                                               AhItemData itemData, int price) {
        Listing listing = new Listing();
        listing.id         = sellerId + "_" + System.currentTimeMillis();
        listing.sellerId   = sellerId;
        listing.sellerName = sellerName;
        listing.item       = itemData;
        listing.price      = price;
        listing.timestamp  = System.currentTimeMillis();

        allListings.add(listing);
        save();
        return listing;
    }

    public synchronized Listing removeListing(String listingId) {
        Iterator<Listing> it = allListings.iterator();
        while (it.hasNext()) {
            Listing l = it.next();
            if (listingId.equals(l.id)) {
                it.remove();
                save();
                return l;
            }
        }
        return null;
    }

    public synchronized List<Listing> getPlayerListings(String playerId) {
        List<Listing> result = new ArrayList<>();
        for (Listing l : allListings) {
            if (playerId.equals(l.sellerId)) result.add(l);
        }
        return result;
    }

    public synchronized int getPlayerListingCount(String playerId) {
        return (int) allListings.stream().filter(l -> playerId.equals(l.sellerId)).count();
    }

    // ─── Collection API ───────────────────────────────────────────────────────

    public synchronized List<CollectionEntry> getPlayerCollection(String playerId) {
        return new ArrayList<>(collections.getOrDefault(playerId, new ArrayList<>()));
    }

    public synchronized boolean addToCollection(String playerId, AhItemData itemData) {
        CollectionEntry entry = new CollectionEntry();
        entry.id        = playerId + "_" + System.currentTimeMillis() + "_" + Math.random();
        entry.item      = itemData;
        entry.timestamp = System.currentTimeMillis();

        collections.computeIfAbsent(playerId, k -> new ArrayList<>()).add(entry);
        saveCollections();
        return true;
    }

    public synchronized CollectionEntry removeFromCollection(String playerId, String itemId) {
        List<CollectionEntry> col = collections.get(playerId);
        if (col == null) return null;
        Iterator<CollectionEntry> it = col.iterator();
        while (it.hasNext()) {
            CollectionEntry e = it.next();
            if (itemId.equals(e.id)) {
                it.remove();
                saveCollections();
                return e;
            }
        }
        return null;
    }

    public synchronized int getCollectionCount(String playerId) {
        List<CollectionEntry> col = collections.get(playerId);
        return col == null ? 0 : col.size();
    }

    // ─── Backup (mirrors backupAHData in ah_storage.js) ──────────────────────

    private static final int MAX_BACKUPS = 5;

    public synchronized boolean backupAHData() {
        try {
            Path backup = listingsFile.getParent().resolve("ah_backup_" + System.currentTimeMillis() + ".json");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("timestamp", System.currentTimeMillis());
            data.put("listings",  allListings);
            data.put("collections", collections);
            Files.write(backup, GSON.toJson(data).getBytes(StandardCharsets.UTF_8));
            pruneOldBackups();
            return true;
        } catch (IOException e) {
            logger.severe("[AH Storage] Backup failed: " + e.getMessage());
            return false;
        }
    }

    /** Keep only the MAX_BACKUPS most recent ah_backup_*.json files. */
    private void pruneOldBackups() {
        try {
            Path dir = listingsFile.getParent();
            List<Path> backups = Files.list(dir)
                    .filter(p -> p.getFileName().toString().startsWith("ah_backup_")
                              && p.getFileName().toString().endsWith(".json"))
                    .sorted(java.util.Comparator.comparing(p -> {
                        try { return Files.getLastModifiedTime(p); }
                        catch (IOException ex) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                    }))
                    .collect(java.util.stream.Collectors.toList());

            int toDelete = backups.size() - MAX_BACKUPS;
            for (int i = 0; i < toDelete; i++) {
                Files.deleteIfExists(backups.get(i));
                logger.info("[AH Storage] Pruned old backup: " + backups.get(i).getFileName());
            }
        } catch (IOException e) {
            logger.warning("[AH Storage] Backup pruning failed: " + e.getMessage());
        }
    }

    // ─── Persistence ──────────────────────────────────────────────────────────

    private void load() {
        // Listings
        if (Files.exists(listingsFile)) {
            try (Reader r = Files.newBufferedReader(listingsFile, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<List<Listing>>(){}.getType();
                List<Listing> loaded = GSON.fromJson(r, type);
                if (loaded != null) allListings.addAll(loaded);
            } catch (IOException e) {
                logger.severe("[AH Storage] Failed to load listings: " + e.getMessage());
            }
        }

        // Collections
        if (Files.exists(collectionsFile)) {
            try (Reader r = Files.newBufferedReader(collectionsFile, StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<String, List<CollectionEntry>>>(){}.getType();
                Map<String, List<CollectionEntry>> loaded = GSON.fromJson(r, type);
                if (loaded != null) collections.putAll(loaded);
            } catch (IOException e) {
                logger.severe("[AH Storage] Failed to load collections: " + e.getMessage());
            }
        }
    }

    private void save() {
        try {
            Files.createDirectories(listingsFile.getParent());
            Files.write(listingsFile, GSON.toJson(allListings).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.severe("[AH Storage] Failed to save listings: " + e.getMessage());
        }
        // Collections save shares the same cadence
        saveCollections();
    }

    private void saveCollections() {
        try {
            Files.createDirectories(collectionsFile.getParent());
            Files.write(collectionsFile, GSON.toJson(collections).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.severe("[AH Storage] Failed to save collections: " + e.getMessage());
        }
    }
}

package com.skyblock.dungeon.floor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names and tracks the dungeon's on-disk world folders across resets.
 *
 * Every reset gives the dungeon world a brand-new, never-before-used
 * folder name instead of recreating it under a fixed literal name -
 * that's the fix for the real underlying bug (Paper/Bukkit's region-file
 * and chunk caching is keyed by absolute path, so recreating a world at
 * a path that was JUST vacated risks resolving reads through a stale
 * cached handle instead of the fresh bytes on disk). See
 * DungeonResetScheduler for the full writeup.
 *
 * Naming scheme: {@code dungeon_<seed>_<yyyyMMdd-HHmmss>} (UTC), e.g.
 * {@code dungeon_3f9a2c1b7e4d5f80_20260701-063000}. The seed makes each
 * name unique even if two resets somehow landed in the same second; the
 * date makes names sortable/parseable so old cycles can be identified
 * and pruned without needing to remember exactly which single folder
 * was "the previous one" - a full directory sweep can find every
 * folder matching the pattern, extract its date, and clean up anything
 * older than the current cycle, including orphans left behind by a
 * crash or a failed background delete in some earlier cycle.
 *
 * The currently active name also has to survive a server restart
 * mid-cycle (otherwise startup would have no way to know which folder
 * is live and would either create a brand-new one or fall back to a
 * stale default), so it's persisted to a tiny one-line file in the
 * plugin's data folder.
 */
public final class DungeonWorldNameStore {

    private static final String RECORD_FILE = "dungeon-world-name.txt";
    private static final String PREFIX = "dungeon_";

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    /** Matches names this class generates: dungeon_<hex seed>_<yyyyMMdd-HHmmss>. */
    private static final Pattern NAME_PATTERN =
            Pattern.compile("^dungeon_[0-9a-fA-F]+_(\\d{8}-\\d{6})$");

    private DungeonWorldNameStore() {
    }

    /**
     * Generates a brand-new folder name for a dungeon world cycle,
     * derived from a seed (for uniqueness) and the current UTC date/time
     * (for sortability/pruning).
     */
    public static String generateNextName(long seed) {
        String seedPart = Long.toHexString(seed);
        String datePart = DATE_FORMAT.format(Instant.now());
        return PREFIX + seedPart + "_" + datePart;
    }

    /**
     * Extracts the UTC instant encoded in a dungeon world folder name, if
     * it matches this class's naming scheme. Returns empty for anything
     * that doesn't match - a pre-this-fix legacy "dungeon" folder, an
     * unrelated world folder like "world" or "world_nether", or garbage -
     * so callers never accidentally treat an unrelated folder as prunable.
     */
    public static Optional<Instant> extractInstant(String worldName) {
        Matcher matcher = NAME_PATTERN.matcher(worldName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            LocalDateTime parsed = LocalDateTime.parse(matcher.group(1), DATE_FORMAT);
            return Optional.of(parsed.toInstant(ZoneOffset.UTC));
        } catch (RuntimeException e) {
            // Matched the shape but somehow failed to parse - treat as
            // unrecognized rather than risk a bad comparison.
            return Optional.empty();
        }
    }

    /** Reads the currently active dungeon world folder name, if a reset (or first startup) has ever recorded one. */
    public static Optional<String> load(File dataFolder, Logger logger) {
        File record = new File(dataFolder, RECORD_FILE);
        if (!record.exists()) {
            return Optional.empty();
        }
        try {
            String name = Files.readString(record.toPath(), StandardCharsets.UTF_8).trim();
            return name.isEmpty() ? Optional.empty() : Optional.of(name);
        } catch (IOException e) {
            logger.warning("[Dungeon] Could not read persisted world name record at '" + record.getPath()
                    + "': " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Persists the currently active dungeon world folder name so a
     * server restart before the next reset picks up the right folder
     * instead of guessing.
     */
    public static void save(File dataFolder, String worldName, Logger logger) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.severe("[Dungeon] Could not create plugin data folder '" + dataFolder.getPath()
                    + "' to persist the current world name.");
            return;
        }
        File record = new File(dataFolder, RECORD_FILE);
        try {
            Files.writeString(record.toPath(), worldName, StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.severe("[Dungeon] Could not persist current world name '" + worldName + "' to '"
                    + record.getPath() + "' - a server restart before the next reset would lose track of "
                    + "this folder: " + e.getMessage());
        }
    }
}

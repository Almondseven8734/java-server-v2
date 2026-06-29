package com.skyblock.generator;

import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.logging.Logger;

/**
 * Island Generation
 *
 * Translated from island_generator.js.
 *
 * JS used Bedrock's `/structure load "I" ...` and `/fill ... air`.
 * In Java (Geyser / Paper/Spigot backend) we use Bukkit's World API.
 *
 * Structure loading:  dispatches the structure command through the server
 *                     (works if the world has the "I" structure saved).
 * Island clearing:    fills a generous air volume via BlockData iteration.
 *
 * Constants match JS exactly:
 *   STRUCTURE_Y = 76   (structure load origin Y)
 *   HOME_Y      = 75   (player home Y on top of island)
 */
public class IslandGenerator {

    // Y level of the structure load origin point
    private static final int STRUCTURE_Y = 76;

    // Player home Y offset from center
    public static final int HOME_Y = 75;

    // XZ radius must match IslandProtection.ISLAND_RADIUS so every block a
    // player could have placed within their border is wiped on reset.
    private static final int CLEAR_XZ_RADIUS = 200;

    // Full world height for 1.18+ (bedrock at -64, build limit at 319).
    // Using world.getMinHeight() / getMaxHeight() at call-time would be
    // cleaner but requires a World reference here; these constants match
    // Paper's default overworld and are safe to hard-code.
    private static final int CLEAR_Y_MIN = -64;
    private static final int CLEAR_Y_MAX = 319;

    private final Logger logger;

    public IslandGenerator(Logger logger) {
        this.logger = logger;
    }

    // ─── Result ───────────────────────────────────────────────────────────────

    public static class Result {
        public final double homeX;
        public final double homeY;
        public final double homeZ;

        public Result(double homeX, double homeY, double homeZ) {
            this.homeX = homeX;
            this.homeY = homeY;
            this.homeZ = homeZ;
        }
    }

    // ─── generateIsland ───────────────────────────────────────────────────────

    /**
     * Loads the starter island structure "i" at the given center coordinates
     * using the modern "place template" command.
     *
     * JS equivalent (Bedrock):
     *   dimension.runCommand(`structure load "I" ${centerX - 15} ${STRUCTURE_Y - 12} ${centerZ - 9}`)
     *
     * On Java Edition we use "place template minecraft:i x y z" instead of the
     * older "structure load" syntax, which doesn't reliably resolve namespaced
     * IDs in this environment.
     *
     * On a Bukkit/Paper server the easiest route is dispatching a console command.
     * If you use a structure library or custom generator, swap out the
     * dispatchCommand call here.
     *
     * @param centerX   Island centre X coordinate.
     * @param centerZ   Island centre Z coordinate.
     * @param world     Bukkit World to load the structure in.
     * @return          Home location for the player.
     */
    public Result generateIsland(double centerX, double centerZ, World world) {
        try {
            int sx = (int) (centerX - 15);
            int sy = STRUCTURE_Y - 12;
            int sz = (int) (centerZ - 9);

            // Force-load the chunk(s) the structure will be placed in.
            // Vanilla's "structure load" requires the target position to already
            // be loaded, which void worlds won't do automatically.
            forceLoadArea(world, sx, sz, sx + 30, sz + 18);

            String cmd = String.format("place template minecraft:i %d %d %d", sx, sy, sz);
            Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(), cmd);
            logger.info("[Skyblock] Structure loaded at (" + centerX + ", " + STRUCTURE_Y + ", " + centerZ + ")");
        } catch (Exception e) {
            logger.severe("[Skyblock] Failed to generate island at ("
                    + centerX + ", " + STRUCTURE_Y + ", " + centerZ + "): " + e.getMessage());
        }

        return new Result(centerX, HOME_Y, centerZ);
    }

    /**
     * Forces all chunks covering the given block-coordinate rectangle to be loaded
     * synchronously. Needed before running vanilla commands like
     * "structure load" or "fill" on freshly-generated/void worlds, since those
     * commands silently fail with "position not loaded" otherwise.
     */
    private void forceLoadArea(World world, int xMin, int zMin, int xMax, int zMax) {
        int chunkXMin = xMin >> 4;
        int chunkXMax = xMax >> 4;
        int chunkZMin = zMin >> 4;
        int chunkZMax = zMax >> 4;

        for (int cx = chunkXMin; cx <= chunkXMax; cx++) {
            for (int cz = chunkZMin; cz <= chunkZMax; cz++) {
                if (!world.isChunkLoaded(cx, cz)) {
                    world.loadChunk(cx, cz, true);
                }
            }
        }
    }

    // ─── clearIsland ──────────────────────────────────────────────────────────

    /**
     * Clears the island area by filling it with air.
     *
     * The XZ area is 401×401 = 160,801 blocks wide. The Y range is
     * -64 to 319 = 384 layers. Total = ~61.7M blocks — far above Vanilla's
     * 32,768-block /fill limit, so we split into narrow X-axis strips that
     * each fit within the limit.
     *
     * Strip width calculation:
     *   max blocks per fill = 32,768
     *   Y layers            = CLEAR_Y_MAX - CLEAR_Y_MIN + 1  (384)
     *   Z width             = CLEAR_XZ_RADIUS * 2 + 1        (401)
     *   max X per strip     = floor(32768 / (384 * 401))     = 0  → min 1
     *   → we use X-strip width of 1 (each strip is 1 × 384 × 401 = 153,984,
     *     still too big), so split further by Z as well if needed.
     *
     * Actually the safest approach: iterate X strips of width W where
     *   W * Z_WIDTH * Y_HEIGHT <= 32768
     *   W <= 32768 / (401 * 384) ≈ 0.21  → always 1 column wide is too big.
     *
     * Instead we chunk by X and Y together:
     *   fix Z_WIDTH = 401, fix X_WIDTH = 1 column at a time,
     *   split that column's Y range into slices of height h where
     *   1 * 401 * h <= 32768  →  h <= 81.
     *   We use h = 80 to keep a margin.
     */
    public void clearIsland(double centerX, double centerZ, World world) {
        try {
            int x1 = (int) (centerX - CLEAR_XZ_RADIUS);
            int x2 = (int) (centerX + CLEAR_XZ_RADIUS);
            int z1 = (int) (centerZ - CLEAR_XZ_RADIUS);
            int z2 = (int) (centerZ + CLEAR_XZ_RADIUS);

            // Use the world's actual height bounds if available
            int worldYMin = world.getMinHeight();
            int worldYMax = world.getMaxHeight() - 1; // getMaxHeight() is exclusive
            int yMin = Math.max(CLEAR_Y_MIN, worldYMin);
            int yMax = Math.min(CLEAR_Y_MAX, worldYMax);

            int zWidth    = z2 - z1 + 1; // 401
            // Max Y layers per fill call for a 1-wide X strip
            int ySlice    = Math.max(1, 32768 / zWidth); // floor(32768/401) = 81 → use 80
            ySlice = Math.min(ySlice, 80);

            // Force-load all chunks in the full clear area before any fills.
            forceLoadArea(world, x1, z1, x2, z2);

            // Walk every X column, slicing the Y range into safe chunks
            for (int x = x1; x <= x2; x++) {
                for (int yBase = yMin; yBase <= yMax; yBase += ySlice) {
                    int yTop = Math.min(yBase + ySlice - 1, yMax);
                    String cmd = String.format("fill %d %d %d %d %d %d air",
                            x, yBase, z1, x, yTop, z2);
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
            }

            logger.info("[Skyblock] Cleared island at (" + centerX + ", " + centerZ + ")");
        } catch (Exception e) {
            logger.severe("[Skyblock] Failed to clear island: " + e.getMessage());
        }
    }
}

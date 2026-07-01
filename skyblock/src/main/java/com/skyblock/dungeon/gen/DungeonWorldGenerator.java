package com.skyblock.dungeon.gen;

import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The dungeon world's ChunkGenerator — fills each floor's vertical
 * band with solid stone (later carved into caves by DungeonRoomPlanner)
 * and places a bedrock border between floors.
 *
 * Isolation guarantees — nothing in here can affect any other world:
 *
 *   shouldGenerateNoise()        = true   Our generateNoise() replaces
 *                                         vanilla terrain entirely for
 *                                         this world. No vanilla
 *                                         heightmap, no biome surface
 *                                         blending, nothing.
 *   shouldGenerateSurface()      = false  No grass/dirt/sand layers.
 *   shouldGenerateCaves()        = false  No vanilla cave carvers.
 *   shouldGenerateDecorations()  = false  No ores, no vegetation.
 *   shouldGenerateMobs()         = false  No creature spawning pass.
 *   shouldGenerateStructures()   = false  No villages, mineshafts, etc.
 *   shouldGenerateBedrock()      = false  We handle bedrock borders
 *                                         ourselves in generateNoise().
 *   getDefaultPopulators()       = []     Suppresses any vanilla
 *                                         BlockPopulator that Bukkit
 *                                         might otherwise inject (ore
 *                                         veins, decorators, etc.).
 *
 * These flags are per-world — they have no effect on the overworld or
 * any other loaded world. Each world's ChunkGenerator is completely
 * independent; Bukkit/Paper never shares generator state across worlds.
 *
 * This class must also be used (not StoneBufferGenerator) because a
 * Bukkit world has exactly one ChunkGenerator applied to the full world
 * height in one pass. StoneBufferGenerator only handles a single floor
 * band — this class loops over all floor slots in one pass, which is
 * what the multi-floor stacked design requires.
 */
public final class DungeonWorldGenerator extends ChunkGenerator {

    private final FloorBounds floorBounds;
    private final double originX;
    private final double originZ;
    private final Material fillMaterial;

    public DungeonWorldGenerator(FloorBounds floorBounds, double originX, double originZ) {
        this(floorBounds, originX, originZ, Material.STONE);
    }

    public DungeonWorldGenerator(FloorBounds floorBounds, double originX, double originZ, Material fillMaterial) {
        this.floorBounds  = floorBounds;
        this.originX      = originX;
        this.originZ      = originZ;
        this.fillMaterial = fillMaterial;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        int maxFloors = floorBounds.maxFloorCount();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = (chunkX << 4) + localX;
                int worldZ = (chunkZ << 4) + localZ;

                if (!floorBounds.isWithinGenerationRadius(originX, originZ, worldX, worldZ)) {
                    // Outside every floor's leash — leave the whole column as air.
                    continue;
                }

                for (int floorNumber = 1; floorNumber <= maxFloors; floorNumber++) {
                    int floorBottomY = floorBounds.floorBottomY(floorNumber);
                    int floorTopY    = floorBounds.floorTopY(floorNumber);

                    // Fill the playable band with stone (DungeonRoomPlanner will
                    // carve caves into this as players explore).
                    for (int y = floorBottomY; y < floorTopY; y++) {
                        chunkData.setBlock(localX, y, localZ, fillMaterial);
                    }

                    // Bedrock border immediately below this floor separates it
                    // from the floor below (or from below-bedrock space on floor 1).
                    chunkData.setBlock(localX, floorBottomY - 1, localZ, Material.BEDROCK);
                }
            }
        }
    }

    // ─── Vanilla pass suppression ────────────────────────────────────────────

    @Override
    public boolean shouldGenerateNoise() {
        return true; // use OUR generateNoise above, not vanilla terrain
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return false; // we place bedrock borders manually in generateNoise
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }

    /**
     * Returning an empty list here is the critical suppression that stops
     * Bukkit from injecting vanilla BlockPopulators (ore veins, surface
     * decorators, village roads, etc.) into the dungeon world's chunks.
     * Without this, Paper may still run its default population passes
     * even when all shouldGenerate* flags are false.
     */
    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        return Collections.emptyList();
    }
}

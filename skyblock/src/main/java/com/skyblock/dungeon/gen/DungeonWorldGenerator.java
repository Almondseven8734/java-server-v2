package com.skyblock.dungeon.gen;

import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/**
 * The dungeon world's actual ChunkGenerator.
 *
 * IMPORTANT ARCHITECTURAL NOTE: a Bukkit World has exactly one
 * ChunkGenerator, and generateNoise fires exactly once per chunk
 * column across the WHOLE world height - not once per floor. Since
 * all floors share one vertical world column (per design), the
 * original per-floor StoneBufferGenerator can't be assigned directly
 * as a world's generator: only whichever single floor it was built
 * for would ever get filled, and every other floor's band would stay
 * permanently air. This class is the actual fix - it loops over every
 * floor slot (1..FloorBounds.maxFloorCount()) in a single pass and
 * fills each one's stone buffer + bedrock border, all using the same
 * shared XZ origin (every floor's origin sits on the same XZ column,
 * per design).
 *
 * StoneBufferGenerator itself is kept as-is and is still useful as a
 * single-floor reference/test utility, but should NOT be assigned
 * directly as the dungeon world's generator - use this class instead.
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
        this.floorBounds = floorBounds;
        this.originX = originX;
        this.originZ = originZ;
        this.fillMaterial = fillMaterial;
    }

    @Override
    public void generateNoise(org.bukkit.generator.WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        int maxFloors = floorBounds.maxFloorCount();

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = (chunkX << 4) + localX;
                int worldZ = (chunkZ << 4) + localZ;

                if (!floorBounds.isWithinGenerationRadius(originX, originZ, worldX, worldZ)) {
                    continue; // outside every floor's leash at this XZ - leave the whole column as air
                }

                for (int floorNumber = 1; floorNumber <= maxFloors; floorNumber++) {
                    int floorBottomY = floorBounds.floorBottomY(floorNumber);
                    int floorTopY = floorBounds.floorTopY(floorNumber);

                    for (int y = floorBottomY; y < floorTopY; y++) {
                        chunkData.setBlock(localX, y, localZ, fillMaterial);
                    }

                    // Bottom border separating this floor from the one below.
                    chunkData.setBlock(localX, floorBottomY - 1, localZ, Material.BEDROCK);
                }
            }
        }
    }

    @Override
    public boolean shouldGenerateNoise() {
        return true;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
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
}

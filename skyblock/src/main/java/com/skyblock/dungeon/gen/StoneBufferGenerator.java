package com.skyblock.dungeon.gen;

import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/**
 * Implements the cheap "far field" pass of the two-tier generation
 * model: fills a floor's chunk with solid stone wherever the real
 * dungeon planner hasn't committed actual rooms/corridors yet, clipped
 * to the floor's vertical slot (FloorBounds) and the 2000-block
 * horizontal leash.
 *
 * This is intentionally dumb and fast - no room/corridor logic lives
 * here. DungeonRoomPlanner (a later piece) is responsible for carving
 * real dungeon content out of this stone within ~30 blocks of an
 * active player, converting buffer stone into dungeon just-in-time.
 *
 * Chunks entirely outside this floor's generation radius are left as
 * pure air above/below the border, so the floor's bounding walls and
 * vertical separation stay intact without this class needing to know
 * about other floors at all.
 */
public final class StoneBufferGenerator extends ChunkGenerator {

    private final FloorBounds floorBounds;
    private final int floorNumber;
    private final double originX;
    private final double originZ;
    private final Material fillMaterial;

    public StoneBufferGenerator(FloorBounds floorBounds, int floorNumber,
                                 double originX, double originZ) {
        this(floorBounds, floorNumber, originX, originZ, Material.STONE);
    }

    public StoneBufferGenerator(FloorBounds floorBounds, int floorNumber,
                                 double originX, double originZ, Material fillMaterial) {
        this.floorBounds = floorBounds;
        this.floorNumber = floorNumber;
        this.originX = originX;
        this.originZ = originZ;
        this.fillMaterial = fillMaterial;
    }

    @Override
    public void generateNoise(org.bukkit.generator.WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        int floorBottomY = floorBounds.floorBottomY(floorNumber);
        int floorTopY = floorBounds.floorTopY(floorNumber);

        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                int worldX = (chunkX << 4) + localX;
                int worldZ = (chunkZ << 4) + localZ;

                if (!floorBounds.isWithinGenerationRadius(originX, originZ, worldX, worldZ)) {
                    continue; // outside the 2000-block leash - leave as air, floor simply doesn't extend here
                }

                // Fill the floor's vertical band with stone. The real
                // room/corridor planner later carves this down into
                // actual dungeon content within ~30 blocks of a player.
                for (int y = floorBottomY; y < floorTopY; y++) {
                    chunkData.setBlock(localX, y, localZ, fillMaterial);
                }

                // Solid bottom border, separating this floor from the one below.
                chunkData.setBlock(localX, floorBottomY - 1, localZ, Material.BEDROCK);
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
        return false; // mob spawning is handled separately by the dungeon's own spawner, tied to floor themes
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}

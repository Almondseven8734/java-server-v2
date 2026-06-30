package com.skyblock.dungeon.spawn;

import com.skyblock.dungeon.combat.MobBuffApplicator;
import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.config.FloorThemeRegistry;
import com.skyblock.dungeon.gen.DungeonRoom;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.Random;

/**
 * Populates a newly carved room with ambient mobs drawn from that
 * floor's FloorTheme mob pool, scaled via MobBuffApplicator's
 * per-floor curve. Intended to be called from a
 * DungeonRoomPlanner.RoomCarveListener the moment a NORMAL or CHEST
 * room finishes carving - BOSS and BUFFER rooms are skipped here,
 * since boss rooms get their own dedicated trigger and buffer rooms
 * are meant to be plain connectors per design.
 */
public final class DungeonRoomMobSpawner {

    /** Roughly one ambient mob per this many blocks of room footprint area. */
    private static final int BLOCKS_PER_MOB = 18;
    private static final int MIN_MOBS_PER_ROOM = 1;
    private static final int MAX_MOBS_PER_ROOM = 6;

    private final FloorThemeRegistry themeRegistry;
    private final MobBuffApplicator buffApplicator;
    private final Random random;

    public DungeonRoomMobSpawner(FloorThemeRegistry themeRegistry, MobBuffApplicator buffApplicator, Random random) {
        this.themeRegistry = themeRegistry;
        this.buffApplicator = buffApplicator;
        this.random = random;
    }

    public void spawnForRoom(World world, int floorNumber, int floorBottomY, DungeonRoom room) {
        if (room.type() == DungeonRoom.Type.BOSS || room.type() == DungeonRoom.Type.BUFFER) {
            return;
        }

        FloorTheme theme = themeRegistry.getTheme(floorNumber);
        List<EntityType> mobPool = theme.getMobPool();
        if (mobPool.isEmpty()) {
            return;
        }

        int footprintArea = (room.radiusX() * 2 + 1) * (room.radiusZ() * 2 + 1);
        int mobCount = Math.min(MAX_MOBS_PER_ROOM,
                Math.max(MIN_MOBS_PER_ROOM, footprintArea / BLOCKS_PER_MOB));

        for (int i = 0; i < mobCount; i++) {
            int dx = random.nextInt(room.radiusX() * 2 + 1) - room.radiusX();
            int dz = random.nextInt(room.radiusZ() * 2 + 1) - room.radiusZ();
            int x = room.centerX() + dx;
            int z = room.centerZ() + dz;

            EntityType type = mobPool.get(random.nextInt(mobPool.size()));
            Location spawnLoc = new Location(world, x + 0.5, floorBottomY + 1, z + 0.5);

            if (!(world.spawnEntity(spawnLoc, type) instanceof LivingEntity entity)) {
                continue;
            }
            buffApplicator.applyAmbientMobScaling(entity, floorNumber);
        }
    }
}

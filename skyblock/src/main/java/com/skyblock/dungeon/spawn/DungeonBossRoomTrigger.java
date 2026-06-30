package com.skyblock.dungeon.spawn;

import com.skyblock.dungeon.combat.MilestoneBoss;
import com.skyblock.dungeon.combat.MobBuffApplicator;
import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.config.FloorThemeRegistry;
import com.skyblock.dungeon.floor.DungeonStaircaseOrchestrator;
import com.skyblock.dungeon.gen.DungeonRoom;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fires once per floor, the first time any player's position falls
 * inside that floor's registered boss room. Spawns that floor's
 * boss(es) - buffed vanilla mobs on ordinary floors, or a real
 * MilestoneBoss on milestone floors if a factory was registered for
 * it - and hands each spawned boss entity to the
 * DungeonStaircaseOrchestrator so its eventual death is attributed
 * correctly.
 *
 * Per design, boss room placement is independent of how much terrain
 * has generated - this class doesn't care how the player got there,
 * only that they're now standing inside the room's bounds.
 */
public final class DungeonBossRoomTrigger {

    /** Produces a scripted MilestoneBoss wrapper around a freshly spawned entity, for milestone floors. */
    @FunctionalInterface
    public interface MilestoneBossFactory {
        MilestoneBoss create(JavaPlugin plugin, LivingEntity entity, int floorNumber);
    }

    /** How often (in ticks) a spawned MilestoneBoss's update() is driven. 20 ticks = 1 second. */
    private static final long MILESTONE_BOSS_TICK_PERIOD = 10L;

    private final JavaPlugin plugin;
    private final FloorThemeRegistry themeRegistry;
    private final MobBuffApplicator buffApplicator;
    private final DungeonStaircaseOrchestrator orchestrator;
    private final Random random;

    /** Optional per-floor scripted boss factories. Floors without an entry here fall back to buffed vanilla. */
    private final java.util.Map<Integer, MilestoneBossFactory> milestoneFactories = new ConcurrentHashMap<>();

    /** Room IDs that have already triggered, so re-entering an already-triggered boss room is a no-op. */
    private final Set<UUID> triggeredRooms = ConcurrentHashMap.newKeySet();

    public DungeonBossRoomTrigger(JavaPlugin plugin, FloorThemeRegistry themeRegistry,
                                   MobBuffApplicator buffApplicator, DungeonStaircaseOrchestrator orchestrator,
                                   Random random) {
        this.plugin = plugin;
        this.themeRegistry = themeRegistry;
        this.buffApplicator = buffApplicator;
        this.orchestrator = orchestrator;
        this.random = random;
    }

    /** Registers a scripted boss factory for a specific milestone floor (e.g. 5, 10, 15). */
    public void registerMilestoneBossFactory(int floorNumber, MilestoneBossFactory factory) {
        milestoneFactories.put(floorNumber, factory);
    }

    /**
     * Call on every player position update for players on this floor.
     * No-ops unless the player is actually inside the boss room and it
     * hasn't already triggered.
     */
    public void onPlayerPosition(World world, int floorNumber, int floorBottomY,
                                  DungeonRoom bossRoom, double playerX, double playerZ) {
        if (bossRoom == null || bossRoom.type() != DungeonRoom.Type.BOSS) {
            return;
        }
        if (!triggeredRooms.add(bossRoom.id())) {
            return; // already triggered (add() returns false if already present)
        }
        if (!bossRoom.containsXZ((int) Math.round(playerX), (int) Math.round(playerZ))) {
            triggeredRooms.remove(bossRoom.id()); // wasn't actually inside - allow a real future trigger
            return;
        }

        spawnBosses(world, floorNumber, floorBottomY, bossRoom);
    }

    private void spawnBosses(World world, int floorNumber, int floorBottomY, DungeonRoom bossRoom) {
        FloorTheme theme = themeRegistry.getTheme(floorNumber);
        int bossCount = theme.getBossCount();
        MilestoneBossFactory factory = theme.isMilestoneFloor() ? milestoneFactories.get(floorNumber) : null;

        for (int i = 0; i < bossCount; i++) {
            Location spawnLoc = randomPointInRoom(world, bossRoom, floorBottomY);
            EntityType type = pickBossEntityType(theme);

            if (!(world.spawnEntity(spawnLoc, type) instanceof LivingEntity entity)) {
                continue;
            }

            orchestrator.registerBoss(entity.getUniqueId(), floorNumber);

            if (factory != null) {
                MilestoneBoss boss = factory.create(plugin, entity, floorNumber);
                plugin.getServer().getScheduler().runTaskTimer(plugin, boss::update, 0L, MILESTONE_BOSS_TICK_PERIOD);
            } else {
                buffApplicator.applyBossScaling(entity, floorNumber);
            }
        }
    }

    /** Picks the strongest-looking entry in the floor's mob pool to stand in as the buffed-vanilla boss. */
    private EntityType pickBossEntityType(FloorTheme theme) {
        List<EntityType> pool = theme.getMobPool();
        if (pool.isEmpty()) {
            return EntityType.ZOMBIE;
        }
        return pool.get(pool.size() - 1);
    }

    private Location randomPointInRoom(World world, DungeonRoom room, int floorBottomY) {
        int dx = random.nextInt(Math.max(1, room.radiusX() * 2 - 1)) - (room.radiusX() - 1);
        int dz = random.nextInt(Math.max(1, room.radiusZ() * 2 - 1)) - (room.radiusZ() - 1);
        return new Location(world, room.centerX() + dx + 0.5, floorBottomY + 1, room.centerZ() + dz + 0.5);
    }
}

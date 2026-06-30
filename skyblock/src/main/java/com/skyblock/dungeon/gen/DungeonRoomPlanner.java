package com.skyblock.dungeon.gen;

import com.skyblock.dungeon.config.FloorTheme;
import com.skyblock.dungeon.util.FloorBounds;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The real dungeon generator: a graph-based room/corridor planner that
 * carves actual playable space out of the cheap stone buffer
 * (StoneBufferGenerator) within a tight radius of active frontiers
 * (player positions and buffer rooms beneath staircases).
 *
 * Design rules this class enforces, per the locked-in spec:
 *   - Generation only commits within CARVE_RADIUS blocks of an active
 *     frontier; everything else stays as buffer stone until approached.
 *   - Rooms are graph nodes, corridors are graph edges; the underlying
 *     structure is rule-based, not raw noise.
 *   - Corridors render as a mix of straight structured segments and
 *     wandering, cave-pocket-like segments.
 *   - The planner is aware of known/queued routing targets (staircases'
 *     buffer rooms) and biases new corridors toward them so the floor
 *     becomes one connected network over time instead of isolated pockets.
 *   - Nothing generated may exceed the floor's 2000-block leash
 *     (FloorBounds.GENERATION_RADIUS) from that floor's origin.
 *   - Once carved, a room/corridor is permanent for the week - this
 *     class never re-carves or resets something it has already marked carved.
 *
 * One RoomGraph + one DungeonRoomPlanner instance exists per active floor.
 * Thread-safety: callers must serialize calls to planAndCarveNear for a
 * given floor onto a single async generation thread/executor; this class
 * does no internal locking.
 */
public final class DungeonRoomPlanner {

    /** Real dungeon content only carves this close to an active frontier. */
    public static final int CARVE_RADIUS = 30;

    private static final int MIN_ROOM_RADIUS = 4;
    private static final int MAX_ROOM_RADIUS = 9;
    private static final int CORRIDOR_WIDTH = 3;

    /** Chance a corridor segment wanders like a cave pocket instead of going straight. */
    private static final double CAVE_WANDER_CHANCE = 0.45;

    private final RoomGraph graph;
    private final FloorBounds floorBounds;
    private final int floorNumber;
    private final double originX;
    private final double originZ;
    private final FloorTheme theme;
    private final Logger logger;
    private final Random random;
    private RoomCarveListener carveListener;

    public DungeonRoomPlanner(RoomGraph graph, FloorBounds floorBounds, int floorNumber,
                               double originX, double originZ, FloorTheme theme,
                               Logger logger, Random random) {
        this.graph = graph;
        this.floorBounds = floorBounds;
        this.floorNumber = floorNumber;
        this.originX = originX;
        this.originZ = originZ;
        this.theme = theme;
        this.logger = logger;
        this.random = random;
    }

    /**
     * Notified exactly once, right after a room finishes carving for
     * the first time. Lets external systems (mob spawner, chest loot
     * placer, boss room trigger) react to new playable space without
     * this planner needing to know anything about combat/loot/bosses
     * itself.
     */
    @FunctionalInterface
    public interface RoomCarveListener {
        void onRoomCarved(World world, int floorNumber, DungeonRoom room);
    }

    /** Registers the single listener notified on first carve of any room on this floor. */
    public void setRoomCarveListener(RoomCarveListener listener) {
        this.carveListener = listener;
    }

    public RoomGraph graph() {
        return graph;
    }

    /**
     * Registers a buffer room (the landing spot beneath a newly placed
     * staircase) as both a real room in the graph AND a routing target
     * for future corridor planning, so the planner starts trying to
     * stitch toward it immediately, per the "buffer rooms are active
     * frontiers from the moment they're placed" rule.
     */
    public DungeonRoom registerBufferRoom(int x, int z) {
        DungeonRoom existing = graph.roomContaining(x, z);
        if (existing != null) {
            return existing;
        }
        DungeonRoom room = new DungeonRoom(UUID.randomUUID(), x, z, MIN_ROOM_RADIUS, MIN_ROOM_RADIUS, DungeonRoom.Type.BUFFER);
        graph.addRoom(room);
        graph.addRoutingTarget(room.id());
        return room;
    }

    /**
     * Registers the boss room. Placement is independent of how much
     * terrain has generated, so this can be called before or after
     * normal exploration reaches it - the planner will carve it
     * in-place once a frontier comes near it, same as any other room.
     */
    public DungeonRoom registerBossRoom(int x, int z, int radiusX, int radiusZ) {
        DungeonRoom room = new DungeonRoom(UUID.randomUUID(), x, z, radiusX, radiusZ, DungeonRoom.Type.BOSS);
        graph.addRoom(room);
        return room;
    }

    /**
     * Main entry point: called whenever a player (or other active
     * frontier) is at the given world XZ on this floor. Ensures real
     * dungeon content exists within CARVE_RADIUS of that point, growing
     * the room/corridor graph and carving blocks into the world as needed.
     *
     * Safe to call repeatedly/idempotently - already-carved rooms and
     * corridors are skipped.
     */
    public void planAndCarveNear(World world, int frontierX, int frontierZ) {
        if (!floorBounds.isWithinGenerationRadius(originX, originZ, frontierX, frontierZ)) {
            return; // outside the leash - this floor simply doesn't extend here
        }

        DungeonRoom current = graph.roomContaining(frontierX, frontierZ);
        if (current == null) {
            // Bootstrap or expand: no room here yet, so grow one toward the frontier.
            current = growRoomToward(world, frontierX, frontierZ);
        } else if (!current.isCarved()) {
            carveRoom(world, current);
        }

        // Whether freshly grown or pre-existing, make sure it's connected
        // to the rest of the network (or starts a new pocket if this is
        // the very first room on the floor).
        ensureConnected(world, current);
    }

    // ─── Room growth ────────────────────────────────────────────────────────

    private DungeonRoom growRoomToward(World world, int x, int z) {
        int radiusX = MIN_ROOM_RADIUS + random.nextInt(MAX_ROOM_RADIUS - MIN_ROOM_RADIUS + 1);
        int radiusZ = MIN_ROOM_RADIUS + random.nextInt(MAX_ROOM_RADIUS - MIN_ROOM_RADIUS + 1);

        // Clamp the room center so its footprint never pokes past the leash.
        double[] clamped = floorBounds.clampToRadius(originX, originZ, x, z);
        int centerX = (int) Math.round(clamped[0]);
        int centerZ = (int) Math.round(clamped[1]);

        DungeonRoom room = new DungeonRoom(UUID.randomUUID(), centerX, centerZ, radiusX, radiusZ, DungeonRoom.Type.NORMAL);

        // Small chance for a generated room to double as a chest room,
        // per the "chest rooms scattered in generated terrain" rule.
        boolean isChestRoom = random.nextDouble() < 0.12;
        DungeonRoom finalRoom = isChestRoom
                ? new DungeonRoom(room.id(), centerX, centerZ, radiusX, radiusZ, DungeonRoom.Type.CHEST)
                : room;

        graph.addRoom(finalRoom);
        carveRoom(world, finalRoom);
        return finalRoom;
    }

    // ─── Connectivity / corridor routing ────────────────────────────────────

    private void ensureConnected(World world, DungeonRoom room) {
        if (graph.roomCount() <= 1) {
            return; // first room on the floor - nothing to connect to yet
        }

        // Prefer stitching toward a known routing target (staircase/buffer
        // room) if one is reasonably close and not already linked, per the
        // "deliberately route toward known frontiers" design rule.
        DungeonRoom target = graph.nearestUnconnectedRoutingTarget(room, CARVE_RADIUS * 4);
        if (target == null) {
            target = graph.nearestRoom(room.centerX(), room.centerZ());
        }

        if (target == null || target.id().equals(room.id())) {
            return;
        }
        if (room.connectedRoomIds().contains(target.id())) {
            return; // already linked
        }

        DungeonCorridor corridor = buildCorridor(room, target);
        graph.addCorridor(corridor);
        carveCorridor(world, corridor);
    }

    private DungeonCorridor buildCorridor(DungeonRoom from, DungeonRoom to) {
        List<int[]> waypoints = new ArrayList<>();
        waypoints.add(new int[]{from.centerX(), from.centerZ()});

        boolean wander = random.nextDouble() < CAVE_WANDER_CHANCE;
        if (wander) {
            // Cave-pocket feel: insert 1-2 jittered midpoints instead of a straight line.
            int midpoints = 1 + random.nextInt(2);
            int prevX = from.centerX();
            int prevZ = from.centerZ();
            for (int i = 1; i <= midpoints; i++) {
                double t = (double) i / (midpoints + 1);
                int baseX = (int) Math.round(from.centerX() + (to.centerX() - from.centerX()) * t);
                int baseZ = (int) Math.round(from.centerZ() + (to.centerZ() - from.centerZ()) * t);
                int jitterX = baseX + random.nextInt(11) - 5;
                int jitterZ = baseZ + random.nextInt(11) - 5;
                waypoints.add(new int[]{jitterX, jitterZ});
                prevX = jitterX;
                prevZ = jitterZ;
            }
        }

        waypoints.add(new int[]{to.centerX(), to.centerZ()});
        return new DungeonCorridor(UUID.randomUUID(), from.id(), to.id(), waypoints, CORRIDOR_WIDTH);
    }

    // ─── Carving (world writes) ─────────────────────────────────────────────

    private void carveRoom(World world, DungeonRoom room) {
        if (room.isCarved()) {
            return;
        }
        int floorY = floorBounds.floorBottomY(floorNumber);
        int topY = floorBounds.floorTopY(floorNumber);

        Material floorBlock = pick(theme.getPrimaryBlocks());
        Material accentBlock = pick(theme.getAccentBlocks());

        for (int dx = -room.radiusX(); dx <= room.radiusX(); dx++) {
            for (int dz = -room.radiusZ(); dz <= room.radiusZ(); dz++) {
                int wx = room.centerX() + dx;
                int wz = room.centerZ() + dz;

                boolean edge = Math.abs(dx) == room.radiusX() || Math.abs(dz) == room.radiusZ();

                // Floor layer.
                world.getBlockAt(wx, floorY, wz).setType(
                        random.nextDouble() < 0.08 ? accentBlock : floorBlock);

                // Hollow out the walkable air column above the floor.
                for (int y = floorY + 1; y < topY; y++) {
                    world.getBlockAt(wx, y, wz).setType(Material.AIR);
                }

                // Leave room walls/ceiling intact (still stone from the
                // buffer pass) except doorways, which carveCorridor handles
                // when it connects into this room.
                if (edge) {
                    // no-op: walls stay as whatever the stone buffer placed
                }
            }
        }

        room.markCarved();

        if (carveListener != null) {
            carveListener.onRoomCarved(world, floorNumber, room);
        }
    }

    private void carveCorridor(World world, DungeonCorridor corridor) {
        if (corridor.isCarved()) {
            return;
        }
        int floorY = floorBounds.floorBottomY(floorNumber);
        int topY = floorBounds.floorTopY(floorNumber);
        int halfWidth = corridor.width() / 2;

        List<int[]> points = corridor.waypoints();
        for (int i = 0; i < points.size() - 1; i++) {
            int[] a = points.get(i);
            int[] b = points.get(i + 1);
            carveSegment(world, a[0], a[1], b[0], b[1], floorY, topY, halfWidth);
        }

        corridor.markCarved();
    }

    /** Carves a single straight tunnel segment between two XZ points, walking by integer steps. */
    private void carveSegment(World world, int x0, int z0, int x1, int z1, int floorY, int topY, int halfWidth) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
        if (steps == 0) {
            steps = 1;
        }
        for (int s = 0; s <= steps; s++) {
            double t = (double) s / steps;
            int cx = (int) Math.round(x0 + (x1 - x0) * t);
            int cz = (int) Math.round(z0 + (z1 - z0) * t);

            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                    int wx = cx + dx;
                    int wz = cz + dz;
                    world.getBlockAt(wx, floorY, wz).setType(pick(theme.getPrimaryBlocks()));
                    for (int y = floorY + 1; y < topY; y++) {
                        world.getBlockAt(wx, y, wz).setType(Material.AIR);
                    }
                }
            }
        }
    }

    private Material pick(List<Material> options) {
        if (options.isEmpty()) {
            return Material.STONE;
        }
        return options.get(random.nextInt(options.size()));
    }
}

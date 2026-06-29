package com.skyblock.island;

import com.skyblock.storage.IslandData;
import com.skyblock.storage.IslandStorage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.logging.Logger;

/**
 * Border Enforcement & Guest Protection
 *
 * Translated from island_protection.js.
 *
 * Three systems:
 *   1. Border check (every 10 ticks): snaps players back inside their island
 *      if they walk just outside the ISLAND_RADIUS (200-block square).
 *   2. Block-break protection: non-members cannot break blocks on islands.
 *   3. Block-place protection: non-members cannot place blocks on islands.
 *   4. Interaction protection: non-members cannot interact with
 *      containers, buttons, signs, etc.
 *
 * All constants match the JS source exactly.
 */
public class IslandProtection implements Listener {

    // ─── Constants ────────────────────────────────────────────────────────────

    private static final int ISLAND_RADIUS = 200;

    /** Materials that guests cannot interact with. Mirrors RESTRICTED_BLOCKS in JS. */
    private static final Set<Material> RESTRICTED_MATERIALS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.SHULKER_BOX,
            Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX, Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX, Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX,
            Material.DISPENSER, Material.DROPPER, Material.HOPPER,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.LEVER,
            Material.STONE_BUTTON,
            Material.OAK_BUTTON, Material.SPRUCE_BUTTON, Material.BIRCH_BUTTON,
            Material.JUNGLE_BUTTON, Material.ACACIA_BUTTON, Material.DARK_OAK_BUTTON,
            Material.MANGROVE_BUTTON, Material.BAMBOO_BUTTON, Material.CHERRY_BUTTON,
            Material.CRIMSON_BUTTON, Material.WARPED_BUTTON,
            Material.POLISHED_BLACKSTONE_BUTTON,
            Material.OAK_SIGN, Material.SPRUCE_SIGN, Material.BIRCH_SIGN,
            Material.JUNGLE_SIGN, Material.ACACIA_SIGN, Material.DARK_OAK_SIGN,
            Material.MANGROVE_SIGN, Material.BAMBOO_SIGN, Material.CHERRY_SIGN,
            Material.CRIMSON_SIGN, Material.WARPED_SIGN,
            Material.OAK_WALL_SIGN, Material.SPRUCE_WALL_SIGN, Material.BIRCH_WALL_SIGN,
            Material.JUNGLE_WALL_SIGN, Material.ACACIA_WALL_SIGN,
            Material.DARK_OAK_WALL_SIGN, Material.MANGROVE_WALL_SIGN,
            Material.BAMBOO_WALL_SIGN, Material.CHERRY_WALL_SIGN,
            Material.CRIMSON_WALL_SIGN, Material.WARPED_WALL_SIGN,
            Material.OAK_HANGING_SIGN, Material.SPRUCE_HANGING_SIGN,
            Material.BIRCH_HANGING_SIGN, Material.JUNGLE_HANGING_SIGN,
            Material.ACACIA_HANGING_SIGN, Material.DARK_OAK_HANGING_SIGN,
            Material.MANGROVE_HANGING_SIGN, Material.BAMBOO_HANGING_SIGN,
            Material.CHERRY_HANGING_SIGN, Material.CRIMSON_HANGING_SIGN,
            Material.WARPED_HANGING_SIGN
    );

    // ─── State ────────────────────────────────────────────────────────────────

    /** Players currently being snapped back (to avoid snap loops). */
    private final Set<UUID> snapping = Collections.synchronizedSet(new HashSet<>());

    private final IslandStorage storage;
    private final JavaPlugin     plugin;
    private final Logger         logger;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public IslandProtection(IslandStorage storage, JavaPlugin plugin, Logger logger) {
        this.storage = storage;
        this.plugin  = plugin;
        this.logger  = logger;
        startBorderTask();
        logger.info("[Skyblock] Border & protection system loaded!");
    }

    // ─── Helper: island at location ───────────────────────────────────────────

    private IslandData getIslandAtLocation(double x, double z) {
        for (IslandData island : storage.getAllIslands()) {
            if (Math.abs(x - island.centerX) <= ISLAND_RADIUS
                    && Math.abs(z - island.centerZ) <= ISLAND_RADIUS) {
                return island;
            }
        }
        return null;
    }

    // ─── Helper: is player allowed to modify blocks? ──────────────────────────

    private boolean isAllowedToModify(Player player) {
        if (player.getScoreboardTags().contains("island_block_bypass")) return true;
        IslandData island = getIslandAtLocation(
                player.getLocation().getX(), player.getLocation().getZ());
        if (island == null) return true; // not on any island
        String role = storage.getMemberRole(island, player.getUniqueId().toString());
        return "Owner".equals(role) || "Admin".equals(role) || "Member".equals(role)
                || "Co-Owner".equals(role) || "Moderator".equals(role);
    }

    // ─── Border task ──────────────────────────────────────────────────────────

    /**
     * Runs every 10 ticks. Clamps players to 1 block inside the island border
     * if they stray into the "snap zone" (between RADIUS and 2×RADIUS).
     *
     * Mirrors system.runInterval(..., 10) in island_protection.js.
     */
    private void startBorderTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    World overworld = plugin.getServer().getWorld("world");
                    if (overworld == null) return;

                    List<IslandData> islands = storage.getAllIslands();

                    for (Player player : plugin.getServer().getOnlinePlayers()) {
                        if (!player.getWorld().equals(overworld)) continue;
                        if (player.getScoreboardTags().contains("island_border_bypass")) continue;
                        UUID uid = player.getUniqueId();
                        if (snapping.contains(uid)) continue;

                        Location loc = player.getLocation();
                        double px = loc.getX();
                        double pz = loc.getZ();

                        for (IslandData island : islands) {
                            double dx = Math.abs(px - island.centerX);
                            double dz = Math.abs(pz - island.centerZ);

                            // Cleanly inside — no action needed
                            if (dx <= ISLAND_RADIUS && dz <= ISLAND_RADIUS) break;

                            // In snap zone (just outside, within 2× radius)
                            if (dx <= ISLAND_RADIUS * 2 && dz <= ISLAND_RADIUS * 2) {
                                snapping.add(uid);

                                double snapX = Math.max(island.centerX - (ISLAND_RADIUS - 1),
                                               Math.min(island.centerX + (ISLAND_RADIUS - 1), px));
                                double snapZ = Math.max(island.centerZ - (ISLAND_RADIUS - 1),
                                               Math.min(island.centerZ + (ISLAND_RADIUS - 1), pz));

                                Location dest = new Location(overworld, snapX, loc.getY(), snapZ,
                                        loc.getYaw(), loc.getPitch());
                                player.teleport(dest);
                                player.sendMessage("§c⚠ You cannot leave this island border!");

                                new BukkitRunnable() {
                                    @Override public void run() { snapping.remove(uid); }
                                }.runTaskLater(plugin, 20L);
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.severe("[Skyblock Border] " + e.getMessage());
                }
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    // ─── Block-break protection ───────────────────────────────────────────────

    /**
     * Mirrors world.beforeEvents.playerBreakBlock in island_protection.js.
     */
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!isAllowedToModify(player)) {
            event.setCancelled(true);
            player.sendMessage("§cYou cannot break blocks on this island.");
        }
    }

    // ─── Block-place protection ───────────────────────────────────────────────

    /**
     * Mirrors world.afterEvents.playerPlaceBlock in island_protection.js.
     * JS set the block to air; here we cancel the event before the block is placed.
     */
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!isAllowedToModify(player)) {
            event.setCancelled(true);
            player.sendMessage("§cYou cannot place blocks on this island.");
        }
    }

    // ─── Interaction protection ───────────────────────────────────────────────

    /**
     * Mirrors world.beforeEvents.playerInteractWithBlock in island_protection.js.
     * Blocks container, button, and sign interactions for non-members.
     */
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;
        Player player = event.getPlayer();
        if (isAllowedToModify(player)) return;

        Location loc = player.getLocation();
        IslandData island = getIslandAtLocation(loc.getX(), loc.getZ());
        if (island == null) return;

        if (RESTRICTED_MATERIALS.contains(event.getClickedBlock().getType())) {
            event.setCancelled(true);
            player.sendMessage("§cYou cannot interact with this on someone else's island.");
        }
    }
}

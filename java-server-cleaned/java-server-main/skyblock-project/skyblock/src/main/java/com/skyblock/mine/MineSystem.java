package com.skyblock.mine;

import com.skyblock.admin.AdminSystem;
import com.skyblock.crates.CrateSystem;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Logger;

/**
 * Mine System — Shared server-wide mine with tiered ore layers.
 * Auto-resets at 85% depletion or every 1 hour.
 *
 * Features:
 *  - 9 ore types with vanilla-style vein generation and Y-level banding
 *  - Amethyst spawns as AMETHYST_BLOCK; drops 4–10 shards + 1% void shard
 *  - 1 sponge per reset (hidden rare find)
 *  - 1–5 barrel crates pre-filled with keys and misc loot
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     resetmine:
 *       description: Manually reset the mine (admin only)
 *       usage: /resetmine
 */
public class MineSystem implements CommandExecutor, Listener {

    // ─── Mine bounds ──────────────────────────────────────────────────────────
    private static final int MINE_MIN_X = 255, MINE_MAX_X = 293;
    private static final int MINE_MIN_Y = 150, MINE_MAX_Y = 199;
    private static final int MINE_MIN_Z = -19,  MINE_MAX_Z =  19;

    private static final int    MINE_TOTAL       = 39 * 50 * 39; // 76,050 blocks
    private static final double RESET_THRESHOLD  = 0.85;
    private static final long   CHECK_INTERVAL   = 1200L;        // ticks — 1 minute
    private static final long   TIMER_MS         = 60 * 60 * 1000L; // 1 hour

    // Persistence keys
    private static final String KEY_BROKEN     = "mine_broken";
    private static final String KEY_NEXT_RESET = "mine_next_reset";
    private static final String WORLD_NAME     = "world";

    // ─── Void Shard PDC ───────────────────────────────────────────────────────
    // Echo Shard stamped with a PDC tag so the server can identify it as a
    // Void Shard distinct from a plain vanilla Echo Shard.
    private static final String VOID_SHARD_PDC_KEY   = "void_shard";
    private static final String VOID_SHARD_PDC_VALUE = "true";

    private NamespacedKey voidShardKey; // initialised in constructor

    // ─── Prismatic Gem PDC ────────────────────────────────────────────────────
    private static final String PRISMATIC_GEM_PDC_KEY   = "prismatic_gem";
    private static final String PRISMATIC_GEM_PDC_VALUE = "true";

    private NamespacedKey prismaticGemKey; // initialised in constructor

    // ─── Ore layer config ─────────────────────────────────────────────────────
    //
    // Y-levels are expressed as OFFSETS from MINE_MIN_Y.
    // Ore tiers (top → bottom), mirroring vanilla depth logic:
    //
    //   Coal      — top half+   (very common, surface ore)
    //   Copper    — upper band  (mid-upper)
    //   Iron      — wide mid    (vanilla wide band)
    //   Gold      — mid         (vanilla mid-depth)
    //   Lapis     — mid-lower   (vanilla ~Y 32)
    //   Redstone  — lower       (vanilla lower depths)
    //   Diamond   — near bottom (vanilla ~Y 16)
    //   Emerald   — very bottom (very rare)
    //   Amethyst  — bottom 5 Y  (placed as AMETHYST_BLOCK, rarest)
    //
    // density  = base probability a grid cell becomes a vein seed
    //            (all values below are AFTER the 60 % density reduction)
    // veinMin/veinMax = blocks per vein (vanilla-like clump sizes)
    //
    private static class Layer {
        final int      minYOff, maxYOff; // offsets from MINE_MIN_Y
        final Material ore;
        final double   density;          // already reduced by 60 %
        final int      veinMin, veinMax;

        Layer(int minYOff, int maxYOff, Material ore, double baseDensity, int veinMin, int veinMax) {
            this.minYOff  = minYOff;
            this.maxYOff  = maxYOff;
            this.ore      = ore;
            // Apply 60 % overall density reduction
            this.density  = baseDensity * 0.40;
            this.veinMin  = veinMin;
            this.veinMax  = veinMax;
        }

        int minY() { return MINE_MIN_Y + minYOff; }
        int maxY() { return MINE_MIN_Y + maxYOff; }
    }

    //           minOff  maxOff  material                 base%   vMin vMax
    private static final Layer[] LAYERS = {
        new Layer(20, 49, Material.COAL_ORE,      0.28,  4, 9),  // coal
        new Layer(15, 49, Material.COPPER_ORE,    0.20,  3, 7),  // copper
        new Layer(10, 45, Material.IRON_ORE,      0.18,  3, 7),  // iron
        new Layer( 8, 35, Material.GOLD_ORE,      0.13,  2, 6),  // gold
        new Layer( 5, 28, Material.LAPIS_ORE,     0.11,  2, 5),  // lapis
        new Layer( 3, 20, Material.REDSTONE_ORE,  0.10,  2, 5),  // redstone
        new Layer( 1, 12, Material.DIAMOND_ORE,   0.07,  2, 4),  // diamond
        new Layer( 0,  8, Material.EMERALD_ORE,   0.04,  1, 3),  // emerald
        // Amethyst — handled separately; density field unused here
        new Layer( 0,  5, Material.AMETHYST_BLOCK, 0.00, 1, 1),
    };

    private static final int AMETHYST_IDX = LAYERS.length - 1;

    // Amethyst base density before 60 % reduction
    private static final double AMETHYST_DENSITY = 0.015 * 0.40; // ~0.6 % of bottom-5 grid cells

    private static final int BLEND_MARGIN = 2;
    private static final int ORE_GRID     = 3; // sample every N blocks

    // ─── Amethyst drop config ─────────────────────────────────────────────────
    private static final int    AMETHYST_SHARD_MIN  = 4;
    private static final int    AMETHYST_SHARD_MAX  = 10;
    private static final double VOID_SHARD_CHANCE   = 0.01; // 1 %

    // ─── Crate / barrel config ────────────────────────────────────────────────
    private static final int CRATE_MIN = 1;
    private static final int CRATE_MAX = 5;

    // Key loot weights (higher = more likely)
    private static final int WEIGHT_BASIC  = 70;
    private static final int WEIGHT_COMMON = 25;
    private static final int WEIGHT_NOVA   = 5;
    private static final int WEIGHT_TOTAL  = WEIGHT_BASIC + WEIGHT_COMMON + WEIGHT_NOVA;

    // ─── Runtime state ────────────────────────────────────────────────────────
    private final JavaPlugin  plugin;
    private final AdminSystem adminSystem;
    private final CrateSystem crateSystem;
    private final Logger      log;
    private final Random      rng = new Random();

    private int        brokenCount  = 0;
    private long       nextReset    = 0L;
    private boolean    resetPending = false;
    private BukkitTask activeJob    = null;

    private final List<Location> activeBarrels = new ArrayList<>();

    // ─── Constructor ──────────────────────────────────────────────────────────
    public MineSystem(JavaPlugin plugin, AdminSystem adminSystem, CrateSystem crateSystem) {
        this.plugin       = plugin;
        this.adminSystem  = adminSystem;
        this.crateSystem  = crateSystem;
        this.log          = plugin.getLogger();
        this.voidShardKey    = new NamespacedKey(plugin, VOID_SHARD_PDC_KEY);
        this.prismaticGemKey = new NamespacedKey(plugin, PRISMATIC_GEM_PDC_KEY);

        loadState();

        plugin.getServer().getScheduler().runTaskTimer(plugin, this::timedResetCheck, 0L, CHECK_INTERVAL);
        plugin.getServer().getScheduler().runTaskLater(plugin, this::onFirstLoad, 10L);
    }

    // ─── /resetmine command ──────────────────────────────────────────────────
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }
        adminResetMine(player);
        return true;
    }

    // ─── Admin manual reset ───────────────────────────────────────────────────
    public void adminResetMine(Player player) {
        if (!adminSystem.isWhitelisted(player.getName())) {
            player.sendMessage("§c[Mine] You do not have permission to reset the mine.");
            return;
        }
        plugin.getServer().broadcastMessage(
                "§6§l[Mine] §r§eAdmin §6" + player.getName() + " §eis resetting the mine!");
        generateMine();
    }

    // ─── Block break tracking ─────────────────────────────────────────────────
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Location loc = event.getBlock().getLocation();
        if (!isInMine(loc)) return;

        // Protect barrels — players open them, not break them
        if (event.getBlock().getType() == Material.BARREL) {
            event.setCancelled(true);
            return;
        }

        // Handle amethyst block custom drops (mine-only)
        if (event.getBlock().getType() == Material.AMETHYST_BLOCK) {
            event.setDropItems(false); // suppress vanilla drops

            Player player = event.getPlayer();
            int shards = AMETHYST_SHARD_MIN
                    + rng.nextInt(AMETHYST_SHARD_MAX - AMETHYST_SHARD_MIN + 1);

            // Drop amethyst shards at block location.
            // 1 % chance: one shard is replaced by a Void Shard instead.
            Location dropLoc = loc.clone().add(0.5, 0.5, 0.5);
            if (rng.nextDouble() < VOID_SHARD_CHANCE) {
                // Replace one shard with a Void Shard (net drop count unchanged)
                int normalShards = Math.max(0, shards - 1);
                if (normalShards > 0) {
                    loc.getWorld().dropItemNaturally(dropLoc, new ItemStack(Material.AMETHYST_SHARD, normalShards));
                }
                loc.getWorld().dropItemNaturally(dropLoc, buildVoidShard());
                player.sendMessage("§5§l✦ §dA §5Void Shard §dreplaced one of your amethyst shards! §5§l✦");
            } else {
                loc.getWorld().dropItemNaturally(dropLoc, new ItemStack(Material.AMETHYST_SHARD, shards));
            }
        }

        // ─── Prismatic Gem drop ───────────────────────────────────────────────
        // Chance scales with ore rarity. Must be checked for ALL ore types,
        // including amethyst (handled above) — use getType() before the block
        // is broken (it still exists at event time).
        Material brokenType = event.getBlock().getType();
        if (isOreBlock(brokenType)) {
            double gemChance = prismaticGemChance(brokenType);
            if (rng.nextDouble() < gemChance) {
                Location gemLoc = loc.clone().add(0.5, 0.5, 0.5);
                loc.getWorld().dropItemNaturally(gemLoc, buildPrismaticGem());
                event.getPlayer().sendMessage(
                        "§b§l✦ §3A §b§lPrismatic Gem §3has shattered free from the rock! §b§l✦");
            }
        }

        brokenCount++;
        saveState();

        if (!resetPending && brokenCount >= MINE_TOTAL * RESET_THRESHOLD) {
            resetPending = true;
            plugin.getServer().broadcastMessage(
                    "§6§l[Mine] §r§eThe mine is nearly depleted! Resetting in 5 seconds...");
            plugin.getServer().getScheduler().runTaskLater(plugin, this::generateMine, 100L);
        }
    }

    // ─── Block place prevention ───────────────────────────────────────────────
    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isInMine(event.getBlock().getLocation())) return;
        // Admins with spawn bypass active are exempt
        if (event.getPlayer().getScoreboardTags().contains(
                com.skyblock.protection.SpawnProtection.BYPASS_TAG)) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage("§c[Mine] You cannot place blocks inside the mine.");
    }

    // ─── Barrel (crate) interaction ───────────────────────────────────────────
    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.BARREL) return;

        Location barrelLoc = event.getClickedBlock().getLocation();
        if (!isInMine(barrelLoc)) return;

        // Only allow interaction with barrels that were placed by the mine system.
        // This prevents smuggled barrels from being used inside the mine.
        boolean isMineBarrel = false;
        for (Location tracked : activeBarrels) {
            if (tracked.getBlockX() == barrelLoc.getBlockX()
                    && tracked.getBlockY() == barrelLoc.getBlockY()
                    && tracked.getBlockZ() == barrelLoc.getBlockZ()) {
                isMineBarrel = true;
                break;
            }
        }

        if (!isMineBarrel) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§c[Mine] That barrel isn't a mine crate.");
            return;
        }

        // Use getState(false) to bypass Paper's cached snapshot so we get the live
        // Barrel tile entity even if the chunk hasn't fully ticked since setType().
        org.bukkit.block.BlockState state = event.getClickedBlock().getState(false);
        if (state instanceof org.bukkit.block.Barrel barrel) {
            Inventory inv = barrel.getInventory();
            if (inv.isEmpty()) {
                fillBarrelInventory(inv);
                // force=true writes the inventory NBT to the world immediately.
                barrel.update(true, false);
            }
            // Allow vanilla open — player sees the inventory normally.
        }
    }

    /**
     * Fills a barrel's inventory with mine crate loot.
     * Items sit inside the barrel so players interact with them naturally.
     * Keys are built as authentic PDC-stamped items matching CrateSystem's format.
     */
    private void fillBarrelInventory(Inventory inv) {
        inv.clear();

        // Slot 0 — primary key (basic 70 %, common 25 %, nova 5 %)
        int roll = rng.nextInt(WEIGHT_TOTAL);
        int primaryQty;
        String primaryType;
        if (roll < WEIGHT_BASIC) {
            primaryType = "basic";
            primaryQty  = 1 + rng.nextInt(10);
        } else if (roll < WEIGHT_BASIC + WEIGHT_COMMON) {
            primaryType = "common";
            primaryQty  = 1 + rng.nextInt(3);
        } else {
            primaryType = "nova";
            primaryQty  = 1;
        }
        inv.setItem(0, crateSystem.buildKey(primaryType, primaryQty));

        // Slot 1 — bonus basic keys (50 %)
        if (rng.nextDouble() < 0.50) {
            inv.setItem(1, crateSystem.buildKey("basic", 1 + rng.nextInt(5)));
        }

        // Slot 2 — bonus common key (15 %)
        if (rng.nextDouble() < 0.15) {
            inv.setItem(2, crateSystem.buildKey("common", 1));
        }

        // Slot 3 — torches (always; miners need light)
        inv.setItem(3, new ItemStack(Material.TORCH, 8 + rng.nextInt(25)));

        // Slot 4 — food: bread (70 %) or golden apple (30 %)
        if (rng.nextDouble() < 0.70) {
            inv.setItem(4, new ItemStack(Material.BREAD, 2 + rng.nextInt(5)));
        } else {
            inv.setItem(4, new ItemStack(Material.GOLDEN_APPLE, 1));
        }

        // Slot 5 — small bonus ore stack
        Material[] bonusOres = {Material.COAL, Material.RAW_IRON, Material.RAW_GOLD,
                                Material.LAPIS_LAZULI, Material.REDSTONE};
        inv.setItem(5, new ItemStack(bonusOres[rng.nextInt(bonusOres.length)], 2 + rng.nextInt(7)));

        // Slot 6 — amethyst shards (20 %)
        if (rng.nextDouble() < 0.20) {
            inv.setItem(6, new ItemStack(Material.AMETHYST_SHARD, 1 + rng.nextInt(4)));
        }
    }

    // ─── Void Shard builder ──────────────────────────────────────────────────
    /**
     * Creates a Void Shard: an Echo Shard with custom display name, lore,
     * and a PDC tag so the server can distinguish it from a vanilla Echo Shard.
     */
    public ItemStack buildVoidShard() {
        ItemStack item = new ItemStack(Material.ECHO_SHARD, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§5§lVoid Shard");
            meta.setLore(Arrays.asList(
                    "§r§7A crystallised fragment of the void,",
                    "§r§7pried loose from the depths of the mine.",
                    "§r§7",
                    "§r§8Rarity: §5§lMythic"
            ));
            // Stamp PDC so code can verify this is a real Void Shard
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(voidShardKey, PersistentDataType.STRING, VOID_SHARD_PDC_VALUE);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Returns true if the given ItemStack is a plugin-issued Void Shard. */
    public boolean isVoidShard(ItemStack item) {
        if (item == null || item.getType() != Material.ECHO_SHARD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return VOID_SHARD_PDC_VALUE.equals(pdc.get(voidShardKey, PersistentDataType.STRING));
    }

    // ─── Prismatic Gem builder ────────────────────────────────────────────────
    /**
     * Creates a Prismatic Gem: an Eye of Ender with custom display name, lore,
     * and a PDC tag so the server can distinguish it from a vanilla Eye of Ender.
     *
     * Drop chance scales with ore rarity:
     *   Amethyst block → 10 %
     *   Emerald ore    →  3 %
     *   Diamond ore    →  1.5 %
     *   All others     →  0.2 %
     */
    public ItemStack buildPrismaticGem() {
        ItemStack item = new ItemStack(Material.ENDER_EYE, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§b§lPrismatic Gem");
            meta.setLore(Arrays.asList(
                    "§r§7A gem that refracts light into every",
                    "§r§7colour at once — born deep within the mine.",
                    "§r§7",
                    "§r§3\"It hums with a faint, prismatic glow.\"",
                    "§r§7",
                    "§r§8Rarity: §b§lLegendary"
            ));
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(prismaticGemKey, PersistentDataType.STRING, PRISMATIC_GEM_PDC_VALUE);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Returns the prismatic gem drop chance for the given ore material. */
    private double prismaticGemChance(Material mat) {
        return switch (mat) {
            case AMETHYST_BLOCK -> 0.10;   // 10 %
            case EMERALD_ORE   -> 0.03;    //  3 %
            case DIAMOND_ORE   -> 0.015;   //  1.5 %
            default            -> 0.002;   //  0.2 %
        };
    }

    /** Returns true if the given ItemStack is a plugin-issued Prismatic Gem. */
    public boolean isPrismaticGem(ItemStack item) {
        if (item == null || item.getType() != Material.ENDER_EYE) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return PRISMATIC_GEM_PDC_VALUE.equals(pdc.get(prismaticGemKey, PersistentDataType.STRING));
    }

    /** Returns true if the given material is any ore type tracked by the mine. */
    private boolean isOreBlock(Material mat) {
        return switch (mat) {
            case COAL_ORE, COPPER_ORE, IRON_ORE, GOLD_ORE,
                 LAPIS_ORE, REDSTONE_ORE, DIAMOND_ORE,
                 EMERALD_ORE, AMETHYST_BLOCK -> true;
            default -> false;
        };
    }

    // ─── Timed reset check ───────────────────────────────────────────────────
    private void timedResetCheck() {
        if (nextReset > 0 && System.currentTimeMillis() >= nextReset && !resetPending) {
            plugin.getServer().broadcastMessage(
                    "§6§l[Mine] §r§eHourly mine reset! The mine has been refreshed.");
            generateMine();
        }
    }

    // ─── On first load ───────────────────────────────────────────────────────
    private void onFirstLoad() {
        if (nextReset == 0) {
            log.info("[Mine] First load — generating mine...");
            generateMine();
        } else if (System.currentTimeMillis() >= nextReset) {
            log.info("[Mine] Overdue reset detected — regenerating...");
            generateMine();
        } else {
            long minsLeft = (nextReset - System.currentTimeMillis()) / 60000;
            log.info("[Mine] State restored. Next reset in ~" + minsLeft + " min.");
        }
    }

    // ─── Mine generation ─────────────────────────────────────────────────────
    public void generateMine() {
        resetPending = false;
        brokenCount  = 0;
        nextReset    = System.currentTimeMillis() + TIMER_MS;
        saveState();

        if (activeJob != null) {
            activeJob.cancel();
            activeJob = null;
        }

        World world = plugin.getServer().getWorld(WORLD_NAME);
        if (world == null) {
            log.severe("[Mine] Could not find world '" + WORLD_NAME + "'. Mine generation aborted.");
            return;
        }

        // 1. Remove old barrels
        for (Location bl : activeBarrels) {
            org.bukkit.block.Block b = world.getBlockAt(bl);
            if (b.getType() == Material.BARREL) b.setType(Material.STONE, false);
        }
        activeBarrels.clear();

        // 2. Flood-fill mine with stone
        for (int y = MINE_MIN_Y; y <= MINE_MAX_Y; y++)
            for (int x = MINE_MIN_X; x <= MINE_MAX_X; x++)
                for (int z = MINE_MIN_Z; z <= MINE_MAX_Z; z++)
                    world.getBlockAt(x, y, z).setType(Material.STONE, false);

        // 3. Async ore seed calculation
        activeJob = plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {

            List<int[]> orePlacements    = new ArrayList<>();
            List<int[]> amethystBlocks   = new ArrayList<>();

            for (int y = MINE_MIN_Y; y <= MINE_MAX_Y; y++) {
                List<double[]> candidates = getOreChanceAtY(y);
                for (int x = MINE_MIN_X; x <= MINE_MAX_X; x += ORE_GRID) {
                    for (int z = MINE_MIN_Z; z <= MINE_MAX_Z; z += ORE_GRID) {

                        // Amethyst roll (independent)
                        int yOff = y - MINE_MIN_Y;
                        if (yOff <= LAYERS[AMETHYST_IDX].maxYOff
                                && rng.nextDouble() < AMETHYST_DENSITY) {
                            amethystBlocks.add(new int[]{x, y, z});
                            continue;
                        }

                        // Normal ore vein roll
                        double roll = rng.nextDouble();
                        for (double[] entry : candidates) {
                            int    layerIdx = (int) entry[0];
                            double weight   = entry[1];
                            if (roll < weight) {
                                Layer layer = LAYERS[layerIdx];
                                int size = layer.veinMin
                                        + rng.nextInt(layer.veinMax - layer.veinMin + 1);
                                orePlacements.add(new int[]{layerIdx, x, y, z, size});
                                break;
                            }
                            roll -= weight;
                        }
                    }
                }
            }

            // 4. Sponge — one random block anywhere in the mine
            int spongeX = MINE_MIN_X + rng.nextInt(MINE_MAX_X - MINE_MIN_X + 1);
            int spongeY = MINE_MIN_Y + rng.nextInt(MINE_MAX_Y - MINE_MIN_Y + 1);
            int spongeZ = MINE_MIN_Z + rng.nextInt(MINE_MAX_Z - MINE_MIN_Z + 1);

            // 5. Barrel positions
            int barrelCount = CRATE_MIN + rng.nextInt(CRATE_MAX - CRATE_MIN + 1);
            List<int[]> barrelPositions = pickBarrelPositions(barrelCount);

            // 6. Place everything on the main thread
            plugin.getServer().getScheduler().runTask(plugin, () -> {

                // Ore veins
                for (int[] p : orePlacements) {
                    Layer l = LAYERS[p[0]];
                    growVein(world, l.ore, p[1], p[2], p[3], p[4]);
                }

                // Amethyst blocks
                for (int[] pos : amethystBlocks) {
                    org.bukkit.block.Block b = world.getBlockAt(pos[0], pos[1], pos[2]);
                    if (b.getType() == Material.STONE) {
                        b.setType(Material.AMETHYST_BLOCK, false);
                    }
                }

                // Sponge
                world.getBlockAt(spongeX, spongeY, spongeZ).setType(Material.SPONGE, false);

                // Barrels
                for (int[] pos : barrelPositions) {
                    Location bl = new Location(world, pos[0], pos[1], pos[2]);
                    world.getBlockAt(bl).setType(Material.BARREL, false);
                    activeBarrels.add(bl.clone());
                    // Loot is generated on-demand when a player opens the barrel,
                    // so no pre-fill needed here.
                }

                activeJob = null;
                plugin.getServer().broadcastMessage(
                        "§6§l[Mine] §r§eThe mine has been reset! Happy mining!");
                log.info("[Mine] Generation complete."
                        + " Ores: " + orePlacements.size()
                        + " Amethyst blocks: " + amethystBlocks.size()
                        + " Barrels: " + barrelPositions.size());

                // Teleport any players currently inside the mine to the mine entrance.
                Location mineEntrance = new Location(world, 296, 200, 0, 0f, 0f);
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    if (isInMine(online.getLocation())) {
                        online.teleport(mineEntrance);
                        online.sendMessage("§6§l[Mine] §r§eThe mine has reset — you've been moved to the entrance.");
                    }
                }
            });
        });
    }

    // ─── Barrel position picker ───────────────────────────────────────────────
    private List<int[]> pickBarrelPositions(int count) {
        List<int[]> chosen = new ArrayList<>();
        int attempts = 0;
        while (chosen.size() < count && attempts < 500) {
            attempts++;
            int x = MINE_MIN_X + 1 + rng.nextInt(MINE_MAX_X - MINE_MIN_X - 1);
            int y = MINE_MIN_Y + 1 + rng.nextInt(MINE_MAX_Y - MINE_MIN_Y - 1);
            int z = MINE_MIN_Z + 1 + rng.nextInt(MINE_MAX_Z - MINE_MIN_Z - 1);

            boolean tooClose = false;
            for (int[] c : chosen) {
                int dx = c[0] - x, dy = c[1] - y, dz = c[2] - z;
                if (dx*dx + dy*dy + dz*dz < 25) { // 5-block min gap
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) chosen.add(new int[]{x, y, z});
        }
        return chosen;
    }


    // ─── Ore chance by Y level ────────────────────────────────────────────────
    private List<double[]> getOreChanceAtY(int y) {
        List<double[]> candidates = new ArrayList<>();
        for (int i = 0; i < LAYERS.length; i++) {
            if (i == AMETHYST_IDX) continue;
            Layer layer = LAYERS[i];
            int layerMin = layer.minY(), layerMax = layer.maxY();
            if (y >= layerMin && y <= layerMax) {
                candidates.add(new double[]{i, layer.density});
            } else if (y >= layerMin - BLEND_MARGIN && y < layerMin) {
                int dist = layerMin - y;
                double blended = layer.density * (1.0 - (double) dist / (BLEND_MARGIN + 1));
                candidates.add(new double[]{i, blended});
            } else if (y > layerMax && y <= layerMax + BLEND_MARGIN) {
                int dist = y - layerMax;
                double blended = layer.density * (1.0 - (double) dist / (BLEND_MARGIN + 1));
                candidates.add(new double[]{i, blended});
            }
        }
        return candidates;
    }

    // ─── Vein growth (shuffled BFS) ───────────────────────────────────────────
    private void growVein(World world, Material ore, int sx, int sy, int sz, int size) {
        Deque<int[]> queue   = new ArrayDeque<>();
        Set<Long>    visited = new HashSet<>();
        queue.add(new int[]{sx, sy, sz});
        visited.add(key(sx, sy, sz));
        int placed = 0;
        int[][] directions = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        while (!queue.isEmpty() && placed < size) {
            int[] pos = queue.pollFirst();
            int x = pos[0], y = pos[1], z = pos[2];
            if (!isInMineBounds(x, y, z)) continue;
            try {
                org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                if (block.getType() == Material.STONE) {
                    block.setType(ore, false);
                    placed++;
                }
            } catch (Exception ignored) {}

            List<int[]> dirs = Arrays.asList(directions.clone());
            Collections.shuffle(dirs, rng);
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                long k = key(nx, ny, nz);
                if (!visited.contains(k)) {
                    visited.add(k);
                    queue.add(new int[]{nx, ny, nz});
                }
            }
        }
    }

    private long key(int x, int y, int z) {
        return ((long)(x + 30000000)) * 60000000L * 256L
             + ((long)(z + 30000000)) * 256L
             + y;
    }

    // ─── Bounds checks ───────────────────────────────────────────────────────

    /** Y ceiling extended to 205 so special picks (Aqua/Nova) work at the top of the mine structure. */
    public boolean isInMineExtended(Location loc) {
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= MINE_MIN_X && x <= MINE_MAX_X
            && y >= MINE_MIN_Y && y <= 205
            && z >= MINE_MIN_Z && z <= MINE_MAX_Z;
    }

    public boolean isInMine(Location loc) {
        return isInMineBounds(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private boolean isInMineBounds(int x, int y, int z) {
        return x >= MINE_MIN_X && x <= MINE_MAX_X
            && y >= MINE_MIN_Y && y <= MINE_MAX_Y
            && z >= MINE_MIN_Z && z <= MINE_MAX_Z;
    }

    // ─── State persistence ───────────────────────────────────────────────────
    private void loadState() {
        brokenCount = plugin.getConfig().getInt(KEY_BROKEN, 0);
        nextReset   = plugin.getConfig().getLong(KEY_NEXT_RESET, 0L);
    }

    private void saveState() {
        plugin.getConfig().set(KEY_BROKEN, brokenCount);
        plugin.getConfig().set(KEY_NEXT_RESET, nextReset);
        plugin.saveConfig();
    }
}

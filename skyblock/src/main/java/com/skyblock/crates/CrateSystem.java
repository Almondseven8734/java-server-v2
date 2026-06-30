package com.skyblock.crates;

import com.skyblock.items.SpecialItems;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Crate System
 *
 * Translated from crate_system.js.
 *
 * Handles:
 *   - 9 crate types: vote, common, basic, lunar, aqua, nova, void, solar, kill
 *   - Key detection and removal from player inventory
 *   - Weighted loot tables with special rewards (money, exp, keys, talismans, etc.)
 *   - Color-cycling opening animation
 *   - Shulker box right-click detection near spawn area to open crates
 *   - Aqua Pic (iron pickaxe, 3x3x1 Vein Strike) + Nova Pic (diamond pickaxe, 3x3x3 Void Drill)
 *   - Talisman passive effect application (every second)
 *   - Haste Potion right-click consumption
 *
 * Crate blocks are colored shulker boxes placed within SPAWN_RADIUS of (0,0).
 * Players right-click the shulker to open; shift+click for bulk open.
 */
public class CrateSystem implements Listener {

    // ─── Constants ────────────────────────────────────────────────────────────
    private static final double SPAWN_RADIUS = 100.0;
    // ─── Crate config ─────────────────────────────────────────────────────────
    public static class CrateConfig {
        public final String name;
        public final Material shulkerColor;
        public final Material keyMaterial;
        public final String keyName;
        public final Material[] cycleColors;

        public CrateConfig(String name, Material shulkerColor, Material keyMaterial,
                           String keyName, Material... cycleColors) {
            this.name = name;
            this.shulkerColor = shulkerColor;
            this.keyMaterial = keyMaterial;
            this.keyName = keyName;
            this.cycleColors = cycleColors;
        }
    }

    // ─── Loot entry ──────────────────────────────────────────────────────────
    private static class LootEntry {
        final String item;     // "minecraft:xxx" or "special:xxx"
        final int minAmount, maxAmount;
        final double weight;
        final String special;  // nullable

        LootEntry(String item, int min, int max, double weight, String special) {
            this.item = item;
            this.minAmount = min;
            this.maxAmount = max;
            this.weight = weight;
            this.special = special;
        }

        LootEntry(String item, int amount, double weight) {
            this(item, amount, amount, weight, null);
        }

        LootEntry(String item, int amount, double weight, String special) {
            this(item, amount, amount, weight, special);
        }
    }

    static class SelectedReward {
        String item;
        int amount;
        String special;
    }

    // ─── Crate configs map ────────────────────────────────────────────────────
    // Public so other systems (e.g. AdminShopSystem) can reuse the exact same
    // key materials/names/crate names instead of redefining them elsewhere.
    public static final Map<String, CrateConfig> CRATE_CONFIGS = new LinkedHashMap<>();
    static {
        // NOTE: key names carry a trailing "§7 ✦" verified-key mark. This keeps each
        // name *very close* to the original (same words, same colors) while giving
        // staff/players an easy visual tell that the item is a genuine, freshly
        // issued key — actual authenticity is enforced in code via PersistentData,
        // see KEY MANAGEMENT below.
        CRATE_CONFIGS.put("vote",   new CrateConfig("§eVote Crate",   Material.YELLOW_SHULKER_BOX,      Material.GLOWSTONE_DUST,      "§r§l§eVote §fKey §7✦",        Material.YELLOW_SHULKER_BOX, Material.ORANGE_SHULKER_BOX));
        CRATE_CONFIGS.put("common", new CrateConfig("§aCommon Crate", Material.LIME_SHULKER_BOX,        Material.SLIME_BALL,          "§r§l§2Common §aKey §7✦",       Material.LIME_SHULKER_BOX, Material.GREEN_SHULKER_BOX));
        CRATE_CONFIGS.put("basic",  new CrateConfig("§fBasic Crate",  Material.WHITE_SHULKER_BOX,       Material.QUARTZ,              "§r§l§7Basic §fKey §7✦",        Material.WHITE_SHULKER_BOX, Material.LIGHT_GRAY_SHULKER_BOX));
        CRATE_CONFIGS.put("lunar",  new CrateConfig("§1Lunar Crate",  Material.BLUE_SHULKER_BOX,        Material.PRISMARINE_SHARD,    "§r§l§1Lunar §8Key §7✦",        Material.BLUE_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX));
        CRATE_CONFIGS.put("aqua",   new CrateConfig("§bAqua Crate",   Material.LIGHT_BLUE_SHULKER_BOX,  Material.HEART_OF_THE_SEA,    "§r§l§3Aqua §bKey §7✦",         Material.LIGHT_BLUE_SHULKER_BOX, Material.CYAN_SHULKER_BOX));
        CRATE_CONFIGS.put("nova",   new CrateConfig("§dNova Crate",   Material.MAGENTA_SHULKER_BOX,     Material.AMETHYST_SHARD,      "§r§l§dNova §dKey §7✦",         Material.MAGENTA_SHULKER_BOX, Material.PURPLE_SHULKER_BOX));
        CRATE_CONFIGS.put("void",   new CrateConfig("§0Void Crate",   Material.BLACK_SHULKER_BOX,       Material.FLINT,               "§r§l§0Void §7Key §7✦",         Material.BLACK_SHULKER_BOX, Material.GRAY_SHULKER_BOX));
        CRATE_CONFIGS.put("solar",  new CrateConfig("§6Solar Crate",  Material.ORANGE_SHULKER_BOX,      Material.MAGMA_CREAM,         "§r§l§6Solar §eKey §7✦",        Material.ORANGE_SHULKER_BOX, Material.RED_SHULKER_BOX));
        CRATE_CONFIGS.put("kill",   new CrateConfig("§cKill Crate",   Material.RED_SHULKER_BOX,         Material.TRIAL_KEY,           "§r§l§cKill §6Key §7✦",         Material.RED_SHULKER_BOX, Material.PINK_SHULKER_BOX));
    }

    // ─── Loot tables ──────────────────────────────────────────────────────────
    private static final Map<String, List<LootEntry>> CRATE_LOOT = new HashMap<>();
    static {
        // VOTE crate
        CRATE_LOOT.put("vote", Arrays.asList(
            new LootEntry("minecraft:sponge",     1, 0.25),
            new LootEntry("special:basic_key",    1, 18),
            new LootEntry("special:common_key",   1, 14),
            new LootEntry("minecraft:cooked_beef",16, 22),
            new LootEntry("minecraft:iron_ingot", 32, 20),
            new LootEntry("minecraft:gold_ingot", 16, 18),
            new LootEntry("minecraft:music_disc_13", 1, 5),
            new LootEntry("special:money_100",    1, 50),
            new LootEntry("special:money_200",    1, 38),
            new LootEntry("special:money_500",    1, 25),
            new LootEntry("special:money_1000",   1, 14),
            new LootEntry("special:money_2000",   1, 8),
            new LootEntry("special:money_5000",   1, 4),
            new LootEntry("special:money_10000",  1, 2),
            new LootEntry("special:money_20000",  1, 1),
            new LootEntry("special:exp_10",       1, 45),
            new LootEntry("special:exp_20",       1, 32),
            new LootEntry("special:exp_50",       1, 18),
            new LootEntry("special:exp_100",      1, 10),
            new LootEntry("special:exp_200",      1, 5)
        ));

        // BASIC crate
        CRATE_LOOT.put("basic", Arrays.asList(
            new LootEntry("special:basic_key",      1, 18),
            new LootEntry("special:common_key",     1, 12),
            new LootEntry("minecraft:wheat_seeds",  32, 24),
            new LootEntry("minecraft:potato",       16, 22),
            new LootEntry("minecraft:carrot",       16, 22),
            new LootEntry("minecraft:beetroot_seeds",32, 22),
            new LootEntry("minecraft:cooked_beef",  32, 20),
            new LootEntry("minecraft:copper_ingot", 32, 18),
            new LootEntry("minecraft:coal",         32, 18),
            new LootEntry("minecraft:coal",         16, 24),
            new LootEntry("minecraft:music_disc_strad", 1, 4),
            new LootEntry("special:money_50",   1, 55),
            new LootEntry("special:money_100",  1, 38),
            new LootEntry("special:money_200",  1, 22),
            new LootEntry("special:money_500",  1, 12),
            new LootEntry("special:money_1000", 1, 6),
            new LootEntry("special:money_2000", 1, 3),
            new LootEntry("special:money_5000", 1, 1),
            new LootEntry("special:exp_10",     1, 50),
            new LootEntry("special:exp_20",     1, 35),
            new LootEntry("special:exp_50",     1, 20),
            new LootEntry("special:exp_100",    1, 10),
            new LootEntry("special:exp_200",    1, 5),
            new LootEntry("special:exp_500",    1, 2)
        ));

        // COMMON crate
        CRATE_LOOT.put("common", Arrays.asList(
            new LootEntry("special:aqua_key",           1, 6),
            new LootEntry("special:common_key",         1, 14),
            new LootEntry("special:basic_key",          1, 18),
            new LootEntry("minecraft:pumpkin_seeds",    16, 22),
            new LootEntry("minecraft:melon_seeds",      16, 22),
            new LootEntry("minecraft:cooked_beef",      32, 20),
            new LootEntry("minecraft:copper_ingot",     32, 18),
            new LootEntry("minecraft:iron_ingot",       16, 16),
            new LootEntry("special:chain_set",          1, 10),
            new LootEntry("minecraft:oak_sapling",      1, 12),
            new LootEntry("minecraft:spruce_sapling",   1, 12),
            new LootEntry("minecraft:birch_sapling",    1, 12),
            new LootEntry("minecraft:jungle_sapling",   1, 12),
            new LootEntry("minecraft:acacia_sapling",   1, 12),
            new LootEntry("minecraft:cherry_sapling",   1, 10),
            new LootEntry("minecraft:dark_oak_sapling", 4, 10),
            new LootEntry("minecraft:music_disc_cat",   1, 4),
            new LootEntry("minecraft:music_disc_ward",  1, 4),
            new LootEntry("minecraft:music_disc_far",   1, 4),
            new LootEntry("special:money_500",  1, 45),
            new LootEntry("special:money_1000", 1, 30),
            new LootEntry("special:money_2000", 1, 16),
            new LootEntry("special:money_5000", 1, 8),
            new LootEntry("special:money_10000",1, 3),
            new LootEntry("special:exp_10",     1, 45),
            new LootEntry("special:exp_20",     1, 30),
            new LootEntry("special:exp_50",     1, 15)
        ));

        // AQUA crate
        CRATE_LOOT.put("aqua", Arrays.asList(
            new LootEntry("special:common_key",     1, 14),
            new LootEntry("special:basic_key",      1, 16),
            new LootEntry("special:nova_key",       1, 6),
            new LootEntry("minecraft:sponge",       1, 10),
            new LootEntry("minecraft:gold_ingot",   16, 18),
            new LootEntry("minecraft:iron_ingot",   32, 20),
            new LootEntry("minecraft:diamond",      8, 12),
            new LootEntry("minecraft:golden_apple", 3, 14),
            new LootEntry("minecraft:golden_apple", 5, 8),
            new LootEntry("minecraft:cooked_beef",  32, 20),
            new LootEntry("special:iron_set",       1, 10),
            new LootEntry("minecraft:potion",       1, 14, "health_i"),
            new LootEntry("minecraft:music_disc_otherside", 1, 4),
            new LootEntry("minecraft:music_disc_wait",      1, 4),
            new LootEntry("special:aqua_pic",       1, 4),
            new LootEntry("special:money_20",   1, 60),
            new LootEntry("special:money_50",   1, 45),
            new LootEntry("special:money_100",  1, 30),
            new LootEntry("special:money_200",  1, 18),
            new LootEntry("special:money_500",  1, 10),
            new LootEntry("special:money_1000", 1, 5),
            new LootEntry("special:money_2000", 1, 2),
            new LootEntry("special:money_5000", 1, 1),
            new LootEntry("special:exp_10",     1, 40),
            new LootEntry("special:exp_50",     1, 15)
        ));

        // NOVA crate
        CRATE_LOOT.put("nova", Arrays.asList(
            new LootEntry("special:common_key",     1, 14),
            new LootEntry("special:void_key",       1, 6),
            new LootEntry("special:basic_key",      1, 16),
            new LootEntry("special:aqua_key",       1, 10),
            new LootEntry("special:solar_key",      1, 8),
            new LootEntry("special:lunar_key",      1, 8),
            new LootEntry("minecraft:trident",      1, 4),
            new LootEntry("minecraft:breeze_rod",   1, 6),
            new LootEntry("minecraft:sponge",       1, 8),
            new LootEntry("minecraft:wind_charge",  32, 10),
            new LootEntry("minecraft:diamond",      16, 16),
            new LootEntry("minecraft:diamond",      32, 8),
            new LootEntry("minecraft:diamond",      64, 3),
            new LootEntry("minecraft:enchanted_golden_apple", 1, 6),
            new LootEntry("minecraft:golden_apple", 3, 14),
            new LootEntry("minecraft:golden_apple", 5, 9),
            new LootEntry("special:diamond_set",    1, 5),
            new LootEntry("special:nova_pic",       1, 4),
            new LootEntry("minecraft:music_disc_mellohi", 1, 3),
            new LootEntry("minecraft:music_disc_mall",    1, 3),
            new LootEntry("special:money_500",  1, 50),
            new LootEntry("special:money_1000", 1, 35),
            new LootEntry("special:money_2000", 1, 20),
            new LootEntry("special:money_5000", 1, 10),
            new LootEntry("special:money_10000",1, 5),
            new LootEntry("special:money_20000",1, 2),
            new LootEntry("special:exp_100",    1, 40),
            new LootEntry("special:exp_200",    1, 22),
            new LootEntry("special:exp_500",    1, 10)
        ));

        // VOID crate
        CRATE_LOOT.put("void", Arrays.asList(
            new LootEntry("minecraft:netherite_helmet",    1, 5),
            new LootEntry("minecraft:netherite_chestplate",1, 5),
            new LootEntry("minecraft:netherite_leggings",  1, 5),
            new LootEntry("minecraft:netherite_boots",     1, 5),
            new LootEntry("minecraft:netherite_sword",     1, 6),
            new LootEntry("minecraft:heavy_core",          1, 3),
            new LootEntry("minecraft:dragon_egg",          1, 1),
            new LootEntry("minecraft:shulker_shell",       2, 6),
            new LootEntry("minecraft:sponge",              1, 5),
            new LootEntry("minecraft:turtle_helmet",       1, 4),
            new LootEntry("minecraft:piglin_head",         1, 3),
            new LootEntry("minecraft:feather",    1, 8,  "jump_talisman"),
            new LootEntry("minecraft:clay_ball",  1, 8,  "strength_talisman"),
            new LootEntry("minecraft:rabbit_foot",1, 8,  "speed_talisman"),
            new LootEntry("minecraft:spider_eye", 1, 7,  "regen_talisman"),
            new LootEntry("minecraft:armadillo_scute", 1, 7, "defence_talisman"),
            new LootEntry("minecraft:nautilus_shell",  1, 6, "multi_talisman"),
            new LootEntry("minecraft:feather",    1, 4,  "jump_talisman_t2"),
            new LootEntry("minecraft:clay_ball",  1, 4,  "strength_talisman_t2"),
            new LootEntry("minecraft:rabbit_foot",1, 4,  "speed_talisman_t2"),
            new LootEntry("minecraft:spider_eye", 1, 3,  "regen_talisman_t2"),
            new LootEntry("minecraft:armadillo_scute", 1, 3, "defence_talisman_t2"),
            new LootEntry("minecraft:dragon_breath",   1, 5, "haste_3"),
            new LootEntry("minecraft:dragon_breath",   1, 3, "haste_5"),
            new LootEntry("minecraft:music_disc_11",   1, 2),
            new LootEntry("minecraft:music_disc_stal", 1, 2),
            new LootEntry("minecraft:music_disc_5",    1, 2),
            new LootEntry("special:money_1000",   1, 50),
            new LootEntry("special:money_2000",   1, 35),
            new LootEntry("special:money_5000",   1, 20),
            new LootEntry("special:money_10000",  1, 10),
            new LootEntry("special:money_20000",  1, 5),
            new LootEntry("special:money_50000",  1, 2),
            new LootEntry("special:money_100000", 1, 1),
            new LootEntry("special:money_200000", 1, 0.5),
            new LootEntry("special:money_1000000",1, 0.1),
            new LootEntry("special:exp_10",   1, 40),
            new LootEntry("special:exp_20",   1, 30),
            new LootEntry("special:exp_50",   1, 20),
            new LootEntry("special:exp_100",  1, 12),
            new LootEntry("special:exp_200",  1, 8),
            new LootEntry("special:exp_500",  1, 4),
            new LootEntry("special:exp_1000", 1, 2),
            new LootEntry("special:exp_2000", 1, 1)
        ));

        // SOLAR crate
        CRATE_LOOT.put("solar", Arrays.asList(
            new LootEntry("special:nova_key",       1, 8),
            new LootEntry("special:void_key",       1, 6),
            new LootEntry("special:common_key",     1, 14),
            new LootEntry("special:basic_key",      1, 16),
            new LootEntry("special:aqua_key",       1, 10),
            new LootEntry("minecraft:sponge",       1, 8),
            new LootEntry("minecraft:shulker_shell",2, 10),
            new LootEntry("minecraft:enchanted_golden_apple", 1, 6),
            new LootEntry("minecraft:enchanted_golden_apple", 3, 3),
            new LootEntry("minecraft:golden_apple", 8, 12),
            new LootEntry("minecraft:spider_eye",   1, 10, "regen_talisman"),
            new LootEntry("minecraft:spider_eye",   1, 4,  "regen_talisman_t2"),
            new LootEntry("minecraft:music_disc_relic",              1, 3),
            new LootEntry("minecraft:music_disc_creator_music_box",  1, 3),
            new LootEntry("special:money_200",  1, 50),
            new LootEntry("special:money_500",  1, 35),
            new LootEntry("special:money_1000", 1, 20),
            new LootEntry("special:money_2000", 1, 10),
            new LootEntry("special:money_5000", 1, 4),
            new LootEntry("special:money_10000",1, 2),
            new LootEntry("special:exp_100",    1, 40),
            new LootEntry("special:exp_200",    1, 20),
            new LootEntry("special:exp_500",    1, 8)
        ));

        // LUNAR crate
        CRATE_LOOT.put("lunar", Arrays.asList(
            new LootEntry("special:void_key",       1, 5),
            new LootEntry("special:common_key",     1, 14),
            new LootEntry("special:basic_key",      1, 16),
            new LootEntry("special:nova_key",       1, 8),
            new LootEntry("special:aqua_key",       1, 10),
            new LootEntry("minecraft:sponge",               1, 8),
            new LootEntry("minecraft:shulker_shell",        2, 10),
            new LootEntry("minecraft:enchanted_golden_apple",1, 5),
            new LootEntry("minecraft:golden_apple",         5, 14),
            new LootEntry("minecraft:golden_apple",         8, 8),
            new LootEntry("minecraft:armadillo_scute",      1, 10, "defence_talisman"),
            new LootEntry("minecraft:armadillo_scute",      1, 4,  "defence_talisman_t2"),
            new LootEntry("minecraft:music_disc_precipice",       1, 3),
            new LootEntry("minecraft:music_disc_creator",         1, 3),
            new LootEntry("special:money_2000",   1, 50),
            new LootEntry("special:money_5000",   1, 30),
            new LootEntry("special:money_10000",  1, 15),
            new LootEntry("special:money_20000",  1, 7),
            new LootEntry("special:money_50000",  1, 3),
            new LootEntry("special:money_100000", 1, 1),
            new LootEntry("special:exp_100",  1, 40),
            new LootEntry("special:exp_200",  1, 22),
            new LootEntry("special:exp_500",  1, 10),
            new LootEntry("special:exp_1000", 1, 4)
        ));

        // KILL crate
        CRATE_LOOT.put("kill", Arrays.asList(
            new LootEntry("minecraft:diamond_helmet",    1, 5),
            new LootEntry("minecraft:diamond_chestplate",1, 5),
            new LootEntry("minecraft:diamond_leggings",  1, 5),
            new LootEntry("minecraft:diamond_boots",     1, 5),
            new LootEntry("minecraft:diamond_sword",     1, 6),
            new LootEntry("minecraft:diamond_axe",       1, 5),
            new LootEntry("minecraft:bow",               1, 6),
            new LootEntry("minecraft:crossbow",          1, 5),
            new LootEntry("minecraft:shield",            1, 6),
            new LootEntry("minecraft:potion",            1, 8, "health_ii"),
            new LootEntry("minecraft:potion",            1, 8, "regen_ii"),
            new LootEntry("minecraft:potion",            1, 8, "speed_ii"),
            new LootEntry("minecraft:arrow",             32, 7, "weakness_arrow"),
            new LootEntry("minecraft:totem_of_undying",  1, 3),
            new LootEntry("minecraft:golden_apple",      8, 8),
            new LootEntry("minecraft:enchanted_golden_apple", 1, 4),
            new LootEntry("minecraft:feather",    1, 5, "jump_talisman"),
            new LootEntry("minecraft:clay_ball",  1, 5, "strength_talisman"),
            new LootEntry("minecraft:rabbit_foot",1, 5, "speed_talisman"),
            new LootEntry("minecraft:music_disc_pigstep", 1, 2),
            new LootEntry("minecraft:music_disc_blocks",  1, 2),
            new LootEntry("minecraft:music_disc_chirp",   1, 2),
            new LootEntry("special:nova_key",             1, 6),
            new LootEntry("special:aqua_key",             1, 4),
            new LootEntry("special:money_100",  1, 20),
            new LootEntry("special:money_500",  1, 10),
            new LootEntry("special:money_1000", 1, 5),
            new LootEntry("special:money_5000", 1, 1),
            new LootEntry("special:exp_10",     1, 5),
            new LootEntry("special:exp_50",     1, 3),
            new LootEntry("special:exp_100",    1, 2)
        ));
    }

    // ─── Runtime state ────────────────────────────────────────────────────────
    private final JavaPlugin    plugin;
    private final SpecialItems  specialItems;
    private final Logger log;
    private final Random rng = new Random();

    // ─── Key authenticity (anti-forgery) ─────────────────────────────────────
    // Real keys carry two PersistentDataContainer entries that a vanilla item of
    // the same material can NEVER have: a crate-type id and a signature derived
    // from a secret salt. Anvils, vanilla /give, and ordinary gameplay cannot set
    // PDC values, so a player can never "transmute" a plain Slime Ball/Quartz/etc.
    // into a working key just by matching its name and lore. Only this plugin can
    // produce an item that passes isValidKey(). Change KEY_SALT to instantly
    // invalidate every key currently in the world (e.g. after a dupe incident).
    private static final String KEY_SALT = "Dyric-CrateAuth-7f3Q9xLp2v-v1";
    private final NamespacedKey keyIdTag;
    private final NamespacedKey keySigTag;

    // Map shulker material → crate type for quick lookup
    private static final Map<Material, String> SHULKER_TO_CRATE = new HashMap<>();
    static {
        for (Map.Entry<String, CrateConfig> e : CRATE_CONFIGS.entrySet())
            SHULKER_TO_CRATE.put(e.getValue().shulkerColor, e.getKey());
    }

    public CrateSystem(JavaPlugin plugin, SpecialItems specialItems) {
        this.plugin       = plugin;
        this.specialItems = specialItems;
        this.log          = plugin.getLogger();
        this.keyIdTag  = new NamespacedKey(plugin, "crate_key_id");
        this.keySigTag = new NamespacedKey(plugin, "crate_key_sig");

        // (Talisman effects are now handled by SpecialItems)
    }

    // =========================================================================
    // KEY MANAGEMENT
    // =========================================================================

    /** Deterministic per-crate-type signature, derived from a secret salt. */
    private static String signature(String crateType) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((KEY_SALT + ':' + crateType).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(24);
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory on every JVM; this is unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Give a crate key to a player */
    public boolean giveKey(Player player, String crateType) {
        CrateConfig config = CRATE_CONFIGS.get(crateType);
        if (config == null) return false;

        ItemStack key = buildKey(crateType, 1);
        if (key == null) return false;
        player.getInventory().addItem(key);
        return true;
    }

    /**
     * Builds an authenticated crate key ItemStack without giving it to a player.
     * Use this to place keys inside barrel inventories.
     */
    public ItemStack buildKey(String crateType, int qty) {
        CrateConfig config = CRATE_CONFIGS.get(crateType);
        if (config == null) return null;

        ItemStack key = new ItemStack(config.keyMaterial, qty);
        ItemMeta meta = key.getItemMeta();
        meta.setDisplayName(config.keyName);
        meta.setLore(Arrays.asList(
                "§r§7Crate Key",
                "§r§7",
                "§r§7Opens: " + config.name,
                "§r§7",
                "§r§7Right-click the crate to open"
        ));

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(keyIdTag, PersistentDataType.STRING, crateType);
        pdc.set(keySigTag, PersistentDataType.STRING, signature(crateType));

        key.setItemMeta(meta);
        return key;
    }

    /** True only for items this plugin actually issued as a key of this crateType. */
    private boolean isValidKey(ItemStack item, String crateType) {
        if (item == null) return false;
        CrateConfig config = CRATE_CONFIGS.get(crateType);
        if (config == null || item.getType() != config.keyMaterial) return false;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String id  = pdc.get(keyIdTag, PersistentDataType.STRING);
        String sig = pdc.get(keySigTag, PersistentDataType.STRING);
        if (id == null || sig == null) return false;          // no vanilla item has these
        if (!crateType.equals(id)) return false;
        return signature(crateType).equals(sig);
    }

    private boolean hasKey(Player player, String crateType) {
        return findKey(player, crateType) != -1;
    }

    private int findKey(Player player, String crateType) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isValidKey(contents[i], crateType)) return i;
        }
        return -1;
    }

    private boolean removeKey(Player player, String crateType) {
        int slot = findKey(player, crateType);
        if (slot == -1) return false;

        ItemStack item = player.getInventory().getItem(slot);
        if (item.getAmount() > 1) {
            item.setAmount(item.getAmount() - 1);
        } else {
            player.getInventory().setItem(slot, null);
        }
        return true;
    }

    // =========================================================================
    // LOOT SELECTION
    // =========================================================================

    private SelectedReward selectLoot(String crateType) {
        List<LootEntry> table = CRATE_LOOT.get(crateType);
        if (table == null || table.isEmpty()) return null;

        double totalWeight = table.stream().mapToDouble(e -> e.weight).sum();
        double roll = rng.nextDouble() * totalWeight;

        for (LootEntry entry : table) {
            roll -= entry.weight;
            if (roll <= 0) {
                SelectedReward reward = new SelectedReward();
                reward.item = entry.item;
                reward.amount = entry.minAmount + (entry.maxAmount > entry.minAmount
                        ? rng.nextInt(entry.maxAmount - entry.minAmount + 1) : 0);
                reward.special = entry.special;
                return reward;
            }
        }
        // Fallback
        LootEntry first = table.get(0);
        SelectedReward reward = new SelectedReward();
        reward.item = first.item;
        reward.amount = first.minAmount;
        reward.special = first.special;
        return reward;
    }

    // =========================================================================
    // CRATE OPENING
    // =========================================================================

    public boolean openCrate(Player player, String crateType, Location blockLoc) {
        CrateConfig config = CRATE_CONFIGS.get(crateType);
        if (config == null) { player.sendMessage("§cInvalid crate type!"); return false; }

        if (!hasKey(player, crateType)) {
            player.sendMessage("§c✖ You need a " + config.keyName + "§c!");
            player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
            return false;
        }
        if (!removeKey(player, crateType)) { player.sendMessage("§cFailed to remove key!"); return false; }

        SelectedReward reward = selectLoot(crateType);
        if (reward == null) { player.sendMessage("§cNo loot found!"); return false; }

        log.info("[CRATE] " + player.getName() + " opening " + crateType + " crate — "
                + reward.amount + "x " + reward.item
                + (reward.special != null ? " (" + reward.special + ")" : ""));

        player.playSound(player.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 1.0f, 0.8f);
        playCyclingEffect(player, crateType, reward, blockLoc);
        return true;
    }

    /** Bulk open — open up to limit times until keys run out */
    public void bulkOpenCrate(Player player, String crateType, Location blockLoc) {
        int count = 0;
        while (hasKey(player, crateType) && count < 64) {
            openCrate(player, crateType, blockLoc);
            count++;
        }
        if (count == 0) player.sendMessage("§cYou have no keys for this crate!");
        else player.sendMessage("§aOpened §e" + count + " §a" + CRATE_CONFIGS.get(crateType).name + " §acrates!");
    }

    // =========================================================================
    // ANIMATION
    // =========================================================================

    private void playCyclingEffect(Player player, String crateType, SelectedReward finalReward, Location blockLoc) {
        CrateConfig config = CRATE_CONFIGS.get(crateType);
        final int maxCycles = 15;
        final int[] cycle = {0};

        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            cycle[0]++;

            if (cycle[0] < maxCycles && blockLoc != null) {
                Material color = config.cycleColors[cycle[0] % config.cycleColors.length];
                Block b = blockLoc.getBlock();
                if (b.getType() != Material.AIR) b.setType(color, false);
            }

            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT,
                    0.5f, 0.5f + cycle[0] * 0.1f);

            if (cycle[0] >= maxCycles) {
                ((BukkitTask) task).cancel();
                if (blockLoc != null) blockLoc.getBlock().setType(config.shulkerColor, false);
                giveReward(player, finalReward, config);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
            }
        }, 0L, 3L);
    }

    // =========================================================================
    // REWARD DELIVERY
    // =========================================================================

    private void giveReward(Player player, SelectedReward reward, CrateConfig config) {
        try {
            if (reward.item.startsWith("special:")) {
                handleSpecialReward(player, reward, config.name);
                return;
            }
            if (reward.special != null) {
                handleSpecialItem(player, reward, config.name);
                return;
            }

            Material mat = Material.matchMaterial(reward.item);
            if (mat == null) { player.sendMessage("§cUnknown item: " + reward.item); return; }
            player.getInventory().addItem(new ItemStack(mat, reward.amount));
            player.sendMessage("§aYou received §e" + reward.amount + "x §f"
                    + formatName(reward.item) + " §afrom " + config.name + "§a!");

        } catch (Exception e) {
            player.sendMessage("§cError giving reward!");
            log.severe("[CRATE] Reward error: " + e.getMessage());
        }
    }

    // =========================================================================
    // ADMIN SHOP HOOKS
    //
    // Thin wrappers that let other systems (AdminShopSystem) trigger the exact
    // same reward-creation code crates use — without going through a crate open
    // (no key consumption, no RNG, no money). "sourceName" only affects the
    // chat message the player sees (e.g. "from §6Admin Shop§a!").
    // =========================================================================

    /**
     * Grants a "special:" reward by id — covers every crate key (vote_key,
     * common_key, basic_key, lunar_key, aqua_key, nova_key, void_key,
     * solar_key, kill_key) plus iron_set, chain_set, diamond_set, aqua_pic,
     * and nova_pic. This is the exact same dispatch handleSpecialReward()
     * uses inside a real crate opening — see the specialType branches above.
     */
    public void grantAdminSpecial(Player player, String specialId) {
        SelectedReward r = new SelectedReward();
        r.item = "special:" + specialId;
        r.amount = 1;
        r.special = null;
        handleSpecialReward(player, r, "§6Admin Shop");
    }

    /**
     * Grants an item+special pairing — covers every crate talisman (and its
     * tier II variant), the Instant Health/Regeneration/Speed potions, the
     * Arrow of Weakness, and the Haste III/V items. itemId/specialTag must
     * match a pairing that actually exists in CRATE_LOOT (e.g.
     * "minecraft:feather" + "jump_talisman").
     */
    public void grantAdminSpecialItem(Player player, String itemId, String specialTag, int amount) {
        SelectedReward r = new SelectedReward();
        r.item = itemId;
        r.amount = Math.max(1, amount);
        r.special = specialTag;
        handleSpecialItem(player, r, "§6Admin Shop");
    }

    private void handleSpecialReward(Player player, SelectedReward reward, String sourceName) {
        String specialType = reward.item.replace("special:", "");

        if (specialType.startsWith("money_")) {
            int amount = Integer.parseInt(specialType.replace("money_", ""));
            player.sendMessage("§aYou received §e" + amount + "g §afrom " + sourceName + "§a!");
            try {
                org.bukkit.scoreboard.Scoreboard board = org.bukkit.Bukkit.getScoreboardManager().getMainScoreboard();
                org.bukkit.scoreboard.Objective obj = board.getObjective("Money");
                if (obj == null) obj = board.registerNewObjective("Money", "dummy", "§6Money");
                org.bukkit.scoreboard.Score score = obj.getScore(player.getName());
                score.setScore(score.isScoreSet() ? score.getScore() + amount : amount);
            } catch (Exception e) {
                log.warning("[CRATE] Failed to give money to " + player.getName() + ": " + e.getMessage());
            }
            log.info("[CRATE] Gave " + player.getName() + " " + amount + "g");

        } else if (specialType.startsWith("exp_")) {
            int amount = Integer.parseInt(specialType.replace("exp_", ""));
            player.sendMessage("§aYou received §e" + amount + " Experience §afrom " + sourceName + "§a!");
            player.giveExp(amount);
            log.info("[CRATE] Gave " + player.getName() + " " + amount + " exp");

        } else if (specialType.endsWith("_key")) {
            String keyType = specialType.replace("_key", "");
            giveKey(player, keyType);
            CrateConfig kc = CRATE_CONFIGS.get(keyType);
            player.sendMessage("§aYou received §f"
                    + (kc != null ? kc.name + " Key" : keyType + " Key")
                    + " §afrom " + sourceName + "§a!");

        } else if (specialType.equals("iron_set")) {
            for (String id : new String[]{"minecraft:iron_helmet","minecraft:iron_chestplate",
                    "minecraft:iron_leggings","minecraft:iron_boots","minecraft:iron_sword",
                    "minecraft:iron_pickaxe","minecraft:iron_axe","minecraft:iron_shovel","minecraft:iron_hoe"}) {
                Material mat = Material.matchMaterial(id);
                if (mat != null) player.getInventory().addItem(new ItemStack(mat));
            }
            player.sendMessage("§aYou received §7Full Iron Set §7(Armor + Tools) §afrom " + sourceName + "§a!");

        } else if (specialType.equals("chain_set")) {
            for (String id : new String[]{"minecraft:chainmail_helmet","minecraft:chainmail_chestplate",
                    "minecraft:chainmail_leggings","minecraft:chainmail_boots"}) {
                Material mat = Material.matchMaterial(id);
                if (mat != null) player.getInventory().addItem(new ItemStack(mat));
            }
            player.sendMessage("§aYou received §7Full Chain Armor §afrom " + sourceName + "§a!");

        } else if (specialType.equals("diamond_set")) {
            for (String id : new String[]{"minecraft:diamond_helmet","minecraft:diamond_chestplate",
                    "minecraft:diamond_leggings","minecraft:diamond_boots"}) {
                Material mat = Material.matchMaterial(id);
                if (mat != null) player.getInventory().addItem(new ItemStack(mat));
            }
            player.sendMessage("§aYou received §bFull Diamond Armor §afrom " + sourceName + "§a!");

        } else if (specialType.equals("aqua_pic")) {
            specialItems.giveAquaPic(player);
            player.sendMessage("§aYou received §r§l§3Aqua §bPic §afrom " + sourceName + "§a!");

        } else if (specialType.equals("nova_pic")) {
            specialItems.giveNovaPic(player);
            player.sendMessage("§aYou received §r§l§dNova §5Pic §afrom " + sourceName + "§a!");
        }
    }

    private void handleSpecialItem(Player player, SelectedReward reward, String sourceName) {
        Material mat = Material.matchMaterial(reward.item);
        if (mat == null) return;
        specialItems.giveSpecialItem(player, mat, reward.special, reward.amount);
    }

    // =========================================================================
    // SHULKER BOX RIGHT-CLICK — CRATE OPEN TRIGGER
    // =========================================================================

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;

        String crateType = SHULKER_TO_CRATE.get(block.getType());
        if (crateType == null) return;

        // Must be near spawn
        Location loc = block.getLocation();
        double dist = Math.sqrt(loc.getX() * loc.getX() + loc.getZ() * loc.getZ());
        if (dist > SPAWN_RADIUS) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (player.isSneaking()) {
            bulkOpenCrate(player, crateType, loc);
        } else {
            openCrate(player, crateType, loc);
        }
    }

    private String formatName(String id) {
        return Arrays.stream(id.replace("minecraft:", "").split("_"))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .reduce((a, b) -> a + " " + b).orElse(id);
    }
}
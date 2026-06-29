package com.skyblock.shop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;

import java.util.*;
import java.util.logging.Logger;

/**
 * Sell & Trash System
 *
 * Translated from sell_trash_system.js.
 *
 * Provides:
 *   - {@link #showSellCategoryMenu(Player)}  — browse sell categories (mirrors showSellCategoryMenu)
 *   - {@link #showSellInventoryMenu(Player)} — inventory-mirror sell (mirrors showSellInventoryMenu)
 *   - {@link #showTrashMenu(Player)}         — free-form "drop items here" storage bin; whatever is
 *                                               still inside when the menu is closed gets destroyed
 *   - {@link #sellAllItems(Player)}          — instant sell everything (mirrors sellAllItems)
 *
 * Money is stored on the "Money" scoreboard objective (same as ShopSystem).
 *
 * The Bedrock ChestFormData is replaced by standard Bukkit {@link Inventory} GUIs.
 * Click handling is done via InventoryClickEvent with a UUID tag on the inventory title.
 *
 * Register this class as both a Listener and provide its public methods to the
 * /sell and /trash command executors.
 */
public class SellTrashSystem implements Listener {

    // =========================================================================
    // SELL DATA — mirrors SELL_DATA in sell_trash_system.js
    // =========================================================================

    public static class SellItem {
        public final String name;
        public final String itemId;   // e.g. "minecraft:diamond"
        public final Material material;
        public final int sellPrice;
        public final String category;

        public SellItem(String name, String itemId, int sellPrice, String category) {
            this.name      = name;
            this.itemId    = itemId;
            this.material  = parseMaterial(itemId);
            this.sellPrice = sellPrice;
            this.category  = category;
        }

        private static Material parseMaterial(String itemId) {
            String key = itemId.replace("minecraft:", "").toUpperCase();
            // Bedrock ↔ Java name fixes
            switch (key) {
                case "STONEBRICK":            return Material.STONE_BRICKS;
                case "MOSSY_STONE_BRICK":     return Material.MOSSY_STONE_BRICKS;
                case "CRACKED_STONE_BRICK":   return Material.CRACKED_STONE_BRICKS;
                case "BRICK_BLOCK":           return Material.BRICKS;
                case "QUARTZ_ORE":            return Material.NETHER_QUARTZ_ORE;
                default:
                    try { return Material.valueOf(key); }
                    catch (IllegalArgumentException e) { return Material.PAPER; }
            }
        }
    }

    // ─── Category metadata ────────────────────────────────────────────────────

    public static class SellCategory {
        public final String id;
        public final String displayName;
        public final Material icon;
        public final List<SellItem> items;

        public SellCategory(String id, String displayName, Material icon) {
            this.id          = id;
            this.displayName = displayName;
            this.icon        = icon;
            this.items       = new ArrayList<>();
        }
    }

    // ─── Master data structures ────────────────────────────────────────────────

    /** All sell categories in order. */
    public static final List<SellCategory> SELL_CATEGORIES = new ArrayList<>();

    /** Fast lookup: Material → SellItem. Built from SELL_CATEGORIES. */
    public static final Map<Material, SellItem> SELLABLE_ITEMS = new HashMap<>();

    static {
        // ── Ores & Minerals ───────────────────────────────────────────────────
        SellCategory ores = new SellCategory("ores", "§bOres & Minerals", Material.DIAMOND);
        add(ores, "§fCoal",                "minecraft:coal",                  3);
        add(ores, "§fRaw Iron",            "minecraft:raw_iron",              10);
        add(ores, "§fRaw Copper",          "minecraft:raw_copper",             8);
        add(ores, "§fRaw Gold",            "minecraft:raw_gold",              20);
        add(ores, "§fIron Ingot",          "minecraft:iron_ingot",            15);
        add(ores, "§fGold Ingot",          "minecraft:gold_ingot",            30);
        add(ores, "§fCopper Ingot",        "minecraft:copper_ingot",          12);
        add(ores, "§fNetherite Ingot",     "minecraft:netherite_ingot",      500);
        add(ores, "§bDiamond",             "minecraft:diamond",               60);
        add(ores, "§aEmerald",             "minecraft:emerald",               50);
        add(ores, "§9Lapis Lazuli",        "minecraft:lapis_lazuli",           8);
        add(ores, "§cRedstone Dust",       "minecraft:redstone",               5);
        add(ores, "§fNether Quartz",       "minecraft:quartz",                12);
        add(ores, "§5Amethyst Shard",      "minecraft:amethyst_shard",        18);
        add(ores, "§fCoal Ore",            "minecraft:coal_ore",               4);
        add(ores, "§fIron Ore",            "minecraft:iron_ore",              12);
        add(ores, "§fGold Ore",            "minecraft:gold_ore",              22);
        add(ores, "§bDiamond Ore",         "minecraft:diamond_ore",           65);
        add(ores, "§aEmerald Ore",         "minecraft:emerald_ore",           55);
        add(ores, "§9Lapis Ore",           "minecraft:lapis_ore",             10);
        add(ores, "§cRedstone Ore",        "minecraft:redstone_ore",           7);
        add(ores, "§fDeepslate Coal Ore",  "minecraft:deepslate_coal_ore",     5);
        add(ores, "§fDeepslate Iron Ore",  "minecraft:deepslate_iron_ore",    14);
        add(ores, "§fDeepslate Gold Ore",  "minecraft:deepslate_gold_ore",    25);
        add(ores, "§bDeepslate Diamond Ore","minecraft:deepslate_diamond_ore",70);
        add(ores, "§aDeepslate Emerald Ore","minecraft:deepslate_emerald_ore",60);
        add(ores, "§fNether Quartz Ore",   "minecraft:quartz_ore",            14);
        add(ores, "§cAncient Debris",      "minecraft:ancient_debris",       300);
        SELL_CATEGORIES.add(ores);

        // ── Wood & Logs ───────────────────────────────────────────────────────
        SellCategory wood = new SellCategory("wood", "§6Wood & Logs", Material.OAK_LOG);
        add(wood, "§fOak Log",            "minecraft:oak_log",       5);
        add(wood, "§fSpruce Log",         "minecraft:spruce_log",    5);
        add(wood, "§fBirch Log",          "minecraft:birch_log",     5);
        add(wood, "§fJungle Log",         "minecraft:jungle_log",    5);
        add(wood, "§fAcacia Log",         "minecraft:acacia_log",    5);
        add(wood, "§fDark Oak Log",       "minecraft:dark_oak_log",  5);
        add(wood, "§fMangrove Log",       "minecraft:mangrove_log",  6);
        add(wood, "§dCherry Log",         "minecraft:cherry_log",    7);
        add(wood, "§cCrimson Stem",       "minecraft:crimson_stem",  6);
        add(wood, "§aCrimson Stem",       "minecraft:warped_stem",   6);
        add(wood, "§fOak Planks",         "minecraft:oak_planks",    2);
        add(wood, "§fSpruce Planks",      "minecraft:spruce_planks", 2);
        add(wood, "§fBirch Planks",       "minecraft:birch_planks",  2);
        add(wood, "§fJungle Planks",      "minecraft:jungle_planks", 2);
        add(wood, "§fAcacia Planks",      "minecraft:acacia_planks", 2);
        add(wood, "§fDark Oak Planks",    "minecraft:dark_oak_planks",2);
        add(wood, "§fMangrove Planks",    "minecraft:mangrove_planks",3);
        add(wood, "§dCherry Planks",      "minecraft:cherry_planks", 3);
        add(wood, "§cCrimson Planks",     "minecraft:crimson_planks",3);
        add(wood, "§aWarped Planks",      "minecraft:warped_planks", 3);
        SELL_CATEGORIES.add(wood);

        // ── Stone & Earth ─────────────────────────────────────────────────────
        SellCategory stone = new SellCategory("stone_earth", "§7Stone & Earth", Material.STONE);
        add(stone, "§fStone",              "minecraft:stone",             5);
        add(stone, "§fCobblestone",        "minecraft:cobblestone",       1);
        add(stone, "§fSmooth Stone",       "minecraft:smooth_stone",      6);
        add(stone, "§fStone Bricks",       "minecraft:stone_bricks",      7);
        add(stone, "§fMossy Stone Bricks", "minecraft:mossy_stone_bricks",9);
        add(stone, "§fCracked Stone Bricks","minecraft:cracked_stone_bricks",8);
        add(stone, "§fCobbled Deepslate",  "minecraft:cobbled_deepslate", 3);
        add(stone, "§fDeepslate",          "minecraft:deepslate",         7);
        add(stone, "§fDeepslate Bricks",   "minecraft:deepslate_bricks",  9);
        add(stone, "§fDeepslate Tiles",    "minecraft:deepslate_tiles",   9);
        add(stone, "§fDirt",               "minecraft:dirt",              1);
        add(stone, "§fGravel",             "minecraft:gravel",            2);
        add(stone, "§fSand",               "minecraft:sand",              3);
        add(stone, "§fGlass",              "minecraft:glass",            10);
        SELL_CATEGORIES.add(stone);

        // ── Farming ───────────────────────────────────────────────────────────
        SellCategory farming = new SellCategory("farming", "§aFarming", Material.WHEAT);
        add(farming, "§fWheat",             "minecraft:wheat",              3);
        add(farming, "§fCarrot",            "minecraft:carrot",             4);
        add(farming, "§fPotato",            "minecraft:potato",             4);
        add(farming, "§fBeetroot",          "minecraft:beetroot",           4);
        add(farming, "§fMelon Slice",       "minecraft:melon_slice",        2);
        add(farming, "§fPumpkin",           "minecraft:pumpkin",            8);
        add(farming, "§fSugar Cane",        "minecraft:sugar_cane",         3);
        add(farming, "§fCactus",            "minecraft:cactus",             2);
        add(farming, "§fBamboo",            "minecraft:bamboo",             2);
        add(farming, "§fCocoa Beans",       "minecraft:cocoa_beans",        5);
        add(farming, "§fNether Wart",       "minecraft:nether_wart",        8);
        add(farming, "§fSweet Berries",     "minecraft:sweet_berries",      6);
        add(farming, "§fGlow Berries",      "minecraft:glow_berries",      10);
        SELL_CATEGORIES.add(farming);

        // ── Mob Drops ─────────────────────────────────────────────────────────
        SellCategory mobs = new SellCategory("mob_drops", "§cMob Drops", Material.BONE);
        add(mobs, "§fBone",                "minecraft:bone",               5);
        add(mobs, "§fString",              "minecraft:string",             4);
        add(mobs, "§fSpider Eye",          "minecraft:spider_eye",         8);
        add(mobs, "§fGunpowder",           "minecraft:gunpowder",         10);
        add(mobs, "§fRotten Flesh",        "minecraft:rotten_flesh",       2);
        add(mobs, "§fArrow",               "minecraft:arrow",              2);
        add(mobs, "§fFeather",             "minecraft:feather",            3);
        add(mobs, "§fLeather",             "minecraft:leather",            8);
        add(mobs, "§fRabbit Hide",         "minecraft:rabbit_hide",        6);
        add(mobs, "§fRabbit Foot",         "minecraft:rabbit_foot",       15);
        add(mobs, "§fInk Sac",             "minecraft:ink_sac",            5);
        add(mobs, "§bGlow Ink Sac",        "minecraft:glow_ink_sac",      15);
        add(mobs, "§fSlimeball",           "minecraft:slime_ball",        12);
        add(mobs, "§fMagma Cream",         "minecraft:magma_cream",       18);
        add(mobs, "§fBlaze Rod",           "minecraft:blaze_rod",         25);
        add(mobs, "§fGhast Tear",          "minecraft:ghast_tear",        35);
        add(mobs, "§fEnder Pearl",         "minecraft:ender_pearl",       20);
        add(mobs, "§5Chorus Fruit",        "minecraft:chorus_fruit",      15);
        add(mobs, "§fShulker Shell",       "minecraft:shulker_shell",     60);
        add(mobs, "§fPrismarine Shard",    "minecraft:prismarine_shard",  12);
        add(mobs, "§fPrismarine Crystals", "minecraft:prismarine_crystals",10);
        add(mobs, "§fNautilus Shell",      "minecraft:nautilus_shell",    40);
        add(mobs, "§6Heart of the Sea",    "minecraft:heart_of_the_sea", 200);
        add(mobs, "§fSponge",              "minecraft:sponge",            20);
        SELL_CATEGORIES.add(mobs);

        // ── Fish & Sea ────────────────────────────────────────────────────────
        SellCategory fish = new SellCategory("fish_sea", "§9Fish & Sea", Material.COD);
        add(fish, "§fCod",                 "minecraft:cod",                8);
        add(fish, "§fSalmon",              "minecraft:salmon",            10);
        add(fish, "§fTropical Fish",       "minecraft:tropical_fish",     15);
        add(fish, "§fPufferfish",          "minecraft:pufferfish",        12);
        add(fish, "§fDried Kelp",          "minecraft:dried_kelp",         2);
        add(fish, "§fSeagrass",            "minecraft:seagrass",           2);
        add(fish, "§fSea Pickle",          "minecraft:sea_pickle",         5);
        add(fish, "§fCoral Block",         "minecraft:brain_coral_block",  8);
        SELL_CATEGORIES.add(fish);

        // ── Nether ────────────────────────────────────────────────────────────
        SellCategory nether = new SellCategory("nether", "§cNether", Material.NETHERRACK);
        add(nether, "§fNetherrack",        "minecraft:netherrack",         2);
        add(nether, "§fSoul Sand",         "minecraft:soul_sand",          8);
        add(nether, "§fSoul Soil",         "minecraft:soul_soil",          8);
        add(nether, "§fBasalt",            "minecraft:basalt",             6);
        add(nether, "§fBlackstone",        "minecraft:blackstone",         5);
        add(nether, "§fCrimson Fungus",    "minecraft:crimson_fungus",     5);
        add(nether, "§fWarped Fungus",     "minecraft:warped_fungus",      5);
        add(nether, "§fCrimson Roots",     "minecraft:crimson_roots",      3);
        add(nether, "§fWarped Roots",      "minecraft:warped_roots",       3);
        add(nether, "§fShroomlight",       "minecraft:shroomlight",       20);
        add(nether, "§fGlowstone Dust",    "minecraft:glowstone_dust",    10);
        SELL_CATEGORIES.add(nether);

        // ── Miscellaneous ─────────────────────────────────────────────────────
        SellCategory misc = new SellCategory("misc", "§eMisc", Material.COMPASS);
        add(misc, "§fBook",                "minecraft:book",               5);
        add(misc, "§fPaper",               "minecraft:paper",              2);
        add(misc, "§fName Tag",            "minecraft:name_tag",          30);
        add(misc, "§fSaddle",              "minecraft:saddle",            25);
        add(misc, "§fHoney Bottle",        "minecraft:honey_bottle",      10);
        add(misc, "§fHoneycomb",           "minecraft:honeycomb",         12);
        add(misc, "§fWax",                 "minecraft:honeycomb",         12);
        add(misc, "§fExperience Bottle",   "minecraft:experience_bottle", 20);
        add(misc, "§fPrismatic Slab",      "minecraft:prismarine_slab",    8);
        SELL_CATEGORIES.add(misc);

        // ── Build SELLABLE_ITEMS lookup ────────────────────────────────────────
        for (SellCategory cat : SELL_CATEGORIES) {
            for (SellItem item : cat.items) {
                SELLABLE_ITEMS.put(item.material, item);
            }
        }
    }

    private static void add(SellCategory cat, String name, String itemId, int price) {
        SellItem si = new SellItem(name, itemId, price, cat.id);
        cat.items.add(si);
    }

    // =========================================================================
    // DEPENDENCIES & STATE
    // =========================================================================

    private final JavaPlugin plugin;
    private final Logger logger;

    /** Tracks which open inventory belongs to which player + what mode/page. */
    private final Map<UUID, MenuSession> openMenus = new HashMap<>();

    private enum MenuType { SELL_CATEGORIES, SELL_ITEM_LIST, SELL_CONFIRM, TRASH_STORAGE, SELL_INVENTORY }

    private static class MenuSession {
        final MenuType type;
        final String   categoryId;   // Used for SELL_ITEM_LIST / SELL_CONFIRM
        final int      page;
        final int      targetSlot;   // Used for TRASH_CONFIRM / SELL_CONFIRM
        final Material targetMaterial;

        MenuSession(MenuType type, String categoryId, int page, int targetSlot, Material targetMaterial) {
            this.type           = type;
            this.categoryId     = categoryId;
            this.page           = page;
            this.targetSlot     = targetSlot;
            this.targetMaterial = targetMaterial;
        }
    }

    public SellTrashSystem(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        logger.info("[SellTrashSystem] Loaded.");
    }

    // =========================================================================
    // MONEY HELPERS
    // =========================================================================

    /** Mirrors getPlayerMoney() — reads the "Money" scoreboard objective. */
    public static int getPlayerMoney(Player player) {
        try {
            org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective("Money");
            if (obj == null) return 0;
            Score score = obj.getScore(player.getName());
            return score.isScoreSet() ? score.getScore() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Mirrors setPlayerMoney(). */
    public static void setPlayerMoney(Player player, int amount) {
        try {
            org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective("Money");
            if (obj == null) {
                obj = board.registerNewObjective("Money", "dummy", "Money");
            }
            obj.getScore(player.getName()).setScore(Math.max(0, amount));
        } catch (Exception ignored) {}
    }

    /** Mirrors addPlayerMoney(). */
    public static void addPlayerMoney(Player player, int amount) {
        setPlayerMoney(player, getPlayerMoney(player) + amount);
    }

    // =========================================================================
    // CRATE KEY CHECK
    // =========================================================================

    /**
     * Returns true if the item has the lore line "§r§7Crate Key".
     * Mirrors isCrateKey() in sell_trash_system.js.
     */
    private static boolean isCrateKey(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        List<String> lore = item.getItemMeta().getLore();
        if (lore == null) return false;
        return lore.stream().anyMatch(l -> l.equals("§r§7Crate Key"));
    }

    // =========================================================================
    // SELLABLE ITEMS SCAN
    // =========================================================================

    private static class SellEntry {
        final int      slot;
        final ItemStack item;
        final SellItem  data;
        final int       totalValue;

        SellEntry(int slot, ItemStack item, SellItem data) {
            this.slot       = slot;
            this.item       = item;
            this.data       = data;
            this.totalValue = data.sellPrice * item.getAmount();
        }
    }

    private static List<SellEntry> getPlayerSellableItems(Player player) {
        List<SellEntry> found = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;
            if (isCrateKey(item)) continue;
            SellItem data = SELLABLE_ITEMS.get(item.getType());
            if (data != null) found.add(new SellEntry(i, item, data));
        }
        return found;
    }

    // =========================================================================
    // SELL ALL
    // Mirrors sellAllItems() in sell_trash_system.js
    // =========================================================================

    public void sellAllItems(Player player) {
        List<SellEntry> toSell = getPlayerSellableItems(player);
        if (toSell.isEmpty()) {
            player.sendMessage("§cYou have no sellable items in your inventory!");
            return;
        }

        int totalEarned = 0;
        List<String> lines = new ArrayList<>();
        lines.add("§6§lSELL RECEIPT");

        // Reverse order to avoid index shifting
        for (int i = toSell.size() - 1; i >= 0; i--) {
            SellEntry e = toSell.get(i);
            int earned = e.data.sellPrice * e.item.getAmount();
            if (lines.size() <= 8) {
                lines.add("§7  " + e.item.getAmount() + "x " + e.data.name + " §8→ §a$" + String.format("%,d", earned));
            } else if (lines.size() == 9) {
                lines.add("§7  ...and more items");
            }
            totalEarned += earned;
            player.getInventory().setItem(e.slot, null);
        }

        addPlayerMoney(player, totalEarned);
        lines.add("§e§lTotal Earned: §a§l$" + String.format("%,d", totalEarned));
        lines.add("§7New Balance: §a$" + String.format("%,d", getPlayerMoney(player)));

        for (String line : lines) player.sendMessage(line);
    }

    // =========================================================================
    // SELL CATEGORY MENU (54-slot chest)
    // Mirrors showSellCategoryMenu() in sell_trash_system.js
    // =========================================================================

    public void showSellCategoryMenu(Player player) {
        int balance     = getPlayerMoney(player);
        List<SellEntry> sellable  = getPlayerSellableItems(player);
        int totalValue  = sellable.stream().mapToInt(e -> e.totalValue).sum();

        Inventory inv = Bukkit.createInventory(null, 54,
                "§6§lSELL MENU §8| §eBalance: §a$" + String.format("%,d", balance));

        // Fill with black glass
        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Sell All (slot 4)
        if (!sellable.isEmpty()) {
            inv.setItem(4, makeItem(Material.EMERALD, "§a§lSELL ALL", Arrays.asList(
                    "§7" + sellable.size() + " item type(s) found",
                    "§7Value: §a$" + String.format("%,d", totalValue),
                    "§eClick to sell everything!")));
        } else {
            inv.setItem(4, makeItem(Material.BARRIER, "§7SELL ALL",
                    Collections.singletonList("§cNo sellable items found")));
        }

        // Category buttons
        int[] catSlots = {11, 12, 13, 14, 15, 21, 22, 23};
        for (int i = 0; i < Math.min(SELL_CATEGORIES.size(), catSlots.length); i++) {
            SellCategory cat = SELL_CATEGORIES.get(i);
            final String catId = cat.id;
            int invCount = (int) sellable.stream().filter(e -> e.data.category.equals(catId)).count();
            inv.setItem(catSlots[i], makeItem(cat.icon, cat.displayName, Arrays.asList(
                    "§7" + cat.items.size() + " items",
                    invCount > 0 ? "§a" + invCount + " in inventory" : "§7Browse items")));
        }

        // Close (slot 49)
        inv.setItem(49, makeItem(Material.BARRIER, "§cClose",
                Collections.singletonList("§7Exit sell menu")));

        openMenus.put(player.getUniqueId(),
                new MenuSession(MenuType.SELL_CATEGORIES, null, 0, -1, null));
        player.openInventory(inv);
    }

    // =========================================================================
    // SELL ITEM LIST (paginated)
    // Mirrors showSellItemList() in sell_trash_system.js
    // =========================================================================

    private static final int ITEMS_PER_PAGE = 28; // slots 10-17, 19-26, 28-35, 37-44

    public void showSellItemList(Player player, String categoryId, int page) {
        SellCategory cat = SELL_CATEGORIES.stream()
                .filter(c -> c.id.equals(categoryId)).findFirst().orElse(null);
        if (cat == null) return;

        int balance = getPlayerMoney(player);
        int totalPages = (int) Math.ceil((double) cat.items.size() / ITEMS_PER_PAGE);
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, 54,
                "§6" + cat.displayName + " §8(p." + (page + 1) + ") §8| §a$" + String.format("%,d", balance));

        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Item slots (inner 4×7 = 28)
        int[] itemSlots = new int[ITEMS_PER_PAGE];
        int si = 0;
        for (int row = 1; row <= 4; row++)
            for (int col = 1; col <= 7; col++)
                itemSlots[si++] = row * 9 + col;

        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= cat.items.size()) break;
            SellItem sellItem = cat.items.get(idx);
            inv.setItem(itemSlots[i], makeItem(sellItem.material, sellItem.name, Arrays.asList(
                    "§7Sell Price: §a$" + sellItem.sellPrice + "§7/ea",
                    "§eClick to sell from inventory")));
        }

        // Nav row (row 0)
        inv.setItem(0, makeItem(Material.ARROW, "§7← Back",
                Collections.singletonList("§7Return to sell menu")));
        if (page > 0) {
            inv.setItem(3, makeItem(Material.ARROW, "§7◄ Prev Page", Collections.emptyList()));
        }
        if (page < totalPages - 1) {
            inv.setItem(5, makeItem(Material.ARROW, "§7Next Page ►", Collections.emptyList()));
        }
        inv.setItem(8, makeItem(Material.BARRIER, "§cClose", Collections.emptyList()));

        openMenus.put(player.getUniqueId(),
                new MenuSession(MenuType.SELL_ITEM_LIST, categoryId, page, -1, null));
        player.openInventory(inv);
    }

    // =========================================================================
    // TRASH MENU — now a "storage" container: shift-click or drag items in,
    // and whatever is still sitting in it when the menu closes is destroyed.
    // No confirmation screen — putting an item in and walking away IS the
    // confirmation, so we let Bukkit handle all click/drag movement normally
    // (see the TRASH_STORAGE early-return in onInventoryClick below) and only
    // step in on close to wipe out whatever remains.
    // =========================================================================

    private static final String TRASH_TITLE = "§c§lTRASH §8| §7Drop items here — wiped when closed";
    private static final int    TRASH_SIZE  = 54;

    public void showTrashMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, TRASH_SIZE, TRASH_TITLE);

        openMenus.put(player.getUniqueId(),
                new MenuSession(MenuType.TRASH_STORAGE, null, 0, -1, null));
        player.openInventory(inv);
    }

    /** Destroys everything currently sitting in a just-closed trash storage menu. */
    private void wipeTrashContents(Player player, Inventory inv) {
        int destroyedStacks = 0;
        int destroyedItems  = 0;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType() == Material.AIR) continue;
            destroyedStacks++;
            destroyedItems += item.getAmount();
            inv.setItem(i, null);
        }
        if (destroyedStacks > 0) {
            player.sendMessage("§c\uD83D\uDDD1 Trashed " + destroyedItems + " item(s) across "
                    + destroyedStacks + " stack(s).");
        }
    }

    // =========================================================================
    // SELL INVENTORY MENU (inventory mirror)
    // Mirrors showSellInventoryMenu() in sell_trash_system.js
    // =========================================================================

    public void showSellInventoryMenu(Player player) {
        int balance = getPlayerMoney(player);

        int totalSellableValue = 0;
        int sellableCount      = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() == Material.AIR || isCrateKey(item)) continue;
            SellItem data = SELLABLE_ITEMS.get(item.getType());
            if (data != null) {
                totalSellableValue += data.sellPrice * item.getAmount();
                sellableCount++;
            }
        }

        Inventory inv = Bukkit.createInventory(null, 54,
                "§6§lSELL INVENTORY §8| §eBalance: §a$" + String.format("%,d", balance));

        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Mirror player inventory slots 0-35
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() == Material.AIR) {
                inv.setItem(i, filler);
                continue;
            }
            if (isCrateKey(item)) {
                String dn = item.hasItemMeta() && item.getItemMeta().hasDisplayName()
                        ? item.getItemMeta().getDisplayName() : friendlyName(item.getType());
                inv.setItem(i, makeItem(item.getType(), "§c" + dn, Arrays.asList(
                        "§7Amount: §e" + item.getAmount(),
                        "§c🔑 Key — Cannot be sold")));
                continue;
            }
            SellItem sd = SELLABLE_ITEMS.get(item.getType());
            String rawName = friendlyName(item.getType());
            if (sd != null) {
                int stackValue = sd.sellPrice * item.getAmount();
                inv.setItem(i, makeItem(item.getType(), "§a" + rawName, Arrays.asList(
                        "§7Amount: §e" + item.getAmount(),
                        "§7Price: §a$" + sd.sellPrice + "§7/ea",
                        "§eClick to sell for §a$" + String.format("%,d", stackValue))));
            } else {
                inv.setItem(i, makeItem(item.getType(), "§7" + rawName, Arrays.asList(
                        "§7Amount: §e" + item.getAmount(),
                        "§7Not sellable")));
            }
        }

        // Bottom row
        inv.setItem(36, makeItem(Material.GOLD_INGOT, "§eBalance",
                Collections.singletonList("§a$" + String.format("%,d", balance))));

        if (sellableCount > 0) {
            inv.setItem(40, makeItem(Material.EMERALD, "§a§lSELL ALL", Arrays.asList(
                    "§7" + sellableCount + " stack(s) found",
                    "§7Total: §a$" + String.format("%,d", totalSellableValue),
                    "§eClick to sell everything")));
        } else {
            inv.setItem(40, makeItem(Material.BARRIER, "§7SELL ALL",
                    Collections.singletonList("§cNo sellable items")));
        }

        inv.setItem(44, makeItem(Material.BARRIER, "§cClose",
                Collections.singletonList("§7Exit sell menu")));

        openMenus.put(player.getUniqueId(),
                new MenuSession(MenuType.SELL_INVENTORY, null, 0, -1, null));
        player.openInventory(inv);
    }

    // =========================================================================
    // CLICK HANDLER
    // =========================================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        UUID uid = player.getUniqueId();

        MenuSession session = openMenus.get(uid);
        if (session == null) return;

        if (session.type == MenuType.TRASH_STORAGE) {
            // Free-form container: let Bukkit handle pickup/place/shift-click/
            // drag normally. We only act when the menu closes (see below).
            return;
        }

        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
        int slot = event.getRawSlot();

        switch (session.type) {

            case SELL_CATEGORIES:
                handleSellCategoriesClick(player, slot, session);
                break;

            case SELL_ITEM_LIST:
                handleSellItemListClick(player, slot, session);
                break;

            case SELL_INVENTORY:
                handleSellInventoryClick(player, slot, session);
                break;
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uid = event.getPlayer().getUniqueId();
        MenuSession session = openMenus.remove(uid);
        if (session != null && session.type == MenuType.TRASH_STORAGE
                && event.getPlayer() instanceof Player player) {
            wipeTrashContents(player, event.getInventory());
        }
    }

    // ─── SELL_CATEGORIES clicks ───────────────────────────────────────────────

    private void handleSellCategoriesClick(Player player, int slot, MenuSession session) {
        if (slot == 4) { // Sell All
            player.closeInventory();
            sellAllItems(player);
            return;
        }
        if (slot == 49) { player.closeInventory(); return; }

        int[] catSlots = {11, 12, 13, 14, 15, 21, 22, 23};
        for (int i = 0; i < catSlots.length; i++) {
            if (slot == catSlots[i] && i < SELL_CATEGORIES.size()) {
                final SellCategory cat = SELL_CATEGORIES.get(i);
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> showSellItemList(player, cat.id, 0));
                return;
            }
        }
    }

    // ─── SELL_ITEM_LIST clicks ────────────────────────────────────────────────

    private void handleSellItemListClick(Player player, int slot, MenuSession session) {
        String catId = session.categoryId;
        int page     = session.page;

        SellCategory cat = SELL_CATEGORIES.stream()
                .filter(c -> c.id.equals(catId)).findFirst().orElse(null);
        if (cat == null) return;

        if (slot == 0) { // Back
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> showSellCategoryMenu(player));
            return;
        }
        if (slot == 3 && page > 0) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> showSellItemList(player, catId, page - 1));
            return;
        }
        if (slot == 5) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> showSellItemList(player, catId, page + 1));
            return;
        }
        if (slot == 8) { player.closeInventory(); return; }

        // Item slot clicked — sell that item type from inventory
        int[] itemSlots = new int[ITEMS_PER_PAGE];
        int si = 0;
        for (int row = 1; row <= 4; row++)
            for (int col = 1; col <= 7; col++)
                itemSlots[si++] = row * 9 + col;

        for (int i = 0; i < itemSlots.length; i++) {
            if (slot == itemSlots[i]) {
                int idx = page * ITEMS_PER_PAGE + i;
                if (idx < cat.items.size()) {
                    SellItem sellItem = cat.items.get(idx);
                    // Sell all of this material from inventory
                    int earned = 0;
                    for (int invSlot = 0; invSlot < 36; invSlot++) {
                        ItemStack item = player.getInventory().getItem(invSlot);
                        if (item != null && item.getType() == sellItem.material) {
                            earned += sellItem.sellPrice * item.getAmount();
                            player.getInventory().setItem(invSlot, null);
                        }
                    }
                    if (earned > 0) {
                        addPlayerMoney(player, earned);
                        player.sendMessage("§a✓ Sold §f" + sellItem.name
                                + " §7→ §a$" + String.format("%,d", earned)
                                + " §7| Balance: §a$" + String.format("%,d", getPlayerMoney(player)));
                    } else {
                        player.sendMessage("§cYou don't have any " + sellItem.name + "§c in your inventory.");
                    }
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> showSellItemList(player, catId, page));
                }
                return;
            }
        }
    }

    // ─── SELL_INVENTORY clicks ────────────────────────────────────────────────

    private void handleSellInventoryClick(Player player, int slot, MenuSession session) {
        if (slot == 44) { player.closeInventory(); return; }
        if (slot == 40) { // Sell All
            sellAllItems(player);
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> showSellInventoryMenu(player));
            return;
        }
        if (slot >= 0 && slot <= 35) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType() == Material.AIR) return;
            if (isCrateKey(item)) {
                player.sendMessage("§c🔑 Keys cannot be sold!");
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> showSellInventoryMenu(player));
                return;
            }
            SellItem sd = SELLABLE_ITEMS.get(item.getType());
            if (sd == null) {
                player.sendMessage("§cThis item cannot be sold.");
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> showSellInventoryMenu(player));
                return;
            }
            int earned = sd.sellPrice * item.getAmount();
            player.getInventory().setItem(slot, null);
            addPlayerMoney(player, earned);
            player.sendMessage("§a✓ Sold §e" + item.getAmount() + "x §f" + friendlyName(item.getType())
                    + " §7→ §a$" + String.format("%,d", earned)
                    + " §7| Balance: §a$" + String.format("%,d", getPlayerMoney(player)));
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> showSellInventoryMenu(player));
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private static ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Converts a Material enum name to a friendly display name. */
    private static String friendlyName(Material mat) {
        String name = mat.name().replace('_', ' ');
        StringBuilder sb = new StringBuilder();
        for (String word : name.split(" ")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1).toLowerCase());
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }
}

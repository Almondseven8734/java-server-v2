package com.skyblock.shop;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
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
 * Shop System
 *
 * Translated from shop_system.js.
 *
 * Provides a multi-category shop GUI (/shop) matching the Bedrock ChestFormData
 * layout, implemented using standard Bukkit Inventory GUIs.
 *
 * Supported commands (register in plugin.yml):
 *   /shop   — open the shop
 *   /bal    — check balance (alias for BalCommand, also handled here)
 *
 * Money is stored on the "Money" scoreboard objective (dummy).
 *
 * Categories (matches SHOP_DATA in shop_system.js):
 *   building_blocks, tools, food, colored_blocks, misc,
 *   nature, minerals, redstone, potions, mob_drops, dyes, decorations
 */
public class ShopSystem implements CommandExecutor, Listener {

    // =========================================================================
    // SHOP ITEM / CATEGORY DATA
    // =========================================================================

    public static class ShopItem {
        public final String   name;
        public final Material material;
        public final int      price;
        public final int      maxStack;

        public ShopItem(String name, String itemId, int price, int maxStack) {
            this.name     = name;
            this.material = parseMat(itemId);
            this.price    = price;
            this.maxStack = maxStack;
        }

        private static Material parseMat(String id) {
            String k = id.replace("minecraft:", "").toUpperCase();
            // Bedrock → Java name mappings
            switch (k) {
                case "STONEBRICK":             return Material.STONE_BRICKS;
                case "BRICK_BLOCK":            return Material.BRICKS;
                case "QUARTZ_ORE":             return Material.NETHER_QUARTZ_ORE;
                case "STONECUTTER_BLOCK":      return Material.STONECUTTER;
                case "NOTEBLOCK":              return Material.NOTE_BLOCK;
                case "GOLDEN_RAIL":            return Material.POWERED_RAIL;
                case "REEDS":                  return Material.SUGAR_CANE;
                case "MELON":                  return Material.MELON_SLICE;
                case "MELON_BLOCK":            return Material.MELON;
                case "DEADBUSH":               return Material.DEAD_BUSH;
                case "HOUSTONIA":              return Material.AZURE_BLUET;
                case "WATERLILY":              return Material.LILY_PAD;
                case "YELLOW_FLOWER":          return Material.DANDELION;
                case "RED_FLOWER":             return Material.POPPY;
                case "MUTTONCOOK":
                case "MUTTONCOOKED":           return Material.COOKED_MUTTON;
                case "COOKED_FISH":            return Material.COOKED_COD;
                case "APPLEENCHANTED":         return Material.ENCHANTED_GOLDEN_APPLE;
                case "FRAME":                  return Material.ITEM_FRAME;
                case "GLOW_FRAME":             return Material.GLOW_ITEM_FRAME;
                case "TOTEM":                  return Material.TOTEM_OF_UNDYING;
                case "TURTLE_SHELL_PIECE":     return Material.TURTLE_SCUTE;
                case "RECORD_13":              return Material.MUSIC_DISC_13;
                case "RECORD_CAT":             return Material.MUSIC_DISC_CAT;
                case "RECORD_BLOCKS":          return Material.MUSIC_DISC_BLOCKS;
                case "RECORD_CHIRP":           return Material.MUSIC_DISC_CHIRP;
                case "RECORD_FAR":             return Material.MUSIC_DISC_FAR;
                case "RECORD_MALL":            return Material.MUSIC_DISC_MALL;
                case "RECORD_MELLOHI":         return Material.MUSIC_DISC_MELLOHI;
                case "RECORD_STAL":            return Material.MUSIC_DISC_STAL;
                case "RECORD_STRAD":           return Material.MUSIC_DISC_STRAD;
                case "RECORD_WARD":            return Material.MUSIC_DISC_WARD;
                case "RECORD_11":              return Material.MUSIC_DISC_11;
                case "RECORD_WAIT":            return Material.MUSIC_DISC_WAIT;
                case "RECORD_PIGSTEP":         return Material.MUSIC_DISC_PIGSTEP;
                case "RECORD_5":              return Material.MUSIC_DISC_5;
                case "RECORD_OTHERSIDE":       return Material.MUSIC_DISC_OTHERSIDE;
                case "RECORD_RELIC":           return Material.MUSIC_DISC_RELIC;
                case "STONE_BLOCK_SLAB":       return Material.STONE_SLAB;
                case "COBBLESTONE_SLAB":       return Material.COBBLESTONE_SLAB;
                case "STONE_STAIRS":           return Material.COBBLESTONE_STAIRS;
                case "DISC_FRAGMENT_5":        return Material.DISC_FRAGMENT_5;
                case "HEAVY_WEIGHTED_PRESSURE_PLATE": return Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
                case "LIGHT_WEIGHTED_PRESSURE_PLATE": return Material.LIGHT_WEIGHTED_PRESSURE_PLATE;
                default:
                    try { return Material.valueOf(k); }
                    catch (IllegalArgumentException e) { return Material.PAPER; }
            }
        }
    }

    public static class ShopCategory {
        public final String         id;
        public final String         displayName;
        public final Material       icon;
        public final List<ShopItem> items = new ArrayList<>();

        public ShopCategory(String id, String displayName, Material icon) {
            this.id          = id;
            this.displayName = displayName;
            this.icon        = icon;
        }
    }

    // ─── Master category list ─────────────────────────────────────────────────
    public static final List<ShopCategory> CATEGORIES = new ArrayList<>();

    static {

        // ── Building Blocks ───────────────────────────────────────────────────
        ShopCategory bb = new ShopCategory("building_blocks", "§6Building Blocks", Material.STONE);
        addI(bb,"§fStone","minecraft:stone",10,64);
        addI(bb,"§fOak Planks","minecraft:oak_planks",5,64);
        addI(bb,"§fSpruce Planks","minecraft:spruce_planks",5,64);
        addI(bb,"§fBirch Planks","minecraft:birch_planks",5,64);
        addI(bb,"§fJungle Planks","minecraft:jungle_planks",5,64);
        addI(bb,"§fAcacia Planks","minecraft:acacia_planks",5,64);
        addI(bb,"§fDark Oak Planks","minecraft:dark_oak_planks",5,64);
        addI(bb,"§fCobblestone","minecraft:cobblestone",2,64);
        addI(bb,"§fGlass","minecraft:glass",15,64);
        addI(bb,"§fBrick Block","minecraft:brick_block",20,64);
        addI(bb,"§fQuartz Block","minecraft:quartz_block",30,64);
        addI(bb,"§fSandstone","minecraft:sandstone",8,64);
        addI(bb,"§fSmooth Stone","minecraft:smooth_stone",12,64);
        addI(bb,"§fWhite Concrete","minecraft:white_concrete",18,64);
        addI(bb,"§fBlack Concrete","minecraft:black_concrete",18,64);
        addI(bb,"§fRed Concrete","minecraft:red_concrete",18,64);
        addI(bb,"§fBlue Concrete","minecraft:blue_concrete",18,64);
        addI(bb,"§fGreen Concrete","minecraft:green_concrete",18,64);
        addI(bb,"§fYellow Concrete","minecraft:yellow_concrete",18,64);
        addI(bb,"§fOak Log","minecraft:oak_log",8,64);
        addI(bb,"§fSpruce Log","minecraft:spruce_log",8,64);
        addI(bb,"§fBirch Log","minecraft:birch_log",8,64);
        addI(bb,"§fTerracotta","minecraft:terracotta",10,64);
        addI(bb,"§fPolished Andesite","minecraft:polished_andesite",12,64);
        addI(bb,"§fPolished Diorite","minecraft:polished_diorite",12,64);
        addI(bb,"§fPolished Granite","minecraft:polished_granite",12,64);
        addI(bb,"§fPrismarine","minecraft:prismarine",25,64);
        addI(bb,"§fPurpur Block","minecraft:purpur_block",22,64);
        addI(bb,"§fEnd Stone","minecraft:end_stone",20,64);
        addI(bb,"§fNether Brick","minecraft:nether_brick",18,64);
        addI(bb,"§fRed Nether Brick","minecraft:red_nether_brick",18,64);
        addI(bb,"§fBlackstone","minecraft:blackstone",15,64);
        addI(bb,"§fBasalt","minecraft:basalt",12,64);
        addI(bb,"§fDeepslate","minecraft:deepslate",14,64);
        addI(bb,"§fDiorite","minecraft:diorite",8,64);
        addI(bb,"§fAndesite","minecraft:andesite",8,64);
        addI(bb,"§fGranite","minecraft:granite",8,64);
        addI(bb,"§fObsidian","minecraft:obsidian",50,64);
        addI(bb,"§fCrying Obsidian","minecraft:crying_obsidian",60,64);
        addI(bb,"§fNetherrack","minecraft:netherrack",5,64);
        addI(bb,"§fSoul Sand","minecraft:soul_sand",10,64);
        addI(bb,"§fCrimson Planks","minecraft:crimson_planks",6,64);
        addI(bb,"§fWarped Planks","minecraft:warped_planks",6,64);
        addI(bb,"§fMangrove Planks","minecraft:mangrove_planks",6,64);
        addI(bb,"§dCherry Planks","minecraft:cherry_planks",7,64);
        addI(bb,"§fBamboo Planks","minecraft:bamboo_planks",5,64);
        addI(bb,"§fCrimson Stem","minecraft:crimson_stem",9,64);
        addI(bb,"§fWarped Stem","minecraft:warped_stem",9,64);
        addI(bb,"§fAcacia Log","minecraft:acacia_log",8,64);
        addI(bb,"§fDark Oak Log","minecraft:dark_oak_log",8,64);
        addI(bb,"§fJungle Log","minecraft:jungle_log",8,64);
        addI(bb,"§fMangrove Log","minecraft:mangrove_log",9,64);
        addI(bb,"§dCherry Log","minecraft:cherry_log",9,64);
        addI(bb,"§fStone Bricks","minecraft:stonebrick",15,64);
        addI(bb,"§fMossy Stone Bricks","minecraft:mossy_stone_bricks",18,64);
        addI(bb,"§fCracked Stone Bricks","minecraft:cracked_stone_bricks",16,64);
        addI(bb,"§fChiseled Stone Bricks","minecraft:chiseled_stone_bricks",20,64);
        addI(bb,"§fMossy Cobblestone","minecraft:mossy_cobblestone",5,64);
        addI(bb,"§fCobbled Deepslate","minecraft:cobbled_deepslate",16,64);
        addI(bb,"§fPolished Deepslate","minecraft:polished_deepslate",20,64);
        addI(bb,"§fDeepslate Bricks","minecraft:deepslate_bricks",22,64);
        addI(bb,"§fDeepslate Tiles","minecraft:deepslate_tiles",24,64);
        addI(bb,"§fChiseled Deepslate","minecraft:chiseled_deepslate",25,64);
        addI(bb,"§fPolished Blackstone","minecraft:polished_blackstone",18,64);
        addI(bb,"§fPolished Blackstone Bricks","minecraft:polished_blackstone_bricks",22,64);
        addI(bb,"§fMagma Block","minecraft:magma",15,64);
        CATEGORIES.add(bb);

        // ── Tools & Weapons ───────────────────────────────────────────────────
        ShopCategory tw = new ShopCategory("tools", "§aTools & Weapons", Material.IRON_SWORD);
        addI(tw,"§fIron Sword","minecraft:iron_sword",150,1);
        addI(tw,"§fIron Pickaxe","minecraft:iron_pickaxe",200,1);
        addI(tw,"§fIron Axe","minecraft:iron_axe",200,1);
        addI(tw,"§fIron Shovel","minecraft:iron_shovel",100,1);
        addI(tw,"§fIron Hoe","minecraft:iron_hoe",80,1);
        addI(tw,"§fStone Sword","minecraft:stone_sword",60,1);
        addI(tw,"§fStone Pickaxe","minecraft:stone_pickaxe",70,1);
        addI(tw,"§fStone Axe","minecraft:stone_axe",70,1);
        addI(tw,"§fStone Shovel","minecraft:stone_shovel",40,1);
        addI(tw,"§fStone Hoe","minecraft:stone_hoe",30,1);
        addI(tw,"§6Golden Sword","minecraft:golden_sword",120,1);
        addI(tw,"§6Golden Pickaxe","minecraft:golden_pickaxe",140,1);
        addI(tw,"§6Golden Axe","minecraft:golden_axe",140,1);
        addI(tw,"§6Golden Shovel","minecraft:golden_shovel",80,1);
        addI(tw,"§6Golden Hoe","minecraft:golden_hoe",60,1);
        addI(tw,"§bDiamond Sword","minecraft:diamond_sword",800,1);
        addI(tw,"§bDiamond Pickaxe","minecraft:diamond_pickaxe",1000,1);
        addI(tw,"§bDiamond Axe","minecraft:diamond_axe",1000,1);
        addI(tw,"§bDiamond Shovel","minecraft:diamond_shovel",600,1);
        addI(tw,"§bDiamond Hoe","minecraft:diamond_hoe",500,1);
        addI(tw,"§5Netherite Sword","minecraft:netherite_sword",3000,1);
        addI(tw,"§5Netherite Pickaxe","minecraft:netherite_pickaxe",3500,1);
        addI(tw,"§5Netherite Axe","minecraft:netherite_axe",3500,1);
        addI(tw,"§5Netherite Shovel","minecraft:netherite_shovel",2500,1);
        addI(tw,"§5Netherite Hoe","minecraft:netherite_hoe",2000,1);
        addI(tw,"§fIron Helmet","minecraft:iron_helmet",200,1);
        addI(tw,"§fIron Chestplate","minecraft:iron_chestplate",300,1);
        addI(tw,"§fIron Leggings","minecraft:iron_leggings",250,1);
        addI(tw,"§fIron Boots","minecraft:iron_boots",150,1);
        addI(tw,"§bDiamond Helmet","minecraft:diamond_helmet",900,1);
        addI(tw,"§bDiamond Chestplate","minecraft:diamond_chestplate",1400,1);
        addI(tw,"§bDiamond Leggings","minecraft:diamond_leggings",1200,1);
        addI(tw,"§bDiamond Boots","minecraft:diamond_boots",700,1);
        addI(tw,"§5Netherite Helmet","minecraft:netherite_helmet",3000,1);
        addI(tw,"§5Netherite Chestplate","minecraft:netherite_chestplate",4500,1);
        addI(tw,"§5Netherite Leggings","minecraft:netherite_leggings",4000,1);
        addI(tw,"§5Netherite Boots","minecraft:netherite_boots",2500,1);
        addI(tw,"§fLeather Helmet","minecraft:leather_helmet",30,1);
        addI(tw,"§fLeather Chestplate","minecraft:leather_chestplate",50,1);
        addI(tw,"§fLeather Leggings","minecraft:leather_leggings",40,1);
        addI(tw,"§fLeather Boots","minecraft:leather_boots",25,1);
        addI(tw,"§6Golden Helmet","minecraft:golden_helmet",150,1);
        addI(tw,"§6Golden Chestplate","minecraft:golden_chestplate",220,1);
        addI(tw,"§6Golden Leggings","minecraft:golden_leggings",180,1);
        addI(tw,"§6Golden Boots","minecraft:golden_boots",110,1);
        addI(tw,"§fBow","minecraft:bow",100,1);
        addI(tw,"§fCrossbow","minecraft:crossbow",200,1);
        addI(tw,"§fArrow","minecraft:arrow",2,64);
        addI(tw,"§bTrident","minecraft:trident",1200,1);
        addI(tw,"§fShield","minecraft:shield",120,1);
        addI(tw,"§fFlint and Steel","minecraft:flint_and_steel",50,1);
        addI(tw,"§fFishing Rod","minecraft:fishing_rod",60,1);
        addI(tw,"§fShears","minecraft:shears",50,1);
        CATEGORIES.add(tw);

        // ── Food ─────────────────────────────────────────────────────────────
        ShopCategory food = new ShopCategory("food", "§cFood", Material.COOKED_BEEF);
        addI(food,"§fCooked Beef","minecraft:cooked_beef",8,64);
        addI(food,"§fBread","minecraft:bread",5,64);
        addI(food,"§fApple","minecraft:apple",5,64);
        addI(food,"§fGolden Apple","minecraft:golden_apple",50,64);
        addI(food,"§fCooked Porkchop","minecraft:cooked_porkchop",8,64);
        addI(food,"§6Enchanted Golden Apple","minecraft:appleEnchanted",500,64);
        addI(food,"§fCooked Chicken","minecraft:cooked_chicken",6,64);
        addI(food,"§fCooked Salmon","minecraft:cooked_salmon",7,64);
        addI(food,"§fCooked Cod","minecraft:cooked_fish",6,64);
        addI(food,"§fCooked Mutton","minecraft:muttonCooked",7,64);
        addI(food,"§fCooked Rabbit","minecraft:cooked_rabbit",7,64);
        addI(food,"§fBaked Potato","minecraft:baked_potato",4,64);
        addI(food,"§fCookie","minecraft:cookie",3,64);
        addI(food,"§fPumpkin Pie","minecraft:pumpkin_pie",6,64);
        addI(food,"§fCake","minecraft:cake",20,1);
        addI(food,"§fMelon Slice","minecraft:melon",2,64);
        addI(food,"§fCarrot","minecraft:carrot",3,64);
        addI(food,"§6Golden Carrot","minecraft:golden_carrot",30,64);
        addI(food,"§fPotato","minecraft:potato",2,64);
        addI(food,"§fBeetroot","minecraft:beetroot",3,64);
        addI(food,"§fMushroom Stew","minecraft:mushroom_stew",12,1);
        addI(food,"§fRabbit Stew","minecraft:rabbit_stew",15,1);
        addI(food,"§fBeetroot Soup","minecraft:beetroot_soup",12,1);
        addI(food,"§6Honey Bottle","minecraft:honey_bottle",18,16);
        addI(food,"§fDried Kelp","minecraft:dried_kelp",2,64);
        addI(food,"§fSweet Berries","minecraft:sweet_berries",4,64);
        addI(food,"§fGlow Berries","minecraft:glow_berries",6,64);
        addI(food,"§dChorus Fruit","minecraft:chorus_fruit",15,64);
        addI(food,"§fRaw Beef","minecraft:beef",4,64);
        addI(food,"§fRaw Chicken","minecraft:chicken",3,64);
        addI(food,"§fRaw Porkchop","minecraft:porkchop",4,64);
        CATEGORIES.add(food);

        // ── Colored Blocks ────────────────────────────────────────────────────
        ShopCategory col = new ShopCategory("colored_blocks", "§dColored Blocks", Material.PINK_WOOL);
        addI(col,"§fWhite Wool","minecraft:white_wool",5,64);
        addI(col,"§fOrange Wool","minecraft:orange_wool",5,64);
        addI(col,"§fMagenta Wool","minecraft:magenta_wool",5,64);
        addI(col,"§fLight Blue Wool","minecraft:light_blue_wool",5,64);
        addI(col,"§fYellow Wool","minecraft:yellow_wool",5,64);
        addI(col,"§fLime Wool","minecraft:lime_wool",5,64);
        addI(col,"§fPink Wool","minecraft:pink_wool",5,64);
        addI(col,"§fGray Wool","minecraft:gray_wool",5,64);
        addI(col,"§fLight Gray Wool","minecraft:light_gray_wool",5,64);
        addI(col,"§fCyan Wool","minecraft:cyan_wool",5,64);
        addI(col,"§fPurple Wool","minecraft:purple_wool",5,64);
        addI(col,"§fBlue Wool","minecraft:blue_wool",5,64);
        addI(col,"§fBrown Wool","minecraft:brown_wool",5,64);
        addI(col,"§fGreen Wool","minecraft:green_wool",5,64);
        addI(col,"§fRed Wool","minecraft:red_wool",5,64);
        addI(col,"§fBlack Wool","minecraft:black_wool",5,64);
        addI(col,"§fWhite Stained Glass","minecraft:white_stained_glass",20,64);
        addI(col,"§fOrange Stained Glass","minecraft:orange_stained_glass",20,64);
        addI(col,"§fMagenta Stained Glass","minecraft:magenta_stained_glass",20,64);
        addI(col,"§fLight Blue Stained Glass","minecraft:light_blue_stained_glass",20,64);
        addI(col,"§fYellow Stained Glass","minecraft:yellow_stained_glass",20,64);
        addI(col,"§fLime Stained Glass","minecraft:lime_stained_glass",20,64);
        addI(col,"§fPink Stained Glass","minecraft:pink_stained_glass",20,64);
        addI(col,"§fGray Stained Glass","minecraft:gray_stained_glass",20,64);
        addI(col,"§fCyan Stained Glass","minecraft:cyan_stained_glass",20,64);
        addI(col,"§fPurple Stained Glass","minecraft:purple_stained_glass",20,64);
        addI(col,"§fBlue Stained Glass","minecraft:blue_stained_glass",20,64);
        addI(col,"§fBrown Stained Glass","minecraft:brown_stained_glass",20,64);
        addI(col,"§fGreen Stained Glass","minecraft:green_stained_glass",20,64);
        addI(col,"§fBlack Stained Glass","minecraft:black_stained_glass",20,64);
        addI(col,"§fOrange Concrete","minecraft:orange_concrete",18,64);
        addI(col,"§fMagenta Concrete","minecraft:magenta_concrete",18,64);
        addI(col,"§fLight Blue Concrete","minecraft:light_blue_concrete",18,64);
        addI(col,"§fLime Concrete","minecraft:lime_concrete",18,64);
        addI(col,"§fPink Concrete","minecraft:pink_concrete",18,64);
        addI(col,"§fGray Concrete","minecraft:gray_concrete",18,64);
        addI(col,"§fLight Gray Concrete","minecraft:light_gray_concrete",18,64);
        addI(col,"§fCyan Concrete","minecraft:cyan_concrete",18,64);
        addI(col,"§fPurple Concrete","minecraft:purple_concrete",18,64);
        addI(col,"§fBrown Concrete","minecraft:brown_concrete",18,64);
        addI(col,"§fWhite Terracotta","minecraft:white_terracotta",12,64);
        addI(col,"§fOrange Terracotta","minecraft:orange_terracotta",12,64);
        addI(col,"§fYellow Terracotta","minecraft:yellow_terracotta",12,64);
        addI(col,"§fRed Terracotta","minecraft:red_terracotta",12,64);
        addI(col,"§fWhite Carpet","minecraft:white_carpet",3,64);
        addI(col,"§fOrange Carpet","minecraft:orange_carpet",3,64);
        addI(col,"§fYellow Carpet","minecraft:yellow_carpet",3,64);
        addI(col,"§fRed Carpet","minecraft:red_carpet",3,64);
        addI(col,"§fBlack Carpet","minecraft:black_carpet",3,64);
        addI(col,"§fWhite Banner","minecraft:white_banner",15,16);
        addI(col,"§fRed Banner","minecraft:red_banner",15,16);
        addI(col,"§fBlue Banner","minecraft:blue_banner",15,16);
        addI(col,"§fYellow Banner","minecraft:yellow_banner",15,16);
        addI(col,"§fGreen Banner","minecraft:green_banner",15,16);
        addI(col,"§fBlack Banner","minecraft:black_banner",15,16);
        CATEGORIES.add(col);

        // ── Miscellaneous ─────────────────────────────────────────────────────
        ShopCategory misc = new ShopCategory("misc", "§7Miscellaneous", Material.NAME_TAG);
        addI(misc,"§fTorch","minecraft:torch",2,64);
        addI(misc,"§fLadder","minecraft:ladder",3,64);
        addI(misc,"§fChest","minecraft:chest",15,64);
        addI(misc,"§fCrafting Table","minecraft:crafting_table",10,64);
        addI(misc,"§fFurnace","minecraft:furnace",25,64);
        addI(misc,"§fSoul Torch","minecraft:soul_torch",3,64);
        addI(misc,"§fLantern","minecraft:lantern",8,64);
        addI(misc,"§fSoul Lantern","minecraft:soul_lantern",10,64);
        addI(misc,"§fCampfire","minecraft:campfire",20,64);
        addI(misc,"§fTrapped Chest","minecraft:trapped_chest",20,64);
        addI(misc,"§fBarrel","minecraft:barrel",18,64);
        addI(misc,"§fSmoker","minecraft:smoker",30,64);
        addI(misc,"§fBlast Furnace","minecraft:blast_furnace",50,64);
        addI(misc,"§fAnvil","minecraft:anvil",80,64);
        addI(misc,"§fEnchanting Table","minecraft:enchanting_table",200,64);
        addI(misc,"§fBookshelf","minecraft:bookshelf",20,64);
        addI(misc,"§5Ender Chest","minecraft:ender_chest",250,64);
        addI(misc,"§dShulker Box","minecraft:shulker_box",300,1);
        addI(misc,"§fHopper","minecraft:hopper",60,64);
        addI(misc,"§fDropper","minecraft:dropper",30,64);
        addI(misc,"§fDispenser","minecraft:dispenser",40,64);
        addI(misc,"§fBrewing Stand","minecraft:brewing_stand",70,64);
        addI(misc,"§fCauldron","minecraft:cauldron",40,64);
        addI(misc,"§aBeacon","minecraft:beacon",2000,64);
        addI(misc,"§6Bell","minecraft:bell",150,64);
        addI(misc,"§bConduit","minecraft:conduit",1000,64);
        addI(misc,"§fGrindstone","minecraft:grindstone",35,64);
        addI(misc,"§fSmithing Table","minecraft:smithing_table",30,64);
        addI(misc,"§fStonecutter","minecraft:stonecutter_block",20,64);
        addI(misc,"§fName Tag","minecraft:name_tag",80,64);
        addI(misc,"§fLead","minecraft:lead",25,64);
        addI(misc,"§fSaddle","minecraft:saddle",100,1);
        addI(misc,"§fBook","minecraft:book",10,64);
        addI(misc,"§fBook & Quill","minecraft:writable_book",20,1);
        addI(misc,"§fClock","minecraft:clock",50,64);
        addI(misc,"§fCompass","minecraft:compass",30,64);
        addI(misc,"§fBucket","minecraft:bucket",20,16);
        addI(misc,"§fWater Bucket","minecraft:water_bucket",25,1);
        addI(misc,"§fLava Bucket","minecraft:lava_bucket",35,1);
        addI(misc,"§fTNT","minecraft:tnt",50,64);
        addI(misc,"§5Elytra","minecraft:elytra",3000,1);
        CATEGORIES.add(misc);

        // ── Nature ────────────────────────────────────────────────────────────
        ShopCategory nat = new ShopCategory("nature", "§2Nature", Material.OAK_SAPLING);
        addI(nat,"§fDirt","minecraft:dirt",1,64);
        addI(nat,"§fGrass Block","minecraft:grass_block",3,64);
        addI(nat,"§fOak Sapling","minecraft:oak_sapling",5,64);
        addI(nat,"§fOak Leaves","minecraft:oak_leaves",2,64);
        addI(nat,"§fSand","minecraft:sand",2,64);
        addI(nat,"§fGravel","minecraft:gravel",2,64);
        addI(nat,"§fSpruce Sapling","minecraft:spruce_sapling",5,64);
        addI(nat,"§fBirch Sapling","minecraft:birch_sapling",5,64);
        addI(nat,"§fJungle Sapling","minecraft:jungle_sapling",6,64);
        addI(nat,"§fAcacia Sapling","minecraft:acacia_sapling",5,64);
        addI(nat,"§fDark Oak Sapling","minecraft:dark_oak_sapling",6,64);
        addI(nat,"§dCherry Sapling","minecraft:cherry_sapling",8,64);
        addI(nat,"§fMangrove Propagule","minecraft:mangrove_propagule",7,64);
        addI(nat,"§fDandelion","minecraft:yellow_flower",2,64);
        addI(nat,"§fPoppy","minecraft:red_flower",2,64);
        addI(nat,"§eSunflower","minecraft:sunflower",5,64);
        addI(nat,"§8Wither Rose","minecraft:wither_rose",20,64);
        addI(nat,"§fRed Mushroom","minecraft:red_mushroom",5,64);
        addI(nat,"§fBrown Mushroom","minecraft:brown_mushroom",5,64);
        addI(nat,"§4Crimson Fungus","minecraft:crimson_fungus",8,64);
        addI(nat,"§3Warped Fungus","minecraft:warped_fungus",8,64);
        addI(nat,"§fVine","minecraft:vine",3,64);
        addI(nat,"§fLily Pad","minecraft:waterlily",4,64);
        addI(nat,"§fKelp","minecraft:kelp",2,64);
        addI(nat,"§fBamboo","minecraft:bamboo",3,64);
        addI(nat,"§fCactus","minecraft:cactus",4,64);
        addI(nat,"§fSugar Cane","minecraft:reeds",2,64);
        addI(nat,"§fWheat Seeds","minecraft:wheat_seeds",2,64);
        addI(nat,"§fWheat","minecraft:wheat",3,64);
        addI(nat,"§fPumpkin","minecraft:pumpkin",6,64);
        addI(nat,"§fMelon","minecraft:melon_block",10,64);
        addI(nat,"§fMoss Block","minecraft:moss_block",6,64);
        addI(nat,"§fSculk","minecraft:sculk",8,64);
        addI(nat,"§dChorus Flower","minecraft:chorus_flower",15,64);
        CATEGORIES.add(nat);

        // ── Minerals ──────────────────────────────────────────────────────────
        ShopCategory min = new ShopCategory("minerals", "§bMinerals", Material.DIAMOND);
        addI(min,"§fCoal","minecraft:coal",5,64);
        addI(min,"§fIron Ingot","minecraft:iron_ingot",25,64);
        addI(min,"§fGold Ingot","minecraft:gold_ingot",50,64);
        addI(min,"§fDiamond","minecraft:diamond",100,64);
        addI(min,"§fEmerald","minecraft:emerald",80,64);
        addI(min,"§fLapis Lazuli","minecraft:lapis_lazuli",15,64);
        addI(min,"§fIron Nugget","minecraft:iron_nugget",3,64);
        addI(min,"§6Gold Nugget","minecraft:gold_nugget",6,64);
        addI(min,"§fCopper Ingot","minecraft:copper_ingot",10,64);
        addI(min,"§5Netherite Ingot","minecraft:netherite_ingot",550,64);
        addI(min,"§5Netherite Scrap","minecraft:netherite_scrap",120,64);
        addI(min,"§5Ancient Debris","minecraft:ancient_debris",300,64);
        addI(min,"§fNether Quartz","minecraft:quartz",8,64);
        addI(min,"§dAmethyst Shard","minecraft:amethyst_shard",10,64);
        addI(min,"§fRaw Iron","minecraft:raw_iron",12,64);
        addI(min,"§6Raw Gold","minecraft:raw_gold",25,64);
        addI(min,"§fRaw Copper","minecraft:raw_copper",5,64);
        addI(min,"§fDiamond Ore","minecraft:diamond_ore",80,64);
        addI(min,"§fFlint","minecraft:flint",3,64);
        addI(min,"§bPrismarine Crystals","minecraft:prismarine_crystals",15,64);
        addI(min,"§fPrismarine Shard","minecraft:prismarine_shard",10,64);
        CATEGORIES.add(min);

        // ── Redstone ──────────────────────────────────────────────────────────
        ShopCategory red = new ShopCategory("redstone", "§4Redstone", Material.REDSTONE);
        addI(red,"§fRedstone Dust","minecraft:redstone",10,64);
        addI(red,"§fRedstone Torch","minecraft:redstone_torch",12,64);
        addI(red,"§fRedstone Repeater","minecraft:repeater",20,64);
        addI(red,"§fRedstone Comparator","minecraft:comparator",25,64);
        addI(red,"§fPiston","minecraft:piston",30,64);
        addI(red,"§fSticky Piston","minecraft:sticky_piston",35,64);
        addI(red,"§fLever","minecraft:lever",5,64);
        addI(red,"§fStone Button","minecraft:stone_button",4,64);
        addI(red,"§fOak Button","minecraft:oak_button",3,64);
        addI(red,"§fObserver","minecraft:observer",40,64);
        addI(red,"§fDaylight Detector","minecraft:daylight_detector",35,64);
        addI(red,"§fTarget","minecraft:target",20,64);
        addI(red,"§fLightning Rod","minecraft:lightning_rod",50,64);
        addI(red,"§6Powered Rail","minecraft:golden_rail",30,64);
        addI(red,"§fRail","minecraft:rail",10,64);
        addI(red,"§fMinecart","minecraft:minecart",40,1);
        addI(red,"§fOak Trapdoor","minecraft:oak_trapdoor",8,64);
        addI(red,"§fIron Trapdoor","minecraft:iron_trapdoor",20,64);
        addI(red,"§fOak Door","minecraft:oak_door",8,64);
        addI(red,"§fIron Door","minecraft:iron_door",20,64);
        addI(red,"§fOak Fence","minecraft:oak_fence",5,64);
        addI(red,"§fIron Bars","minecraft:iron_bars",10,64);
        CATEGORIES.add(red);

        // ── Potions ───────────────────────────────────────────────────────────
        ShopCategory pot = new ShopCategory("potions", "§9Potions", Material.POTION);
        addI(pot,"§cPotion of Healing","minecraft:potion",40,1);
        addI(pot,"§cSplash Healing","minecraft:splash_potion",50,1);
        addI(pot,"§dPotion of Regeneration","minecraft:potion",80,1);
        addI(pot,"§4Potion of Strength","minecraft:potion",70,1);
        addI(pot,"§fPotion of Swiftness","minecraft:potion",50,1);
        addI(pot,"§6Potion of Fire Resistance","minecraft:potion",60,1);
        addI(pot,"§1Potion of Night Vision","minecraft:potion",45,1);
        addI(pot,"§7Potion of Invisibility","minecraft:potion",55,1);
        addI(pot,"§bPotion of Water Breathing","minecraft:potion",65,1);
        addI(pot,"§aPotion of Leaping","minecraft:potion",50,1);
        addI(pot,"§fGlass Bottle","minecraft:glass_bottle",5,64);
        addI(pot,"§6Blaze Powder","minecraft:blaze_powder",20,64);
        addI(pot,"§fGunpowder","minecraft:gunpowder",10,64);
        addI(pot,"§5Dragon's Breath","minecraft:dragon_breath",200,64);
        CATEGORIES.add(pot);

        // ── Mob Drops ─────────────────────────────────────────────────────────
        ShopCategory mob = new ShopCategory("mob_drops", "§eMob Drops", Material.BONE);
        addI(mob,"§fBone","minecraft:bone",8,64);
        addI(mob,"§fBone Meal","minecraft:bone_meal",5,64);
        addI(mob,"§fLeather","minecraft:leather",12,64);
        addI(mob,"§fFeather","minecraft:feather",6,64);
        addI(mob,"§fInk Sac","minecraft:ink_sac",8,64);
        addI(mob,"§fGlow Ink Sac","minecraft:glow_ink_sac",20,64);
        addI(mob,"§fSpider Eye","minecraft:spider_eye",8,64);
        addI(mob,"§aSlimeball","minecraft:slime_ball",15,64);
        addI(mob,"§6Magma Cream","minecraft:magma_cream",20,64);
        addI(mob,"§6Blaze Rod","minecraft:blaze_rod",35,64);
        addI(mob,"§5Ender Pearl","minecraft:ender_pearl",40,16);
        addI(mob,"§5Eye of Ender","minecraft:ender_eye",80,64);
        addI(mob,"§6Nether Star","minecraft:nether_star",1500,64);
        addI(mob,"§fGhast Tear","minecraft:ghast_tear",60,64);
        addI(mob,"§fPhantom Membrane","minecraft:phantom_membrane",50,64);
        addI(mob,"§fRabbit's Foot","minecraft:rabbit_foot",30,64);
        addI(mob,"§fRotten Flesh","minecraft:rotten_flesh",2,64);
        addI(mob,"§8Wither Skull","minecraft:wither_skeleton_skull",400,64);
        addI(mob,"§5Dragon Egg","minecraft:dragon_egg",9999,1);
        addI(mob,"§5Elytra","minecraft:elytra",3000,1);
        addI(mob,"§6Totem of Undying","minecraft:totem",1000,1);
        addI(mob,"§bNautilus Shell","minecraft:nautilus_shell",50,64);
        addI(mob,"§bHeart of the Sea","minecraft:heart_of_the_sea",500,64);
        addI(mob,"§dShulker Shell","minecraft:shulker_shell",150,64);
        CATEGORIES.add(mob);

        // ── Dyes ─────────────────────────────────────────────────────────────
        ShopCategory dye = new ShopCategory("dyes", "§eDyes & Colors", Material.RED_DYE);
        addI(dye,"§cRed Dye","minecraft:red_dye",4,64);
        addI(dye,"§6Orange Dye","minecraft:orange_dye",4,64);
        addI(dye,"§eYellow Dye","minecraft:yellow_dye",4,64);
        addI(dye,"§aLime Dye","minecraft:lime_dye",4,64);
        addI(dye,"§2Green Dye","minecraft:green_dye",4,64);
        addI(dye,"§3Cyan Dye","minecraft:cyan_dye",4,64);
        addI(dye,"§bLight Blue Dye","minecraft:light_blue_dye",4,64);
        addI(dye,"§9Blue Dye","minecraft:blue_dye",4,64);
        addI(dye,"§5Purple Dye","minecraft:purple_dye",4,64);
        addI(dye,"§dMagenta Dye","minecraft:magenta_dye",4,64);
        addI(dye,"§dPink Dye","minecraft:pink_dye",4,64);
        addI(dye,"§fWhite Dye","minecraft:white_dye",4,64);
        addI(dye,"§7Light Gray Dye","minecraft:light_gray_dye",4,64);
        addI(dye,"§8Gray Dye","minecraft:gray_dye",4,64);
        addI(dye,"§6Brown Dye","minecraft:brown_dye",4,64);
        addI(dye,"§0Black Dye","minecraft:black_dye",4,64);
        addI(dye,"§6Cocoa Beans","minecraft:cocoa_beans",5,64);
        addI(dye,"§fDisc 13","minecraft:record_13",100,1);
        addI(dye,"§fDisc Cat","minecraft:record_cat",100,1);
        addI(dye,"§6Disc Pigstep","minecraft:record_pigstep",250,1);
        addI(dye,"§6Disc Relic","minecraft:record_relic",200,1);
        CATEGORIES.add(dye);

        // ── Decorations ───────────────────────────────────────────────────────
        ShopCategory dec = new ShopCategory("decorations", "§bDecorations", Material.DECORATED_POT);
        addI(dec,"§fDecorated Pot","minecraft:decorated_pot",30,64);
        addI(dec,"§fChiseled Bookshelf","minecraft:chiseled_bookshelf",30,64);
        addI(dec,"§fCandle","minecraft:candle",8,64);
        addI(dec,"§fWhite Candle","minecraft:white_candle",8,64);
        addI(dec,"§fOrange Candle","minecraft:orange_candle",8,64);
        addI(dec,"§fYellow Candle","minecraft:yellow_candle",8,64);
        addI(dec,"§fRed Candle","minecraft:red_candle",8,64);
        addI(dec,"§fBlue Candle","minecraft:blue_candle",8,64);
        addI(dec,"§fGreen Candle","minecraft:green_candle",8,64);
        addI(dec,"§fOak Sign","minecraft:oak_sign",6,16);
        addI(dec,"§fSpruce Sign","minecraft:spruce_sign",6,16);
        addI(dec,"§fBirch Sign","minecraft:birch_sign",6,16);
        addI(dec,"§fOak Slab","minecraft:oak_slab",3,64);
        addI(dec,"§fStone Slab","minecraft:stone_block_slab",5,64);
        addI(dec,"§fCobblestone Slab","minecraft:cobblestone_slab",2,64);
        addI(dec,"§fOak Stairs","minecraft:oak_stairs",4,64);
        addI(dec,"§fCobblestone Stairs","minecraft:stone_stairs",3,64);
        addI(dec,"§fBrick Stairs","minecraft:brick_stairs",11,64);
        addI(dec,"§fStone Brick Stairs","minecraft:stone_brick_stairs",8,64);
        addI(dec,"§fSpruce Stairs","minecraft:spruce_stairs",4,64);
        addI(dec,"§fBirch Stairs","minecraft:birch_stairs",4,64);
        addI(dec,"§fDeepslate Brick Stairs","minecraft:deepslate_brick_stairs",12,64);
        CATEGORIES.add(dec);
    }

    private static void addI(ShopCategory cat, String name, String id, int price, int maxStack) {
        cat.items.add(new ShopItem(name, id, price, maxStack));
    }

    // =========================================================================
    // STATE
    // =========================================================================

    /** Per-player menu session. */
    private static class Session {
        MenuType type          = MenuType.CATEGORIES;
        int      categoryIndex = 0;
        int      page          = 0;
        int      itemIndex     = 0; // within category
        int      quantity      = 1; // selected quantity in the purchase menu (default 1)
    }

    private enum MenuType { CATEGORIES, ITEM_LIST, PURCHASE, BULK }

    private final Map<UUID, Session> sessions = new HashMap<>();

    private final JavaPlugin plugin;
    private final Logger     logger;

    private static final int ITEMS_PER_PAGE = 28;

    public ShopSystem(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        // Ensure Money objective exists
        try {
            org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            if (board.getObjective("Money") == null) {
                board.registerNewObjective("Money", "dummy", "§6Money");
            }
        } catch (Exception ignored) {}
        logger.info("[ShopSystem] Loaded — " + CATEGORIES.size() + " categories.");
    }

    // =========================================================================
    // MONEY HELPERS (shared with SellTrashSystem via scoreboard)
    // =========================================================================

    public static int getPlayerMoney(Player player) {
        try {
            org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective("Money");
            if (obj == null) return 0;
            Score score = obj.getScore(player.getName());
            return score.isScoreSet() ? score.getScore() : 0;
        } catch (Exception e) { return 0; }
    }

    private static void setPlayerMoney(Player player, int amount) {
        try {
            org.bukkit.scoreboard.Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective("Money");
            if (obj == null) obj = board.registerNewObjective("Money", "dummy", "§6Money");
            obj.getScore(player.getName()).setScore(Math.max(0, amount));
        } catch (Exception ignored) {}
    }

    private static boolean removePlayerMoney(Player player, int amount) {
        int current = getPlayerMoney(player);
        if (current < amount) return false;
        setPlayerMoney(player, current - amount);
        return true;
    }

    // =========================================================================
    // /shop COMMAND
    // =========================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cPlayers only.");
            return true;
        }
        Player player = (Player) sender;
        String cmd = command.getName().toLowerCase();

        if (cmd.equals("shop")) {
            showCategoryMenu(player);
        } else if (cmd.equals("bal") || cmd.equals("balance")) {
            player.sendMessage("§6💰 Balance: §a$" + String.format("%,d", getPlayerMoney(player)));
        }
        return true;
    }

    // =========================================================================
    // CATEGORY MENU
    // Mirrors showCategoryMenu() in shop_system.js
    // =========================================================================

    public void showCategoryMenu(Player player) {
        int balance = getPlayerMoney(player);
        Inventory inv = Bukkit.createInventory(null, 54,
                "§6§lSHOP §8| §eBalance: §a$" + String.format("%,d", balance));

        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Categories in rows — up to 12 (matches categorySlots in shop_system.js)
        int[] catSlots = {10,11,12,13,14,15,16,20,21,22,23,24};
        for (int i = 0; i < Math.min(CATEGORIES.size(), catSlots.length); i++) {
            ShopCategory cat = CATEGORIES.get(i);
            inv.setItem(catSlots[i], makeItem(cat.icon, cat.displayName, Arrays.asList(
                    "§7" + cat.items.size() + " items available",
                    "§eClick to browse")));
        }

        inv.setItem(49, makeItem(Material.BARRIER, "§cClose", Collections.emptyList()));

        Session s = new Session();
        s.type = MenuType.CATEGORIES;
        sessions.put(player.getUniqueId(), s);
        player.openInventory(inv);
    }

    // =========================================================================
    // ITEM LIST (paginated)
    // Mirrors showItemList() in shop_system.js
    // =========================================================================

    private void showItemList(Player player, int catIndex, int page) {
        ShopCategory cat = CATEGORIES.get(catIndex);
        int balance      = getPlayerMoney(player);
        int totalPages   = (int) Math.ceil((double) cat.items.size() / ITEMS_PER_PAGE);
        page             = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, 54,
                "§6" + cat.displayName + " §8(" + (page+1) + "/" + totalPages + ") §8| §a$" + String.format("%,d", balance));

        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Inner slots
        int[] itemSlots = innerSlots();
        int start = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= cat.items.size()) break;
            ShopItem si = cat.items.get(idx);
            boolean canAfford = balance >= si.price;
            inv.setItem(itemSlots[i], makeItem(si.material, si.name, Arrays.asList(
                    "§7Price: §e$" + String.format("%,d", si.price),
                    "§7Max Stack: §f" + si.maxStack,
                    canAfford ? "§eClick to buy" : "§cNot enough money")));
        }

        // Nav
        inv.setItem(0, makeItem(Material.ARROW, "§7← Back", Collections.emptyList()));
        if (page > 0) inv.setItem(3, makeItem(Material.ARROW, "§7◄ Prev", Collections.emptyList()));
        if (page < totalPages - 1) inv.setItem(5, makeItem(Material.ARROW, "§7Next ►", Collections.emptyList()));
        inv.setItem(8, makeItem(Material.BARRIER, "§cClose", Collections.emptyList()));

        Session s = sessions.computeIfAbsent(player.getUniqueId(), k -> new Session());
        s.type = MenuType.ITEM_LIST;
        s.categoryIndex = catIndex;
        s.page = page;
        player.openInventory(inv);
    }

    // =========================================================================
    // PURCHASE MENU (double chest)
    //
    // Row 2 (slots 10-16): quantity stepper — (set 0)(-16)(-1)(item)(+1)(+16)(set 64)
    // Row 3 (slot 22): order summary
    // Row 4 (slots 30, 32): Buy / Cancel
    // Row 6 (slot 49): Bulk Purchase (opens the 1-28 stack page)
    // =========================================================================

    private void showPurchaseMenu(Player player, int catIndex, int itemIndex) {
        ShopCategory cat = CATEGORIES.get(catIndex);
        ShopItem     si  = cat.items.get(itemIndex);
        int balance      = getPlayerMoney(player);

        Session s = sessions.computeIfAbsent(player.getUniqueId(), k -> new Session());
        s.type = MenuType.PURCHASE;
        s.categoryIndex = catIndex;
        s.itemIndex = itemIndex;

        int maxQty = si.maxStack * 28; // matches the bulk page's 28-stack ceiling
        s.quantity = Math.max(0, Math.min(s.quantity, maxQty));
        int qty = s.quantity;
        int totalPrice = si.price * qty;
        boolean canBuy = qty > 0 && balance >= totalPrice;

        Inventory inv = Bukkit.createInventory(null, 54,
                "§6Buy: " + si.name + " §8| §a$" + String.format("%,d", balance));
        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // Row 1 — navigation
        inv.setItem(0, makeItem(Material.ARROW, "§7← Back", Collections.emptyList()));
        inv.setItem(8, makeItem(Material.BARRIER, "§cClose", Collections.emptyList()));

        // Row 2 — quantity stepper
        inv.setItem(10, makeItem(Material.REDSTONE_BLOCK, "§cSet to 0", Collections.singletonList("§7Reset quantity")));
        inv.setItem(11, makeItem(Material.RED_DYE, "§7-16", Collections.singletonList("§7Decrease by 16")));
        inv.setItem(12, makeItem(Material.RED_DYE, "§7-1", Collections.singletonList("§7Decrease by 1")));
        inv.setItem(13, makeItem(si.material, qty, si.name, Arrays.asList(
                "§7Price: §e$" + String.format("%,d", si.price) + " §7each",
                "§7Stack size: §f" + si.maxStack,
                "§7Selected: §f" + qty)));
        inv.setItem(14, makeItem(Material.LIME_DYE, "§a+1", Collections.singletonList("§7Increase by 1")));
        inv.setItem(15, makeItem(Material.LIME_DYE, "§a+16", Collections.singletonList("§7Increase by 16")));
        inv.setItem(16, makeItem(Material.EMERALD_BLOCK, "§aSet to 64", Collections.singletonList("§7Jump to 64")));

        // Row 3 — order summary (unchanged position)
        inv.setItem(22, makeItem(Material.PAPER, "§eOrder Summary", Arrays.asList(
                "§7Quantity: §f" + qty,
                "§7Total: §e$" + String.format("%,d", totalPrice),
                "§7Balance: §a$" + String.format("%,d", balance),
                qty == 0 ? "§7Select a quantity" : (canBuy ? "§aReady to purchase" : "§cNot enough money"))));

        // Row 4 — buy / cancel
        inv.setItem(30, makeItem(Material.GREEN_STAINED_GLASS_PANE,
                "§a§lBuy", Arrays.asList("§7Confirm purchase",
                        canBuy ? "§eClick to buy" : "§cSelect a valid quantity")));
        inv.setItem(32, makeItem(Material.RED_STAINED_GLASS_PANE, "§c§lCancel",
                Collections.singletonList("§7Reset the selected quantity")));

        // Row 6 — bulk purchase
        inv.setItem(49, makeItem(Material.CHEST, "§dBulk Purchase",
                Arrays.asList("§7Buy up to 28 stacks at once", "§eClick to open")));

        player.openInventory(inv);
    }

    // =========================================================================
    // BULK PURCHASE MENU (double chest)
    //
    // 1-item glass border around the centre 7x4, each inner button buys
    // 1-28 stacks (stack size = ShopItem.maxStack) of the selected item.
    // =========================================================================

    private void showBulkMenu(Player player, int catIndex, int itemIndex) {
        ShopCategory cat = CATEGORIES.get(catIndex);
        ShopItem     si  = cat.items.get(itemIndex);
        int balance      = getPlayerMoney(player);

        Inventory inv = Bukkit.createInventory(null, 54,
                "§dBulk Buy: " + si.name + " §8| §a$" + String.format("%,d", balance));
        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        int[] slots = innerSlots(); // centre 7x4 = 28 slots
        for (int n = 1; n <= 28; n++) {
            int qty   = n * si.maxStack;
            int total = si.price * qty;
            boolean can = balance >= total;
            inv.setItem(slots[n - 1], makeItem(can ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    (can ? "§a" : "§c") + n + (n == 1 ? " Stack" : " Stacks"),
                    Arrays.asList("§7Quantity: §f" + qty,
                            "§7Total: §e$" + String.format("%,d", total),
                            can ? "§eClick to purchase" : "§cNot enough money")));
        }

        inv.setItem(45, makeItem(Material.ARROW, "§7← Back", Collections.emptyList()));
        inv.setItem(53, makeItem(Material.BARRIER, "§cClose", Collections.emptyList()));

        Session s = sessions.computeIfAbsent(player.getUniqueId(), k -> new Session());
        s.type = MenuType.BULK;
        s.categoryIndex = catIndex;
        s.itemIndex = itemIndex;
        player.openInventory(inv);
    }

    // =========================================================================
    // PURCHASE
    // Mirrors purchaseItem() in shop_system.js
    // =========================================================================

    private void purchaseItem(Player player, ShopItem si, int quantity) {
        int totalPrice = si.price * quantity;
        int balance    = getPlayerMoney(player);

        if (balance < totalPrice) {
            player.sendMessage("§c✗ Not enough money! Need $" + String.format("%,d", totalPrice)
                    + ", have $" + String.format("%,d", balance));
            return;
        }

        // Give items first, then deduct
        int remaining = quantity;
        while (remaining > 0) {
            int amount = Math.min(remaining, 64);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(si.material, amount));
            if (!overflow.isEmpty()) {
                player.sendMessage("§c✗ Not enough inventory space for " + quantity + " items!");
                // Don't charge — nothing given
                return;
            }
            remaining -= amount;
        }

        if (!removePlayerMoney(player, totalPrice)) {
            player.sendMessage("§c✗ Failed to deduct balance.");
            return;
        }

        String stackInfo = quantity == 64 ? " (1 stack)" : quantity > 64 ? " (" + (quantity/64) + " stacks)" : "";
        player.sendMessage("§a✓ Purchased " + quantity + "x " + si.name + stackInfo);
        player.sendMessage("§7Cost: §e$" + String.format("%,d", totalPrice)
                + " §7| Balance: §a$" + String.format("%,d", getPlayerMoney(player)));
    }

    // =========================================================================
    // CLICK HANDLER
    // =========================================================================

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        if (sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
        int slot = event.getRawSlot();

        switch (s.type) {
            case CATEGORIES:   handleCatClick(player, slot, s); break;
            case ITEM_LIST:    handleItemListClick(player, slot, s); break;
            case PURCHASE:     handlePurchaseClick(player, slot, s); break;
            case BULK:         handleBulkClick(player, slot, s); break;
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        UUID id = player.getUniqueId();

        // openInventory() (used when navigating between shop menus) fires this
        // close event for the OLD inventory synchronously, on the same tick as
        // the new menu's session is set. If we remove the session immediately
        // here, the brand-new menu loses its session right after being opened,
        // which makes every button silently stop working and leaves clicks
        // uncancelled (letting items leak in/out of the GUI).
        //
        // Instead, check one tick later whether the player still has a
        // shop-owned session/inventory open; only clear it if they actually
        // closed out rather than navigated to a different shop menu.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Session current = sessions.get(id);
            if (current == null) return;
            String title = player.getOpenInventory().getTitle();
            boolean stillInShop = title.contains("SHOP") || title.startsWith("§6") || title.contains("Buy:");
            if (!stillInShop) {
                sessions.remove(id);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private void handleCatClick(Player player, int slot, Session s) {
        int[] catSlots = {10,11,12,13,14,15,16,20,21,22,23,24};
        if (slot == 49) { player.closeInventory(); return; }
        for (int i = 0; i < catSlots.length; i++) {
            if (slot == catSlots[i] && i < CATEGORIES.size()) {
                final int ci = i;
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> showItemList(player, ci, 0));
                return;
            }
        }
    }

    private void handleItemListClick(Player player, int slot, Session s) {
        if (slot == 0) {
            plugin.getServer().getScheduler().runTask(plugin, () -> showCategoryMenu(player));
            return;
        }
        if (slot == 3 && s.page > 0) {
            plugin.getServer().getScheduler().runTask(plugin, () -> showItemList(player, s.categoryIndex, s.page - 1));
            return;
        }
        if (slot == 5) {
            plugin.getServer().getScheduler().runTask(plugin, () -> showItemList(player, s.categoryIndex, s.page + 1));
            return;
        }
        if (slot == 8) { player.closeInventory(); return; }

        int[] is = innerSlots();
        for (int i = 0; i < is.length; i++) {
            if (slot == is[i]) {
                int idx = s.page * ITEMS_PER_PAGE + i;
                ShopCategory cat = CATEGORIES.get(s.categoryIndex);
                if (idx < cat.items.size()) {
                    final int fi = idx;
                    s.quantity = 1; // fresh selection for the newly-picked item
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> showPurchaseMenu(player, s.categoryIndex, fi));
                }
                return;
            }
        }
    }

    private void handlePurchaseClick(Player player, int slot, Session s) {
        ShopCategory cat = CATEGORIES.get(s.categoryIndex);
        ShopItem     si  = cat.items.get(s.itemIndex);
        int maxQty = si.maxStack * 28; // matches the bulk page's 28-stack ceiling

        if (slot == 0) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> showItemList(player, s.categoryIndex, s.page));
            return;
        }
        if (slot == 8) { player.closeInventory(); return; }

        if (slot == 49) { // Bulk Purchase
            final int ci = s.categoryIndex, ii = s.itemIndex;
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> showBulkMenu(player, ci, ii));
            return;
        }

        switch (slot) {
            case 10: s.quantity = 0; break;                                   // Set to 0
            case 11: s.quantity = Math.max(0, s.quantity - 16); break;        // -16
            case 12: s.quantity = Math.max(0, s.quantity - 1); break;         // -1
            case 14: s.quantity = Math.min(maxQty, s.quantity + 1); break;    // +1
            case 15: s.quantity = Math.min(maxQty, s.quantity + 16); break;   // +16
            case 16: s.quantity = Math.min(maxQty, 64); break;                // Set to 64
            case 32: s.quantity = 0; break;                                   // Cancel
            case 30: {                                                       // Buy
                if (s.quantity > 0) {
                    purchaseItem(player, si, s.quantity);
                    s.quantity = 0;
                } else {
                    player.sendMessage("§cSelect a quantity first.");
                }
                break;
            }
            default:
                return;
        }

        final int ci = s.categoryIndex, ii = s.itemIndex;
        plugin.getServer().getScheduler().runTask(plugin, () -> showPurchaseMenu(player, ci, ii));
    }

    private void handleBulkClick(Player player, int slot, Session s) {
        ShopCategory cat = CATEGORIES.get(s.categoryIndex);
        ShopItem     si  = cat.items.get(s.itemIndex);

        if (slot == 45) {
            final int ci = s.categoryIndex, ii = s.itemIndex;
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> showPurchaseMenu(player, ci, ii));
            return;
        }
        if (slot == 53) { player.closeInventory(); return; }

        int[] slots = innerSlots();
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i]) {
                int n   = i + 1;          // 1-28 stacks
                int qty = n * si.maxStack;
                purchaseItem(player, si, qty);
                final int ci = s.categoryIndex, ii = s.itemIndex;
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> showBulkMenu(player, ci, ii));
                return;
            }
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private static int[] innerSlots() {
        int[] slots = new int[ITEMS_PER_PAGE];
        int si = 0;
        for (int row = 1; row <= 4; row++)
            for (int col = 1; col <= 7; col++)
                slots[si++] = row * 9 + col;
        return slots;
    }

    private static ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta  meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Overload that sets the stack's visible amount (e.g. to show the selected purchase quantity). */
    private static ItemStack makeItem(Material mat, int amount, String name, List<String> lore) {
        ItemStack item = makeItem(mat, name, lore);
        item.setAmount(Math.max(1, amount));
        return item;
    }
}

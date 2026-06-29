package com.skyblock.admin;

import com.skyblock.crates.CrateSystem;
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

import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Admin Shop System
 *
 * A free, no-money "shop" reachable only from the Admin Panel (see the
 * "Admin Shop" button added in AdminSystem.openMainMenu()). It uses the exact
 * same category → item-list → purchase-menu → bulk-menu GUI flow as
 * ShopSystem (same slot layout, same pagination, same quantity stepper), the
 * only difference being every item costs nothing and there's no balance
 * check anywhere.
 *
 * Categories:
 *   - Crate Keys           — every key defined in CrateSystem.CRATE_CONFIGS,
 *                             handed out via CrateSystem.grantAdminSpecial()
 *                             (the same "special:xxx_key" trigger a crate's
 *                             own loot table uses).
 *   - Crate Special Items   — every non-key "special:" reward (armor sets,
 *                             Aqua/Nova Pic) and every item+special pairing
 *                             (talismans, potions, Haste items, Arrow of
 *                             Weakness) defined in CrateSystem's loot tables,
 *                             triggered via CrateSystem.grantAdminSpecial()
 *                             / grantAdminSpecialItem().
 *   - Command-Only Items    — vanilla Java Edition items that cannot be
 *                             obtained in Survival (and most can't even be
 *                             picked from the normal Creative inventory
 *                             without enabling the Operator Items Tab):
 *                             Command Block (+ Chain/Repeating/Minecart),
 *                             Structure Block, Structure Void, Jigsaw Block,
 *                             Barrier, Light, Debug Stick, Knowledge Book,
 *                             Petrified Oak Slab, Wither/Ender Dragon spawn
 *                             eggs. Handed out directly as plain ItemStacks.
 */
public class AdminShopSystem implements Listener {

    // =========================================================================
    // ITEM / CATEGORY DATA
    // =========================================================================

    public static class AdminShopItem {
        public final String name;
        public final Material icon;
        public final List<String> lore;
        public final int maxStack;
        /** (player, quantity) -> actually hand over the item(s)/trigger the reward. */
        public final BiConsumer<Player, Integer> giver;

        public AdminShopItem(String name, Material icon, List<String> lore, int maxStack,
                              BiConsumer<Player, Integer> giver) {
            this.name     = name;
            this.icon     = icon;
            this.lore     = lore;
            this.maxStack = maxStack;
            this.giver    = giver;
        }
    }

    public static class AdminShopCategory {
        public final String              id;
        public final String              displayName;
        public final Material            icon;
        public final List<AdminShopItem> items = new ArrayList<>();

        public AdminShopCategory(String id, String displayName, Material icon) {
            this.id          = id;
            this.displayName = displayName;
            this.icon        = icon;
        }
    }

    private final List<AdminShopCategory> categories = new ArrayList<>();

    // =========================================================================
    // CONSTRUCTION — builds the category list, wiring each item's giver back
    // into the matching CrateSystem trigger (keys / special items) or a plain
    // vanilla ItemStack hand-out (command-only items).
    // =========================================================================

    private final JavaPlugin   plugin;
    private final CrateSystem  crateSystem;
    private final Logger       logger;

    public AdminShopSystem(JavaPlugin plugin, Logger logger, CrateSystem crateSystem) {
        this.plugin      = plugin;
        this.logger      = logger;
        this.crateSystem = crateSystem;
        buildCategories();
        logger.info("[AdminShopSystem] Loaded — " + categories.size() + " categories.");
    }

    private void buildCategories() {

        // ── Crate Keys — pulled straight from CrateSystem.CRATE_CONFIGS, so
        //    this list can never drift from the real keys the crate code uses. ──
        AdminShopCategory keys = new AdminShopCategory("crate_keys", "§eCrate Keys", Material.TRIAL_KEY);
        for (Map.Entry<String, CrateSystem.CrateConfig> entry : CrateSystem.CRATE_CONFIGS.entrySet()) {
            String                    crateType = entry.getKey();
            CrateSystem.CrateConfig   config    = entry.getValue();
            keys.items.add(new AdminShopItem(
                    config.keyName,
                    config.keyMaterial,
                    Arrays.asList("§7Opens: " + config.name, "§7", "§aFree — Admin Shop"),
                    64,
                    (player, qty) -> {
                        for (int i = 0; i < qty; i++) crateSystem.grantAdminSpecial(player, crateType + "_key");
                    }
            ));
        }
        categories.add(keys);

        // ── Crate Special Items — every non-key "special:" reward and every
        //    item+special pairing found in CrateSystem's CRATE_LOOT tables. ──
        AdminShopCategory special = new AdminShopCategory("crate_specials", "§dCrate Special Items", Material.NETHER_STAR);

        addSpecial(special, "§7Full Iron Set", Material.IRON_CHESTPLATE, 8,
                "iron_set", Arrays.asList("§7Full armor + tool set", "§7", "§aFree — Admin Shop"));
        addSpecial(special, "§7Full Chainmail Set", Material.CHAINMAIL_CHESTPLATE, 8,
                "chain_set", Arrays.asList("§7Full chainmail armor", "§7", "§aFree — Admin Shop"));
        addSpecial(special, "§bFull Diamond Set", Material.DIAMOND_CHESTPLATE, 8,
                "diamond_set", Arrays.asList("§7Full diamond armor", "§7", "§aFree — Admin Shop"));
        addSpecial(special, "§r§l§3Aqua §bPic", Material.IRON_PICKAXE, 8,
                "aqua_pic", Arrays.asList("§7Vein Strike — mines a 3x3x1 face", "§7", "§aFree — Admin Shop"));
        addSpecial(special, "§r§l§dNova §5Pic", Material.DIAMOND_PICKAXE, 8,
                "nova_pic", Arrays.asList("§7Void Drill — mines a 3x3x3 area", "§7", "§aFree — Admin Shop"));

        addSpecialItem(special, "§eJump Talisman", Material.FEATHER, 64,
                "minecraft:feather", "jump_talisman", "§7Grants Jump Boost I when held");
        addSpecialItem(special, "§e§lJump Talisman II", Material.FEATHER, 64,
                "minecraft:feather", "jump_talisman_t2", "§7Grants Jump Boost II when held");
        addSpecialItem(special, "§cStrength Talisman", Material.CLAY_BALL, 64,
                "minecraft:clay_ball", "strength_talisman", "§7Grants Strength I when held");
        addSpecialItem(special, "§c§lStrength Talisman II", Material.CLAY_BALL, 64,
                "minecraft:clay_ball", "strength_talisman_t2", "§7Grants Strength II when held");
        addSpecialItem(special, "§bSpeed Talisman", Material.RABBIT_FOOT, 64,
                "minecraft:rabbit_foot", "speed_talisman", "§7Grants Speed I when held");
        addSpecialItem(special, "§b§lSpeed Talisman II", Material.RABBIT_FOOT, 64,
                "minecraft:rabbit_foot", "speed_talisman_t2", "§7Grants Speed II when held");
        addSpecialItem(special, "§7Defence Talisman", Material.ARMADILLO_SCUTE, 64,
                "minecraft:armadillo_scute", "defence_talisman", "§7Grants Resistance I when held");
        addSpecialItem(special, "§7§lDefence Talisman II", Material.ARMADILLO_SCUTE, 64,
                "minecraft:armadillo_scute", "defence_talisman_t2", "§7Grants Resistance II when held");
        addSpecialItem(special, "§dRegen Talisman", Material.SPIDER_EYE, 64,
                "minecraft:spider_eye", "regen_talisman", "§7Grants Regeneration I when held");
        addSpecialItem(special, "§d§lRegen Talisman II", Material.SPIDER_EYE, 64,
                "minecraft:spider_eye", "regen_talisman_t2", "§7Grants Regeneration II when held");
        addSpecialItem(special, "§6Multi Talisman", Material.NAUTILUS_SHELL, 64,
                "minecraft:nautilus_shell", "multi_talisman", "§7Grants Speed/Strength/Jump Boost I");

        addSpecialItem(special, "§cInstant Health I Potion", Material.POTION, 16,
                "minecraft:potion", "health_i", "§7Instant Health I");
        addSpecialItem(special, "§cInstant Health II Potion", Material.POTION, 16,
                "minecraft:potion", "health_ii", "§7Instant Health II");
        addSpecialItem(special, "§dRegeneration II Potion", Material.POTION, 16,
                "minecraft:potion", "regen_ii", "§7Regeneration II (45s)");
        addSpecialItem(special, "§bSpeed II Potion", Material.POTION, 16,
                "minecraft:potion", "speed_ii", "§7Speed II (90s)");
        addSpecialItem(special, "§7Arrow of Weakness §8(32x)", Material.TIPPED_ARROW, 4,
                "minecraft:arrow", "weakness_arrow", "§7Gives 32 arrows per click");
        addSpecialItem(special, "§6Dyric's Haste III", Material.DRAGON_BREATH, 16,
                "minecraft:dragon_breath", "haste_3", "§7Right-click to use — Haste III, 1 min");
        addSpecialItem(special, "§6Dyric's Haste V", Material.DRAGON_BREATH, 16,
                "minecraft:dragon_breath", "haste_5", "§7Right-click to use — Haste V, 30s");

        categories.add(special);

        // ── Command-Only Items — vanilla items unobtainable in Survival
        //    (most aren't even selectable in Creative without the Operator
        //    Items Tab; a few — Knowledge Book, Petrified Oak Slab, the
        //    Wither/Ender Dragon spawn eggs — aren't in Creative at all). ──
        AdminShopCategory cmd = new AdminShopCategory("command_only", "§cCommand-Only Items", Material.COMMAND_BLOCK);
        addVanilla(cmd, "§cCommand Block", Material.COMMAND_BLOCK, 64);
        addVanilla(cmd, "§cChain Command Block", Material.CHAIN_COMMAND_BLOCK, 64);
        addVanilla(cmd, "§cRepeating Command Block", Material.REPEATING_COMMAND_BLOCK, 64);
        addVanilla(cmd, "§cCommand Block Minecart", Material.COMMAND_BLOCK_MINECART, 1);
        addVanilla(cmd, "§7Structure Block", Material.STRUCTURE_BLOCK, 64);
        addVanilla(cmd, "§7Structure Void", Material.STRUCTURE_VOID, 64);
        addVanilla(cmd, "§7Jigsaw Block", Material.JIGSAW, 64);
        addVanilla(cmd, "§7Barrier", Material.BARRIER, 64);
        addVanilla(cmd, "§eLight Block", Material.LIGHT, 64);
        addVanilla(cmd, "§7Debug Stick", Material.DEBUG_STICK, 1);
        addVanilla(cmd, "§dKnowledge Book", Material.KNOWLEDGE_BOOK, 1);
        addVanilla(cmd, "§7Petrified Oak Slab", Material.PETRIFIED_OAK_SLAB, 64);
        addVanilla(cmd, "§5Wither Spawn Egg", Material.WITHER_SPAWN_EGG, 64);
        addVanilla(cmd, "§5Ender Dragon Spawn Egg", Material.ENDER_DRAGON_SPAWN_EGG, 64);
        categories.add(cmd);
    }

    /** A "special:" reward with no underlying material of its own (sets, pics) — see handleSpecialReward(). */
    private void addSpecial(AdminShopCategory cat, String name, Material icon, int maxStack,
                             String specialId, List<String> lore) {
        cat.items.add(new AdminShopItem(name, icon, lore, maxStack,
                (player, qty) -> {
                    for (int i = 0; i < qty; i++) crateSystem.grantAdminSpecial(player, specialId);
                }));
    }

    /** An item+special pairing (talismans, potions, haste, weakness arrow) — see handleSpecialItem(). */
    private void addSpecialItem(AdminShopCategory cat, String name, Material icon, int maxStack,
                                 String itemId, String specialTag, String loreLine) {
        cat.items.add(new AdminShopItem(name, icon,
                Arrays.asList(loreLine, "§7", "§aFree — Admin Shop"), maxStack,
                (player, qty) -> {
                    for (int i = 0; i < qty; i++) crateSystem.grantAdminSpecialItem(player, itemId, specialTag, 1);
                }));
    }

    /** A plain vanilla item handed out directly (no crate trigger involved). */
    private void addVanilla(AdminShopCategory cat, String name, Material material, int maxStack) {
        cat.items.add(new AdminShopItem(name, material,
                Arrays.asList("§7Command/creative-only item", "§7", "§aFree — Admin Shop"), maxStack,
                (player, qty) -> giveVanillaStacked(player, material, name, qty)));
    }

    private void giveVanillaStacked(Player player, Material material, String name, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int give = Math.min(remaining, 64);
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(material, give));
            if (!overflow.isEmpty()) {
                player.sendMessage("§c✗ Not enough inventory space for " + amount + " items!");
                return;
            }
            remaining -= give;
        }
        player.sendMessage("§a✓ Received §e" + amount + "x §f" + name + " §7(Admin Shop)");
    }

    // =========================================================================
    // GUI STATE
    // =========================================================================

    private enum MenuType { CATEGORIES, ITEM_LIST, PURCHASE, BULK }

    private static class Session {
        MenuType type;
        int      categoryIndex;
        int      itemIndex;
        int      page;
        int      quantity = 1;
    }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private static final int ITEMS_PER_PAGE = 28;

    /** Shared marker string — every Admin Shop GUI title contains this so
     *  the close-handler can tell "still in admin shop" apart from any other
     *  inventory (including AdminSystem's own panel) without ambiguity. */
    private static final String TITLE_MARKER = "ADMIN SHOP";

    // =========================================================================
    // ENTRY POINT — called from AdminSystem's "Admin Shop" button.
    // =========================================================================

    public void showCategoryMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§4§l" + TITLE_MARKER + " §8| §aEverything Free");

        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        int[] catSlots = {10,11,12,13,14,15,16,20,21,22,23,24};
        for (int i = 0; i < Math.min(categories.size(), catSlots.length); i++) {
            AdminShopCategory cat = categories.get(i);
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

    private void showItemList(Player player, int catIndex, int page) {
        AdminShopCategory cat        = categories.get(catIndex);
        int               totalPages = Math.max(1, (int) Math.ceil((double) cat.items.size() / ITEMS_PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, 54,
                "§4" + TITLE_MARKER + " §8| " + cat.displayName + " §8(" + (page + 1) + "/" + totalPages + ")");

        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        int[] itemSlots = innerSlots();
        int   start     = page * ITEMS_PER_PAGE;
        for (int i = 0; i < ITEMS_PER_PAGE; i++) {
            int idx = start + i;
            if (idx >= cat.items.size()) break;
            AdminShopItem ai = cat.items.get(idx);
            List<String> lore = new ArrayList<>(ai.lore);
            lore.add("§eClick to get");
            inv.setItem(itemSlots[i], makeItem(ai.icon, ai.name, lore));
        }

        inv.setItem(0, makeItem(Material.ARROW, "§7← Back", Collections.emptyList()));
        if (page > 0) inv.setItem(3, makeItem(Material.ARROW, "§7◄ Prev", Collections.emptyList()));
        if (page < totalPages - 1) inv.setItem(5, makeItem(Material.ARROW, "§7Next ►", Collections.emptyList()));
        inv.setItem(8, makeItem(Material.BARRIER, "§cClose", Collections.emptyList()));

        Session s = sessions.computeIfAbsent(player.getUniqueId(), k -> new Session());
        s.type          = MenuType.ITEM_LIST;
        s.categoryIndex = catIndex;
        s.page          = page;
        player.openInventory(inv);
    }

    private void showPurchaseMenu(Player player, int catIndex, int itemIndex) {
        AdminShopCategory cat = categories.get(catIndex);
        AdminShopItem     ai  = cat.items.get(itemIndex);

        Session s = sessions.computeIfAbsent(player.getUniqueId(), k -> new Session());
        s.type          = MenuType.PURCHASE;
        s.categoryIndex = catIndex;
        s.itemIndex     = itemIndex;

        int maxQty = ai.maxStack * 28;
        s.quantity = Math.max(0, Math.min(s.quantity, maxQty));
        int qty = s.quantity;

        Inventory inv = Bukkit.createInventory(null, 54,
                "§4" + TITLE_MARKER + " §8| Get: " + ai.name);
        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        inv.setItem(0, makeItem(Material.ARROW, "§7← Back", Collections.emptyList()));
        inv.setItem(8, makeItem(Material.BARRIER, "§cClose", Collections.emptyList()));

        inv.setItem(10, makeItem(Material.REDSTONE_BLOCK, "§cSet to 0", Collections.singletonList("§7Reset quantity")));
        inv.setItem(11, makeItem(Material.RED_DYE, "§7-16", Collections.singletonList("§7Decrease by 16")));
        inv.setItem(12, makeItem(Material.RED_DYE, "§7-1", Collections.singletonList("§7Decrease by 1")));
        inv.setItem(13, makeItem(ai.icon, Math.max(1, qty), ai.name, Arrays.asList(
                "§7Cost: §aFREE",
                "§7Stack size: §f" + ai.maxStack,
                "§7Selected: §f" + qty)));
        inv.setItem(14, makeItem(Material.LIME_DYE, "§a+1", Collections.singletonList("§7Increase by 1")));
        inv.setItem(15, makeItem(Material.LIME_DYE, "§a+16", Collections.singletonList("§7Increase by 16")));
        inv.setItem(16, makeItem(Material.EMERALD_BLOCK, "§aSet to 64", Collections.singletonList("§7Jump to 64")));

        inv.setItem(22, makeItem(Material.PAPER, "§eOrder Summary", Arrays.asList(
                "§7Quantity: §f" + qty,
                "§7Cost: §aFREE",
                qty == 0 ? "§7Select a quantity" : "§aReady to claim")));

        inv.setItem(30, makeItem(Material.GREEN_STAINED_GLASS_PANE, "§a§lGet",
                Arrays.asList("§7Confirm — no cost",
                        qty > 0 ? "§eClick to claim" : "§cSelect a valid quantity")));
        inv.setItem(32, makeItem(Material.RED_STAINED_GLASS_PANE, "§c§lCancel",
                Collections.singletonList("§7Reset the selected quantity")));

        inv.setItem(49, makeItem(Material.CHEST, "§dBulk Get",
                Arrays.asList("§7Get up to 28 stacks at once", "§eClick to open")));

        player.openInventory(inv);
    }

    private void showBulkMenu(Player player, int catIndex, int itemIndex) {
        AdminShopCategory cat = categories.get(catIndex);
        AdminShopItem     ai  = cat.items.get(itemIndex);

        Inventory inv = Bukkit.createInventory(null, 54, "§4" + TITLE_MARKER + " §8| Bulk: " + ai.name);
        ItemStack filler = makeItem(Material.BLACK_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        int[] slots = innerSlots();
        for (int n = 1; n <= 28; n++) {
            int qty = n * ai.maxStack;
            inv.setItem(slots[n - 1], makeItem(Material.LIME_STAINED_GLASS_PANE,
                    "§a" + n + (n == 1 ? " Stack" : " Stacks"),
                    Arrays.asList("§7Quantity: §f" + qty, "§7Cost: §aFREE", "§eClick to claim")));
        }

        inv.setItem(45, makeItem(Material.ARROW, "§7← Back", Collections.emptyList()));
        inv.setItem(53, makeItem(Material.BARRIER, "§cClose", Collections.emptyList()));

        Session s = sessions.computeIfAbsent(player.getUniqueId(), k -> new Session());
        s.type          = MenuType.BULK;
        s.categoryIndex = catIndex;
        s.itemIndex     = itemIndex;
        player.openInventory(inv);
    }

    private void grantItem(Player player, AdminShopItem item, int quantity) {
        if (quantity <= 0) {
            player.sendMessage("§cSelect a quantity first.");
            return;
        }
        try {
            item.giver.accept(player, quantity);
        } catch (Exception e) {
            player.sendMessage("§cFailed to give item: " + e.getMessage());
            logger.severe("[AdminShopSystem] Grant error for '" + item.name + "': " + e.getMessage());
        }
    }

    // =========================================================================
    // CLICK HANDLING — mirrors ShopSystem's dispatcher 1:1, minus money.
    // =========================================================================

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (sessions.containsKey(player.getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session s = sessions.get(player.getUniqueId());
        if (s == null) return;

        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;
        int slot = event.getRawSlot();

        switch (s.type) {
            case CATEGORIES: handleCatClick(player, slot); break;
            case ITEM_LIST:  handleItemListClick(player, slot, s); break;
            case PURCHASE:   handlePurchaseClick(player, slot, s); break;
            case BULK:       handleBulkClick(player, slot, s); break;
        }
    }

    private void handleCatClick(Player player, int slot) {
        int[] catSlots = {10,11,12,13,14,15,16,20,21,22,23,24};
        if (slot == 49) { player.closeInventory(); return; }
        for (int i = 0; i < catSlots.length; i++) {
            if (slot == catSlots[i] && i < categories.size()) {
                final int ci = i;
                plugin.getServer().getScheduler().runTask(plugin, () -> showItemList(player, ci, 0));
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
                AdminShopCategory cat = categories.get(s.categoryIndex);
                if (idx < cat.items.size()) {
                    final int fi = idx;
                    s.quantity = 1;
                    plugin.getServer().getScheduler().runTask(plugin,
                            () -> showPurchaseMenu(player, s.categoryIndex, fi));
                }
                return;
            }
        }
    }

    private void handlePurchaseClick(Player player, int slot, Session s) {
        AdminShopCategory cat = categories.get(s.categoryIndex);
        AdminShopItem     ai  = cat.items.get(s.itemIndex);
        int maxQty = ai.maxStack * 28;

        if (slot == 0) {
            plugin.getServer().getScheduler().runTask(plugin, () -> showItemList(player, s.categoryIndex, s.page));
            return;
        }
        if (slot == 8) { player.closeInventory(); return; }

        if (slot == 49) {
            final int ci = s.categoryIndex, ii = s.itemIndex;
            plugin.getServer().getScheduler().runTask(plugin, () -> showBulkMenu(player, ci, ii));
            return;
        }

        switch (slot) {
            case 10: s.quantity = 0; break;
            case 11: s.quantity = Math.max(0, s.quantity - 16); break;
            case 12: s.quantity = Math.max(0, s.quantity - 1); break;
            case 14: s.quantity = Math.min(maxQty, s.quantity + 1); break;
            case 15: s.quantity = Math.min(maxQty, s.quantity + 16); break;
            case 16: s.quantity = Math.min(maxQty, 64); break;
            case 32: s.quantity = 0; break;
            case 30: {
                if (s.quantity > 0) {
                    grantItem(player, ai, s.quantity);
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
        AdminShopCategory cat = categories.get(s.categoryIndex);
        AdminShopItem     ai  = cat.items.get(s.itemIndex);

        if (slot == 45) {
            final int ci = s.categoryIndex, ii = s.itemIndex;
            plugin.getServer().getScheduler().runTask(plugin, () -> showPurchaseMenu(player, ci, ii));
            return;
        }
        if (slot == 53) { player.closeInventory(); return; }

        int[] slots = innerSlots();
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i]) {
                int n   = i + 1;
                int qty = n * ai.maxStack;
                grantItem(player, ai, qty);
                final int ci = s.categoryIndex, ii = s.itemIndex;
                plugin.getServer().getScheduler().runTask(plugin, () -> showBulkMenu(player, ci, ii));
                return;
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        UUID id = player.getUniqueId();

        // Same one-tick-delay fix as ShopSystem/AdminSystem: openInventory()
        // used for in-system navigation fires this close event for the OLD
        // menu synchronously, so only clear the session if the player truly
        // left the admin shop rather than navigated to another admin-shop menu.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!sessions.containsKey(id)) return;
            String title = player.getOpenInventory().getTitle();
            if (!title.contains(TITLE_MARKER)) sessions.remove(id);
        });
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
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

    private static ItemStack makeItem(Material mat, int amount, String name, List<String> lore) {
        ItemStack item = makeItem(mat, name, lore);
        item.setAmount(Math.max(1, amount));
        return item;
    }
}

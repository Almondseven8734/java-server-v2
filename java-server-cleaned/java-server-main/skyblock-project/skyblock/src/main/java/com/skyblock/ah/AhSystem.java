package com.skyblock.ah;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.text.NumberFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * Auction House System + /ah command
 *
 * Translated from ah_system.js and ah_command.js.
 *
 * The Bedrock chest form UI (ChestFormData) is replaced with native Bukkit
 * inventory GUI menus. Full feature parity:
 *   - Browse all listings (paginated)
 *   - Purchase confirmation
 *   - My listings / cancel listing
 *   - Collection (unclaimed items)
 *   - Sell item at price set by increment/decrement buttons
 *   - Auto-backup every 30 minutes
 *
 * Buyer and seller get in-chat notifications matching the JS messages.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     ah:
 *       description: Open the auction house
 *       usage: /ah
 */
public class AhSystem implements CommandExecutor, Listener {

    // ─── Constants ────────────────────────────────────────────────────────────
    private static final int MAX_LISTINGS_PER_PLAYER = 5;
    private static final int PAGE_SIZE               = 28;

    private static final String MONEY_OBJECTIVE = "Money";
    private static final String MONEY_DISPLAY   = "§6Money";

    /** Auto-backup interval: 30 min (20 ticks/s × 60 × 30 = 36 000 ticks). */
    private static final long BACKUP_INTERVAL_TICKS = 36_000L;

    // ─── GUI tracking (which player has which menu open) ─────────────────────
    /** Map from inventory title prefix to handler state. */
    private final Map<UUID, GuiState> openMenus = new HashMap<>();

    private final AhStorage   storage;
    private final JavaPlugin  plugin;
    private final Logger      logger;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public AhSystem(AhStorage storage, JavaPlugin plugin, Logger logger) {
        this.storage = storage;
        this.plugin  = plugin;
        this.logger  = logger;
        startAutoBackup();
        logger.info("[AH System] Auction house system loaded! Auto-backup every 30 min.");
    }

    // ─── /ah command ──────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }
        showMainAH((Player) sender, 0);
        return true;
    }

    // ─── Main AH menu ─────────────────────────────────────────────────────────

    public void showMainAH(Player player, int page) {
        List<AhStorage.Listing> all = storage.getAllListings();
        int totalPages   = Math.max(1, (int) Math.ceil((double) all.size() / PAGE_SIZE));
        int current      = Math.max(0, Math.min(page, totalPages - 1));
        List<AhStorage.Listing> slice = all.subList(
                current * PAGE_SIZE,
                Math.min((current + 1) * PAGE_SIZE, all.size()));

        Inventory inv = plugin.getServer().createInventory(null, 54,
                "§6§lAuction House §r§7(" + (current + 1) + "/" + totalPages + ")");

        // Borders
        ItemStack border = glass(Material.BLACK_STAINED_GLASS_PANE, "§8");
        ItemStack empty  = glass(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§8");
        for (int slot : borderSlots(54)) inv.setItem(slot, border);

        // Usable slots (rows 1-4, cols 1-7)
        List<Integer> usable = usableSlots54();
        for (int i = 0; i < slice.size() && i < usable.size(); i++) {
            AhStorage.Listing l = slice.get(i);
            inv.setItem(usable.get(i), listingItem(l, false));
        }
        for (int i = slice.size(); i < usable.size(); i++) {
            inv.setItem(usable.get(i), empty);
        }

        // Nav/controls
        if (current > 0) inv.setItem(45, namedItem(Material.ARROW, "§e◀ Previous Page", Collections.singletonList("§7Previous page")));
        if (current < totalPages - 1) inv.setItem(53, namedItem(Material.ARROW, "§eNext Page ▶", Collections.singletonList("§7Next page")));

        int collCount = storage.getCollectionCount(player.getUniqueId().toString());
        if (collCount > 0) {
            inv.setItem(46, namedItem(Material.CHEST, "§b📦 Collection",
                    Arrays.asList("§7Unclaimed items: §e" + collCount, "", "§aClick to claim")));
        }

        int myCount = storage.getPlayerListingCount(player.getUniqueId().toString());
        inv.setItem(47, namedItem(Material.WRITABLE_BOOK, "§6📋 My Listings",
                Arrays.asList("§7Active: §e" + myCount + "§7/§e" + MAX_LISTINGS_PER_PLAYER, "", "§aView & manage")));
        inv.setItem(51, namedItem(Material.EMERALD, "§a💰 Sell Item",
                Arrays.asList("§7List held item", "§7Listings: §e" + myCount + "§7/§e" + MAX_LISTINGS_PER_PLAYER, "", "§aClick")));
        inv.setItem(49, namedItem(Material.BARRIER, "§c✗ Close", Collections.singletonList("§7Close")));

        GuiState state = new GuiState(GuiState.Type.MAIN, current);
        state.pageSlice  = new ArrayList<>(slice);
        state.usableSlots = new ArrayList<>(usable);
        openMenus.put(player.getUniqueId(), state);
        player.openInventory(inv);
    }

    // ─── Inventory click handler ───────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        GuiState state = openMenus.get(player.getUniqueId());
        if (state == null) return;
        event.setCancelled(true);

        int slot = event.getRawSlot();

        switch (state.type) {
            case MAIN:     handleMainClick(player, state, slot); break;
            case CONFIRM:  handleConfirmClick(player, state, slot); break;
            case LISTINGS: handleMyListingsClick(player, state, slot); break;
            case COLLECT:  handleCollectionClick(player, state, slot); break;
            case SELL:     handleSellClick(player, state, slot); break;
        }
    }

    // ─── Main menu click ──────────────────────────────────────────────────────

    private void handleMainClick(Player player, GuiState state, int slot) {
        int current    = state.page;
        int totalPages = Math.max(1, (int) Math.ceil((double) storage.getAllListings().size() / PAGE_SIZE));
        int collCount  = storage.getCollectionCount(player.getUniqueId().toString());

        if (slot == 45 && current > 0) { showMainAH(player, current - 1); return; }
        if (slot == 53 && current < totalPages - 1) { showMainAH(player, current + 1); return; }
        if (slot == 47) { showMyListings(player); return; }
        if (slot == 46 && collCount > 0) { showCollection(player, 0); return; }
        if (slot == 51) { startSellProcess(player); return; }
        if (slot == 49) { player.closeInventory(); openMenus.remove(player.getUniqueId()); return; }

        int idx = state.usableSlots.indexOf(slot);
        if (idx >= 0 && idx < state.pageSlice.size()) {
            showPurchaseConfirmation(player, state.pageSlice.get(idx), current);
        }
    }

    // ─── Purchase confirmation ────────────────────────────────────────────────

    private void showPurchaseConfirmation(Player player, AhStorage.Listing listing, int returnPage) {
        if (listing.sellerId.equals(player.getUniqueId().toString())) {
            player.sendMessage("§c§l✗ Cannot Purchase!");
            player.sendMessage("§7You cannot buy your own listing!");
            showMainAH(player, returnPage);
            return;
        }

        int balance   = getPlayerMoney(player);
        boolean canAfford = balance >= listing.price;
        NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);

        Inventory inv = plugin.getServer().createInventory(null, 27, "§6§lConfirm Purchase");

        // Borders
        ItemStack border = glass(Material.BLACK_STAINED_GLASS_PANE, "§8");
        for (int s : new int[]{0,1,2,3,4,5,6,7,8,9,17,18,19,20,21,22,23,24,25,26}) inv.setItem(s, border);

        inv.setItem(13, listingItem(listing, true));
        if (canAfford) {
            inv.setItem(11, namedItem(Material.EMERALD_BLOCK, "§a§l✓ CONFIRM",
                    Collections.singletonList("§7Buy this item")));
        } else {
            inv.setItem(11, namedItem(Material.REDSTONE_BLOCK, "§c§l✗ CANNOT AFFORD",
                    Collections.singletonList("§7You need more money")));
        }
        inv.setItem(15, namedItem(Material.BARRIER, "§c§l✗ CANCEL",
                Collections.singletonList("§7Return to auction house")));

        GuiState state   = new GuiState(GuiState.Type.CONFIRM, returnPage);
        state.activeListing = listing;
        state.canAfford     = canAfford;
        openMenus.put(player.getUniqueId(), state);
        player.openInventory(inv);
    }

    private void handleConfirmClick(Player player, GuiState state, int slot) {
        if (slot == 15 || slot < 0) { showMainAH(player, state.page); return; }
        if (slot == 11 && state.canAfford && state.activeListing != null) {
            processPurchase(player, state.activeListing, state.page);
        } else {
            showMainAH(player, state.page);
        }
    }

    private void processPurchase(Player player, AhStorage.Listing listing, int returnPage) {
        int balance = getPlayerMoney(player);
        NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);

        if (balance < listing.price) {
            player.sendMessage("§c§l✗ Purchase Failed! Insufficient funds!");
            showMainAH(player, returnPage); return;
        }
        if (!removePlayerMoney(player, listing.price)) {
            player.sendMessage("§c§l✗ Transaction error!");
            showMainAH(player, returnPage); return;
        }

        ItemStack item = deserializeItem(listing.item);
        String name = getItemDisplayName(listing.item);
        boolean full = player.getInventory().firstEmpty() == -1;

        if (!full) {
            player.getInventory().addItem(item);
            player.sendMessage("§a§l✓ Purchase Successful!");
            player.sendMessage("§7Bought §e" + name + " §7for §a$" + fmt.format(listing.price));
            player.sendMessage("§7Balance: §a$" + fmt.format(getPlayerMoney(player)));
        } else {
            storage.addToCollection(player.getUniqueId().toString(), listing.item);
            player.sendMessage("§a§l✓ Purchase Successful!");
            player.sendMessage("§7Bought §e" + name + " §7for §a$" + fmt.format(listing.price));
            player.sendMessage("§6⚠ §7Inventory full! Item sent to §bCollection");
        }

        // Pay seller
        Player seller = plugin.getServer().getPlayer(UUID.fromString(listing.sellerId));
        if (seller != null) {
            addPlayerMoney(seller, listing.price);
            seller.sendMessage("§a§l✓ Item Sold!");
            seller.sendMessage("§7Your §e" + name + " §7sold to §e" + player.getName());
            seller.sendMessage("§a+$" + fmt.format(listing.price)
                    + " §7(Balance: §a$" + fmt.format(getPlayerMoney(seller)) + "§7)");
        }

        storage.removeListing(listing.id);
        showMainAH(player, returnPage);
    }

    // ─── My listings ──────────────────────────────────────────────────────────

    private void showMyListings(Player player) {
        List<AhStorage.Listing> mine = storage.getPlayerListings(player.getUniqueId().toString());

        Inventory inv = plugin.getServer().createInventory(null, 27,
                "§6§lMy Listings §r§7(" + mine.size() + "/" + MAX_LISTINGS_PER_PLAYER + ")");
        ItemStack border = glass(Material.BLACK_STAINED_GLASS_PANE, "§8");
        ItemStack empty  = glass(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§8");
        for (int s : new int[]{0,1,2,3,4,5,6,7,8,9,17,18,19,20,21,22,23,24,25,26}) inv.setItem(s, border);

        int[] slots = {10,11,12,13,14};
        for (int i = 0; i < MAX_LISTINGS_PER_PLAYER; i++) {
            if (i < mine.size()) {
                AhStorage.Listing l = mine.get(i);
                NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
                List<String> lore = Arrays.asList(
                        "§7Price: §a$" + fmt.format(l.price),
                        "§7Amount: §e" + l.item.amount + "x",
                        "", "§cClick to cancel listing");
                inv.setItem(slots[i], namedItem(asMaterial(l.item.typeId), l.item.nameTag != null ? l.item.nameTag : "§f" + getItemDisplayName(l.item), lore));
            } else {
                inv.setItem(slots[i], empty);
            }
        }
        inv.setItem(22, namedItem(Material.BARRIER, "§7← Back to AH", Collections.singletonList("§7Return")));

        GuiState state = new GuiState(GuiState.Type.LISTINGS, 0);
        state.myListings = new ArrayList<>(mine);
        openMenus.put(player.getUniqueId(), state);
        player.openInventory(inv);
    }

    private void handleMyListingsClick(Player player, GuiState state, int slot) {
        if (slot == 22) { showMainAH(player, 0); return; }
        int[] slots = {10,11,12,13,14};
        for (int i = 0; i < slots.length; i++) {
            if (slot == slots[i] && i < state.myListings.size()) {
                AhStorage.Listing l = state.myListings.get(i);
                storage.removeListing(l.id);
                storage.addToCollection(player.getUniqueId().toString(), l.item);
                player.sendMessage("§a§l✓ Listing Cancelled!");
                player.sendMessage("§7Your §e" + getItemDisplayName(l.item) + " §7moved to §bCollection");
                showMyListings(player);
                return;
            }
        }
    }

    // ─── Collection ───────────────────────────────────────────────────────────

    private void showCollection(Player player, int page) {
        List<AhStorage.CollectionEntry> col = storage.getPlayerCollection(player.getUniqueId().toString());
        int totalPages = Math.max(1, (int) Math.ceil((double) col.size() / PAGE_SIZE));
        int current    = Math.max(0, Math.min(page, totalPages - 1));
        List<AhStorage.CollectionEntry> slice = col.subList(
                current * PAGE_SIZE, Math.min((current + 1) * PAGE_SIZE, col.size()));

        Inventory inv = plugin.getServer().createInventory(null, 54,
                "§b§lCollection §r§7(" + (current + 1) + "/" + totalPages + ")");
        ItemStack border = glass(Material.BLACK_STAINED_GLASS_PANE, "§8");
        ItemStack empty  = glass(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§8");
        for (int s : borderSlots(54)) inv.setItem(s, border);

        List<Integer> usable = usableSlots54();
        for (int i = 0; i < slice.size() && i < usable.size(); i++) {
            AhStorage.CollectionEntry e = slice.get(i);
            List<String> lore = Arrays.asList("§7Amount: §e" + e.item.amount + "x", "", "§aClick to claim");
            inv.setItem(usable.get(i), namedItem(asMaterial(e.item.typeId),
                    e.item.nameTag != null ? e.item.nameTag : "§f" + getItemDisplayName(e.item), lore));
        }
        for (int i = slice.size(); i < usable.size(); i++) inv.setItem(usable.get(i), empty);

        if (current > 0) inv.setItem(45, namedItem(Material.ARROW, "§e◀ Previous", null));
        inv.setItem(49, namedItem(Material.BARRIER, "§7← Back to AH", null));
        if (current < totalPages - 1) inv.setItem(53, namedItem(Material.ARROW, "§eNext ▶", null));

        GuiState state = new GuiState(GuiState.Type.COLLECT, current);
        state.colSlice   = new ArrayList<>(slice);
        state.usableSlots = new ArrayList<>(usable);
        openMenus.put(player.getUniqueId(), state);
        player.openInventory(inv);
    }

    private void handleCollectionClick(Player player, GuiState state, int slot) {
        int totalPages = Math.max(1, (int) Math.ceil(
                (double) storage.getPlayerCollection(player.getUniqueId().toString()).size() / PAGE_SIZE));
        if (slot == 45 && state.page > 0) { showCollection(player, state.page - 1); return; }
        if (slot == 53 && state.page < totalPages - 1) { showCollection(player, state.page + 1); return; }
        if (slot == 49) { showMainAH(player, 0); return; }

        int idx = state.usableSlots.indexOf(slot);
        if (idx >= 0 && idx < state.colSlice.size()) {
            AhStorage.CollectionEntry e = state.colSlice.get(idx);
            if (player.getInventory().firstEmpty() == -1) {
                player.sendMessage("§c§l✗ Inventory Full! Need at least 1 empty slot!");
                showCollection(player, state.page); return;
            }
            player.getInventory().addItem(deserializeItem(e.item));
            storage.removeFromCollection(player.getUniqueId().toString(), e.id);
            player.sendMessage("§a§l✓ Item Claimed! §7Received §e" + getItemDisplayName(e.item));
            showCollection(player, state.page);
        }
    }

    // ─── Sell process ─────────────────────────────────────────────────────────

    private void startSellProcess(Player player) {
        if (storage.getPlayerListingCount(player.getUniqueId().toString()) >= MAX_LISTINGS_PER_PLAYER) {
            player.sendMessage("§c§l✗ Listing Limit! Cancel a listing to make space.");
            showMainAH(player, 0); return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) {
            player.sendMessage("§c§l✗ No Item Held! Hold an item to list it.");
            showMainAH(player, 0); return;
        }
        showPriceSelector(player, held, 0);
    }

    private void showPriceSelector(Player player, ItemStack item, int price) {
        NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
        Inventory inv = plugin.getServer().createInventory(null, 54,
                "§6§lSet Price: §a$" + fmt.format(price));

        // Held item preview at slot 13
        inv.setItem(13, item.clone());

        // Increment row (slots 18-26)
        int[] incAmts = {1,10,100,1000,10000,100000,1000000,10000000,100000000};
        for (int i = 0; i < 9; i++) {
            if (price + incAmts[i] <= 1_000_000_000) {
                inv.setItem(18 + i, namedItem(Material.LIME_DYE, "§a+$" + fmt.format(incAmts[i]),
                        Collections.singletonList("§7Add to price")));
            }
        }

        // Decrement row (slots 36-44)
        for (int i = 0; i < 9; i++) {
            if (price - incAmts[i] >= 0) {
                inv.setItem(36 + i, namedItem(Material.RED_DYE, "§c-$" + fmt.format(incAmts[i]),
                        Collections.singletonList("§7Subtract from price")));
            }
        }

        if (price > 0) inv.setItem(49, namedItem(Material.EMERALD_BLOCK, "§a§l✓ LIST ITEM",
                Collections.singletonList("§7List for §a$" + fmt.format(price))));
        inv.setItem(53, namedItem(Material.BARRIER, "§c§l✗ CANCEL", null));

        GuiState state = new GuiState(GuiState.Type.SELL, 0);
        state.sellItem  = item;
        state.sellPrice = price;
        openMenus.put(player.getUniqueId(), state);
        player.openInventory(inv);
    }

    private void handleSellClick(Player player, GuiState state, int slot) {
        int price = state.sellPrice;
        int[] incAmts = {1,10,100,1000,10000,100000,1000000,10000000,100000000};

        if (slot == 53) { showMainAH(player, 0); return; }
        if (slot == 49 && price > 0) {
            confirmListing(player, state.sellItem, price); return;
        }
        for (int i = 0; i < 9; i++) {
            if (slot == 18 + i && price + incAmts[i] <= 1_000_000_000) {
                showPriceSelector(player, state.sellItem, price + incAmts[i]); return;
            }
            if (slot == 36 + i && price - incAmts[i] >= 0) {
                showPriceSelector(player, state.sellItem, price - incAmts[i]); return;
            }
        }
    }

    private void confirmListing(Player player, ItemStack item, int price) {
        if (storage.getPlayerListingCount(player.getUniqueId().toString()) >= MAX_LISTINGS_PER_PLAYER) {
            player.sendMessage("§c§l✗ Listing Limit! Cancel a listing to make space.");
            showMainAH(player, 0); return;
        }
        AhStorage.AhItemData data = serializeItem(item);
        AhStorage.Listing listing = storage.createListing(
                player.getUniqueId().toString(), player.getName(), data, price);
        if (listing == null) {
            player.sendMessage("§c§l✗ Failed to List! Error creating listing.");
            showMainAH(player, 0); return;
        }
        // Remove from inventory
        player.getInventory().removeItem(item);
        NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
        player.sendMessage("§a§l✓ Item Listed!");
        player.sendMessage("§7Listed §e" + getItemDisplayName(data) + " §7for §a$" + fmt.format(price));
        showMainAH(player, 0);
    }

    // ─── Auto-backup ──────────────────────────────────────────────────────────

    private void startAutoBackup() {
        new BukkitRunnable() {
            @Override public void run() { storage.backupAHData(); }
        }.runTaskTimerAsynchronously(plugin, BACKUP_INTERVAL_TICKS, BACKUP_INTERVAL_TICKS);
    }

    // ─── Money helpers ────────────────────────────────────────────────────────

    private int getPlayerMoney(Player player) {
        try {
            Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective(MONEY_OBJECTIVE);
            if (obj == null) return 0;
            Score score = obj.getScore(player.getName());
            return score.isScoreSet() ? score.getScore() : 0;
        } catch (Exception e) { return 0; }
    }

    private boolean setPlayerMoney(Player player, int amount) {
        try {
            Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective(MONEY_OBJECTIVE);
            if (obj == null) obj = board.registerNewObjective(MONEY_OBJECTIVE, "dummy", MONEY_DISPLAY);
            obj.getScore(player.getName()).setScore(amount);
            return true;
        } catch (Exception e) { return false; }
    }

    private boolean addPlayerMoney(Player player, int amount) {
        return setPlayerMoney(player, getPlayerMoney(player) + amount);
    }

    private boolean removePlayerMoney(Player player, int amount) {
        int current = getPlayerMoney(player);
        if (current < amount) return false;
        return setPlayerMoney(player, current - amount);
    }

    // ─── Item serialization ───────────────────────────────────────────────────

    /** Mirrors serializeItem() in ah_system.js. */
    private AhStorage.AhItemData serializeItem(ItemStack stack) {
        AhStorage.AhItemData data = new AhStorage.AhItemData();
        data.typeId  = stack.getType().getKey().toString();
        data.amount  = stack.getAmount();
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            data.nameTag = meta.hasDisplayName() ? meta.getDisplayName() : null;
            data.lore    = meta.hasLore() ? meta.getLore() : new ArrayList<>();
            for (Map.Entry<Enchantment, Integer> entry : stack.getEnchantments().entrySet()) {
                AhStorage.EnchantData ed = new AhStorage.EnchantData();
                ed.type  = entry.getKey().getKey().toString();
                ed.level = entry.getValue();
                data.enchantments.add(ed);
            }
        }
        return data;
    }

    /** Mirrors deserializeItem() in ah_system.js. */
    private ItemStack deserializeItem(AhStorage.AhItemData data) {
        Material mat = asMaterial(data.typeId);
        ItemStack stack = new ItemStack(mat, data.amount);
        ItemMeta meta   = stack.getItemMeta();
        if (meta != null) {
            if (data.nameTag != null) meta.setDisplayName(data.nameTag);
            if (data.lore != null && !data.lore.isEmpty()) meta.setLore(data.lore);
            stack.setItemMeta(meta);
        }
        if (data.enchantments != null) {
            for (AhStorage.EnchantData ed : data.enchantments) {
                try {
                    Enchantment ench = Enchantment.getByKey(
                            org.bukkit.NamespacedKey.fromString(ed.type));
                    if (ench != null) stack.addUnsafeEnchantment(ench, ed.level);
                } catch (Exception e) {
                    logger.fine("[AH] Could not apply enchantment: " + ed.type);
                }
            }
        }
        return stack;
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    private ItemStack glass(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) { meta.setDisplayName(name); item.setItemMeta(meta); }
        return item;
    }

    private ItemStack namedItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore != null) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack listingItem(AhStorage.Listing l, boolean showBalance) {
        NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
        List<String> lore = new ArrayList<>(Arrays.asList(
                "§7Seller: §e" + l.sellerName,
                "§7Price: §a$" + fmt.format(l.price),
                "§7Amount: §e" + l.item.amount + "x",
                "",
                "§aClick to purchase"));
        if (l.item.lore != null && !l.item.lore.isEmpty()) {
            lore.add(""); lore.add("§7Original Lore:"); lore.addAll(l.item.lore);
        }
        return namedItem(asMaterial(l.item.typeId),
                l.item.nameTag != null ? l.item.nameTag : "§f" + getItemDisplayName(l.item), lore);
    }

    private static int[] borderSlots(int size) {
        Set<Integer> set = new LinkedHashSet<>();
        // Top row
        for (int i = 0; i < 9; i++) set.add(i);
        // Bottom row
        for (int i = size - 9; i < size; i++) set.add(i);
        // Left and right columns
        for (int i = 0; i < size / 9; i++) { set.add(i * 9); set.add(i * 9 + 8); }
        return set.stream().mapToInt(Integer::intValue).sorted().toArray();
    }

    private static List<Integer> usableSlots54() {
        List<Integer> list = new ArrayList<>();
        for (int row = 1; row < 5; row++)
            for (int col = 1; col < 8; col++)
                list.add(row * 9 + col);
        return list;
    }

    private static String getItemDisplayName(AhStorage.AhItemData item) {
        if (item.nameTag != null) return item.nameTag;
        String[] parts = item.typeId.split("[:/]");
        String name = parts[parts.length - 1];
        StringBuilder sb = new StringBuilder();
        for (String word : name.split("_")) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    private static Material asMaterial(String typeId) {
        String key = typeId.contains(":") ? typeId.split(":")[1] : typeId;
        try { return Material.matchMaterial(key.toUpperCase()); }
        catch (Exception e) { return Material.PAPER; }
    }

    // ─── GUI state ────────────────────────────────────────────────────────────

    private static class GuiState {
        enum Type { MAIN, CONFIRM, LISTINGS, COLLECT, SELL }

        final Type type;
        int page;
        List<AhStorage.Listing>        pageSlice;
        List<Integer>                  usableSlots;
        AhStorage.Listing              activeListing;
        boolean                        canAfford;
        List<AhStorage.Listing>        myListings;
        List<AhStorage.CollectionEntry> colSlice;
        ItemStack                      sellItem;
        int                            sellPrice;

        GuiState(Type type, int page) { this.type = type; this.page = page; }
    }
}

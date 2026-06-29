package com.skyblock.gemshop;

import com.skyblock.crates.CrateSystem;
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
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

/**
 * Gem Shop System — /gemshop
 *
 * Translated from gemshop_system.js.
 *
 * Features:
 *  - Currency: "gems" scoreboard objective.
 *  - Sell custom fish (tropical_fish with lore Rarity: ...) for gems.
 *  - Buy fishing rods and crate keys with gems.
 *  - Pro Rod: Lure III + Luck of the Sea III, re-enchanted on interval.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     gemshop:
 *       description: Open the gem shop
 *       usage: /gemshop
 */
public class GemshopSystem implements CommandExecutor, Listener {

    // ─── Fish sell prices (mirror FISH_PRICES) ────────────────────────────────
    private static final Map<String, Integer> FISH_PRICES = Map.of(
            "Common",    1,
            "Uncommon",  3,
            "Rare",      5,
            "Epic",      7,
            "Legendary", 10
            // Mythical: 0, unsellable — omitted from map so price lookup returns null
    );

    // ─── Shop entry types ─────────────────────────────────────────────────────
    private enum EntryType { ROD_BASIC, ROD_PRO, KEY }

    private static class ShopEntry {
        final EntryType type;
        final String key;         // for KEY entries
        final String label;
        final List<String> lore;
        final Material material;
        final int price;
        final boolean enchanted;

        ShopEntry(EntryType type, String key, String label, List<String> lore,
                  Material material, int price, boolean enchanted) {
            this.type = type;
            this.key = key;
            this.label = label;
            this.lore = lore;
            this.material = material;
            this.price = price;
            this.enchanted = enchanted;
        }
    }

    // Inner usable slots: 1-block border, rows 1-4, cols 1-7
    // Row 1: 10-16, Row 2: 19-25, Row 3: 28-34, Row 4: 37-43
    private static final int[] INNER_SLOTS;
    static {
        List<Integer> slots = new ArrayList<>();
        for (int row = 1; row <= 4; row++)
            for (int col = 1; col <= 7; col++)
                slots.add(row * 9 + col);
        INNER_SLOTS = slots.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final int SELL_SLOT = 46;
    private static final String PRO_ROD_TAG = "[PRO_ROD]";
    private static final String OBJECTIVE_NAME = "gems";
    private static final String GUI_TITLE = "§3§lGEM SHOP";

    private final JavaPlugin plugin;
    private final CrateSystem crateSystem;
    private final List<ShopEntry> shopEntries;
    private final Map<UUID, Integer> openMenuPage = new HashMap<>();

    public GemshopSystem(JavaPlugin plugin, CrateSystem crateSystem) {
        this.plugin = plugin;
        this.crateSystem = crateSystem;
        this.shopEntries = buildShopEntries();

        // Auto-enchant interval: sweep all inventories every 2 seconds (40 ticks)
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::autoEnchantInterval, 0L, 40L);
    }

    // ─── Build shop entry list ────────────────────────────────────────────────
    private List<ShopEntry> buildShopEntries() {
        return List.of(
            new ShopEntry(EntryType.ROD_BASIC, null,
                "§fFishing Rod",
                List.of("§7A basic fishing rod", "", "§7Price: §e1 gem"),
                Material.FISHING_ROD, 1, false),

            new ShopEntry(EntryType.ROD_PRO, null,
                "§bPro Fishing Rod",
                List.of("§7Lure III + Luck of the Sea III", "", "§7Price: §e50 gems"),
                Material.FISHING_ROD, 50, true),

            new ShopEntry(EntryType.KEY, "basic",
                "§r§l§7Basic §fKey §7✦",
                List.of("§7Opens the §7Basic Crate", "", "§7Price: §e25 gems"),
                Material.QUARTZ, 25, false),

            new ShopEntry(EntryType.KEY, "common",
                "§r§l§2Common §aKey §7✦",
                List.of("§7Opens the §aCommon Crate", "", "§7Price: §e60 gems"),
                Material.SLIME_BALL, 60, false),

            new ShopEntry(EntryType.KEY, "aqua",
                "§r§l§3Aqua §bKey §7✦",
                List.of("§7Opens the §bAqua Crate", "", "§7Price: §e250 gems"),
                Material.HEART_OF_THE_SEA, 250, false),

            new ShopEntry(EntryType.KEY, "nova",
                "§r§l§dNova §dKey §7✦",
                List.of("§7Opens the §dNova Crate", "", "§7Price: §e1,200 gems"),
                Material.AMETHYST_SHARD, 1200, false),

            new ShopEntry(EntryType.KEY, "void",
                "§r§l§0Void §7Key §7✦",
                List.of("§7Opens the §8Void Crate", "", "§7Price: §e25,000 gems"),
                Material.FLINT, 25000, false)
        );
    }

    // ─── /gemshop command ────────────────────────────────────────────────────
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }
        showGemShop(player);
        return true;
    }

    // ─── Gem helpers (mirror scoreboard-based JS helpers) ────────────────────
    private Objective getOrCreateObjective() {
        Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
        Objective obj = board.getObjective(OBJECTIVE_NAME);
        if (obj == null)
            obj = board.registerNewObjective(OBJECTIVE_NAME, "dummy", "Gems");
        return obj;
    }

    public int getPlayerGems(Player player) {
        try {
            Objective obj = getOrCreateObjective();
            return obj.getScore(player.getName()).getScore();
        } catch (Exception e) { return 0; }
    }

    public void addPlayerGems(Player player, int amount) {
        try {
            Objective obj = getOrCreateObjective();
            int cur = obj.getScore(player.getName()).getScore();
            obj.getScore(player.getName()).setScore(cur + amount);
        } catch (Exception ignored) {}
    }

    /** @return true if gems were successfully removed, false if insufficient funds */
    public boolean removePlayerGems(Player player, int amount) {
        try {
            Objective obj = getOrCreateObjective();
            int cur = obj.getScore(player.getName()).getScore();
            if (cur < amount) return false;
            obj.getScore(player.getName()).setScore(cur - amount);
            return true;
        } catch (Exception e) { return false; }
    }

    // ─── Fish rarity detection (mirror getFishRarity) ─────────────────────────
    private String getFishRarity(ItemStack item) {
        if (item == null || item.getType() != Material.TROPICAL_FISH) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        List<String> lore = meta.getLore();
        if (lore == null || lore.isEmpty()) return null;
        String line = lore.get(0);
        if (!line.contains("Rarity:")) return null;
        if (line.contains("§7Common"))    return "Common";
        if (line.contains("§aUncommon"))  return "Uncommon";
        if (line.contains("§bRare"))      return "Rare";
        if (line.contains("§5Epic"))      return "Epic";
        if (line.contains("§6Legendary")) return "Legendary";
        if (line.contains("§3Mythical"))  return "Mythical";
        return null;
    }

    // ─── Sell all fish (mirror sellAllFish) ───────────────────────────────────
    public void sellAllFish(Player player) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        int totalGems = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            String rarity = getFishRarity(item);
            if (rarity == null) continue;
            Integer price = FISH_PRICES.get(rarity);
            if (price == null || price == 0) continue; // Mythical or unknown
            int earned = price * item.getAmount();
            totalGems += earned;
            counts.merge(rarity, item.getAmount(), Integer::sum);
            inv.setItem(i, null);
        }

        player.sendMessage("§3§l[GEM SHOP]");
        if (totalGems > 0) {
            addPlayerGems(player, totalGems);
            player.sendMessage("§aSold fish for §e" + totalGems + " gems§a!");
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                player.sendMessage("§7  " + e.getValue() + "x §f" + e.getKey() +
                        " §7(" + FISH_PRICES.get(e.getKey()) + " gems each)");
            }
        } else {
            player.sendMessage("§cNo sellable fish found in inventory!");
            player.sendMessage("§7(Mythical fish cannot be sold)");
        }
    }

    // ─── Pro Rod helpers ──────────────────────────────────────────────────────
    private boolean isProRod(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null == null;
        List<String> lore = meta.getLore();
        return lore != null && lore.stream().anyMatch(l -> l.contains(PRO_ROD_TAG));
    }

    private ItemStack createProRod() {
        ItemStack rod = new ItemStack(Material.FISHING_ROD);
        ItemMeta meta = rod.getItemMeta();
        meta.setDisplayName("§bPro Fishing Rod");
        meta.setLore(List.of("§7Lure III + Luck of the Sea III", "§r§8" + PRO_ROD_TAG));
        rod.setItemMeta(meta);
        return rod;
    }

    private void enchantProRod(ItemStack item) {
        if (!isProRod(item)) return;
        try {
            item.addUnsafeEnchantment(Enchantment.LURE, 3);
            item.addUnsafeEnchantment(Enchantment.LUCK_OF_THE_SEA, 3);
        } catch (Exception e) {
            plugin.getLogger().warning("[GEM SHOP] Could not enchant pro rod: " + e.getMessage());
        }
    }

    /** Sweep all online players' inventories to keep pro rods enchanted */
    private void autoEnchantInterval() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            for (ItemStack item : player.getInventory().getContents()) {
                if (isProRod(item)) enchantProRod(item);
            }
        }
    }

    // ─── Gem Shop GUI ─────────────────────────────────────────────────────────
    public void showGemShop(Player player) {
        int gems = getPlayerGems(player);
        Inventory inv = plugin.getServer().createInventory(null, 54,
                GUI_TITLE + "\n§7Balance: §e" + String.format("%,d", gems) + " gems");

        // Fill all 54 slots with cyan glass border
        ItemStack border = buildItem(Material.CYAN_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int s = 0; s < 54; s++) inv.setItem(s, border);

        // Place shop items into inner slots
        for (int i = 0; i < shopEntries.size() && i < INNER_SLOTS.length; i++) {
            ShopEntry entry = shopEntries.get(i);
            boolean canAfford = gems >= entry.price;
            List<String> lore = new ArrayList<>(entry.lore);
            lore.add("");
            lore.add(canAfford ? "§aClick to purchase!" : "§cNot enough gems!");

            ItemStack item = buildItem(entry.material, entry.label, lore);
            if (entry.enchanted) {
                item.addUnsafeEnchantment(Enchantment.LURE, 3);
            }
            inv.setItem(INNER_SLOTS[i], item);
        }

        // Sell Fish button at slot 46
        inv.setItem(SELL_SLOT, buildItem(Material.TROPICAL_FISH, "§a§lSELL ALL FISH", List.of(
                "§7Sell all custom fish in your inventory",
                "",
                "§7Common: §e1 gem each",
                "§7Uncommon: §e3 gems each",
                "§7Rare: §e5 gems each",
                "§7Epic: §e7 gems each",
                "§7Legendary: §e10 gems each",
                "§7Mythical: §cUnsellable",
                "",
                "§eClick to sell!"
        )));

        player.openInventory(inv);
    }

    private ItemStack buildItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    // ─── Inventory click handler ──────────────────────────────────────────────
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Inventory top = event.getView().getTopInventory();
        if (top == null) return;
        String title = event.getView().getTitle();
        if (!title.startsWith(GUI_TITLE)) return;

        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;

        int slot = event.getRawSlot();

        // Sell fish button
        if (slot == SELL_SLOT) {
            sellAllFish(player);
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> showGemShop(player), 2L);
            return;
        }

        // Find which shop entry was clicked
        int itemIdx = -1;
        for (int i = 0; i < INNER_SLOTS.length; i++) {
            if (INNER_SLOTS[i] == slot) { itemIdx = i; break; }
        }
        if (itemIdx == -1 || itemIdx >= shopEntries.size()) return;

        ShopEntry entry = shopEntries.get(itemIdx);
        int gems = getPlayerGems(player);

        if (gems < entry.price) {
            player.sendMessage("§c§l[GEM SHOP] §cNot enough gems!");
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> showGemShop(player), 2L);
            return;
        }

        if (!removePlayerGems(player, entry.price)) {
            player.sendMessage("§c§l[GEM SHOP] §cTransaction failed!");
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> showGemShop(player), 2L);
            return;
        }

        switch (entry.type) {
            case ROD_BASIC -> {
                player.getInventory().addItem(new ItemStack(Material.FISHING_ROD));
                player.sendMessage("§3§l[GEM SHOP] §aPurchased §fFishing Rod §afor §e" + entry.price + " gem§a!");
            }
            case ROD_PRO -> {
                ItemStack rod = createProRod();
                enchantProRod(rod);
                player.getInventory().addItem(rod);
                player.sendMessage("§3§l[GEM SHOP] §aPurchased §bPro Fishing Rod §afor §e" + entry.price + " gems§a!");
            }
            case KEY -> {
                crateSystem.giveKey(player, entry.key);
                player.sendMessage("§3§l[GEM SHOP] §aPurchased " + entry.label + "§a for §e" +
                        String.format("%,d", entry.price) + " gems§a!");
                player.playSound(player.getLocation(),
                        org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
        }

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> showGemShop(player), 2L);
    }
}

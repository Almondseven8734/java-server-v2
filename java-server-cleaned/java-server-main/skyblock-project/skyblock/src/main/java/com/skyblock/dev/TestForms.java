package com.skyblock.dev;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Logger;

/**
 * TestForms — Developer / Debug Tool
 *
 * Translated from test_forms.js.
 *
 * The original Bedrock script listened for two scripted events:
 *   "shop:test"       → show a simple action (2-button) form
 *   "shop:testchest"  → show a small chest UI with one diamond button
 *
 * In Java/Paper there are no scripted events or ActionFormData.
 * Instead, this class registers two subcommands of /testforms:
 *
 *   /testforms test      → sends a chat message and opens a simple 2-button inventory GUI
 *   /testforms testchest → sends progress messages and opens a 27-slot chest GUI
 *
 * Both match the original intent (verifying that form/GUI plumbing works).
 * Register in plugin.yml:
 *
 *   commands:
 *     testforms:
 *       description: Dev GUI test tool
 *       permission: skyblock.dev
 *
 * Register in your main plugin:
 *   TestForms tf = new TestForms(this, getLogger());
 *   getCommand("testforms").setExecutor(tf);
 *   getServer().getPluginManager().registerEvents(tf, this);
 */
public class TestForms implements CommandExecutor, Listener {

    // =========================================================================
    // CONSTANTS
    // =========================================================================

    /** Title used by the simple action-form simulation (Test 1). */
    private static final String TITLE_TEST      = "§6Test Form";

    /** Title used by the chest form simulation (Test 2). */
    private static final String TITLE_TESTCHEST = "§6Test Chest UI";

    // =========================================================================
    // STATE
    // =========================================================================

    private final JavaPlugin plugin;
    private final Logger     logger;

    /**
     * Tracks which players have a test GUI open so InventoryClickEvent can
     * route correctly.  Value: which test ("test" or "testchest").
     */
    private final Map<UUID, String> openSessions = new HashMap<>();

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public TestForms(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        logger.info("[TestForms] Dev tool loaded.");
    }

    // =========================================================================
    // COMMAND HANDLER  (/testforms <subcommand>)
    // =========================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage("§cUsage: /testforms <test|testchest>");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "test":
                runTestForm(player);
                break;
            case "testchest":
                runTestChestForm(player);
                break;
            default:
                player.sendMessage("§cUnknown subcommand. Use: test | testchest");
        }
        return true;
    }

    // =========================================================================
    // TEST 1 — Simple 2-button action form
    //
    // Original JS:
    //   id === 'shop:test'
    //   → sourceEntity.sendMessage('[TEST] Command received!')
    //   → ActionFormData with 2 buttons
    //   → On response: canceled → '[TEST] Form was canceled'
    //                  else     → '[TEST] You clicked button N'
    // =========================================================================

    private void runTestForm(Player player) {
        // Mirrors: sourceEntity.sendMessage('§a[TEST] Command received!')
        player.sendMessage("§a[TEST] Command received!");

        // In Java there is no ActionFormData; we use a 9-slot inventory with
        // two buttons to simulate the same flow.
        Inventory inv = plugin.getServer().createInventory(null, 9, TITLE_TEST);

        // Fill background
        ItemStack bg = makeItem(Material.GRAY_STAINED_GLASS_PANE, "§8", Collections.emptyList());
        for (int i = 0; i < 9; i++) inv.setItem(i, bg);

        // Button 1 (slot 2) — mirrors ActionFormData button("Button 1")
        inv.setItem(2, makeItem(Material.GREEN_STAINED_GLASS_PANE, "§aButton 1",
                Collections.singletonList("§7Click to test")));

        // Button 2 (slot 6) — mirrors ActionFormData button("Button 2")
        inv.setItem(6, makeItem(Material.RED_STAINED_GLASS_PANE, "§cButton 2",
                Collections.singletonList("§7Click to test")));

        openSessions.put(player.getUniqueId(), "test");
        player.openInventory(inv);
    }

    // =========================================================================
    // TEST 2 — Small chest UI with one diamond button
    //
    // Original JS:
    //   id === 'shop:testchest'
    //   → multiple debug messages confirming each step
    //   → ChestFormData('small') (27-slot chest)
    //   → One button at slot 0: '§eTest Button', lore ['§7This is a test'],
    //     texture 'minecraft:diamond', amount 1
    //   → On response: canceled → '[TEST] Chest form canceled'
    //                  else     → '[TEST] Selection: N'
    // =========================================================================

    private void runTestChestForm(Player player) {
        // Mirrors the step-by-step debug messages in the JS
        player.sendMessage("§a[TEST] Creating chest form...");

        Inventory inv;
        try {
            inv = plugin.getServer().createInventory(null, 27, TITLE_TESTCHEST);
            player.sendMessage("§a[TEST] ChestFormData created");

            // Title is already set via createInventory — mirrors chestForm.title(...)
            player.sendMessage("§a[TEST] Title set");

            // Slot 0: diamond button — mirrors chestForm.button(0, '§eTest Button', ...)
            inv.setItem(0, makeItem(Material.DIAMOND, "§eTest Button",
                    Collections.singletonList("§7This is a test")));
            player.sendMessage("§a[TEST] Button added");

            player.sendMessage("§a[TEST] Showing form...");
        } catch (Exception e) {
            // Mirrors the catch block: sourceEntity.sendMessage('[TEST] Exception: ...')
            player.sendMessage("§c[TEST] Exception: " + e.getMessage());
            return;
        }

        openSessions.put(player.getUniqueId(), "testchest");
        player.openInventory(inv);
    }

    // =========================================================================
    // INVENTORY CLICK HANDLER
    // Mirrors the .then(response => { ... }) callbacks in the JS
    // =========================================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        UUID uid = player.getUniqueId();

        String mode = openSessions.get(uid);
        if (mode == null) return;

        event.setCancelled(true);

        String title = event.getView().getTitle();

        // Guard: only handle our own inventories
        if (!title.equals(TITLE_TEST) && !title.equals(TITLE_TESTCHEST)) return;

        int slot = event.getRawSlot();
        ItemStack clicked = event.getCurrentItem();

        if (clicked == null || clicked.getType() == Material.AIR
                || clicked.getType() == Material.GRAY_STAINED_GLASS_PANE) {
            // Mirrors response.canceled (clicked background / closed without selecting)
            player.sendMessage("§e[TEST] Form was canceled");
            player.closeInventory();
            return;
        }

        switch (mode) {
            case "test":
                // Mirrors: sourceEntity.sendMessage('[TEST] You clicked button N')
                // Slot 2 → selection 0 (Button 1), slot 6 → selection 1 (Button 2)
                int selection = (slot == 2) ? 0 : 1;
                player.sendMessage("§a[TEST] You clicked button " + selection);
                player.closeInventory();
                break;

            case "testchest":
                // Mirrors: sourceEntity.sendMessage('[TEST] Got response!')
                //          sourceEntity.sendMessage('[TEST] Selection: N')
                player.sendMessage("§a[TEST] Got response!");
                player.sendMessage("§a[TEST] Selection: " + slot);
                player.closeInventory();
                break;
        }
    }

    // =========================================================================
    // INVENTORY CLOSE — clean up session when player closes GUI manually
    // Mirrors response.canceled path for the case where the player just closes
    // =========================================================================

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        UUID uid = event.getPlayer().getUniqueId();
        String mode = openSessions.remove(uid);

        if (mode == null) return;

        // Only send the "canceled" message if the inventory really was ours
        String title = event.getView().getTitle();
        if (title.equals(TITLE_TEST) || title.equals(TITLE_TESTCHEST)) {
            // Mimic: sourceEntity.sendMessage('§e[TEST] Form was canceled') on close
            // (only fires if closeInventory() wasn't already called from the click handler)
            event.getPlayer().sendMessage("§e[TEST] Form was canceled");
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
}

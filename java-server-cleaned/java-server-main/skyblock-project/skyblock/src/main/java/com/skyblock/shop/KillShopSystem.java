package com.skyblock.shop;

import com.skyblock.crates.CrateSystem;
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
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.List;
import java.util.logging.Logger;

/**
 * Kill Shop System — /killshop
 *
 * A GUI shop where players spend Kill Credits for rewards.
 * Currency: "KillCredit" scoreboard objective (tracked by KillSystem).
 *
 * Current items:
 *   - Kill Key: 25 Kill Credits
 */
public class KillShopSystem implements CommandExecutor, Listener {

    private static final String GUI_TITLE      = "§4§l☠ KILL SHOP";
    private static final String OBJ_CREDITS    = KillSystem.OBJ_KILL_CREDIT;
    private static final int    KILL_KEY_COST  = 25;

    private final JavaPlugin  plugin;
    private final CrateSystem crateSystem;
    private final Logger      logger;

    public KillShopSystem(JavaPlugin plugin, CrateSystem crateSystem, Logger logger) {
        this.plugin      = plugin;
        this.crateSystem = crateSystem;
        this.logger      = logger;
        logger.info("[KillShopSystem] /killshop GUI loaded.");
    }

    // ─── /killshop ───────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use /killshop.");
            return true;
        }
        openShop(player);
        return true;
    }

    private void openShop(Player player) {
        int credits = getCredits(player);
        Inventory inv = plugin.getServer().createInventory(null, 27, GUI_TITLE);

        // Border
        ItemStack pane = buildItem(Material.RED_STAINED_GLASS_PANE, "§r", List.of());
        for (int i : new int[]{0,1,2,3,4,5,6,7,8,9,17,18,19,20,21,22,23,24,25,26}) {
            inv.setItem(i, pane);
        }

        // Credits display
        inv.setItem(4, buildItem(Material.NETHER_STAR,
                "§6⚔ Your Kill Credits",
                List.of("", "§eCredits: §f" + credits)));

        // Kill Key item (slot 13)
        boolean canAfford = credits >= KILL_KEY_COST;
        inv.setItem(13, buildItem(Material.TRIAL_KEY,
                "§r§l§cKill §6Key §7✦",
                List.of(
                    "",
                    "§7Open the §cKill Crate §7for",
                    "§7exclusive combat rewards.",
                    "",
                    "§6Cost: §f" + KILL_KEY_COST + " Kill Credits",
                    "",
                    canAfford ? "§aClick to purchase!" : "§cNot enough Kill Credits"
                )));

        player.openInventory(inv);
    }

    // ─── Click handler ────────────────────────────────────────────────────────

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!event.getView().getTitle().equals(GUI_TITLE)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;

        if (event.getRawSlot() == 13) {
            int credits = getCredits(player);
            if (credits < KILL_KEY_COST) {
                player.sendMessage("§c✖ Not enough kill credits. You need §e" + KILL_KEY_COST +
                        " §cbut have §e" + credits + "§c.");
                return;
            }
            setCredits(player, credits - KILL_KEY_COST);
            boolean given = crateSystem.giveKey(player, "kill");
            if (given) {
                player.sendMessage("§a✔ You spent §6" + KILL_KEY_COST +
                        " §akill credits and received a §r§l§cKill §6Key§a!");
                player.closeInventory();
            } else {
                setCredits(player, credits); // refund
                player.sendMessage("§c✖ Could not give Kill Key — credits refunded.");
            }
        }
    }

    // ─── Scoreboard helpers ───────────────────────────────────────────────────

    private int getCredits(Player player) {
        try {
            Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective(OBJ_CREDITS);
            if (obj == null) return 0;
            var score = obj.getScore(player.getName());
            return score.isScoreSet() ? score.getScore() : 0;
        } catch (Exception e) { return 0; }
    }

    private void setCredits(Player player, int value) {
        try {
            Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective(OBJ_CREDITS);
            if (obj != null) obj.getScore(player.getName()).setScore(value);
        } catch (Exception e) {
            logger.warning("[KillShopSystem] setCredits error: " + e.getMessage());
        }
    }

    // ─── Item builder ─────────────────────────────────────────────────────────

    private ItemStack buildItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
}

package com.skyblock.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.skyblock.shop.SellTrashSystem;

/**
 * Sell Commands — /sell, /sellall
 *
 * Translated from sell_command.js.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     sell:
 *       description: Open the sell menu — click items in your inventory to sell them instantly
 *       usage: /sell
 *     sellall:
 *       description: Instantly sell all sellable items in your inventory
 *       usage: /sellall
 */
public class SellCommand implements CommandExecutor {

    private final SellTrashSystem sellTrashSystem;

    public SellCommand(SellTrashSystem sellTrashSystem) {
        this.sellTrashSystem = sellTrashSystem;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        try {
            if (command.getName().equalsIgnoreCase("sellall")) {
                sellTrashSystem.sellAllItems(player);
            } else {
                sellTrashSystem.showSellInventoryMenu(player);
            }
        } catch (Exception e) {
            player.sendMessage("§cFailed to open sell menu: " + e.getMessage());
        }

        return true;
    }
}

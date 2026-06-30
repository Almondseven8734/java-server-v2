package com.skyblock.commands;

import com.skyblock.shop.ShopSystem;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Shop Command — /shop
 *
 * Translated from shop_command.js.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     shop:
 *       description: Open the shop menu
 *       usage: /shop
 */
public class ShopCommand implements CommandExecutor {

    private final ShopSystem shopSystem;

    public ShopCommand(ShopSystem shopSystem) {
        this.shopSystem = shopSystem;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        try {
            shopSystem.showCategoryMenu(player);
        } catch (Exception e) {
            player.sendMessage("§cFailed to open shop: " + e.getMessage());
        }

        return true;
    }
}

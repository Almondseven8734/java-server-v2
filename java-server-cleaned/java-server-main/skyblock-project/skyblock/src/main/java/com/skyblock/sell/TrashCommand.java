package com.skyblock.sell;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.skyblock.shop.SellTrashSystem;

/**
 * Trash Command — /trash
 *
 * Translated from trash_command.js.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     trash:
 *       description: Open the trash/disposal bin to destroy unwanted items
 *       usage: /trash
 */
public class TrashCommand implements CommandExecutor {

    private final SellTrashSystem sellTrashSystem;

    public TrashCommand(SellTrashSystem sellTrashSystem) {
        this.sellTrashSystem = sellTrashSystem;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        try {
            sellTrashSystem.showTrashMenu(player);
        } catch (Exception e) {
            player.sendMessage("§cFailed to open trash bin: " + e.getMessage());
        }

        return true;
    }
}

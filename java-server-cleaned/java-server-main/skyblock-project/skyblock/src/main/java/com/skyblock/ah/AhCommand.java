package com.skyblock.ah;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Auction House Command — /ah
 *
 * Translated from ah_command.js.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     ah:
 *       description: Open the auction house
 *       usage: /ah
 */
public class AhCommand implements CommandExecutor {

    private final AhSystem ahSystem;

    public AhCommand(AhSystem ahSystem) {
        this.ahSystem = ahSystem;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        try {
            ahSystem.showMainAH(player, 0);
        } catch (Exception e) {
            player.sendMessage("§cFailed to open auction house: " + e.getMessage());
        }

        return true;
    }
}

package com.skyblock.commands;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * /crates — teleports the player to the crates area at 24, 285, 0.
 */
public class CrateCommand implements CommandExecutor {

    private static final double CRATES_X = 24;
    private static final double CRATES_Y = 285;
    private static final double CRATES_Z = 0;

    private final JavaPlugin plugin;

    public CrateCommand(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cOnly players can use /crates.");
            return true;
        }
        World world = plugin.getServer().getWorld("world");
        if (world == null) {
            player.sendMessage("§cCould not find world.");
            return true;
        }
        player.teleport(new Location(world, CRATES_X, CRATES_Y, CRATES_Z));
        player.sendMessage("§6✦ §eWelcome to the §6Crates §earea!");
        return true;
    }
}

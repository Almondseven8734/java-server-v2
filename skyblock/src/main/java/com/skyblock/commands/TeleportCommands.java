package com.skyblock.commands;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.logging.Logger;

/**
 * Teleport Commands
 *
 * Translated from teleport_commands.js.
 *
 * Registers two commands:
 *   /mine      — teleports to the mine entrance
 *   /pvp       — teleports to the PvP arena
 *
 * (/dungeons used to be a stub handled here; it's now a real alias of
 * /dungeon, routed to DungeonCommand - see plugin.yml.)
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     mine:
 *       description: Teleport to the mine
 *       usage: /mine
 *     pvp:
 *       description: Teleport to the PvP arena
 *       usage: /pvp
 */
public class TeleportCommands implements CommandExecutor {

    // ─── Destinations (match JS constants exactly) ────────────────────────────
    private static final double MINE_X = 296, MINE_Y = 201, MINE_Z = 0;
    /** West-facing yaw: in JS rotation.y = 90 → in Bukkit yaw = 90 */
    private static final float  MINE_YAW = 90f, MINE_PITCH = 0f;

    private static final double PVP_X = -121, PVP_Y = 226, PVP_Z = 0;
    private static final float  PVP_YAW = 90f, PVP_PITCH = 0f;

    private final Logger logger;

    public TeleportCommands(Logger logger) {
        this.logger = logger;
        logger.info("[Teleport] /mine and /pvp commands loaded.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        World world = player.getWorld();

        switch (command.getName().toLowerCase()) {

            case "mine": {
                Location dest = new Location(world, MINE_X, MINE_Y, MINE_Z, MINE_YAW, MINE_PITCH);
                teleport(player, dest, "§aTeleported to the §eMine§a!");
                break;
            }

            case "pvp": {
                Location dest = new Location(world, PVP_X, PVP_Y, PVP_Z, PVP_YAW, PVP_PITCH);
                teleport(player, dest, "§aTeleported to the §cPvP Arena§a!");
                break;
            }

            default:
                return false;
        }

        return true;
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private void teleport(Player player, Location dest, String successMsg) {
        try {
            player.teleport(dest);
            player.sendMessage(successMsg);
        } catch (Exception e) {
            player.sendMessage("§cFailed to teleport: " + e.getMessage());
            logger.warning("[Teleport] Teleport error for " + player.getName() + ": " + e.getMessage());
        }
    }
}

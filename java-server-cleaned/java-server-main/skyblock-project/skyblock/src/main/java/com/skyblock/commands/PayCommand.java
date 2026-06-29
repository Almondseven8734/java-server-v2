package com.skyblock.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Pay Command
 *
 * Translated from pay_command.js.
 *
 * /pay <player> <amount> — transfers money from the sender to another
 * online player via the Money scoreboard objective.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     pay:
 *       description: Send money to another player
 *       usage: /pay <player> <amount>
 */
public class PayCommand implements CommandExecutor {

    private static final String MONEY_OBJECTIVE = "Money";
    private static final String MONEY_DISPLAY   = "§6Money";

    private final Logger logger;

    public PayCommand(Logger logger) {
        this.logger = logger;
        logger.info("[Pay] /pay command loaded.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length < 2) {
            player.sendMessage("§cUsage: /pay <player> <amount>");
            player.sendMessage("§7Example: §a/pay AlmondSeven8734 1000");
            return true;
        }

        String targetName = args[0];

        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§c§l✖ Invalid Amount!");
            player.sendMessage("§7Amount must be a whole number.");
            return true;
        }

        // Validate amount
        if (amount <= 0) {
            player.sendMessage("§c§l✖ Invalid Amount!");
            player.sendMessage("§7Amount must be a positive number!");
            player.sendMessage("§7Example: §a/pay AlmondSeven8734 1000");
            return true;
        }

        // Find target player (case-insensitive, online only)
        Player target = null;
        for (Player p : player.getServer().getOnlinePlayers()) {
            if (p.getName().equalsIgnoreCase(targetName)) {
                target = p;
                break;
            }
        }

        if (target == null) {
            player.sendMessage("§c§l✖ Player Not Found!");
            player.sendMessage("§7Could not find player: §e" + targetName);
            StringBuilder online = new StringBuilder();
            for (Player p : player.getServer().getOnlinePlayers()) {
                if (online.length() > 0) online.append("§7, §a");
                online.append("§a").append(p.getName());
            }
            if (online.length() > 0) {
                player.sendMessage("§7Online players: " + online);
            }
            return true;
        }

        // Check self-payment
        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage("§c§l✖ Invalid Transaction!");
            player.sendMessage("§7You cannot pay yourself!");
            return true;
        }

        // Check sender balance
        int senderBalance = getPlayerMoney(player);
        if (senderBalance < amount) {
            NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
            player.sendMessage("§c§l✖ Insufficient Funds!");
            player.sendMessage("§7You have: §c$" + fmt.format(senderBalance));
            player.sendMessage("§7You need: §e$" + fmt.format(amount));
            player.sendMessage("§7Short by: §c$" + fmt.format(amount - senderBalance));
            return true;
        }

        // Process transaction
        if (removePlayerMoney(player, amount)) {
            if (addPlayerMoney(target, amount)) {
                NumberFormat fmt = NumberFormat.getNumberInstance(Locale.US);
                player.sendMessage("§a§l✓ Payment Sent!");
                player.sendMessage("§7Sent §a$" + fmt.format(amount) + " §7to §e" + target.getName());
                player.sendMessage("§7New balance: §a$" + fmt.format(getPlayerMoney(player)));

                target.sendMessage("§a§l✓ Payment Received!");
                target.sendMessage("§7Received §a$" + fmt.format(amount) + " §7from §e" + player.getName());
                target.sendMessage("§7New balance: §a$" + fmt.format(getPlayerMoney(target)));
            } else {
                // Refund if target credit failed
                addPlayerMoney(player, amount);
                player.sendMessage("§c§l✖ Transaction Failed!");
                player.sendMessage("§7Failed to send money. Your money has been refunded.");
            }
        } else {
            player.sendMessage("§c§l✖ Transaction Failed!");
            player.sendMessage("§7An error occurred while processing the payment.");
        }

        return true;
    }

    // ─── Scoreboard money helpers ─────────────────────────────────────────────

    public static int getPlayerMoney(Player player) {
        try {
            Scoreboard board = player.getServer().getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective(MONEY_OBJECTIVE);
            if (obj == null) return 0;
            Score score = obj.getScore(player.getName());
            return score.isScoreSet() ? score.getScore() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public static boolean setPlayerMoney(Player player, int amount) {
        try {
            Scoreboard board = player.getServer().getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective(MONEY_OBJECTIVE);
            if (obj == null) {
                obj = board.registerNewObjective(MONEY_OBJECTIVE, "dummy", MONEY_DISPLAY);
            }
            obj.getScore(player.getName()).setScore(amount);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean addPlayerMoney(Player player, int amount) {
        return setPlayerMoney(player, getPlayerMoney(player) + amount);
    }

    public static boolean removePlayerMoney(Player player, int amount) {
        int current = getPlayerMoney(player);
        if (current < amount) return false;
        return setPlayerMoney(player, current - amount);
    }
}

package com.skyblock.economy;

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
 * Balance Command
 *
 * Translated from bal_command.js.
 *
 * /bal — displays the player's current Money scoreboard balance.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     bal:
 *       description: Check your money balance
 *       usage: /bal
 */
public class BalCommand implements CommandExecutor {

    private static final String MONEY_OBJECTIVE = "Money";

    private final Logger logger;

    public BalCommand(Logger logger) {
        this.logger = logger;
        logger.info("[Bal] /bal command loaded.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        try {
            int balance = getPlayerMoney(player);
            String formatted = NumberFormat.getNumberInstance(Locale.US).format(balance);
            player.sendMessage("§6💰 Balance: §a$" + formatted);
        } catch (Exception e) {
            player.sendMessage("§cFailed to check balance: " + e.getMessage());
            logger.warning("[BAL] Command error for " + player.getName() + ": " + e.getMessage());
        }

        return true;
    }

    // ─── Scoreboard helper (mirrors getPlayerMoney in bal_command.js) ─────────

    /**
     * Returns the player's Money scoreboard score, or 0 if the objective
     * doesn't exist or the player has no score yet.
     */
    public static int getPlayerMoney(Player player) {
        try {
            Scoreboard board = player.getServer().getScoreboardManager().getMainScoreboard();
            Objective objective = board.getObjective(MONEY_OBJECTIVE);
            if (objective == null) return 0;
            Score score = objective.getScore(player.getName());
            return score.isScoreSet() ? score.getScore() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}

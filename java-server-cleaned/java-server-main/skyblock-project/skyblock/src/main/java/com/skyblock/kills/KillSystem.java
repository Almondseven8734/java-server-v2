package com.skyblock.kills;

import com.skyblock.crates.CrateSystem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.logging.Logger;

/**
 * Kill System
 *
 * Features:
 *  - Tracks player kills and deaths via PlayerDeathEvent
 *  - Scoreboard objectives: "kills" and "deaths"
 *  - Grants 1 KillCredit per PvP kill (objective: "KillCredit")
 *  - /killshop  — spend 25 KillCredits for a Kill Key
 *  - /kills [player] — view your own (or another player's) kill/death stats
 *
 * Register in plugin.yml:
 *   commands:
 *     killshop:
 *       description: Spend kill credits for rewards
 *       usage: /killshop
 *     kills:
 *       description: Check kill/death stats
 *       usage: /kills [player]
 */
public class KillSystem implements Listener, CommandExecutor {

    // ─── Scoreboard objective names ───────────────────────────────────────────
    public static final String OBJ_KILLS       = "kills";
    public static final String OBJ_DEATHS      = "deaths";
    public static final String OBJ_KILL_CREDIT = "KillCredit";

    private static final int KILL_KEY_COST = 25;

    private final JavaPlugin  plugin;
    private final CrateSystem crateSystem;
    private final Logger      logger;

    public KillSystem(JavaPlugin plugin, CrateSystem crateSystem, Logger logger) {
        this.plugin      = plugin;
        this.crateSystem = crateSystem;
        this.logger      = logger;
        ensureObjectives();
        logger.info("[KillSystem] Kill tracking, kill shop, and kill credits loaded.");
    }

    // ─── Ensure scoreboard objectives exist ──────────────────────────────────

    private void ensureObjectives() {
        Scoreboard board = getBoard();
        if (board.getObjective(OBJ_KILLS) == null) {
            board.registerNewObjective(OBJ_KILLS, "playerKillCount", ChatColor.RED + "☠ Kills");
        }
        if (board.getObjective(OBJ_DEATHS) == null) {
            board.registerNewObjective(OBJ_DEATHS, "deathCount", ChatColor.GRAY + "💀 Deaths");
        }
        if (board.getObjective(OBJ_KILL_CREDIT) == null) {
            board.registerNewObjective(OBJ_KILL_CREDIT, "dummy", ChatColor.GOLD + "⚔ Kill Credits");
        }
    }

    // ─── Event: player death ──────────────────────────────────────────────────

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();

        // Track death
        addScore(OBJ_DEATHS, victim.getName(), 1);

        if (killer != null && !killer.equals(victim)) {
            // Track kill
            addScore(OBJ_KILLS, killer.getName(), 1);

            // Grant 1 kill credit
            addScore(OBJ_KILL_CREDIT, killer.getName(), 1);
            int newCredits = getScore(OBJ_KILL_CREDIT, killer.getName());

            killer.sendMessage(
                ChatColor.GOLD + "⚔ Kill credit awarded! " +
                ChatColor.YELLOW + "You now have " + ChatColor.GREEN + newCredits +
                ChatColor.YELLOW + " kill credit(s). (25 = 1 Kill Key via /killshop)"
            );
        }
    }

    // ─── Commands ─────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {

            case "killshop":
                return handleKillShop(sender);

            case "kills":
                return handleKills(sender, args);

            default:
                return false;
        }
    }

    private boolean handleKillShop(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use /killshop.");
            return true;
        }
        Player player = (Player) sender;

        int credits = getScore(OBJ_KILL_CREDIT, player.getName());

        if (credits < KILL_KEY_COST) {
            player.sendMessage(
                ChatColor.RED + "✖ Not enough kill credits. You have " +
                ChatColor.YELLOW + credits + ChatColor.RED + "/" +
                ChatColor.YELLOW + KILL_KEY_COST + ChatColor.RED + " needed."
            );
            return true;
        }

        // Deduct 25 credits
        setScore(OBJ_KILL_CREDIT, player.getName(), credits - KILL_KEY_COST);

        // Give Kill Key via CrateSystem
        boolean given = crateSystem.giveKey(player, "kill");
        if (given) {
            player.sendMessage(
                ChatColor.GREEN + "✔ You spent " + ChatColor.GOLD + KILL_KEY_COST +
                ChatColor.GREEN + " kill credits and received a " +
                ChatColor.RED + "§lKill Key" + ChatColor.GREEN + "!"
            );
        } else {
            // Refund if something went wrong
            setScore(OBJ_KILL_CREDIT, player.getName(), credits);
            player.sendMessage(ChatColor.RED + "✖ Could not give kill key — your credits were refunded.");
        }
        return true;
    }

    private boolean handleKills(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 1) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player '" + args[0] + "' is not online.");
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /kills <player>");
                return true;
            }
            target = (Player) sender;
        }

        int kills   = getScore(OBJ_KILLS,       target.getName());
        int deaths  = getScore(OBJ_DEATHS,       target.getName());
        int credits = getScore(OBJ_KILL_CREDIT,  target.getName());
        double kd   = deaths == 0 ? kills : Math.round((kills / (double) deaths) * 100.0) / 100.0;

        sender.sendMessage(ChatColor.GOLD + "━━━ " + ChatColor.RED + target.getName() + "'s Stats" + ChatColor.GOLD + " ━━━");
        sender.sendMessage(ChatColor.RED   + "☠ Kills:        " + ChatColor.WHITE + kills);
        sender.sendMessage(ChatColor.GRAY  + "💀 Deaths:       " + ChatColor.WHITE + deaths);
        sender.sendMessage(ChatColor.YELLOW + "K/D Ratio:      " + ChatColor.WHITE + kd);
        sender.sendMessage(ChatColor.GOLD  + "⚔ Kill Credits: " + ChatColor.WHITE + credits);
        return true;
    }

    // ─── Scoreboard helpers ───────────────────────────────────────────────────

    private Scoreboard getBoard() {
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        return mgr == null ? Bukkit.getServer().getScoreboardManager().getMainScoreboard()
                           : mgr.getMainScoreboard();
    }

    private int getScore(String objName, String playerName) {
        try {
            Objective obj = getBoard().getObjective(objName);
            if (obj == null) return 0;
            Score score = obj.getScore(playerName);
            return score.isScoreSet() ? score.getScore() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void setScore(String objName, String playerName, int value) {
        try {
            ensureObjectives();
            Objective obj = getBoard().getObjective(objName);
            if (obj != null) obj.getScore(playerName).setScore(value);
        } catch (Exception e) {
            logger.warning("[KillSystem] setScore error: " + e.getMessage());
        }
    }

    private void addScore(String objName, String playerName, int amount) {
        setScore(objName, playerName, getScore(objName, playerName) + amount);
    }
}

package com.skyblock.sidebar;

import com.skyblock.kills.KillSystem;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Sidebar System
 *
 * Displays a per-player sidebar scoreboard showing:
 *   ── Skybound Realms ──
 *   💰 Money: $X
 *   💎 Gems: X
 *   ⚔ Kill Credits: X
 *   ☠ Kills / Deaths
 *   ────────────────
 *   ☕ Java: US-MI34B...
 *   📱 Bedrock: 163.123...
 *
 * The sidebar refreshes every 2 seconds (40 ticks).
 *
 * Each player gets their own Scoreboard so we can put custom text lines
 * without interfering with the main scoreboard's "Money" / "gems" /
 * "KillCredit" objectives (which drive the actual economy).
 */
public class SidebarSystem implements Listener {

    // ─── Server info constants ────────────────────────────────────────────────
    private static final String SERVER_NAME    = "Skyblock";

    // ─── Economy objective names (from main scoreboard) ───────────────────────
    private static final String OBJ_MONEY  = "Money";
    private static final String OBJ_GEMS   = "gems";

    // ─── Sidebar objective name on each player's private board ───────────────
    private static final String SIDEBAR_OBJ = "skyboundSide";

    private static final int REFRESH_TICKS = 40; // 2 seconds

    private final JavaPlugin plugin;
    private final Logger     logger;

    /** Each player has their own Scoreboard instance for the sidebar. */
    private final Map<UUID, Scoreboard> playerBoards = new HashMap<>();

    public SidebarSystem(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        startRefreshTask();
        // Apply to players who are already online (reload scenario)
        for (Player p : Bukkit.getOnlinePlayers()) initPlayer(p);
        logger.info("[SidebarSystem] Sidebar scoreboard loaded.");
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Small delay so main scoreboard data is ready
        Bukkit.getScheduler().runTaskLater(plugin, () -> initPlayer(event.getPlayer()), 5L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        playerBoards.remove(event.getPlayer().getUniqueId());
    }

    // ─── Init a player's sidebar scoreboard ──────────────────────────────────

    private void initPlayer(Player player) {
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return;

        Scoreboard board = mgr.getNewScoreboard();
        Objective obj = board.registerNewObjective(
            SIDEBAR_OBJ, "dummy",
            ChatColor.GOLD + "" + ChatColor.BOLD + "✦ " + SERVER_NAME + " ✦"
        );
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        playerBoards.put(player.getUniqueId(), board);
        player.setScoreboard(board);
        updateSidebar(player, board);
    }

    // ─── Refresh task ─────────────────────────────────────────────────────────

    private void startRefreshTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Scoreboard board = playerBoards.get(player.getUniqueId());
                if (board == null) {
                    initPlayer(player);
                } else {
                    updateSidebar(player, board);
                }
            }
        }, REFRESH_TICKS, REFRESH_TICKS);
    }

    // ─── Build/update sidebar lines ───────────────────────────────────────────

    /**
     * Lines (score):
     *   8  — 💰 Money: $X,XXX
     *   7  — 💎 Gems: X
     *   6  — ⚔ Credits: X
     *   5  — ⏱ Time: Xh Xm
     *   4  — separator
     *   3  — ᴅɪsᴄᴏʀᴅ label
     *   2  — discord code
     */
    private static final String NORMAL    = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALLCAPS = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ";

    private String toSmallCaps(String input) {
        StringBuilder sb = new StringBuilder();
        for (char c : input.toLowerCase().toCharArray()) {
            int idx = NORMAL.indexOf(c);
            sb.append(idx >= 0 ? SMALLCAPS.charAt(idx) : c);
        }
        return sb.toString();
    }
    private void updateSidebar(Player player, Scoreboard board) {
        Objective obj = board.getObjective(SIDEBAR_OBJ);
        if (obj == null) return;

        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        int money   = getMainScore(main, OBJ_MONEY, player.getName());
        int gems    = getMainScore(main, OBJ_GEMS,  player.getName());
        int credits = getMainScore(main, KillSystem.OBJ_KILL_CREDIT, player.getName());

        // Time played in minutes from statistic
        long minutesPlayed = player.getStatistic(org.bukkit.Statistic.PLAY_ONE_MINUTE) / 20 / 60;
        long hours   = minutesPlayed / 60;
        long minutes = minutesPlayed % 60;
        String timePlayed = hours + "h " + minutes + "m";

        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);

        setLine(board, obj, 8, "§a§r", ChatColor.YELLOW + "💰 " + ChatColor.WHITE + "Money: "   + ChatColor.GREEN + nf.format(money) + "g");
        setLine(board, obj, 7, "§b§r", ChatColor.AQUA   + "💎 " + ChatColor.WHITE + "Gems: "    + ChatColor.AQUA  + nf.format(gems));
        setLine(board, obj, 6, "§6§r", ChatColor.GOLD   + "⚔ "  + ChatColor.WHITE + "Credits: " + ChatColor.GOLD  + credits);
        setLine(board, obj, 5, "§e§r", ChatColor.WHITE  + "⏱ "  + ChatColor.WHITE + "Time: "    + ChatColor.GREEN + timePlayed);
        setLine(board, obj, 4, "§2§r", ChatColor.DARK_GRAY + "───────");
        setLine(board, obj, 3, "§4§r", ChatColor.LIGHT_PURPLE + toSmallCaps("discord"));
        setLine(board, obj, 2, "§5§r", ChatColor.GRAY + "YxSJP4xN2X");
    }

    /**
     * Sets a sidebar line using a Team to hold the display text.
     *
     * @param board       the player's private scoreboard
     * @param obj         the sidebar objective on that board
     * @param score       the integer slot (higher = higher on sidebar)
     * @param entryKey    a unique colour-code string used as the team entry (must be unique per score)
     * @param displayText the visible text (prefix)
     */
    private void setLine(Scoreboard board, Objective obj, int score, String entryKey, String displayText) {
        String teamName = "sb_" + score; // unique per line
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
            team.addEntry(entryKey);
        }
        team.setPrefix(displayText);

        Score s = obj.getScore(entryKey);
        if (!s.isScoreSet() || s.getScore() != score) {
            s.setScore(score);
        }
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private int getMainScore(Scoreboard main, String objName, String playerName) {
        try {
            Objective o = main.getObjective(objName);
            if (o == null) return 0;
            Score s = o.getScore(playerName);
            return s.isScoreSet() ? s.getScore() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}

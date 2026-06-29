package com.skyblock.vote;

import com.skyblock.crates.CrateSystem;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Vote System
 *
 * Translated from vote_system.js.
 *
 * /vote — checks the minecraftpocket-servers.com API for an unclaimed vote,
 *         marks it claimed, then gives the player a Vote Crate key.
 *
 * Playtime reward: every 30 minutes, all online players receive a Common Key.
 *
 * Bedrock used @minecraft/server-net for HTTP. On Java/Paper we use
 * java.net.HttpURLConnection on an async BukkitRunnable to avoid blocking
 * the main thread.
 *
 * NOTE: The API key is embedded here matching the original JS. Rotate it if
 * you suspect it has been exposed.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     vote:
 *       description: Claim your vote reward
 *       usage: /vote
 */
public class VoteSystem implements CommandExecutor, Listener {

    // ─── API ──────────────────────────────────────────────────────────────────
    private static final String SERVER_KEY = "d5iKIp3IBQAQ0vsghOgqyQlPBvZ9Bl63rdG";
    private static final String CHECK_BASE = "https://minecraftpocket-servers.com/api/?object=votes&element=claim&key="
            + SERVER_KEY + "&username=";
    private static final String CLAIM_BASE = "https://minecraftpocket-servers.com/api/?action=post&object=votes&element=claim&key="
            + SERVER_KEY + "&username=";

    // ─── 5-second anti-spam cooldown ─────────────────────────────────────────
    private static final long COOLDOWN_MS = 5_000L;
    private final Map<String, Long> cooldowns = new HashMap<>();

    // ─── Playtime reward: 30 minutes (20 ticks/s * 60 * 30 = 36,000 ticks) ──
    private static final long PLAYTIME_INTERVAL_TICKS = 36_000L;

    private final JavaPlugin plugin;
    private final Logger logger;
    private final CrateSystem crateSystem;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public VoteSystem(JavaPlugin plugin, Logger logger, CrateSystem crateSystem) {
        this.plugin = plugin;
        this.logger = logger;
        this.crateSystem = crateSystem;
        startPlaytimeReward();
        logger.info("[Vote System] /vote registered! Playtime reward every 30 minutes.");
    }

    // ─── /vote command ────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (isOnCooldown(player.getName())) {
            player.sendMessage("§cPlease wait a moment before checking again.");
            return true;
        }

        setCooldown(player.getName());
        player.sendMessage("§7Checking your vote...");

        // Run HTTP calls off the main thread
        new BukkitRunnable() {
            @Override
            public void run() {
                checkVote(player);
            }
        }.runTaskAsynchronously(plugin);

        return true;
    }

    // ─── Vote check logic (runs async) ───────────────────────────────────────

    private void checkVote(Player player) {
        try {
            String encoded = java.net.URLEncoder.encode(player.getName(), StandardCharsets.UTF_8.name());

            // Step 1: check
            String result = httpGet(CHECK_BASE + encoded).trim();
            logger.info("[VOTE] Check for " + player.getName() + ": " + result);

            switch (result) {
                case "0":
                    sendSync(player, "§cNo vote found! Vote at:");
                    sendSync(player, "§ehttps://minecraftpocket-servers.com");
                    sendSync(player, "§7Then type §e/vote §7to claim your reward.");
                    return;

                case "2":
                    sendSync(player, "§cYou already claimed your vote reward today!");
                    sendSync(player, "§7Come back tomorrow to vote again.");
                    return;

                default:
                    if (!"1".equals(result)) {
                        sendSync(player, "§cAPI error (" + result + "). Try again later.");
                        return;
                    }
            }

            // Step 2: claim
            String claimResult = httpPost(CLAIM_BASE + encoded).trim();
            logger.info("[VOTE] Claim for " + player.getName() + ": " + claimResult);

            // Step 3: give reward (must be on main thread)
            new BukkitRunnable() {
                @Override
                public void run() {
                    giveVoteReward(player);
                }
            }.runTask(plugin);

        } catch (Exception e) {
            logger.warning("[VOTE] Error: " + e.getMessage());
            sendSync(player, "§cVote check failed. Try again later.");
        }
    }

    // ─── Reward ───────────────────────────────────────────────────────────────

    /**
     * Mirrors giveVoteReward() in vote_system.js.
     * Gives a vote key, plays a sound, and broadcasts.
     */
    private void giveVoteReward(Player player) {
        crateSystem.giveKey(player, "vote");
        player.sendMessage("§e§l★ Thanks for voting! §r§7You received a §eVote Key§7!");
        player.sendMessage("§7Use it at the §eVote Crate§7 at spawn!");
        player.playSound(player.getLocation(),
                org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        player.getServer().broadcastMessage(
                "§e§l★ §r§e" + player.getName() + " §7just voted and received a §eVote Key§7!");
    }

    // ─── Playtime reward ──────────────────────────────────────────────────────

    /**
     * Every 30 minutes, every online player gets a Common Key.
     * Mirrors the PLAYTIME_INTERVAL_TICKS runInterval in vote_system.js.
     */
    private void startPlaytimeReward() {
        new BukkitRunnable() {
            @Override
            public void run() {
                var players = plugin.getServer().getOnlinePlayers();
                for (Player p : players) {
                    crateSystem.giveKey(p, "common");
                    p.sendMessage("§7§l[Reward] §r§7You received a §fCommon Key§7 for playing!");
                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                }
                if (!players.isEmpty()) {
                    logger.info("[Playtime Reward] Gave Common Keys to " + players.size() + " player(s).");
                }
            }
        }.runTaskTimer(plugin, PLAYTIME_INTERVAL_TICKS, PLAYTIME_INTERVAL_TICKS);
        logger.info("[Playtime Reward] Common Key every 30 minutes loaded!");
    }

    // ─── Cooldown helpers ─────────────────────────────────────────────────────

    private boolean isOnCooldown(String name) {
        Long last = cooldowns.get(name);
        return last != null && System.currentTimeMillis() - last < COOLDOWN_MS;
    }

    private void setCooldown(String name) {
        cooldowns.put(name, System.currentTimeMillis());
    }

    // ─── HTTP helpers ─────────────────────────────────────────────────────────

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private static String httpPost(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);
        conn.setDoOutput(true);
        conn.getOutputStream().close(); // empty body
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    /** Schedules a message to be sent on the main thread. */
    private void sendSync(Player player, String msg) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline()) player.sendMessage(msg);
            }
        }.runTask(plugin);
    }
}

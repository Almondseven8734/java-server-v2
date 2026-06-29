package com.skyblock.commands;

import com.skyblock.storage.IslandData;
import com.skyblock.storage.IslandStorage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * FlyCommand
 *
 * /fly — toggles flight on/off for survival players.
 * Flight is only permitted while the player is standing within
 * the bounds of their own island. The moment they leave their
 * island territory, flight is silently revoked.
 *
 * Creative / Spectator players are ignored (they always fly).
 *
 * Island boundary uses the same centre + radius logic as IslandGenerator:
 *   center  = island.centerX / island.centerZ
 *   radius  = 200 blocks (half of the 400-block island spacing)
 *
 * Register in plugin.yml:
 *   commands:
 *     fly:
 *       description: Toggle flight on your island
 *       usage: /fly
 */
public class FlyCommand implements CommandExecutor, Listener {

    /** Half-width of each island territory. Adjust to match IslandGenerator spacing. */
    private static final int ISLAND_RADIUS = 200;

    private final IslandStorage storage;
    private final Logger        logger;

    /** Players who have fly toggled ON via /fly (survival mode). */
    private final Set<UUID> flyEnabled = new HashSet<>();

    public FlyCommand(IslandStorage storage, Logger logger) {
        this.storage = storage;
        this.logger  = logger;
        logger.info("[FlyCommand] /fly loaded — island-only flight.");
    }

    // ─── /fly ────────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can use /fly.");
            return true;
        }
        Player player = (Player) sender;

        // Creative/Spectator don't need this
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            player.sendMessage("§7You already have unrestricted flight in your current game mode.");
            return true;
        }

        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null) {
            player.sendMessage("§cYou don't have an island. Flight is only available on your own island.");
            return true;
        }

        if (!isOnOwnIsland(player, island)) {
            player.sendMessage("§cYou can only toggle flight while standing on your own island.");
            return true;
        }

        if (flyEnabled.contains(player.getUniqueId())) {
            // Turn off
            flyEnabled.remove(player.getUniqueId());
            player.setAllowFlight(false);
            player.setFlying(false);
            player.sendMessage("§cFlight disabled.");
        } else {
            // Turn on
            flyEnabled.add(player.getUniqueId());
            player.setAllowFlight(true);
            player.sendMessage("§aFlight enabled! §7(Only active on your island.)");
        }
        return true;
    }

    // ─── Movement check — revoke flight if player leaves their island ─────────

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!flyEnabled.contains(player.getUniqueId())) return;

        // Skip creative/spectator
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) return;

        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (island == null || !isOnOwnIsland(player, island)) {
            flyEnabled.remove(player.getUniqueId());
            player.setAllowFlight(false);
            // Land them safely if mid-air
            if (player.isFlying()) {
                player.setFlying(false);
            }
            player.sendMessage("§cFlight disabled — you left your island.");
        }
    }

    // ─── Cleanup on quit ──────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (flyEnabled.remove(uuid)) {
            // Ensure flight is not persisted on reconnect
            event.getPlayer().setAllowFlight(false);
        }
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private boolean isOnOwnIsland(Player player, IslandData island) {
        double px = player.getLocation().getX();
        double pz = player.getLocation().getZ();
        return Math.abs(px - island.centerX) <= ISLAND_RADIUS
            && Math.abs(pz - island.centerZ) <= ISLAND_RADIUS;
    }
}

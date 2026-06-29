package com.skyblock.pwarp;

import com.skyblock.island.IslandData;
import com.skyblock.island.IslandStorage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Public Warp (PwarpSystem)
 *
 * Translated from pwarp_system.js.
 *
 * /pwarp [warpname] — if no arg given opens an inventory GUI warp browser;
 *                     if a name is given, teleports directly to that warp.
 * /pw [warpname]    — alias for /pwarp.
 *
 * The chest-form UI used in Bedrock is replaced with a standard
 * Bukkit inventory GUI (SmartInventory or manual Inventory API).
 * For simplicity this implementation uses direct chat-list output
 * as a drop-in replacement — swap with an inventory GUI plugin if desired.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     pwarp:
 *       description: Browse or teleport to public island warps
 *       usage: /pwarp [warpname]
 *       aliases: [pw]
 */
public class PwarpSystem implements CommandExecutor {

    private static final int PAGE_SIZE = 28;

    private final IslandStorage storage;
    private final Logger logger;

    public PwarpSystem(IslandStorage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
        logger.info("[Skyblock] /pwarp and /pw registered!");
    }

    // ─── Command ──────────────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length > 0) {
            directPwarp(player, args[0]);
        } else {
            showPwarpList(player, 0);
        }

        return true;
    }

    // ─── All pwarps ───────────────────────────────────────────────────────────

    /**
     * Mirrors getAllPwarps() in pwarp_system.js.
     * Collects every warp from every island into a flat list.
     */
    public List<PwarpEntry> getAllPwarps() {
        List<PwarpEntry> result = new ArrayList<>();
        for (IslandData island : storage.getAllIslands()) {
            if (island.pwarps == null) continue;
            for (IslandData.Warp warp : island.pwarps) {
                result.add(new PwarpEntry(island, warp));
            }
        }
        return result;
    }

    /**
     * Mirrors isPwarpNameTaken() in pwarp_system.js.
     * Case-insensitive check across all islands.
     */
    public boolean isPwarpNameTaken(String warpName) {
        String lower = warpName.toLowerCase();
        for (IslandData island : storage.getAllIslands()) {
            if (island.pwarps == null) continue;
            for (IslandData.Warp warp : island.pwarps) {
                if (warp.name.toLowerCase().equals(lower)) return true;
            }
        }
        return false;
    }

    // ─── Chat-list browser (replaces ChestFormData in Bedrock) ───────────────

    /**
     * Mirrors showPwarpMenu() in pwarp_system.js.
     * Prints a paginated chat list; each entry can be clicked (ClickEvent)
     * to run /pwarp <name>.
     */
    private void showPwarpList(Player player, int page) {
        List<PwarpEntry> all = getAllPwarps();

        if (all.isEmpty()) {
            player.sendMessage("§cNo public warps exist yet! Island members can use §e/setwarp <name>§c.");
            return;
        }

        int totalPages = (int) Math.ceil((double) all.size() / PAGE_SIZE);
        int current    = Math.max(0, Math.min(page, totalPages - 1));
        List<PwarpEntry> slice = all.subList(
                current * PAGE_SIZE,
                Math.min((current + 1) * PAGE_SIZE, all.size()));

        player.sendMessage("§5§l──── Public Warps §r§7(" + (current + 1) + "/" + totalPages + ") §5§l────");
        for (PwarpEntry entry : slice) {
            // Clickable suggestion: typing /pwarp <name> is inserted into chat bar
            net.md_5.bungee.api.chat.TextComponent line =
                    new net.md_5.bungee.api.chat.TextComponent(
                            "§e" + entry.island.name + " §7▸ §b" + entry.warp.name
                            + " §7(Owner: §e" + entry.island.ownerName + "§7) §a[Click]");
            line.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(
                    net.md_5.bungee.api.chat.ClickEvent.Action.SUGGEST_COMMAND,
                    "/pwarp " + entry.warp.name));
            line.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(
                    net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,
                    new net.md_5.bungee.api.chat.BaseComponent[]{
                            new net.md_5.bungee.api.chat.TextComponent(
                                    "§7Set by: §7" + entry.warp.creatorName + "\n§aClick to teleport!")}));
            player.spigot().sendMessage(line);
        }
        if (current > 0) {
            player.sendMessage("§7◀ /pwarp:page " + (current) + " for previous page");
        }
        if (current < totalPages - 1) {
            player.sendMessage("§7▶ /pwarp:page " + (current + 2) + " for next page");
        }
    }

    // ─── Direct warp by name ──────────────────────────────────────────────────

    /**
     * Mirrors directPwarp() in pwarp_system.js.
     */
    public void directPwarp(Player player, String warpName) {
        String lower = warpName.toLowerCase();
        for (IslandData island : storage.getAllIslands()) {
            if (island.pwarps == null) continue;
            for (IslandData.Warp warp : island.pwarps) {
                if (warp.name.toLowerCase().equals(lower)) {
                    World overworld = player.getServer().getWorld("world");
                    if (overworld == null) {
                        player.sendMessage("§cOverworld not found.");
                        return;
                    }
                    player.teleport(new Location(overworld, warp.x, warp.y, warp.z));
                    player.sendMessage("§aTeleported to §b" + warp.name
                            + " §7(§e" + island.name + "§7)§a!");
                    return;
                }
            }
        }
        player.sendMessage("§cNo public warp named §e" + warpName + "§c found.");
    }

    // ─── Inner types ──────────────────────────────────────────────────────────

    /** Flat pair matching the JS { island, warp } object in getAllPwarps(). */
    public static class PwarpEntry {
        public final IslandData island;
        public final IslandData.Warp warp;

        public PwarpEntry(IslandData island, IslandData.Warp warp) {
            this.island = island;
            this.warp   = warp;
        }
    }
}

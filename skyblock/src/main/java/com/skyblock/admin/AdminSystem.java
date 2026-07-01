package com.skyblock.admin;

import com.skyblock.protection.SpawnProtection;
import com.skyblock.shop.AdminShopSystem;
import com.skyblock.island.IslandData;
import com.skyblock.island.IslandStorage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Admin System
 *
 * Features:
 *   - Admin whitelist with per-user permission toggles (persistent JSON)
 *   - Player blacklist (persistent JSON)
 *   - Timed timeouts / temp-bans (persistent JSON)
 *   - Queued offline-player actions (applied on next join)
 *   - Spawn-bypass toggle
 *   - All menus are double-chest (54 slots)
 *   - Owner hardcoded as "Almondseven8734" — cannot be removed
 *   - Only the owner can access/manage the whitelist
 *   - On whitelist removal: inventory cleared, set to survival, deopped
 *   - If player is offline when removed, action is queued for next join
 */
public class AdminSystem implements CommandExecutor, Listener {

    // ─── Constants ────────────────────────────────────────────────────────────
    /** Hardcoded owner — cannot be removed from whitelist, always has all perms. */
    private static final String OWNER_NAME   = "Almondseven8734";
    private static final String DISCORD_LINK = "https://discord.gg/MwuzwAazr";

    private static final double ADMIN_ZONE_X = 190;
    private static final double ADMIN_ZONE_Y = 151;
    private static final double ADMIN_ZONE_Z = -125;

    // ─── Persistence file names ───────────────────────────────────────────────
    private static final String FILE_WHITELIST    = "admin_whitelist.json";
    private static final String FILE_BLACKLIST    = "admin_blacklist.json";
    private static final String FILE_TIMEOUTS     = "admin_timeouts.json";
    private static final String FILE_PERMISSIONS  = "admin_permissions.json";
    private static final String FILE_QUEUED_STRIP = "admin_queued_strip.json";

    // ─── Available permissions (button labels in the admin panel) ────────────
    public enum AdminPerm {
        TELEPORT             ("Teleport to Admin Zone",   Material.ENDER_PEARL),
        SPAWN_BYPASS         ("Spawn Build Bypass",        Material.GRASS_BLOCK),
        GAMEMODE             ("Change Gamemode",           Material.COMMAND_BLOCK),
        ADMIN_SHOP           ("Admin Shop",                Material.ENDER_CHEST),
        KICK_PLAYERS         ("Kick Players",              Material.IRON_DOOR),
        TIMEOUT_PLAYERS      ("Timeout Players",           Material.CLOCK),
        ISLAND_BLOCK_BYPASS  ("Island Block Bypass",       Material.DIAMOND_PICKAXE),
        ISLAND_BORDER_BYPASS ("Island Border Bypass",      Material.COMPASS),
        COMMAND_OVERRIDE     ("Command Override",          Material.NETHER_STAR);

        public final String label;
        public final Material icon;

        AdminPerm(String label, Material icon) {
            this.label = label;
            this.icon  = icon;
        }
    }

    // ─── State ────────────────────────────────────────────────────────────────
    /** Lower-cased names of whitelisted admins (owner is implicitly included). */
    private final Set<String>                      whitelist    = new HashSet<>();
    /** Per-admin permission map: lowerName → set of granted AdminPerm names. */
    private final Map<String, Set<String>>         permissions  = new HashMap<>();
    private final Set<String>                      blacklist    = new HashSet<>();
    /** Map<lowerName, expiryEpochMs> */
    private final Map<String, Long>                timeouts     = new HashMap<>();
    /** Players queued for strip on next join (removed while offline). */
    private final Set<String>                      queuedStrip  = new HashSet<>();
    /**
     * UUIDs of admins currently mid-dispatch of a "/admin execute" command.
     * Other restriction listeners (e.g. DungeonCommandLockdownListener) can
     * check {@link #isOverrideActive(UUID)} to skip their own cancellation
     * logic for the single command being force-executed. Membership is only
     * ever held for the duration of the dispatch call itself.
     */
    private final Set<UUID>                         commandOverrideActive = new HashSet<>();

    private static AdminSystem INSTANCE;

    // ─── GUI menu state ───────────────────────────────────────────────────────
    private enum MenuType { MAIN, GAMEMODE, WHITELIST, BLACKLIST, PLAYER_MANAGE }
    private final Map<UUID, MenuType> menuSessions       = new HashMap<>();
    private final Map<UUID, Integer>  listPage           = new HashMap<>();
    /** For PLAYER_MANAGE: which whitelist name is being edited. */
    private final Map<UUID, String>   managingPlayer     = new HashMap<>();

    /** Player-head grid — centre rows of a 54-slot double chest. */
    private static final int[] GRID_SLOTS = {
            10,11,12,13,14,15,16,
            19,20,21,22,23,24,25,
            28,29,30,31,32,33,34,
            37,38,39,40,41,42,43
    };

    // Permission toggle slots (in the PLAYER_MANAGE sub-menu, 54-slot chest)
    // Each AdminPerm gets a dedicated slot.
    private static final int[] PERM_SLOTS = { 10, 11, 12, 13, 14, 15, 19, 20, 21 };

    // ─── Chat-input state ─────────────────────────────────────────────────────
    private enum InputMode { NONE, ADD_WHITELIST, ADD_BLACKLIST }
    private final Map<UUID, InputMode> inputMode = new HashMap<>();

    private final JavaPlugin    plugin;
    private final IslandStorage storage;
    private final Logger        logger;
    private final Path          dataDir;
    private final AdminShopSystem adminShopSystem;

    /**
     * Hook for "/admin dungeon start" - set via setDungeonAdminHandler()
     * once the dungeon system is constructed in SkyblockPlugin.onEnable()
     * (it's wired after AdminSystem since AdminSystem is constructed
     * early, before MineSystem, per the existing comment there). Null
     * until that wiring happens, e.g. if dungeon world creation failed.
     */
    @FunctionalInterface
    public interface DungeonAdminHandler {
        void start(Player player);
    }

    private DungeonAdminHandler dungeonAdminHandler;

    public void setDungeonAdminHandler(DungeonAdminHandler handler) {
        this.dungeonAdminHandler = handler;
    }

    // ─── Constructor ──────────────────────────────────────────────────────────

    public AdminSystem(JavaPlugin plugin, IslandStorage storage, Logger logger, AdminShopSystem adminShopSystem) {
        this.plugin         = plugin;
        this.storage        = storage;
        this.logger         = logger;
        this.dataDir        = plugin.getDataFolder().toPath();
        this.adminShopSystem = adminShopSystem;
        INSTANCE            = this;
        load();
        startBlacklistEnforcer();
        logger.info("[Admin System] Loaded!");
    }

    // ─── Static helpers ───────────────────────────────────────────────────────

    public static boolean isWhitelisted(String name) {
        if (name == null || INSTANCE == null) return false;
        if (name.equalsIgnoreCase(OWNER_NAME)) return true;
        return INSTANCE.whitelist.contains(name.toLowerCase());
    }

    public static boolean hasPerm(String name, AdminPerm perm) {
        if (name == null || INSTANCE == null) return false;
        if (name.equalsIgnoreCase(OWNER_NAME)) return true; // owner has all perms
        Set<String> perms = INSTANCE.permissions.get(name.toLowerCase());
        if (perms == null) return false;
        return perms.contains(perm.name());
    }

    /**
     * True while the given player is mid-dispatch of a "/admin execute"
     * command. Other restriction listeners (dungeon command lockdown,
     * region protections, etc.) should skip their own cancellation logic
     * when this returns true for the acting player, so that COMMAND_OVERRIDE
     * genuinely bypasses every command-level restriction, not just this one.
     */
    public static boolean isOverrideActive(UUID uuid) {
        return INSTANCE != null && uuid != null && INSTANCE.commandOverrideActive.contains(uuid);
    }

    public static boolean isTimedOut(String name) {
        if (INSTANCE == null) return false;
        Long until = INSTANCE.timeouts.get(name.toLowerCase());
        if (until == null) return false;
        if (System.currentTimeMillis() > until) {
            INSTANCE.timeouts.remove(name.toLowerCase());
            INSTANCE.saveTimeouts();
            return false;
        }
        return true;
    }

    public static long getTimeoutRemaining(String name) {
        if (INSTANCE == null) return 0;
        Long until = INSTANCE.timeouts.get(name.toLowerCase());
        if (until == null) return 0;
        return Math.max(0, until - System.currentTimeMillis());
    }

    // ─── Whitelist helpers ────────────────────────────────────────────────────

    public void addToWhitelist(String name) {
        String lower = name.toLowerCase();
        whitelist.add(lower);
        permissions.putIfAbsent(lower, new HashSet<>());
        saveWhitelist();
        savePermissions();
    }

    /**
     * Remove a player from the whitelist.
     * If online: immediately clears inventory, sets survival, deopped.
     * If offline: queues the strip action for next login.
     */
    public void removeFromWhitelist(String name) {
        if (name.equalsIgnoreCase(OWNER_NAME)) {
            logger.warning("[Admin] Attempt to remove owner from whitelist blocked.");
            return;
        }
        String lower = name.toLowerCase();
        whitelist.remove(lower);
        permissions.remove(lower);
        saveWhitelist();
        savePermissions();

        // Apply strip — or queue if offline
        Player target = plugin.getServer().getPlayerExact(name);
        if (target != null && target.isOnline()) {
            applyStrip(target, name);
        } else {
            queuedStrip.add(lower);
            saveQueuedStrip();
            logger.info("[Admin] Queued strip for offline player: " + name);
        }
    }

    /** Immediately strip a player: clear inventory, set survival, deop. */
    private void applyStrip(Player player, String name) {
        player.getInventory().clear();
        player.setGameMode(GameMode.SURVIVAL);
        if (player.isOp()) player.setOp(false);
        player.sendMessage("§c[Admin] Your admin privileges have been revoked. Inventory cleared.");
        logger.info("[Admin] Stripped (whitelist removal): " + name);
    }

    public List<String> getWhitelistNames() {
        List<String> list = new ArrayList<>(whitelist);
        // Ensure owner is always first
        list.remove(OWNER_NAME.toLowerCase());
        list.sort(String::compareTo);
        list.add(0, OWNER_NAME.toLowerCase());
        return list;
    }

    // ─── Permission helpers ───────────────────────────────────────────────────

    public void grantPerm(String name, AdminPerm perm) {
        permissions.computeIfAbsent(name.toLowerCase(), k -> new HashSet<>()).add(perm.name());
        savePermissions();
    }

    public void revokePerm(String name, AdminPerm perm) {
        Set<String> perms = permissions.get(name.toLowerCase());
        if (perms != null) perms.remove(perm.name());
        savePermissions();
    }

    public void togglePerm(String name, AdminPerm perm) {
        if (hasPerm(name, perm)) revokePerm(name, perm);
        else grantPerm(name, perm);
    }

    // ─── Blacklist helpers ────────────────────────────────────────────────────

    public boolean isBlacklisted(String name) {
        return blacklist.contains(name.toLowerCase());
    }

    public void addToBlacklist(String name) {
        if (name.equalsIgnoreCase(OWNER_NAME)) return;
        blacklist.add(name.toLowerCase());
        saveBlacklist();
        removeFromWhitelist(name);
    }

    public void removeFromBlacklist(String name) {
        blacklist.remove(name.toLowerCase());
        saveBlacklist();
    }

    public List<String> getBlacklistNames() {
        List<String> list = new ArrayList<>(blacklist);
        list.sort(String::compareTo);
        return list;
    }

    // ─── Timeout helpers ──────────────────────────────────────────────────────

    public void addTimeout(String name, int minutes) {
        timeouts.put(name.toLowerCase(), System.currentTimeMillis() + minutes * 60_000L);
        saveTimeouts();
    }

    // ─── Spawn bypass toggle ──────────────────────────────────────────────────
    // Also covers dungeon block protection (DungeonBlockProtectionListener)
    // since both share SpawnProtection.BYPASS_TAG - one toggle, two zones.

    public void toggleSpawnBypass(Player player) {
        if (!hasPerm(player.getName(), AdminPerm.SPAWN_BYPASS)) {
            player.sendMessage("§c[Spawn Protection] You do not have permission.");
            return;
        }
        if (player.getScoreboardTags().contains(SpawnProtection.BYPASS_TAG)) {
            player.removeScoreboardTag(SpawnProtection.BYPASS_TAG);
            player.sendMessage("§c[Spawn Protection] Build bypass OFF (spawn + dungeon).");
        } else {
            player.addScoreboardTag(SpawnProtection.BYPASS_TAG);
            player.sendMessage("§a[Spawn Protection] Build bypass ON (spawn + dungeon).");
        }
    }

    // ─── Generic tag toggle ───────────────────────────────────────────────────

    private void toggleTag(Player player, String tag, String label) {
        if (player.getScoreboardTags().contains(tag)) {
            player.removeScoreboardTag(tag);
            player.sendMessage("§c[Admin] " + label + " OFF.");
        } else {
            player.addScoreboardTag(tag);
            player.sendMessage("§a[Admin] " + label + " ON.");
        }
    }

    // ─── Bukkit events ────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        String name = event.getPlayer().getName();
        if (name.equalsIgnoreCase(OWNER_NAME)) return;

        if (isTimedOut(name)) {
            long mins = (long) Math.ceil(getTimeoutRemaining(name) / 60_000.0);
            event.disallow(PlayerLoginEvent.Result.KICK_BANNED,
                    "§cYou are temporarily banned.\n§7Remaining: §e" + mins + " minute(s)\n§7Appeal: §b" + DISCORD_LINK);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String lower  = player.getName().toLowerCase();

        // Apply any queued strip
        if (queuedStrip.remove(lower)) {
            saveQueuedStrip();
            new BukkitRunnable() {
                @Override public void run() {
                    if (player.isOnline()) {
                        applyStrip(player, player.getName());
                    }
                }
            }.runTaskLater(plugin, 20L); // slight delay so world loads
        }

        // Blacklist enforcement
        if (isBlacklisted(player.getName())) {
            new BukkitRunnable() {
                @Override public void run() {
                    if (player.isOnline()) player.setGameMode(GameMode.SURVIVAL);
                }
            }.runTaskLater(plugin, 40L);
        }
    }

    // ─── Blacklist enforcer ───────────────────────────────────────────────────

    private void startBlacklistEnforcer() {
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : plugin.getServer().getOnlinePlayers()) {
                    if (isBlacklisted(p.getName())) p.setGameMode(GameMode.SURVIVAL);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // ─── /admin command ───────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        if (!isWhitelisted(player.getName())) {
            player.sendMessage("§c[Admin] You do not have permission.");
            return true;
        }

        if (args.length == 0) {
            openMainMenu(player);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "tp": {
                if (!hasPerm(player.getName(), AdminPerm.TELEPORT)) {
                    player.sendMessage("§c[Admin] No permission."); break;
                }
                player.teleport(new org.bukkit.Location(
                        plugin.getServer().getWorld("world"),
                        ADMIN_ZONE_X, ADMIN_ZONE_Y, ADMIN_ZONE_Z));
                player.sendMessage("§a[Admin] Teleported to Admin Zone.");
                break;
            }

            case "gm": {
                if (!hasPerm(player.getName(), AdminPerm.GAMEMODE)) {
                    player.sendMessage("§c[Admin] No permission."); break;
                }
                if (args.length < 2) { player.sendMessage("§7Usage: /admin gm <survival|creative|adventure|spectator>"); break; }
                try {
                    player.setGameMode(GameMode.valueOf(args[1].toUpperCase()));
                    player.sendMessage("§a[Admin] Gamemode set to " + args[1].toLowerCase() + ".");
                } catch (IllegalArgumentException e) {
                    player.sendMessage("§cInvalid gamemode: " + args[1]);
                }
                break;
            }

            case "bypass": {
                toggleSpawnBypass(player);
                break;
            }

            case "dungeon": {
                if (args.length < 2 || !args[1].equalsIgnoreCase("start")) {
                    player.sendMessage("§7Usage: /admin dungeon start");
                    break;
                }
                if (dungeonAdminHandler == null) {
                    player.sendMessage("§c[Admin] Dungeon system is not initialized (world creation may have failed on startup).");
                    break;
                }
                dungeonAdminHandler.start(player);
                break;
            }

            case "kick": {
                if (!hasPerm(player.getName(), AdminPerm.KICK_PLAYERS)) {
                    player.sendMessage("§c[Admin] No permission."); break;
                }
                if (args.length < 2) { player.sendMessage("§7Usage: /admin kick <player> [reason]"); break; }
                Player target = plugin.getServer().getPlayer(args[1]);
                if (target == null) { player.sendMessage("§cPlayer not found."); break; }
                if (target.getName().equalsIgnoreCase(OWNER_NAME) || isWhitelisted(target.getName())) {
                    player.sendMessage("§cYou cannot kick another admin."); break;
                }
                String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "No reason provided";
                target.kickPlayer("§cKicked by admin.\n§7Reason: §f" + reason + "\n§7Appeal: §b" + DISCORD_LINK);
                player.sendMessage("§a[Admin] Kicked " + target.getName() + ".");
                plugin.getServer().broadcastMessage("§c[Admin] " + target.getName() + " was kicked by " + player.getName() + ".");
                break;
            }

            case "timeout": {
                if (!hasPerm(player.getName(), AdminPerm.TIMEOUT_PLAYERS)) {
                    player.sendMessage("§c[Admin] No permission."); break;
                }
                if (args.length < 3) { player.sendMessage("§7Usage: /admin timeout <player> <minutes> [reason]"); break; }
                String targetName = args[1];
                int minutes;
                try { minutes = Integer.parseInt(args[2]); } catch (NumberFormatException e) { player.sendMessage("§cInvalid minutes."); break; }
                String reason = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)) : "No reason provided";
                addTimeout(targetName, minutes);
                Player target = plugin.getServer().getPlayer(targetName);
                if (target != null) {
                    target.kickPlayer("§cYou have been temporarily banned.\n§7Reason: §f" + reason
                            + "\n§7Duration: §e" + minutes + " min\n§7Appeal: §b" + DISCORD_LINK);
                }
                player.sendMessage("§a[Admin] Timed out " + targetName + " for " + minutes + " minutes.");
                plugin.getServer().broadcastMessage("§c[Admin] " + targetName + " was timed out for " + minutes + " minutes by " + player.getName() + ".");
                break;
            }

            case "execute": {
                if (!hasPerm(player.getName(), AdminPerm.COMMAND_OVERRIDE)) {
                    player.sendMessage("§c[Admin] No permission."); break;
                }
                if (args.length < 2) { player.sendMessage("§7Usage: /admin execute <command>"); break; }
                String rawCommand = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                String toRun = rawCommand.startsWith("/") ? rawCommand.substring(1) : rawCommand;

                UUID uuid = player.getUniqueId();
                commandOverrideActive.add(uuid);
                boolean dispatched;
                try {
                    // performCommand() (unlike typing in chat) never fires
                    // PlayerCommandPreprocessEvent, and the commandOverrideActive
                    // flag lets any listener that checks it (dungeon lockdown,
                    // region guards, etc.) skip its own restriction too - so this
                    // genuinely runs as the player, with every command-level
                    // restriction bypassed, rather than just working around one.
                    dispatched = player.performCommand(toRun);
                } finally {
                    commandOverrideActive.remove(uuid);
                }

                player.sendMessage((dispatched ? "§a[Admin] Executed: §f/" : "§c[Admin] Command failed or not found: §f/") + toRun);
                logger.warning("[Admin] " + player.getName() + " used command override to run: /" + toRun);
                plugin.getServer().broadcastMessage("§c[Admin] " + player.getName() + " used a command override.");
                break;
            }

            case "whitelist": {
                if (!player.getName().equalsIgnoreCase(OWNER_NAME)) {
                    player.sendMessage("§c[Admin] Only the owner can manage the whitelist."); break;
                }
                if (args.length >= 3) {
                    if (args[1].equalsIgnoreCase("add")) {
                        addToWhitelist(args[2]);
                        player.sendMessage("§a[Admin] Added §f" + args[2] + "§a to whitelist.");
                    } else if (args[1].equalsIgnoreCase("remove")) {
                        removeFromWhitelist(args[2]);
                        player.sendMessage("§a[Admin] Removed §f" + args[2] + "§a from whitelist.");
                    }
                } else {
                    player.sendMessage("§eWhitelisted admins: §f" + String.join(", ", getWhitelistNames()));
                }
                break;
            }

            case "blacklist": {
                if (!player.getName().equalsIgnoreCase(OWNER_NAME)) {
                    player.sendMessage("§c[Admin] Only the owner can manage the blacklist."); break;
                }
                if (args.length >= 3) {
                    if (args[1].equalsIgnoreCase("add")) {
                        addToBlacklist(args[2]);
                        player.sendMessage("§c[Admin] Added §f" + args[2] + "§c to blacklist.");
                    } else if (args[1].equalsIgnoreCase("remove")) {
                        removeFromBlacklist(args[2]);
                        player.sendMessage("§a[Admin] Removed §f" + args[2] + "§a from blacklist.");
                    }
                } else {
                    player.sendMessage("§eBlacklisted players: §f" + String.join(", ", getBlacklistNames()));
                }
                break;
            }

            default:
                printAdminHelp(player);
        }

        return true;
    }

    private void printAdminHelp(Player player) {
        boolean isOwner = player.getName().equalsIgnoreCase(OWNER_NAME);
        player.sendMessage("§4§l──── Admin Panel ────");
        if (hasPerm(player.getName(), AdminPerm.TELEPORT))
            player.sendMessage("§e/admin tp §7— Teleport to Admin Zone");
        if (hasPerm(player.getName(), AdminPerm.GAMEMODE))
            player.sendMessage("§e/admin gm <mode> §7— Change gamemode");
        if (hasPerm(player.getName(), AdminPerm.SPAWN_BYPASS))
            player.sendMessage("§e/admin bypass §7— Toggle spawn build bypass");
        player.sendMessage("§e/admin dungeon start §7— Initialize dungeon generation and teleport to the entrance");
        if (hasPerm(player.getName(), AdminPerm.KICK_PLAYERS))
            player.sendMessage("§e/admin kick <player> [reason] §7— Kick a player");
        if (hasPerm(player.getName(), AdminPerm.TIMEOUT_PLAYERS))
            player.sendMessage("§e/admin timeout <player> <mins> [reason] §7— Temp ban");
        if (hasPerm(player.getName(), AdminPerm.COMMAND_OVERRIDE))
            player.sendMessage("§e/admin execute <command> §7— Run a command bypassing all restrictions");
        if (isOwner) {
            player.sendMessage("§e/admin whitelist §7— View/manage whitelist");
            player.sendMessage("§e/admin blacklist §7— View/manage blacklist");
        }
    }

    // =========================================================================
    // MAIN ADMIN MENU — double chest (54 slots)
    // =========================================================================

    private static ItemStack makeItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat, 1);
        ItemMeta meta  = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack makeSkull(String playerName, List<String> lore) {
        ItemStack skull  = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta   = (SkullMeta) skull.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§e" + playerName);
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
            if (!lore.isEmpty()) meta.setLore(lore);
            skull.setItemMeta(meta);
        }
        return skull;
    }

    private static ItemStack filler() {
        return makeItem(Material.GRAY_STAINED_GLASS_PANE, "§8", Collections.emptyList());
    }

    public void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§4§lAdmin Panel");
        ItemStack f   = filler();
        for (int i = 0; i < 54; i++) inv.setItem(i, f);

        String pName = player.getName();
        boolean isOwner = pName.equalsIgnoreCase(OWNER_NAME);

        // Row 1 (slots 10-16) — available buttons based on player permissions
        int[] btnSlots = { 10, 11, 12, 13, 14, 15, 16 };

        // Teleport
        if (hasPerm(pName, AdminPerm.TELEPORT)) {
            inv.setItem(10, makeItem(Material.ENDER_PEARL, "§6Teleport to Admin Zone",
                    Collections.singletonList("§7Click to teleport")));
        } else {
            inv.setItem(10, makeItem(Material.BARRIER, "§8Teleport to Admin Zone",
                    Collections.singletonList("§cNo permission")));
        }

        // Spawn bypass
        boolean bypassOn = player.getScoreboardTags().contains(SpawnProtection.BYPASS_TAG);
        if (hasPerm(pName, AdminPerm.SPAWN_BYPASS)) {
            inv.setItem(11, makeItem(bypassOn ? Material.LIME_DYE : Material.GRAY_DYE,
                    "§6Spawn Build Bypass: " + (bypassOn ? "§aON" : "§cOFF"),
                    Collections.singletonList("§7Click to toggle")));
        } else {
            inv.setItem(11, makeItem(Material.BARRIER, "§8Spawn Build Bypass",
                    Collections.singletonList("§cNo permission")));
        }

        // Gamemode
        if (hasPerm(pName, AdminPerm.GAMEMODE)) {
            inv.setItem(12, makeItem(Material.COMMAND_BLOCK, "§6Change Gamemode",
                    Collections.singletonList("§7Click to choose a gamemode")));
        } else {
            inv.setItem(12, makeItem(Material.BARRIER, "§8Change Gamemode",
                    Collections.singletonList("§cNo permission")));
        }

        // Admin whitelist (owner only)
        if (isOwner) {
            inv.setItem(13, makeItem(Material.PAPER, "§6Admin Whitelist",
                    Collections.singletonList("§7Manage whitelisted admins")));
        } else {
            inv.setItem(13, makeItem(Material.BARRIER, "§8Admin Whitelist",
                    Collections.singletonList("§cOwner only")));
        }

        // Admin shop
        if (hasPerm(pName, AdminPerm.ADMIN_SHOP)) {
            inv.setItem(14, makeItem(Material.ENDER_CHEST, "§6Admin Shop",
                    Arrays.asList("§7Free crate keys and special items", "§eClick to open")));
        } else {
            inv.setItem(14, makeItem(Material.BARRIER, "§8Admin Shop",
                    Collections.singletonList("§cNo permission")));
        }

        // Kick players
        if (hasPerm(pName, AdminPerm.KICK_PLAYERS)) {
            inv.setItem(15, makeItem(Material.IRON_DOOR, "§6Kick Players",
                    Collections.singletonList("§7Use /admin kick <player>")));
        } else {
            inv.setItem(15, makeItem(Material.BARRIER, "§8Kick Players",
                    Collections.singletonList("§cNo permission")));
        }

        // Timeout players
        if (hasPerm(pName, AdminPerm.TIMEOUT_PLAYERS)) {
            inv.setItem(16, makeItem(Material.CLOCK, "§6Timeout Players",
                    Collections.singletonList("§7Use /admin timeout <player> <mins>")));
        } else {
            inv.setItem(16, makeItem(Material.BARRIER, "§8Timeout Players",
                    Collections.singletonList("§cNo permission")));
        }

        // Island block bypass
        boolean blockBypassOn = player.getScoreboardTags().contains("island_block_bypass");
        if (hasPerm(pName, AdminPerm.ISLAND_BLOCK_BYPASS)) {
            inv.setItem(19, makeItem(blockBypassOn ? Material.LIME_DYE : Material.GRAY_DYE,
                    "§6Island Block Bypass: " + (blockBypassOn ? "§aON" : "§cOFF"),
                    Collections.singletonList("§7Allows breaking/placing on any island")));
        } else {
            inv.setItem(19, makeItem(Material.BARRIER, "§8Island Block Bypass",
                    Collections.singletonList("§cNo permission")));
        }

        // Island border bypass
        boolean borderBypassOn = player.getScoreboardTags().contains("island_border_bypass");
        if (hasPerm(pName, AdminPerm.ISLAND_BORDER_BYPASS)) {
            inv.setItem(20, makeItem(borderBypassOn ? Material.LIME_DYE : Material.GRAY_DYE,
                    "§6Island Border Bypass: " + (borderBypassOn ? "§aON" : "§cOFF"),
                    Collections.singletonList("§7Allows walking past island borders")));
        } else {
            inv.setItem(20, makeItem(Material.BARRIER, "§8Island Border Bypass",
                    Collections.singletonList("§cNo permission")));
        }

        // Ban list (owner only)
        if (isOwner) {
            inv.setItem(28, makeItem(Material.BOOK, "§6Ban List",
                    Collections.singletonList("§7Manage banned players")));
        }

        inv.setItem(49, makeItem(Material.BARRIER, "§cClose", Collections.emptyList()));

        menuSessions.put(player.getUniqueId(), MenuType.MAIN);
        player.openInventory(inv);
    }

    // =========================================================================
    // GAMEMODE SUB-MENU — double chest (54 slots)
    // =========================================================================

    private void openGamemodeMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, "§6Change Gamemode");
        ItemStack f   = filler();
        for (int i = 0; i < 54; i++) inv.setItem(i, f);

        inv.setItem(19, makeItem(Material.IRON_SWORD,     "§aSurvival",  Collections.singletonList("§7Click to set gamemode")));
        inv.setItem(21, makeItem(Material.GRASS_BLOCK,    "§bCreative",  Collections.singletonList("§7Click to set gamemode")));
        inv.setItem(23, makeItem(Material.DIAMOND_PICKAXE,"§dAdventure", Collections.singletonList("§7Click to set gamemode")));
        inv.setItem(25, makeItem(Material.ENDER_EYE,      "§7Spectator", Collections.singletonList("§7Click to set gamemode")));
        inv.setItem(45, makeItem(Material.ARROW, "§7← Back", Collections.emptyList()));
        inv.setItem(49, makeItem(Material.BARRIER, "§cClose", Collections.emptyList()));

        menuSessions.put(player.getUniqueId(), MenuType.GAMEMODE);
        player.openInventory(inv);
    }

    // =========================================================================
    // WHITELIST MENU — double chest (54 slots), owner-only
    // Player heads; click head → open player manage sub-menu
    // =========================================================================

    private void openWhitelistMenu(Player player, int page) {
        if (!player.getName().equalsIgnoreCase(OWNER_NAME)) {
            player.sendMessage("§c[Admin] Only the owner can access the whitelist.");
            return;
        }
        List<String> names = getWhitelistNames();
        int totalPages = Math.max(1, (int) Math.ceil(names.size() / (double) GRID_SLOTS.length));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, 54,
                "§6§lAdmin Whitelist §8(" + (page + 1) + "/" + totalPages + ")");
        ItemStack f = filler();
        for (int i = 0; i < 54; i++) inv.setItem(i, f);

        int start = page * GRID_SLOTS.length;
        for (int i = 0; i < GRID_SLOTS.length; i++) {
            int idx = start + i;
            if (idx >= names.size()) break;
            String name         = names.get(idx);
            boolean isOwnerEntry = name.equalsIgnoreCase(OWNER_NAME);
            List<String> lore   = new ArrayList<>();
            if (isOwnerEntry) {
                lore.add("§7Server Owner — cannot be removed");
            } else {
                lore.add("§eClick to manage permissions / remove");
            }
            inv.setItem(GRID_SLOTS[i], makeSkull(name, lore));
        }

        // Controls
        inv.setItem(0,  makeItem(Material.ARROW,   "§7← Back",          Collections.emptyList()));
        inv.setItem(4,  makeItem(Material.EMERALD,  "§a+ Add Player",     Collections.singletonList("§7Click to whitelist a player")));
        inv.setItem(8,  makeItem(Material.BARRIER,  "§cClose",            Collections.emptyList()));
        if (page > 0)            inv.setItem(45, makeItem(Material.ARROW, "§7◄ Prev Page", Collections.emptyList()));
        if (page < totalPages-1) inv.setItem(53, makeItem(Material.ARROW, "§7Next Page ►", Collections.emptyList()));

        menuSessions.put(player.getUniqueId(), MenuType.WHITELIST);
        listPage.put(player.getUniqueId(), page);
        player.openInventory(inv);
    }

    // =========================================================================
    // PLAYER MANAGE SUB-MENU — double chest (54 slots)
    // Shows permission toggles + a Remove button.
    // =========================================================================

    private void openPlayerManageMenu(Player viewer, String targetName) {
        if (!viewer.getName().equalsIgnoreCase(OWNER_NAME)) return;

        boolean isOwnerEntry = targetName.equalsIgnoreCase(OWNER_NAME);
        Inventory inv = Bukkit.createInventory(null, 54,
                "§6§lManage: §e" + targetName);
        ItemStack f = filler();
        for (int i = 0; i < 54; i++) inv.setItem(i, f);

        // Player head preview (centre top)
        inv.setItem(4, makeSkull(targetName, Collections.singletonList(
                isOwnerEntry ? "§7Server Owner" : "§7Admin")));

        if (!isOwnerEntry) {
            // Permission toggles — one per AdminPerm
            AdminPerm[] perms = AdminPerm.values();
            for (int i = 0; i < perms.length && i < PERM_SLOTS.length; i++) {
                AdminPerm perm  = perms[i];
                boolean granted = hasPerm(targetName, perm);
                List<String> lore = Arrays.asList(
                        (granted ? "§a✔ ENABLED" : "§c✘ DISABLED"),
                        "§7Click to toggle"
                );
                Material icon = granted ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE;
                // Use the perm's own icon tinted by state — put a coloured pane behind the icon via name colour
                inv.setItem(PERM_SLOTS[i], makeItem(perm.icon,
                        (granted ? "§a" : "§c") + perm.label, lore));
            }

            // Remove button
            inv.setItem(31, makeItem(Material.LAVA_BUCKET, "§c§lRemove from Whitelist",
                    Arrays.asList(
                            "§7Removes §e" + targetName + "§7 from the admin whitelist.",
                            "§7Their inventory will be cleared,",
                            "§7gamemode set to Survival, and they",
                            "§7will be de-opped.",
                            "§cIf offline, action is queued for next login."
                    )));
        } else {
            // Owner entry — show info only
            inv.setItem(22, makeItem(Material.NETHER_STAR, "§6Owner Account",
                    Arrays.asList("§7This is the hardcoded server owner.",
                            "§7Cannot be removed or have perms edited.")));
        }

        inv.setItem(45, makeItem(Material.ARROW,   "§7← Back to Whitelist", Collections.emptyList()));
        inv.setItem(49, makeItem(Material.BARRIER,  "§cClose",                Collections.emptyList()));

        managingPlayer.put(viewer.getUniqueId(), targetName.toLowerCase());
        menuSessions.put(viewer.getUniqueId(), MenuType.PLAYER_MANAGE);
        viewer.openInventory(inv);
    }

    // =========================================================================
    // BAN LIST SUB-MENU — double chest (54 slots), owner only
    // =========================================================================

    private void openBlacklistMenu(Player player, int page) {
        if (!player.getName().equalsIgnoreCase(OWNER_NAME)) return;
        List<String> names = getBlacklistNames();
        int totalPages = Math.max(1, (int) Math.ceil(names.size() / (double) GRID_SLOTS.length));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(null, 54,
                "§6§lBan List §8(" + (page + 1) + "/" + totalPages + ")");
        ItemStack f = filler();
        for (int i = 0; i < 54; i++) inv.setItem(i, f);

        int start = page * GRID_SLOTS.length;
        for (int i = 0; i < GRID_SLOTS.length; i++) {
            int idx = start + i;
            if (idx >= names.size()) break;
            String name = names.get(idx);
            inv.setItem(GRID_SLOTS[i], makeSkull(name, Collections.singletonList("§cClick to unban")));
        }

        inv.setItem(0,  makeItem(Material.ARROW,  "§7← Back",      Collections.emptyList()));
        inv.setItem(4,  makeItem(Material.EMERALD, "§a+ Ban Player", Collections.singletonList("§7Click to ban a player")));
        inv.setItem(8,  makeItem(Material.BARRIER, "§cClose",        Collections.emptyList()));
        if (page > 0)            inv.setItem(45, makeItem(Material.ARROW, "§7◄ Prev Page", Collections.emptyList()));
        if (page < totalPages-1) inv.setItem(53, makeItem(Material.ARROW, "§7Next Page ►", Collections.emptyList()));

        menuSessions.put(player.getUniqueId(), MenuType.BLACKLIST);
        listPage.put(player.getUniqueId(), page);
        player.openInventory(inv);
    }

    // =========================================================================
    // INVENTORY CLICK HANDLER
    // =========================================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        MenuType menu = menuSessions.get(player.getUniqueId());
        if (menu == null) return;

        event.setCancelled(true);
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        int slot = event.getRawSlot();

        switch (menu) {
            case MAIN          -> handleMainClick(player, slot);
            case GAMEMODE      -> handleGamemodeClick(player, slot);
            case WHITELIST     -> handleWhitelistClick(player, slot);
            case PLAYER_MANAGE -> handlePlayerManageClick(player, slot, event.getCurrentItem());
            case BLACKLIST     -> handleBlacklistClick(player, slot);
        }
    }

    private void handleMainClick(Player player, int slot) {
        String pName  = player.getName();
        boolean owner = pName.equalsIgnoreCase(OWNER_NAME);
        switch (slot) {
            case 10: // Teleport
                if (!hasPerm(pName, AdminPerm.TELEPORT)) return;
                player.closeInventory();
                player.teleport(new org.bukkit.Location(
                        plugin.getServer().getWorld("world"),
                        ADMIN_ZONE_X, ADMIN_ZONE_Y, ADMIN_ZONE_Z));
                player.sendMessage("§a[Admin] Teleported to Admin Zone.");
                break;
            case 11: // Spawn bypass
                if (!hasPerm(pName, AdminPerm.SPAWN_BYPASS)) return;
                toggleSpawnBypass(player);
                plugin.getServer().getScheduler().runTask(plugin, () -> openMainMenu(player));
                break;
            case 12: // Gamemode
                if (!hasPerm(pName, AdminPerm.GAMEMODE)) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> openGamemodeMenu(player));
                break;
            case 13: // Whitelist (owner only)
                if (!owner) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> openWhitelistMenu(player, 0));
                break;
            case 14: // Admin shop
                if (!hasPerm(pName, AdminPerm.ADMIN_SHOP)) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> adminShopSystem.showCategoryMenu(player));
                break;
            case 28: // Ban list (owner only)
                if (!owner) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> openBlacklistMenu(player, 0));
                break;
            case 19: // Island block bypass
                if (!hasPerm(pName, AdminPerm.ISLAND_BLOCK_BYPASS)) return;
                toggleTag(player, "island_block_bypass", "Island Block Bypass");
                plugin.getServer().getScheduler().runTask(plugin, () -> openMainMenu(player));
                break;
            case 20: // Island border bypass
                if (!hasPerm(pName, AdminPerm.ISLAND_BORDER_BYPASS)) return;
                toggleTag(player, "island_border_bypass", "Island Border Bypass");
                plugin.getServer().getScheduler().runTask(plugin, () -> openMainMenu(player));
                break;
            case 49: // Close
                player.closeInventory();
                break;
        }
    }

    private void handleGamemodeClick(Player player, int slot) {
        GameMode gm = null;
        switch (slot) {
            case 19: gm = GameMode.SURVIVAL;  break;
            case 21: gm = GameMode.CREATIVE;  break;
            case 23: gm = GameMode.ADVENTURE; break;
            case 25: gm = GameMode.SPECTATOR; break;
            case 45:
                plugin.getServer().getScheduler().runTask(plugin, () -> openMainMenu(player));
                return;
            case 49:
                player.closeInventory();
                return;
        }
        if (gm != null) {
            GameMode finalGm = gm;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.setGameMode(finalGm);
                player.sendMessage("§a[Admin] Gamemode set to " + finalGm.name().toLowerCase() + ".");
                player.closeInventory();
            });
        }
    }

    private void handleWhitelistClick(Player player, int slot) {
        if (!player.getName().equalsIgnoreCase(OWNER_NAME)) return;
        int page = listPage.getOrDefault(player.getUniqueId(), 0);

        if (slot == 0) { plugin.getServer().getScheduler().runTask(plugin, () -> openMainMenu(player)); return; }
        if (slot == 8) { player.closeInventory(); return; }
        if (slot == 4) {
            player.closeInventory();
            player.sendMessage("§aType the player name to whitelist in chat (or §7'cancel'§a to abort):");
            inputMode.put(player.getUniqueId(), InputMode.ADD_WHITELIST);
            return;
        }
        if (slot == 45 && page > 0) { final int p = page-1; plugin.getServer().getScheduler().runTask(plugin, () -> openWhitelistMenu(player, p)); return; }
        if (slot == 53)              { final int p = page+1; plugin.getServer().getScheduler().runTask(plugin, () -> openWhitelistMenu(player, p)); return; }

        // Click on a player head → open manage menu
        int gridIdx = indexOfGridSlot(slot);
        if (gridIdx < 0) return;
        List<String> names = getWhitelistNames();
        int idx = page * GRID_SLOTS.length + gridIdx;
        if (idx >= names.size()) return;
        String name = names.get(idx);
        plugin.getServer().getScheduler().runTask(plugin, () -> openPlayerManageMenu(player, name));
    }

    private void handlePlayerManageClick(Player viewer, int slot, ItemStack clicked) {
        if (!viewer.getName().equalsIgnoreCase(OWNER_NAME)) return;
        String targetLower = managingPlayer.get(viewer.getUniqueId());
        if (targetLower == null) return;

        // Restore display name for messages
        String targetDisplay = clicked != null && clicked.getItemMeta() != null
                ? clicked.getItemMeta().getDisplayName().replaceAll("§.", "") : targetLower;

        if (slot == 45) {
            plugin.getServer().getScheduler().runTask(plugin, () -> openWhitelistMenu(viewer, 0));
            return;
        }
        if (slot == 49) { viewer.closeInventory(); return; }

        // Remove button
        if (slot == 31) {
            if (targetLower.equalsIgnoreCase(OWNER_NAME)) {
                viewer.sendMessage("§cYou cannot remove the server owner.");
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                removeFromWhitelist(targetLower);
                viewer.sendMessage("§a[Admin] Removed §f" + targetLower + "§a from whitelist.");
                openWhitelistMenu(viewer, 0);
            });
            return;
        }

        // Permission toggle slots
        AdminPerm[] perms = AdminPerm.values();
        for (int i = 0; i < perms.length && i < PERM_SLOTS.length; i++) {
            if (slot == PERM_SLOTS[i]) {
                if (targetLower.equalsIgnoreCase(OWNER_NAME)) return; // owner perms immutable
                AdminPerm perm = perms[i];
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    togglePerm(targetLower, perm);
                    boolean now = hasPerm(targetLower, perm);
                    viewer.sendMessage("§a[Admin] " + perm.label + " for §f" + targetLower
                            + "§a set to: " + (now ? "§aENABLED" : "§cDISABLED"));
                    openPlayerManageMenu(viewer, targetLower); // refresh
                });
                return;
            }
        }
    }

    private void handleBlacklistClick(Player player, int slot) {
        if (!player.getName().equalsIgnoreCase(OWNER_NAME)) return;
        int page = listPage.getOrDefault(player.getUniqueId(), 0);

        if (slot == 0) { plugin.getServer().getScheduler().runTask(plugin, () -> openMainMenu(player)); return; }
        if (slot == 8) { player.closeInventory(); return; }
        if (slot == 4) {
            player.closeInventory();
            player.sendMessage("§cType the player name to ban in chat (or §7'cancel'§c to abort):");
            inputMode.put(player.getUniqueId(), InputMode.ADD_BLACKLIST);
            return;
        }
        if (slot == 45 && page > 0) { final int p = page-1; plugin.getServer().getScheduler().runTask(plugin, () -> openBlacklistMenu(player, p)); return; }
        if (slot == 53)              { final int p = page+1; plugin.getServer().getScheduler().runTask(plugin, () -> openBlacklistMenu(player, p)); return; }

        int gridIdx = indexOfGridSlot(slot);
        if (gridIdx < 0) return;
        List<String> names = getBlacklistNames();
        int idx = page * GRID_SLOTS.length + gridIdx;
        if (idx >= names.size()) return;
        String name = names.get(idx);
        removeFromBlacklist(name);
        player.sendMessage("§a[Admin] Removed §f" + name + "§a from blacklist.");
        final int p = page;
        plugin.getServer().getScheduler().runTask(plugin, () -> openBlacklistMenu(player, p));
    }

    private static int indexOfGridSlot(int slot) {
        for (int i = 0; i < GRID_SLOTS.length; i++) {
            if (GRID_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    // =========================================================================
    // CHAT INPUT HANDLER
    // =========================================================================

    @EventHandler
    public void onPlayerChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        InputMode mode = inputMode.get(player.getUniqueId());
        if (mode == null || mode == InputMode.NONE) return;

        event.setCancelled(true);
        inputMode.remove(player.getUniqueId());
        String input          = event.getMessage().trim();
        final InputMode fMode = mode;

        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage("§7Cancelled.");
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (fMode == InputMode.ADD_WHITELIST) openWhitelistMenu(player, 0);
                else openBlacklistMenu(player, 0);
            });
            return;
        }

        final String name = input;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.getName().equalsIgnoreCase(OWNER_NAME)) {
                player.sendMessage("§c[Admin] You do not have permission.");
                return;
            }
            if (fMode == InputMode.ADD_WHITELIST) {
                addToWhitelist(name);
                player.sendMessage("§a[Admin] Added §f" + name + "§a to whitelist.");
                openWhitelistMenu(player, 0);
            } else if (fMode == InputMode.ADD_BLACKLIST) {
                addToBlacklist(name);
                player.sendMessage("§c[Admin] Added §f" + name + "§c to blacklist.");
                openBlacklistMenu(player, 0);
            }
        });
    }

    // =========================================================================
    // INVENTORY CLOSE / QUIT CLEANUP
    // =========================================================================

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        UUID id       = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!menuSessions.containsKey(id)) return;
            String title = player.getOpenInventory().getTitle();
            boolean stillIn = title.equals("§4§lAdmin Panel")
                    || title.equals("§6Change Gamemode")
                    || title.startsWith("§6§lAdmin Whitelist")
                    || title.startsWith("§6§lManage: ")
                    || title.startsWith("§6§lBan List");
            if (!stillIn) {
                menuSessions.remove(id);
                listPage.remove(id);
                managingPlayer.remove(id);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        menuSessions.remove(id);
        listPage.remove(id);
        managingPlayer.remove(id);
        inputMode.remove(id);
    }

    // ─── Persistence ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void load() {
        // Whitelist
        try {
            whitelist.add(OWNER_NAME.toLowerCase());
            List<?> wl = readJsonList(dataDir.resolve(FILE_WHITELIST));
            for (Object o : wl) whitelist.add(o.toString().toLowerCase());
        } catch (Exception ignored) {}

        // Permissions
        try {
            Map<?, ?> pm = readJsonMap(dataDir.resolve(FILE_PERMISSIONS));
            for (Map.Entry<?, ?> e : pm.entrySet()) {
                Set<String> set = new HashSet<>();
                if (e.getValue() instanceof List) {
                    for (Object v : (List<?>) e.getValue()) set.add(v.toString());
                }
                permissions.put(e.getKey().toString().toLowerCase(), set);
            }
        } catch (Exception ignored) {}

        // Blacklist
        try {
            List<?> bl = readJsonList(dataDir.resolve(FILE_BLACKLIST));
            for (Object o : bl) blacklist.add(o.toString().toLowerCase());
        } catch (Exception ignored) {}

        // Timeouts
        try {
            Map<?, ?> tm = readJsonMap(dataDir.resolve(FILE_TIMEOUTS));
            for (Map.Entry<?, ?> e : tm.entrySet())
                timeouts.put(e.getKey().toString(), ((Number) e.getValue()).longValue());
        } catch (Exception ignored) {}

        // Queued strip
        try {
            List<?> qs = readJsonList(dataDir.resolve(FILE_QUEUED_STRIP));
            for (Object o : qs) queuedStrip.add(o.toString().toLowerCase());
        } catch (Exception ignored) {}
    }

    private void saveWhitelist() {
        writeJson(dataDir.resolve(FILE_WHITELIST), new ArrayList<>(whitelist));
    }

    private void savePermissions() {
        // Convert Set<String> values to List for JSON serialisation
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : permissions.entrySet())
            out.put(e.getKey(), new ArrayList<>(e.getValue()));
        writeJson(dataDir.resolve(FILE_PERMISSIONS), out);
    }

    private void saveBlacklist() {
        writeJson(dataDir.resolve(FILE_BLACKLIST), new ArrayList<>(blacklist));
    }

    private void saveTimeouts() {
        writeJson(dataDir.resolve(FILE_TIMEOUTS), timeouts);
    }

    private void saveQueuedStrip() {
        writeJson(dataDir.resolve(FILE_QUEUED_STRIP), new ArrayList<>(queuedStrip));
    }

    private static List<?> readJsonList(Path path) throws Exception {
        String raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return new com.google.gson.Gson().fromJson(raw, List.class);
    }

    private static Map<?, ?> readJsonMap(Path path) throws Exception {
        String raw = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return new com.google.gson.Gson().fromJson(raw, Map.class);
    }

    private void writeJson(Path path, Object obj) {
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .create().toJson(obj).getBytes(StandardCharsets.UTF_8);
            Files.write(path, bytes);
        } catch (IOException e) {
            logger.severe("[Admin] Failed to save " + path.getFileName() + ": " + e.getMessage());
        }
    }
}

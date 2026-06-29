package com.skyblock.island;

import com.skyblock.island.IslandGenerator;
import com.skyblock.island.InviteSystem;
import com.skyblock.storage.IslandData;
import com.skyblock.storage.IslandStorage;
import com.skyblock.util.NameValidator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

/**
 * Island Menu — full chest GUI for island management.
 *
 * Panels:
 *   MAIN         — hub for all island actions
 *   INFO         — island stats
 *   MEMBERS      — member list
 *   MEMBER_ACT   — promote / demote / kick
 *   WARPS        — manage public warps
 *   INVITE       — invite online players
 *   FLAGS        — toggle island flags (mob spawning, daylight, pvp, weather)
 *   UPGRADES     — border size upgrades (25 → 50 → 100 → 200)
 *   RESET        — confirm island reset
 *   SHOPS        — quick-nav to /shop, /gemshop, /killshop, /pwarp
 */
public class IslandMenu implements Listener {

    // ─── Layout constants ─────────────────────────────────────────────────────
    private static final Location SPAWN_LOC = new Location(null, 0.5, 284, 0.5);
    private static final int[] BORDER_SLOTS =
        {0,1,2,3,4,5,6,7,8,9,17,18,26,27,35,36,44,45,46,47,48,49,50,51,52,53};
    private static final int[] MEMBER_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25};
    private static final int[] WARP_SLOTS   = {10,11,12,13,14,15,16,19,20,21,22,23,24,25};
    private static final int[] INVITE_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25};

    // ─── GUI titles (used to route click events) ──────────────────────────────
    private static final String TITLE_MAIN        = "§l§bSkyblock Island";
    private static final String TITLE_INFO        = "§l§bIsland Info";
    private static final String TITLE_MEMBERS     = "§l§6Island Members";
    private static final String TITLE_MEMBER_ACT  = "§l§6Manage:";
    private static final String TITLE_WARPS       = "§l§dIsland Warps";
    private static final String TITLE_INVITE      = "§l§aInvite Player";
    private static final String TITLE_RESET       = "§l§cReset Island?";
    private static final String TITLE_FLAGS       = "§l§eIsland Flags";
    private static final String TITLE_UPGRADES    = "§l§6Island Upgrades";
    private static final String TITLE_SHOPS       = "§l§aIsland Shops";

    // ─── Gem objective (for upgrade cost) ────────────────────────────────────
    private static final String OBJ_GEMS = "gems";

    // ─── Border upgrade tiers: radius → gem cost ──────────────────────────────
    private static final int[] BORDER_TIERS  = {25, 50, 100, 200};
    private static final int[] BORDER_COSTS  = {0, 100, 250, 650, 1500};
    // BORDER_COSTS[i] = cost to reach BORDER_TIERS[i] from BORDER_TIERS[i-1]
    // Index 0 unused (starting tier is free), costs[1..4] match tiers[1..3]

    // ─── Chat-input state (warp naming / island rename) ───────────────────────
    private enum InputMode { NONE, SET_WARP, RENAME }
    private final Map<UUID, InputMode> inputMode    = new HashMap<>();
    private final Map<UUID, String>    activeIsland = new HashMap<>();

    private final JavaPlugin      plugin;
    private final IslandStorage   storage;
    private final IslandGenerator generator;
    private final InviteSystem    inviteSystem;
    private final NameValidator   nameValidator;

    public IslandMenu(JavaPlugin plugin, IslandStorage storage, IslandGenerator generator,
                      InviteSystem inviteSystem, NameValidator nameValidator) {
        this.plugin        = plugin;
        this.storage       = storage;
        this.generator     = generator;
        this.inviteSystem  = inviteSystem;
        this.nameValidator = nameValidator;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private World overworld() { return plugin.getServer().getWorld("world"); }

    private Player onlineById(String id) {
        try { return plugin.getServer().getPlayer(UUID.fromString(id)); }
        catch (Exception e) { return null; }
    }

    private void notifyMembers(IslandData island, String excludeId, String msg) {
        for (IslandData.Member m : island.members) {
            if (m.id.equals(excludeId)) continue;
            Player p = onlineById(m.id);
            if (p != null) p.sendMessage(msg);
        }
    }

    private void fillBorder(Inventory inv, Material pane) {
        ItemStack glass = buildItem(pane, "§r", Collections.emptyList());
        for (int slot : BORDER_SLOTS) inv.setItem(slot, glass);
    }

    private ItemStack buildItem(Material mat, String name, List<String> lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta  = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private int getGems(Player player) {
        try {
            Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective(OBJ_GEMS);
            if (obj == null) return 0;
            var score = obj.getScore(player.getName());
            return score.isScoreSet() ? score.getScore() : 0;
        } catch (Exception e) { return 0; }
    }

    private void setGems(Player player, int value) {
        try {
            Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective(OBJ_GEMS);
            if (obj != null) obj.getScore(player.getName()).setScore(value);
        } catch (Exception ignored) {}
    }

    private boolean getFlag(IslandData island, String flag) {
        if (island.flags == null) return true;
        return island.flags.getOrDefault(flag, true);
    }

    // =========================================================================
    // MAIN MENU
    // =========================================================================
    public void showIslandMenu(Player player) {
        IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
        String myRole = island != null ? storage.getMemberRole(island, player.getUniqueId().toString()) : null;

        Inventory inv = plugin.getServer().createInventory(null, 54, TITLE_MAIN);
        fillBorder(inv, Material.CYAN_STAINED_GLASS_PANE);

        if (island == null) {
            inv.setItem(22, buildItem(Material.GRASS_BLOCK, "§l§aCreate Island",
                    List.of("", "§7Start your Skyblock journey!", "", "§eClick to create!")));
        } else {
            // Row 1 inner
            inv.setItem(10, buildItem(Material.RED_BED, "§l§eTeleport Home",
                    List.of("", "§7Go to your island spawn")));
            inv.setItem(12, buildItem(Material.PAPER, "§l§bIsland Info",
                    List.of("", "§7Name: §e" + island.name,
                            "§7Members: §e" + island.members.size() + "§7/§e" + island.maxMembers,
                            "§7Border: §e" + island.borderRadius + " blocks")));
            inv.setItem(14, buildItem(Material.PLAYER_HEAD, "§l§6Members",
                    List.of("", "§7View & manage members")));
            inv.setItem(16, buildItem(Material.ENDER_PEARL, "§l§dWarps",
                    List.of("", "§7Manage public warps")));

            // Row 2 inner
            boolean isOwnerOrAdmin = "Owner".equals(myRole) || "Admin".equals(myRole);
            inv.setItem(28, isOwnerOrAdmin
                    ? buildItem(Material.NAME_TAG, "§l§aInvite Player", List.of("", "§7Invite someone online"))
                    : buildItem(Material.BARRIER, "§8Invite Player", List.of("", "§7Owner/Admin only")));

            inv.setItem(30, buildItem(Material.ENDER_EYE, "§l§dSet Warp Here",
                    List.of("", "§7Create a warp at your location")));

            boolean isOwner = "Owner".equals(myRole);
            inv.setItem(32, isOwner
                    ? buildItem(Material.OAK_SIGN, "§l§eRename Island", List.of("", "§7Change your island name"))
                    : buildItem(Material.BARRIER, "§8Rename Island", List.of("", "§7Owner only")));

            // Row 3 inner
            inv.setItem(37, buildItem(Material.YELLOW_BANNER, "§l§eIsland Flags",
                    List.of("", "§7Toggle mob spawning, PvP,", "§7weather & daylight cycle")));

            inv.setItem(39, buildItem(Material.DIAMOND, "§l§bIsland Upgrades",
                    List.of("", "§7Upgrade your island border", "§7Current: §e" + island.borderRadius + " blocks")));

            inv.setItem(41, buildItem(Material.CHEST, "§l§aShops",
                    List.of("", "§7Quick-access to all shops")));

            if (isOwner) {
                inv.setItem(43, buildItem(Material.TNT, "§l§cReset Island",
                        List.of("", "§7§lWARNING: §r§7Destroys & regenerates island")));
            }

            inv.setItem(49, buildItem(Material.IRON_DOOR, "§l§cLeave Island",
                    List.of("", "§7Leave this island")));
        }
        player.openInventory(inv);
    }

    // =========================================================================
    // INFO MENU
    // =========================================================================
    private void showInfoMenu(Player player, IslandData island) {
        Inventory inv = plugin.getServer().createInventory(null, 54, TITLE_INFO);
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE);

        inv.setItem(20, buildItem(Material.NAME_TAG,    "§l§eName",    List.of("§7" + island.name)));
        inv.setItem(22, buildItem(Material.PLAYER_HEAD, "§l§6Owner",   List.of("§7" + island.ownerName)));
        inv.setItem(24, buildItem(Material.PAPER,       "§l§bMembers", List.of("§7" + island.members.size() + "§8/§7" + island.maxMembers)));
        inv.setItem(29, buildItem(Material.ENDER_PEARL, "§l§aWarps",   List.of("§7" + island.pwarps.size() + " public warp(s)")));
        inv.setItem(31, buildItem(Material.SHIELD,      "§l§7Border",  List.of("§7" + island.borderRadius + " blocks")));
        inv.setItem(33, buildItem(Material.COMPASS,     "§l§7Location",List.of("§7" + (int)island.centerX + ", 64, " + (int)island.centerZ)));
        inv.setItem(49, buildItem(Material.ARROW,       "§l§7Back",    List.of("§7Return to main menu")));
        player.openInventory(inv);
    }

    // =========================================================================
    // FLAGS MENU
    // =========================================================================
    private void showFlagsMenu(Player player, IslandData island) {
        Inventory inv = plugin.getServer().createInventory(null, 27, TITLE_FLAGS);

        ItemStack pane = buildItem(Material.YELLOW_STAINED_GLASS_PANE, "§r", List.of());
        for (int i : new int[]{0,1,2,3,4,5,6,7,8,18,19,20,21,22,23,24,25,26}) inv.setItem(i, pane);

        boolean mobs    = getFlag(island, "mobSpawning");
        boolean day     = getFlag(island, "daylightCycle");
        boolean pvp     = getFlag(island, "pvp");
        boolean weather = getFlag(island, "weather");

        inv.setItem(10, buildFlagItem("Mob Spawning",   mobs,    Material.ZOMBIE_HEAD,    Material.DIRT));
        inv.setItem(12, buildFlagItem("Daylight Cycle", day,     Material.CLOCK,          Material.GRAY_CONCRETE));
        inv.setItem(14, buildFlagItem("PvP",            pvp,     Material.IRON_SWORD,     Material.GRAY_CONCRETE));
        inv.setItem(16, buildFlagItem("Weather",        weather, Material.WATER_BUCKET,   Material.GRAY_CONCRETE));

        inv.setItem(22, buildItem(Material.ARROW, "§l§7Back", List.of("§7Return to main menu")));
        player.openInventory(inv);
    }

    private ItemStack buildFlagItem(String label, boolean enabled, Material onMat, Material offMat) {
        return buildItem(
            enabled ? onMat : offMat,
            (enabled ? "§a✔ " : "§c✖ ") + label,
            List.of("", "§7Status: " + (enabled ? "§aEnabled" : "§cDisabled"), "", "§eClick to toggle")
        );
    }

    // =========================================================================
    // UPGRADES MENU
    // =========================================================================
    private void showUpgradesMenu(Player player, IslandData island) {
        int gems = getGems(player);
        Inventory inv = plugin.getServer().createInventory(null, 27, TITLE_UPGRADES);

        ItemStack pane = buildItem(Material.BLUE_STAINED_GLASS_PANE, "§r", List.of());
        for (int i : new int[]{0,1,2,3,4,5,6,7,8,18,19,20,21,22,23,24,25,26}) inv.setItem(i, pane);

        inv.setItem(4, buildItem(Material.NETHER_STAR, "§b💎 Your Gems",
                List.of("", "§eGems: §f" + gems)));

        // 4 tiers: 25, 50, 100, 200
        // costs to upgrade FROM current: 100, 250, 650, 1500
        int[] tierSlots = {10, 12, 14, 16};
        int[] tierSizes  = {25, 50, 100, 200};
        int[] tierCosts  = {100, 250, 650, 1500}; // cost to unlock each tier (index = tier index)

        for (int i = 0; i < 4; i++) {
            int size = tierSizes[i];
            boolean owned   = island.borderRadius >= size;
            boolean current = island.borderRadius == size;
            boolean canBuy  = !owned && i > 0 && island.borderRadius == tierSizes[i - 1] && gems >= tierCosts[i];
            boolean locked  = !owned && (i == 0 || island.borderRadius < tierSizes[i - 1]);

            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("§7Border size: §e" + size + " blocks");
            if (current)      lore.add("§a✔ Current tier");
            else if (owned)   lore.add("§7(previous tier)");
            else if (locked)  lore.add("§cLocked — upgrade previous tier first");
            else {
                lore.add("§6Cost: §f" + tierCosts[i] + " gems");
                lore.add(canBuy ? "§aClick to upgrade!" : "§cNot enough gems");
            }

            Material mat = owned   ? Material.LIME_CONCRETE
                         : canBuy  ? Material.YELLOW_CONCRETE
                         : Material.RED_CONCRETE;
            String name = (current ? "§a§l" : owned ? "§7§l" : canBuy ? "§e§l" : "§c§l")
                         + "Border: " + size + " blocks";
            inv.setItem(tierSlots[i], buildItem(mat, name, lore));
        }

        inv.setItem(22, buildItem(Material.ARROW, "§l§7Back", List.of("§7Return to main menu")));
        player.openInventory(inv);
    }

    // =========================================================================
    // SHOPS MENU
    // =========================================================================
    private void showShopsMenu(Player player) {
        Inventory inv = plugin.getServer().createInventory(null, 27, TITLE_SHOPS);
        ItemStack pane = buildItem(Material.GREEN_STAINED_GLASS_PANE, "§r", List.of());
        for (int i : new int[]{0,1,2,3,4,5,6,7,8,18,19,20,21,22,23,24,25,26}) inv.setItem(i, pane);

        inv.setItem(10, buildItem(Material.EMERALD,     "§l§aItem Shop",    List.of("", "§7Buy & sell items", "", "§eClick to open /shop")));
        inv.setItem(12, buildItem(Material.PRISMARINE_CRYSTALS, "§l§bGem Shop",  List.of("", "§7Buy with gems", "", "§eClick to open /gemshop")));
        inv.setItem(14, buildItem(Material.TRIAL_KEY,   "§l§cKill Shop",   List.of("", "§7Spend kill credits", "", "§eClick to open /killshop")));
        inv.setItem(16, buildItem(Material.ENDER_PEARL, "§l§dPublic Warps",List.of("", "§7Visit island warps", "", "§eClick to open /pwarp")));

        inv.setItem(22, buildItem(Material.ARROW, "§l§7Back", List.of("§7Return to main menu")));
        player.openInventory(inv);
    }

    // =========================================================================
    // MEMBERS MENU
    // =========================================================================
    private void showMembersMenu(Player player, IslandData island, String myRole) {
        Inventory inv = plugin.getServer().createInventory(null, 54, TITLE_MEMBERS);
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE);

        for (int i = 0; i < island.members.size() && i < MEMBER_SLOTS.length; i++) {
            IslandData.Member m = island.members.get(i);
            String roleColor = "Owner".equals(m.role) ? "§6" : "Admin".equals(m.role) ? "§e" : "§7";
            boolean canAct = "Owner".equals(myRole) || ("Admin".equals(myRole) && "Member".equals(m.role));
            boolean isSelf = m.id.equals(player.getUniqueId().toString());
            inv.setItem(MEMBER_SLOTS[i], buildItem(Material.PLAYER_HEAD,
                    roleColor + m.name,
                    List.of("§7Role: " + roleColor + m.role,
                            canAct && !isSelf ? "§7Click to manage" : "")));
        }
        inv.setItem(49, buildItem(Material.ARROW, "§l§7Back", List.of("§7Return to main menu")));
        player.openInventory(inv);
    }

    // =========================================================================
    // MEMBER ACTION MENU
    // =========================================================================
    private void showMemberActionMenu(Player player, IslandData.Member target) {
        Inventory inv = plugin.getServer().createInventory(null, 9,
                TITLE_MEMBER_ACT + " §r" + target.name);
        inv.setItem(2, buildItem(Material.ARROW, "§l§aPromote", List.of("§7Raise rank")));
        inv.setItem(4, buildItem(Material.ARROW, "§l§eDemote",  List.of("§7Lower rank")));
        inv.setItem(6, buildItem(Material.BARRIER,"§l§cKick",   List.of("§7Remove from island")));
        player.openInventory(inv);
    }

    // =========================================================================
    // WARPS MENU
    // =========================================================================
    private void showWarpsMenu(Player player, IslandData island, String myRole) {
        Inventory inv = plugin.getServer().createInventory(null, 54, TITLE_WARPS);
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE);

        for (int i = 0; i < island.pwarps.size() && i < WARP_SLOTS.length; i++) {
            IslandData.Warp w = island.pwarps.get(i);
            boolean canDel = w.creatorId.equals(player.getUniqueId().toString())
                    || "Owner".equals(myRole) || "Admin".equals(myRole);
            inv.setItem(WARP_SLOTS[i], buildItem(Material.ENDER_PEARL, "§l§d" + w.name,
                    List.of("§7By: §e" + w.creatorName, canDel ? "§7Click to delete" : "")));
        }
        if (island.pwarps.isEmpty())
            inv.setItem(22, buildItem(Material.BARRIER, "§7No warps yet", List.of("§7Use Set Warp to create one")));

        inv.setItem(49, buildItem(Material.ARROW, "§l§7Back", List.of("§7Return to main menu")));
        player.openInventory(inv);
    }

    // =========================================================================
    // INVITE MENU
    // =========================================================================
    private void showInviteMenu(Player player, IslandData island) {
        List<Player> candidates = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers())
            if (!p.getUniqueId().equals(player.getUniqueId())
                    && storage.getIslandByPlayerId(p.getUniqueId().toString()) == null)
                candidates.add(p);

        if (candidates.isEmpty()) {
            player.sendMessage("§cNo players are available to invite right now.");
            showIslandMenu(player);
            return;
        }
        Inventory inv = plugin.getServer().createInventory(null, 54, TITLE_INVITE);
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < candidates.size() && i < INVITE_SLOTS.length; i++)
            inv.setItem(INVITE_SLOTS[i], buildItem(Material.PLAYER_HEAD,
                    "§l§a" + candidates.get(i).getName(), List.of("§7Click to invite")));
        inv.setItem(49, buildItem(Material.ARROW, "§l§7Back", List.of("§7Return to main menu")));
        player.openInventory(inv);
    }

    // =========================================================================
    // SET WARP (chat prompt)
    // =========================================================================
    private void promptSetWarp(Player player, IslandData island) {
        player.closeInventory();
        player.sendMessage("§dType the warp name in chat (or 'cancel' to abort):");
        inputMode.put(player.getUniqueId(), InputMode.SET_WARP);
        activeIsland.put(player.getUniqueId(), island.id);
    }

    // =========================================================================
    // RENAME (chat prompt)
    // =========================================================================
    private void promptRename(Player player, IslandData island) {
        player.closeInventory();
        player.sendMessage("§eType the new island name in chat (or 'cancel' to abort):");
        player.sendMessage("§7Current: §e" + island.name);
        inputMode.put(player.getUniqueId(), InputMode.RENAME);
        activeIsland.put(player.getUniqueId(), island.id);
    }

    // =========================================================================
    // RESET CONFIRM
    // =========================================================================
    private void showResetMenu(Player player) {
        Inventory inv = plugin.getServer().createInventory(null, 9, TITLE_RESET);
        inv.setItem(3, buildItem(Material.TNT, "§l§cYES - Reset",
                List.of("§7§lWARNING: §r§7Cannot be undone!")));
        inv.setItem(5, buildItem(Material.GREEN_CONCRETE, "§l§aCancel", List.of("§7Go back")));
        player.openInventory(inv);
    }

    // =========================================================================
    // CHAT HANDLER
    // =========================================================================
    @EventHandler
    public void onChat(org.bukkit.event.player.AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        InputMode mode = inputMode.get(player.getUniqueId());
        if (mode == null || mode == InputMode.NONE) return;

        event.setCancelled(true);
        inputMode.remove(player.getUniqueId());
        String input    = event.getMessage().trim();
        String islandId = activeIsland.remove(player.getUniqueId());

        if (input.equalsIgnoreCase("cancel")) {
            player.sendMessage("§7Cancelled.");
            plugin.getServer().getScheduler().runTask(plugin, () -> showIslandMenu(player));
            return;
        }

        final InputMode finalMode = mode;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            IslandData island = islandId != null ? storage.getIsland(islandId) : null;
            if (island == null) { player.sendMessage("§cIsland not found."); return; }

            if (finalMode == InputMode.SET_WARP) {
                NameValidator.Result v = nameValidator.validate(input);
                if (!v.valid) { player.sendMessage(v.reason); return; }
                String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
                IslandData.Warp existing = island.pwarps.stream()
                        .filter(w -> w.name.equalsIgnoreCase(input)).findFirst().orElse(null);
                if (existing != null && !existing.creatorId.equals(player.getUniqueId().toString())
                        && !"Owner".equals(myRole) && !"Admin".equals(myRole)) {
                    player.sendMessage("§cCan't overwrite §e" + existing.creatorName + "'s §cwarp."); return;
                }
                Location loc = player.getLocation();
                storage.addPwarp(island.id, input, loc.getX(), loc.getY(), loc.getZ(),
                        player.getUniqueId().toString(), player.getName());
                player.sendMessage("§aWarp §e" + input + " §aset!");

            } else if (finalMode == InputMode.RENAME) {
                NameValidator.Result v = nameValidator.validate(input);
                if (!v.valid) { player.sendMessage(v.reason); return; }
                IslandData conflict = storage.getIslandByName(input);
                if (conflict != null && !conflict.id.equals(island.id)) {
                    player.sendMessage("§cName §e" + input + " §cis taken."); return;
                }
                storage.updateIslandName(island.id, input);
                player.sendMessage("§aIsland renamed to §e" + input + "§a!");
            }
        });
    }

    // =========================================================================
    // INVENTORY CLICK HANDLER
    // =========================================================================
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = event.getView().getTitle();
        Inventory top = event.getView().getTopInventory();
        if (top == null) return;

        boolean ours = title.equals(TITLE_MAIN) || title.equals(TITLE_INFO)
                || title.equals(TITLE_MEMBERS) || title.startsWith(TITLE_MEMBER_ACT)
                || title.equals(TITLE_WARPS)   || title.equals(TITLE_INVITE)
                || title.equals(TITLE_RESET)   || title.equals(TITLE_FLAGS)
                || title.equals(TITLE_UPGRADES)|| title.equals(TITLE_SHOPS);
        if (!ours) return;

        event.setCancelled(true);
        if (event.getClickedInventory() != top) return;
        int slot = event.getRawSlot();

        // ── MAIN ─────────────────────────────────────────────────────────────
        if (title.equals(TITLE_MAIN)) {
            IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
            if (island == null) { if (slot == 22) doCreate(player); return; }
            String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
            boolean isOwner        = "Owner".equals(myRole);
            boolean isOwnerOrAdmin = isOwner || "Admin".equals(myRole);

            switch (slot) {
                case 10 -> doGo(player, island);
                case 12 -> showInfoMenu(player, island);
                case 14 -> showMembersMenu(player, island, myRole);
                case 16 -> showWarpsMenu(player, island, myRole);
                case 28 -> { if (isOwnerOrAdmin) showInviteMenu(player, island); }
                case 30 -> promptSetWarp(player, island);
                case 32 -> { if (isOwner) promptRename(player, island); }
                case 37 -> showFlagsMenu(player, island);
                case 39 -> showUpgradesMenu(player, island);
                case 41 -> showShopsMenu(player);
                case 43 -> { if (isOwner) showResetMenu(player); }
                case 49 -> doLeave(player, island);
            }

        // ── INFO ─────────────────────────────────────────────────────────────
        } else if (title.equals(TITLE_INFO)) {
            if (slot == 49) showIslandMenu(player);

        // ── FLAGS ─────────────────────────────────────────────────────────────
        } else if (title.equals(TITLE_FLAGS)) {
            IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
            if (island == null) return;
            String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
            if (!"Owner".equals(myRole) && !"Admin".equals(myRole)) {
                player.sendMessage("§cOnly Owner/Admin can change island flags."); return;
            }
            String flag = switch (slot) {
                case 10 -> "mobSpawning";
                case 12 -> "daylightCycle";
                case 14 -> "pvp";
                case 16 -> "weather";
                default -> null;
            };
            if (flag != null) {
                boolean current = getFlag(island, flag);
                storage.setIslandFlag(island.id, flag, !current);
                player.sendMessage("§e" + flag + " §7set to §" + (!current ? "a" : "c") + !current + "§7.");
                IslandData fresh = storage.getIslandByPlayerId(player.getUniqueId().toString());
                if (fresh != null) showFlagsMenu(player, fresh);
            } else if (slot == 22) {
                showIslandMenu(player);
            }

        // ── UPGRADES ──────────────────────────────────────────────────────────
        } else if (title.equals(TITLE_UPGRADES)) {
            IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
            if (island == null) return;
            if (!"Owner".equals(storage.getMemberRole(island, player.getUniqueId().toString()))) {
                player.sendMessage("§cOnly the Owner can upgrade the island."); return;
            }
            if (slot == 22) { showIslandMenu(player); return; }

            int[] tierSlots = {10, 12, 14, 16};
            int[] tierSizes = {25, 50, 100, 200};
            int[] tierCosts = {100, 250, 650, 1500};

            for (int i = 0; i < tierSlots.length; i++) {
                if (slot == tierSlots[i]) {
                    int size = tierSizes[i];
                    if (island.borderRadius >= size) {
                        player.sendMessage("§7You already have this border size."); return;
                    }
                    if (i == 0 || island.borderRadius != tierSizes[i - 1]) {
                        player.sendMessage("§cUpgrade previous tier first."); return;
                    }
                    int cost = tierCosts[i];
                    int gems = getGems(player);
                    if (gems < cost) {
                        player.sendMessage("§cNot enough gems. Need §e" + cost + " §cbut have §e" + gems + "§c."); return;
                    }
                    setGems(player, gems - cost);
                    storage.setBorderRadius(island.id, size);
                    player.sendMessage("§a✔ Island border upgraded to §e" + size + " §ablocks! §8(-" + cost + " gems)");
                    IslandData fresh = storage.getIslandByPlayerId(player.getUniqueId().toString());
                    if (fresh != null) showUpgradesMenu(player, fresh);
                    return;
                }
            }

        // ── SHOPS ─────────────────────────────────────────────────────────────
        } else if (title.equals(TITLE_SHOPS)) {
            switch (slot) {
                case 10 -> { player.closeInventory(); player.performCommand("shop"); }
                case 12 -> { player.closeInventory(); player.performCommand("gemshop"); }
                case 14 -> { player.closeInventory(); player.performCommand("killshop"); }
                case 16 -> { player.closeInventory(); player.performCommand("pwarp"); }
                case 22 -> showIslandMenu(player);
            }

        // ── MEMBERS ───────────────────────────────────────────────────────────
        } else if (title.equals(TITLE_MEMBERS)) {
            if (slot == 49) { showIslandMenu(player); return; }
            int idx = indexOf(MEMBER_SLOTS, slot);
            if (idx == -1) return;
            IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
            if (island == null || idx >= island.members.size()) return;
            String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
            IslandData.Member target = island.members.get(idx);
            if (target.id.equals(player.getUniqueId().toString())) return;
            if (!"Owner".equals(myRole) && !("Admin".equals(myRole) && "Member".equals(target.role))) return;
            showMemberActionMenu(player, target);

        // ── MEMBER ACTION ─────────────────────────────────────────────────────
        } else if (title.startsWith(TITLE_MEMBER_ACT)) {
            String targetName = title.replaceFirst(".*§r", "").trim();
            IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
            if (island == null) return;
            String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
            if      (slot == 2) doPromote(player, island, targetName, myRole);
            else if (slot == 4) doDemote(player, island, targetName, myRole);
            else if (slot == 6) doKick(player, island, targetName, myRole);
            else showMembersMenu(player, island, myRole);

        // ── WARPS ─────────────────────────────────────────────────────────────
        } else if (title.equals(TITLE_WARPS)) {
            if (slot == 49) { showIslandMenu(player); return; }
            int idx = indexOf(WARP_SLOTS, slot);
            if (idx == -1) return;
            IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
            if (island == null || idx >= island.pwarps.size()) return;
            String myRole = storage.getMemberRole(island, player.getUniqueId().toString());
            IslandData.Warp warp = island.pwarps.get(idx);
            boolean canDel = warp.creatorId.equals(player.getUniqueId().toString())
                    || "Owner".equals(myRole) || "Admin".equals(myRole);
            if (!canDel) return;
            storage.removePwarp(island.id, warp.name);
            player.sendMessage("§aWarp §e" + warp.name + " §adeleted.");
            IslandData fresh = storage.getIslandByPlayerId(player.getUniqueId().toString());
            if (fresh != null) showWarpsMenu(player, fresh, myRole);

        // ── INVITE ────────────────────────────────────────────────────────────
        } else if (title.equals(TITLE_INVITE)) {
            if (slot == 49) { showIslandMenu(player); return; }
            int idx = indexOf(INVITE_SLOTS, slot);
            if (idx == -1) return;
            List<Player> candidates = new ArrayList<>();
            for (Player p : plugin.getServer().getOnlinePlayers())
                if (!p.getUniqueId().equals(player.getUniqueId())
                        && storage.getIslandByPlayerId(p.getUniqueId().toString()) == null)
                    candidates.add(p);
            if (idx >= candidates.size()) return;
            Player target = candidates.get(idx);
            IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
            if (island == null) return;
            if (island.members.size() >= island.maxMembers) { player.sendMessage("§cIsland full!"); return; }
            inviteSystem.createInvite(island.id, player.getUniqueId().toString(), player.getName(),
                    target.getUniqueId().toString(), target.getName());
            player.sendMessage("§aInvited §e" + target.getName() + "§a!");
            target.sendMessage("§e" + player.getName() + " §ainvited you! Type §e/is accept §ato join.");
            showIslandMenu(player);

        // ── RESET ─────────────────────────────────────────────────────────────
        } else if (title.equals(TITLE_RESET)) {
            if (slot == 3) {
                IslandData island = storage.getIslandByPlayerId(player.getUniqueId().toString());
                if (island != null) doReset(player, island);
            } else {
                showIslandMenu(player);
            }
        }
    }

    // ─── indexOf helper ───────────────────────────────────────────────────────
    private int indexOf(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == val) return i;
        return -1;
    }

    // =========================================================================
    // ACTIONS
    // =========================================================================

    private void doCreate(Player player) {
        if (storage.getIslandByPlayerId(player.getUniqueId().toString()) != null) {
            player.sendMessage("§cYou already have an island!"); return;
        }
        IslandData island = storage.createIsland(player.getUniqueId().toString(), player.getName());
        IslandGenerator.Result result = generator.generateIsland(island.centerX, island.centerZ, overworld());
        storage.updateIslandHome(island.id, result.homeX, result.homeY, result.homeZ);
        player.teleport(new Location(overworld(), result.homeX, 75, result.homeZ));
        player.sendMessage("§a§lIsland created! Welcome to Skyblock!");
    }

    private void doGo(Player player, IslandData island) {
        player.teleport(new Location(overworld(), island.homeX,
                island.homeY > 0 ? island.homeY : 75, island.homeZ));
        player.sendMessage("§aTeleported to your island!");
        player.closeInventory();
    }

    private void doLeave(Player player, IslandData island) {
        boolean isOwner = island.ownerId.equals(player.getUniqueId().toString());
        if (isOwner) {
            player.closeInventory();
            player.sendMessage("§c§lWARNING: §r§cLeaving will delete your island" +
                    (island.members.size() > 1 ? " for everyone!" : "!") +
                    " Type §e/is confirm §cto proceed.");
            return;
        }
        IslandStorage.RemoveResult result = storage.removeMember(island.id, player.getUniqueId().toString());
        player.teleport(new Location(overworld(), SPAWN_LOC.getX(), SPAWN_LOC.getY(), SPAWN_LOC.getZ()));
        player.sendMessage(result.deleted ? "§eIsland removed (last member)." : "§eYou left the island.");
        if (!result.deleted) notifyMembers(result.island, player.getUniqueId().toString(),
                "§e" + player.getName() + " §7left the island.");
        player.closeInventory();
    }

    private void doKick(Player player, IslandData island, String targetName, String myRole) {
        IslandData.Member target = island.members.stream()
                .filter(m -> m.name.equalsIgnoreCase(targetName)).findFirst().orElse(null);
        if (target == null) { player.sendMessage("§c" + targetName + " not found."); return; }
        if ("Admin".equals(myRole) && ("Owner".equals(target.role) || "Admin".equals(target.role))) {
            player.sendMessage("§cAdmins cannot kick Owners or Admins."); return;
        }
        storage.removeMember(island.id, target.id);
        player.sendMessage("§eKicked §c" + target.name + "§e.");
        Player online = onlineById(target.id);
        if (online != null) {
            online.sendMessage("§cYou were kicked from §e" + island.ownerName + "'s §cisland.");
            online.teleport(new Location(overworld(), SPAWN_LOC.getX(), SPAWN_LOC.getY(), SPAWN_LOC.getZ()));
        }
        IslandData fresh = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (fresh != null) showMembersMenu(player, fresh, myRole);
    }

    private void doPromote(Player player, IslandData island, String targetName, String myRole) {
        if (!"Owner".equals(myRole)) { player.sendMessage("§cOnly the Owner can promote."); return; }
        IslandData.Member target = island.members.stream()
                .filter(m -> m.name.equalsIgnoreCase(targetName)).findFirst().orElse(null);
        if (target == null) return;
        String next = "Member".equals(target.role) ? "Admin" : "Admin".equals(target.role) ? "Owner" : null;
        if (next == null) { player.sendMessage("§c" + target.name + " is already max rank."); return; }
        storage.setMemberRole(island.id, target.id, next);
        player.sendMessage("§aPromoted §e" + target.name + " §ato §6" + next + "§a!");
        Player online = onlineById(target.id);
        if (online != null) online.sendMessage("§aYou were promoted to §6" + next + "§a!");
        IslandData fresh = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (fresh != null) showMembersMenu(player, fresh, myRole);
    }

    private void doDemote(Player player, IslandData island, String targetName, String myRole) {
        if (!"Owner".equals(myRole)) { player.sendMessage("§cOnly the Owner can demote."); return; }
        IslandData.Member target = island.members.stream()
                .filter(m -> m.name.equalsIgnoreCase(targetName)).findFirst().orElse(null);
        if (target == null || !"Admin".equals(target.role)) return;
        storage.setMemberRole(island.id, target.id, "Member");
        player.sendMessage("§eDemoted §c" + target.name + " §eto §7Member.");
        Player online = onlineById(target.id);
        if (online != null) online.sendMessage("§cYou were demoted to §7Member§c.");
        IslandData fresh = storage.getIslandByPlayerId(player.getUniqueId().toString());
        if (fresh != null) showMembersMenu(player, fresh, myRole);
    }

    private void doReset(Player player, IslandData island) {
        World world = overworld();
        for (IslandData.Member m : island.members) {
            Player p = onlineById(m.id);
            if (p != null) {
                p.teleport(new Location(world, SPAWN_LOC.getX(), SPAWN_LOC.getY(), SPAWN_LOC.getZ()));
                p.sendMessage("§cIsland is resetting...");
            }
        }
        generator.clearIsland(island.centerX, island.centerZ, world);
        IslandGenerator.Result result = generator.generateIsland(island.centerX, island.centerZ, world);
        storage.updateIslandHome(island.id, result.homeX, result.homeY, result.homeZ);
        storage.clearPwarps(island.id);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            player.teleport(new Location(world, result.homeX, 75, result.homeZ));
            player.sendMessage("§aIsland reset complete!");
        }, 20L);
    }
}

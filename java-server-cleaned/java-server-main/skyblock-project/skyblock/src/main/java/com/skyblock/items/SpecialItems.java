package com.skyblock.items;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;
import java.util.logging.Logger;

/**
 * SpecialItems
 *
 * Owns all custom item logic that was previously scattered across CrateSystem:
 *   - Aqua Pic  (iron pickaxe)    — right-click mines a 3x3x1 face
 *   - Nova Pic  (diamond pickaxe) — right-click mines a 3x3x3 cube
 *   - Talismans — passive potion effects applied every second
 *   - Haste potions — right-click consumption
 *
 * CrateSystem and AdminShopSystem both call give*() methods here to hand
 * items to players. This class registers its own event listener so it owns
 * the full lifecycle of every item it creates.
 */
public class SpecialItems implements Listener {

    // ─── Item identity tags ───────────────────────────────────────────────────
    // Stored in lore (hidden lines) so the server can tell a real custom item
    // from a player-renamed vanilla one.
    private static final String AQUA_PIC_TAG  = "[AQUA_PIC_CUSTOM]";
    private static final String NOVA_PIC_TAG  = "[NOVA_PIC_CUSTOM]";

    // ─── Blocks that should never be broken by a pickaxe ability ─────────────
    private static final Set<Material> UNBREAKABLE = EnumSet.of(
        Material.BEDROCK,
        Material.BARRIER,
        Material.COMMAND_BLOCK,
        Material.CHAIN_COMMAND_BLOCK,
        Material.REPEATING_COMMAND_BLOCK,
        Material.JIGSAW,
        Material.STRUCTURE_BLOCK,
        Material.END_PORTAL_FRAME,
        Material.END_PORTAL,
        Material.NETHER_PORTAL,
        Material.LADDER
    );

    // ─── State ────────────────────────────────────────────────────────────────
    private final JavaPlugin              plugin;
    private com.skyblock.mine.MineSystem  mineSystem; // set after MineSystem is constructed
    private final Logger                  log;
    private final Map<UUID, Long>         regenCooldowns  = new HashMap<>();
    private final Map<UUID, Long>         aquaCooldowns   = new HashMap<>();
    private final Map<UUID, Long>         novaCooldowns   = new HashMap<>();
    // Guards against Bukkit's double-fire of PlayerInteractEvent (main+off hand)
    private final Map<UUID, Long>         lastFired       = new HashMap<>();
    private static final long             DOUBLE_FIRE_MS  = 100;

    // ─── Constructor ──────────────────────────────────────────────────────────

    public SpecialItems(JavaPlugin plugin) {
        this.plugin     = plugin;
        this.mineSystem = null;
        this.log        = plugin.getLogger();

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::applyTalismanEffects, 0L, 20L);
        log.info("[SpecialItems] Loaded — Aqua Pic, Nova Pic, Talismans, Haste.");
    }

    /** Called after MineSystem is constructed so picks can check mine bounds. */
    public void setMineSystem(com.skyblock.mine.MineSystem mineSystem) {
        this.mineSystem = mineSystem;
    }

    // =========================================================================
    // ITEM CREATION — called by CrateSystem (reward) and AdminShopSystem (shop)
    // =========================================================================

    /** Creates and gives the Aqua Pic (iron pickaxe, 3x3x1 Vein Strike). */
    public void giveAquaPic(Player player) {
        ItemStack pic = new ItemStack(Material.IRON_PICKAXE);
        ItemMeta meta = pic.getItemMeta();
        meta.setDisplayName("§r§l§3Aqua §bPic");
        meta.setLore(Arrays.asList(
                "§r§7",
                "§r§3Special Ability: §bVein Strike",
                "§r§7Right-click to mine a §e3x3x1 §7area",
                "§r§7",
                "§r§8" + AQUA_PIC_TAG
        ));
        pic.setItemMeta(meta);
        player.getInventory().addItem(pic);
        log.info("[SpecialItems] Gave Aqua Pic to " + player.getName());
    }

    /** Creates and gives the Nova Pic (diamond pickaxe, 3x3x3 Void Drill). */
    public void giveNovaPic(Player player) {
        ItemStack pic = new ItemStack(Material.DIAMOND_PICKAXE);
        ItemMeta meta = pic.getItemMeta();
        meta.setDisplayName("§r§l§dNova §5Pic");
        meta.setLore(Arrays.asList(
                "§r§7",
                "§r§dSpecial Ability: §5Void Drill",
                "§r§7Right-click to mine a §e3x3x3 §7area",
                "§r§7",
                "§r§8" + NOVA_PIC_TAG
        ));
        pic.setItemMeta(meta);
        player.getInventory().addItem(pic);
        log.info("[SpecialItems] Gave Nova Pic to " + player.getName());
    }

    /** Creates and gives a talisman or haste potion by special tag. */
    public void giveSpecialItem(Player player, Material material, String specialTag, int amount) {
        if (specialTag.equals("health_i") || specialTag.equals("health_ii")
                || specialTag.equals("regen_ii") || specialTag.equals("speed_ii")) {
            givePotion(player, specialTag);
            return;
        }
        if (specialTag.equals("weakness_arrow")) {
            player.getInventory().addItem(new ItemStack(Material.TIPPED_ARROW, 32 * amount));
            player.sendMessage("§aYou received §e" + (32 * amount) + "x §7Arrow of Weakness§a!");
            return;
        }

        ItemStack item = new ItemStack(material, amount);
        ItemMeta meta = item.getItemMeta();
        applyTalismanMeta(meta, specialTag);
        item.setItemMeta(meta);
        player.getInventory().addItem(item);
        player.sendMessage("§aYou received a special item§a!");
        log.info("[SpecialItems] Gave " + player.getName() + " special item: " + specialTag);
    }

    // =========================================================================
    // EVENT HANDLER — pickaxe right-click and haste consumption
    // =========================================================================

    @EventHandler
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType() == Material.AIR) return;
        ItemMeta meta = held.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) return;
        String name = meta.getDisplayName();

        // ── Aqua Pic ──────────────────────────────────────────────────────────
        if (held.getType() == Material.IRON_PICKAXE && hasTag(meta, AQUA_PIC_TAG)) {
            event.setCancelled(true);

            // Double-fire guard
            long now = System.currentTimeMillis();
            if (now - lastFired.getOrDefault(player.getUniqueId(), 0L) < DOUBLE_FIRE_MS) return;
            lastFired.put(player.getUniqueId(), now);

            // Mine-area restriction
            if (mineSystem == null || !mineSystem.isInMine(player.getLocation())) {
                player.sendMessage("§c✖ The Aqua Pic can only be used inside the mine.");
                return;
            }

            // Cooldown check (3s)
            long lastUse = aquaCooldowns.getOrDefault(player.getUniqueId(), 0L);
            long remaining = 3000 - (now - lastUse);
            if (remaining > 0) {
                player.sendMessage("§c✖ Vein Strike on cooldown! §7(" + String.format("%.1f", remaining / 1000.0) + "s)");
                return;
            }

            Block target = resolveTarget(event, player);
            if (target == null) { player.sendMessage("§7No blocks to mine."); return; }
            int broken = mine3x1Face(player, target);
            if (broken == 0) { player.sendMessage("§7No blocks to mine."); return; }

            aquaCooldowns.put(player.getUniqueId(), now);
            drainDurability(held, player, 5, 251);
            player.sendMessage("§3⛏ §bVein Strike! §7Mined " + broken + " block" + (broken > 1 ? "s" : "") + ".");
            player.playSound(player.getLocation(), Sound.BLOCK_STONE_BREAK, 1.0f, 0.8f);
            return;
        }

        // ── Nova Pic ──────────────────────────────────────────────────────────
        if (held.getType() == Material.DIAMOND_PICKAXE && hasTag(meta, NOVA_PIC_TAG)) {
            event.setCancelled(true);

            // Double-fire guard
            long now = System.currentTimeMillis();
            if (now - lastFired.getOrDefault(player.getUniqueId(), 0L) < DOUBLE_FIRE_MS) return;
            lastFired.put(player.getUniqueId(), now);

            // Mine-area restriction
            if (mineSystem == null || !mineSystem.isInMine(player.getLocation())) {
                player.sendMessage("§c✖ The Nova Pic can only be used inside the mine.");
                return;
            }

            // Cooldown check (5s)
            long lastUse = novaCooldowns.getOrDefault(player.getUniqueId(), 0L);
            long remaining = 5000 - (now - lastUse);
            if (remaining > 0) {
                player.sendMessage("§c✖ Void Drill on cooldown! §7(" + String.format("%.1f", remaining / 1000.0) + "s)");
                return;
            }

            Block target = resolveTarget(event, player);
            if (target == null) { player.sendMessage("§7No blocks to mine."); return; }
            int broken = mine3x3x3(player, target);
            if (broken == 0) { player.sendMessage("§7No blocks to mine."); return; }

            novaCooldowns.put(player.getUniqueId(), now);
            drainDurability(held, player, 5, 1562);
            player.sendMessage("§5⛏ §dVoid Drill! §7Mined " + broken + " block" + (broken > 1 ? "s" : "") + ".");
            player.playSound(player.getLocation(), Sound.BLOCK_STONE_BREAK, 1.0f, 0.6f);
            return;
        }

        // ── Haste potions ─────────────────────────────────────────────────────
        if (held.getType() == Material.DRAGON_BREATH) {
            if ("§6Dyric's Haste III".equals(name)) {
                event.setCancelled(true);
                applyHaste(player, held, 3, 1200, 2);
            } else if ("§6Dyric's Haste V".equals(name)) {
                event.setCancelled(true);
                applyHaste(player, held, 5, 600, 4);
            }
        }
    }

    // =========================================================================
    // MINING
    // =========================================================================

    /** Resolves the target block from the event's clicked block or a raycast fallback. */
    private Block resolveTarget(PlayerInteractEvent event, Player player) {
        if (event.getClickedBlock() != null) return event.getClickedBlock();
        return player.getTargetBlockExact(6);
    }

    /**
     * Mines a 3x3x1 slab centred on the target, oriented to the face the player
     * is looking at (up/down → horizontal slab; N/S/E/W → vertical panel).
     */
    private int mine3x1Face(Player player, Block target) {
        int tx = target.getX(), ty = target.getY(), tz = target.getZ();
        List<int[]> blocks = new ArrayList<>();

        float pitch = player.getEyeLocation().getPitch();
        float yaw   = ((player.getEyeLocation().getYaw() % 360) + 360) % 360;

        if (pitch < -45 || pitch > 45) {
            // Looking mostly up or down — mine a horizontal 3x3 slab
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    blocks.add(new int[]{tx + dx, ty, tz + dz});
        } else if (yaw >= 315 || yaw < 45) {
            // Facing north — vertical panel on Z axis
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    blocks.add(new int[]{tx + dx, ty + dy, tz});
        } else if (yaw >= 45 && yaw < 135) {
            // Facing east — vertical panel on X axis
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++)
                    blocks.add(new int[]{tx, ty + dy, tz + dz});
        } else if (yaw >= 135 && yaw < 225) {
            // Facing south — vertical panel on Z axis
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    blocks.add(new int[]{tx + dx, ty + dy, tz});
        } else {
            // Facing west — vertical panel on X axis
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = -1; dy <= 1; dy++)
                    blocks.add(new int[]{tx, ty + dy, tz + dz});
        }

        return breakBlocks(player, blocks);
    }

    /** Mines a full 3x3x3 cube centred 1 block further into the wall the player is facing. */
    private int mine3x3x3(Player player, Block target) {
        // Offset the centre 1 block further in the player's facing direction so the
        // entire 3x3x3 cube sits inside the wall rather than wrapping around the target.
        org.bukkit.util.Vector dir = player.getEyeLocation().getDirection().normalize();
        int ox = (int) Math.round(dir.getX());
        int oy = (int) Math.round(dir.getY());
        int oz = (int) Math.round(dir.getZ());
        // Clamp to cardinal so we don't get a diagonal offset on a steep angle
        if (Math.abs(ox) + Math.abs(oy) + Math.abs(oz) > 1) {
            // Pick the dominant axis
            if (Math.abs(dir.getX()) >= Math.abs(dir.getY()) && Math.abs(dir.getX()) >= Math.abs(dir.getZ())) {
                oy = 0; oz = 0;
            } else if (Math.abs(dir.getY()) >= Math.abs(dir.getX()) && Math.abs(dir.getY()) >= Math.abs(dir.getZ())) {
                ox = 0; oz = 0;
            } else {
                ox = 0; oy = 0;
            }
        }
        int cx = target.getX() + ox;
        int cy = target.getY() + oy;
        int cz = target.getZ() + oz;

        List<int[]> blocks = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                for (int dz = -1; dz <= 1; dz++)
                    blocks.add(new int[]{cx + dx, cy + dy, cz + dz});
        return breakBlocks(player, blocks);
    }

    /**
     * Breaks the given block positions on behalf of the player.
     * Uses BlockBreakEvent simulation so the MineSystem's onBlockBreak handler fires
     * (awarding ore loot, special drops, brokenCount, etc.) exactly as if the player
     * had broken each block manually.
     *
     * Only breaks blocks that are inside the mine bounds — silently skips any
     * position outside the mine so the picks can't be used to grief elsewhere.
     */
    private int breakBlocks(Player player, List<int[]> positions) {
        World world = player.getWorld();
        int count = 0;
        for (int[] pos : positions) {
            Block b = world.getBlockAt(pos[0], pos[1], pos[2]);
            if (b.getType() == Material.AIR) continue;
            if (UNBREAKABLE.contains(b.getType())) continue;

            // Only allow breaks inside the mine
            if (mineSystem == null || !mineSystem.isInMine(b.getLocation())) continue;

            // Simulate the break so Bukkit fires BlockBreakEvent —
            // MineSystem listens to that event for loot, drops, and brokenCount.
            org.bukkit.event.block.BlockBreakEvent bbe =
                    new org.bukkit.event.block.BlockBreakEvent(b, player);
            plugin.getServer().getPluginManager().callEvent(bbe);
            if (bbe.isCancelled()) continue;

            // Drop items if the event handler didn't suppress them
            if (bbe.isDropItems()) b.breakNaturally(player.getInventory().getItemInMainHand());
            else                   b.setType(Material.AIR, false);
            count++;
        }
        return count;
    }

    // =========================================================================
    // DURABILITY
    // =========================================================================

    @SuppressWarnings("deprecation")
    private void drainDurability(ItemStack item, Player player, int amount, int maxDurability) {
        int current = item.getDurability();
        int next = current + amount;
        if (next >= maxDurability - 1) next = maxDurability - 1;
        item.setDurability((short) next);
        player.getInventory().setItemInMainHand(item);
    }

    // =========================================================================
    // HASTE POTIONS
    // =========================================================================

    private void applyHaste(Player player, ItemStack held, int level, int durationTicks, int amplifier) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, durationTicks, amplifier, false, true));
        player.sendMessage("§6You used Dyric's Haste " + toRoman(level) + "! §7(Haste "
                + toRoman(level) + " for " + (durationTicks / 20) + " seconds)");
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 1.0f, 1.0f);
        if (held.getAmount() > 1) held.setAmount(held.getAmount() - 1);
        else player.getInventory().setItemInMainHand(null);
    }

    // =========================================================================
    // TALISMAN PASSIVE EFFECTS
    // =========================================================================

    private void applyTalismanEffects() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                boolean jumpT1 = false, jumpT2 = false;
                boolean strT1  = false, strT2  = false;
                boolean spdT1  = false, spdT2  = false;
                boolean defT1  = false, defT2  = false;
                boolean regT1  = false, regT2  = false;
                boolean multi  = false;

                for (ItemStack item : player.getInventory().getContents()) {
                    if (item == null) continue;
                    ItemMeta m = item.getItemMeta();
                    if (m == null) continue;
                    String n = m.getDisplayName();
                    switch (item.getType().name()) {
                        case "FEATHER"         -> { if ("§eJump Talisman".equals(n))       jumpT1 = true; if ("§e§lJump Talisman II".equals(n))      jumpT2 = true; }
                        case "CLAY_BALL"       -> { if ("§cStrength Talisman".equals(n))   strT1  = true; if ("§c§lStrength Talisman II".equals(n))   strT2  = true; }
                        case "RABBIT_FOOT"     -> { if ("§bSpeed Talisman".equals(n))      spdT1  = true; if ("§b§lSpeed Talisman II".equals(n))      spdT2  = true; }
                        case "ARMADILLO_SCUTE" -> { if ("§7Defence Talisman".equals(n))    defT1  = true; if ("§7§lDefence Talisman II".equals(n))    defT2  = true; }
                        case "SPIDER_EYE"      -> { if ("§dRegen Talisman".equals(n))      regT1  = true; if ("§d§lRegen Talisman II".equals(n))      regT2  = true; }
                        case "NAUTILUS_SHELL"  -> { if ("§6Multi Talisman".equals(n))      multi  = true; }
                    }
                }

                int dur = 25; // ~1.25 s — keeps the effect alive between ticks
                if      (jumpT2)        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST,  dur, 1, false, false), true);
                else if (jumpT1||multi) player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST,  dur, 0, false, false), true);

                if      (strT2)         player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,    dur, 1, false, false), true);
                else if (strT1||multi)  player.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,    dur, 0, false, false), true);

                if      (spdT2)         player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,       dur, 1, false, false), true);
                else if (spdT1||multi)  player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,       dur, 0, false, false), true);

                if      (defT2)         player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,  dur, 1, false, false), true);
                else if (defT1)         player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE,  dur, 0, false, false), true);

                long now      = System.currentTimeMillis();
                long lastRegen = regenCooldowns.getOrDefault(player.getUniqueId(), 0L);
                if ((regT2 || regT1) && now - lastRegen >= 20_000) {
                    int amp = regT2 ? 1 : 0;
                    player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, amp, false, false), true);
                    regenCooldowns.put(player.getUniqueId(), now);
                }

            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    /** Returns true if the item's lore contains the given hidden tag. */
    private boolean hasTag(ItemMeta meta, String tag) {
        List<String> lore = meta.getLore();
        return lore != null && lore.stream().anyMatch(l -> l.contains(tag));
    }

    /** Gives a vanilla potion by special tag. */
    private void givePotion(Player player, String tag) {
        ItemStack pot = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) pot.getItemMeta();
        switch (tag) {
            case "health_i"  -> { meta.addCustomEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH,  1,   0), true); player.sendMessage("§aYou received §cInstant Health I§a!"); }
            case "health_ii" -> { meta.addCustomEffect(new PotionEffect(PotionEffectType.INSTANT_HEALTH,  1,   1), true); player.sendMessage("§aYou received §cInstant Health II§a!"); }
            case "regen_ii"  -> { meta.addCustomEffect(new PotionEffect(PotionEffectType.REGENERATION,    900, 1), true); player.sendMessage("§aYou received §dRegeneration II§a!"); }
            case "speed_ii"  -> { meta.addCustomEffect(new PotionEffect(PotionEffectType.SPEED,           1800,1), true); player.sendMessage("§aYou received §bSpeed II§a!"); }
        }
        pot.setItemMeta(meta);
        player.getInventory().addItem(pot);
    }

    /** Applies display name + lore to a talisman/haste item meta. */
    private void applyTalismanMeta(ItemMeta meta, String tag) {
        switch (tag) {
            case "jump_talisman"        -> { meta.setDisplayName("§eJump Talisman");          meta.setLore(List.of("§r§7Grants Jump Boost I when held")); }
            case "jump_talisman_t2"     -> { meta.setDisplayName("§e§lJump Talisman II");     meta.setLore(List.of("§r§7Grants Jump Boost II when held")); }
            case "strength_talisman"    -> { meta.setDisplayName("§cStrength Talisman");      meta.setLore(List.of("§r§7Grants Strength I when held")); }
            case "strength_talisman_t2" -> { meta.setDisplayName("§c§lStrength Talisman II"); meta.setLore(List.of("§r§7Grants Strength II when held")); }
            case "speed_talisman"       -> { meta.setDisplayName("§bSpeed Talisman");         meta.setLore(List.of("§r§7Grants Speed I when held")); }
            case "speed_talisman_t2"    -> { meta.setDisplayName("§b§lSpeed Talisman II");    meta.setLore(List.of("§r§7Grants Speed II when held")); }
            case "defence_talisman"     -> { meta.setDisplayName("§7Defence Talisman");       meta.setLore(List.of("§r§7Grants Resistance I when held")); }
            case "defence_talisman_t2"  -> { meta.setDisplayName("§7§lDefence Talisman II");  meta.setLore(List.of("§r§7Grants Resistance II when held")); }
            case "regen_talisman"       -> { meta.setDisplayName("§dRegen Talisman");         meta.setLore(List.of("§r§7Grants Regeneration I when held", "§r§720s cooldown")); }
            case "regen_talisman_t2"    -> { meta.setDisplayName("§d§lRegen Talisman II");    meta.setLore(List.of("§r§7Grants Regeneration II when held", "§r§720s cooldown")); }
            case "multi_talisman"       -> { meta.setDisplayName("§6Multi Talisman");         meta.setLore(List.of("§r§7Grants Speed I, Strength I,", "§r§7and Jump Boost I when held")); }
            case "haste_3"              -> { meta.setDisplayName("§6Dyric's Haste III");      meta.setLore(List.of("§r§7Right-click to use", "§r§7Haste III for 1 minute")); }
            case "haste_5"              -> { meta.setDisplayName("§6Dyric's Haste V");        meta.setLore(List.of("§r§7Right-click to use", "§r§7Haste V for 30 seconds")); }
        }
    }

    private static String toRoman(int n) {
        return switch (n) { case 3 -> "III"; case 5 -> "V"; default -> String.valueOf(n); };
    }
}

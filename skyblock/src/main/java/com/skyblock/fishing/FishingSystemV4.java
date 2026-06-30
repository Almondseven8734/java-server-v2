package com.skyblock.fishing;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;

import java.util.*;
import java.util.logging.Logger;

/**
 * Fishing System V4
 *
 * Translated from fishing_system_v4.js.
 *
 * Features:
 *   - 140 custom fish across 6 rarities (COMMON → MYTHICAL).
 *   - Players fishing inside the custom fishing zone (x 261-337, y 255-286,
 *     z 258-359) get a random custom fish instead of vanilla loot.
 *   - Outside the zone: vanilla fishing proceeds normally with a 30-second
 *     warning cooldown.
 *   - Mythical catches are announced server-wide.
 *   - Catch count is tracked on the "catches" scoreboard objective.
 *   - /fish teleports the player to the centre of the fishing zone.
 *
 * Register in plugin.yml:
 *
 *   commands:
 *     fish:
 *       description: Teleport to the custom fishing zone
 *       usage: /fish
 */
public class FishingSystemV4 implements CommandExecutor, Listener {

    // ─── Fishing zone bounds ──────────────────────────────────────────────────
    private static final int ZONE_MIN_X = 261, ZONE_MAX_X = 337;
    private static final int ZONE_MIN_Y = 255, ZONE_MAX_Y = 286;
    private static final int ZONE_MIN_Z = 258, ZONE_MAX_Z = 359;

    /** /fish teleport destination (centre of zone). */
    private static final double TP_X = 300.5, TP_Y = 286, TP_Z = 300.5;

    /** Out-of-zone warning cooldown – 30 seconds in ms. */
    private static final long WARN_COOLDOWN_MS = 30_000L;

    // ─── Dependencies ─────────────────────────────────────────────────────────
    private final JavaPlugin plugin;
    private final Logger logger;

    // ─── State ────────────────────────────────────────────────────────────────
    private final Map<UUID, Long> warnCooldowns = new HashMap<>();

    // ─── Constructor ──────────────────────────────────────────────────────────

    public FishingSystemV4(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        logger.info("[Fishing System v4.0] Loaded! 140 custom fish, 6 rarities.");
    }

    // =========================================================================
    // RARITY
    // =========================================================================

    public enum Rarity {
        COMMON   ("Common",    "§7",  55.0),
        UNCOMMON ("Uncommon",  "§a",  25.0),
        RARE     ("Rare",      "§b",  12.0),
        EPIC     ("Epic",      "§5",   5.0),
        LEGENDARY("Legendary", "§6",   2.5),
        MYTHICAL ("Mythical",  "§3",   0.25);

        public final String displayName;
        public final String color;
        public final double weight;

        Rarity(String displayName, String color, double weight) {
            this.displayName = displayName;
            this.color       = color;
            this.weight      = weight;
        }
    }

    // =========================================================================
    // FISH ENTRY
    // =========================================================================

    public static class FishEntry {
        public final Rarity rarity;
        public final String name;
        public final String desc;

        public FishEntry(Rarity rarity, String name, String desc) {
            this.rarity = rarity;
            this.name   = name;
            this.desc   = desc;
        }
    }

    // =========================================================================
    // FISH DATABASE – 140 entries mirroring FISH_DATABASE in fishing_system_v4.js
    // =========================================================================

    private static final List<FishEntry> FISH_DATABASE = new ArrayList<>();

    static {
        // ── COMMON (40) ──────────────────────────────────────────────────────
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Bass",           "A common freshwater fish found in calm waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Perch",          "A small striped fish abundant in lakes"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Catfish",        "A bottom-dwelling fish with whisker-like barbels"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Bluegill",       "A pan fish with a distinctive blue gill cover"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Carp",           "A large freshwater fish known for its hardiness"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Minnow",         "A tiny fish often used as bait"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Sunfish",        "A colorful fish that basks near the surface"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Crappie",        "A popular game fish with a speckled pattern"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Pike Minnow",    "A small predatory fish found in rivers"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Chub",           "A plump fish common in streams"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Dace",           "A slender fish that swims in schools"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Roach",          "A silvery fish with reddish fins"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Rudd",           "A golden-scaled fish found in slow waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Bream",          "A flat-bodied fish that feeds on insects"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Gudgeon",        "A small bottom-feeding fish"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Minnow Pike",    "An elongated fish with sharp teeth"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Creek Chub",     "A hardy fish that thrives in small streams"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Shiner",         "A small reflective fish that glints in sunlight"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Darter",         "A quick fish that darts between rocks"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Sculpin",        "A bottom-dwelling fish with a large head"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Stickleback",    "A spiny fish found in shallow waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Loach",          "A slender eel-like fish"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Mud Minnow",     "A fish that can survive in muddy conditions"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Killifish",      "A hardy fish that adapts to various waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Topminnow",      "A surface-feeding fish"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Mosquitofish",   "A tiny fish that feeds on mosquito larvae"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Gambusia",       "A small livebearing fish"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Molly",          "A peaceful fish with varied colors"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Platy",          "A colorful tropical fish"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Guppy",          "A tiny fish with a flowing tail"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Swordtail",      "A fish with an extended tail fin"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Tetra",          "A small schooling fish"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Barb",           "An active fish with barbels"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Danio",          "A striped fish known for its speed"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Rasbora",        "A peaceful schooling fish"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "White Cloud",    "A mountain fish that prefers cool waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Zebra Fish",     "A striped fish used in research"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Pearl Danio",    "A shimmering fish with pearlescent scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Neon Tetra",     "A tiny fish with a bright blue stripe"));
        FISH_DATABASE.add(new FishEntry(Rarity.COMMON, "Glow Tetra",     "A translucent fish that seems to glow"));

        // ── UNCOMMON (35) ─────────────────────────────────────────────────────
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Rainbow Trout",      "A colorful fish with pink stripes along its side"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Brook Trout",        "A speckled trout found in cold streams"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Brown Trout",        "A golden-brown fish with red spots"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Lake Trout",         "A large predatory trout of deep waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Walleye",            "A glassy-eyed predator of murky waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Sauger",             "A smaller cousin of the walleye"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Northern Pike",      "A toothy ambush predator"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Chain Pickerel",     "A pike with chain-like markings"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Muskellunge",        "The legendary \"fish of 10,000 casts\""));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Smallmouth Bass",    "A bronze fighter known for its strength"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Largemouth Bass",    "A popular game fish with a huge mouth"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Spotted Bass",       "A bass with distinctive spotted scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "White Bass",         "A silvery schooling bass"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Striped Bass",       "A large bass with horizontal stripes"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Yellow Perch",       "A perch with bold vertical stripes"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "White Perch",        "A silver-scaled perch of brackish waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Drum",               "A fish that makes drumming sounds"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Sheepshead",         "A striped fish with human-like teeth"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Rockfish",           "A spiny fish found among rocks"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Snapper",            "A red fish prized for its taste"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Grouper",            "A large bottom-dwelling predator"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Golden Perch",       "A shimmering perch with golden scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Silver Carp",        "A leaping fish with silver scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Mirror Carp",        "A carp with large, mirror-like scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Koi Carp",           "A decorative carp with vibrant patterns"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Striped Snakehead",  "An invasive predator with serpentine features"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Bowfin",             "A primitive fish with a long dorsal fin"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Gar",                "An ancient fish with armored scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Alligator Gar",      "A massive gar with alligator-like jaws"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Paddlefish",         "A prehistoric fish with a paddle-shaped snout"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Lake Sturgeon",      "An armored fish that can live for decades"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Atlantic Salmon",    "A silvery salmon that migrates to the sea"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Chinook Salmon",     "The king of salmon, large and powerful"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Coho Salmon",        "A silver salmon with hooked jaws"));
        FISH_DATABASE.add(new FishEntry(Rarity.UNCOMMON, "Steelhead",          "A rainbow trout that migrates to the ocean"));

        // ── RARE (30) ─────────────────────────────────────────────────────────
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Crystal Salmon",    "A translucent salmon that sparkles like crystal"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Azure Pike",        "A vibrant blue pike with glowing fins"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Moonlight Tuna",    "A silvery tuna that glows under moonlight"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Starlight Bass",    "A bass with scales that shimmer like stars"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Prism Trout",       "A trout whose scales reflect all colors"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Jade Carp",         "A green carp said to bring good fortune"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Sapphire Snapper",  "A deep blue snapper with gem-like eyes"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Ruby Rockfish",     "A crimson rockfish that glows faintly"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Amber Perch",       "A golden perch preserved in time"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Opal Guppy",        "A tiny fish with opalescent scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Twilight Tetra",    "A tetra that appears during dusk"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Dawn Danio",        "A danio with the colors of sunrise"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Frost Walleye",     "An icy walleye from frozen waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Ember Pike",        "A pike with scales like hot coals"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Mist Minnow",       "A ghost-like minnow that fades in and out"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Echo Bass",         "A bass whose scales create sonic ripples"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Quicksilver Barb",  "A metallic barb that moves like mercury"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Copper Catfish",    "A catfish with metallic copper scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Silver Phantom",    "A translucent fish that haunts deep pools"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Golden Koi",        "A legendary koi with pure golden scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Celestial Carp",    "A carp with constellation patterns"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Mystic Muskie",     "A muskellunge surrounded by magical aura"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Spectral Sturgeon", "An ancient sturgeon with ethereal glow"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Aurora Salmon",     "A salmon with northern lights in its scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Lunar Loach",       "A loach that only appears during full moons"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Solar Stickleback", "A stickleback that radiates warmth"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Tempest Trout",     "A trout born from storm clouds"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Breeze Bass",       "A bass that seems to float on air"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Quartz Sculpin",    "A sculpin with crystalline formations"));
        FISH_DATABASE.add(new FishEntry(Rarity.RARE, "Pearl Perch",       "A perch with scales like perfect pearls"));

        // ── EPIC (20) ─────────────────────────────────────────────────────────
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Phantom Swordfish",     "A ghostly swordfish that phases through water"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Void Manta",            "A dark manta ray from the depths"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Thunder Eel",           "An electric eel crackling with lightning"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Storm Pike",            "A pike that summons tempests"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Magma Catfish",         "A catfish from volcanic waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Glacier Trout",         "A trout from ancient frozen lakes"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Obsidian Sturgeon",     "A sturgeon with black volcanic glass scales"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Diamond Carp",          "A carp with scales harder than steel"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Emerald Bass",          "A bass that radiates green energy"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Amethyst Snapper",      "A purple snapper with crystalline spines"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Titanium Pike",         "A metallic pike of incredible strength"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Nether Gar",            "A gar from the fiery depths"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Ender Minnow",          "A tiny fish that can teleport"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Blaze Barb",            "A burning barb wreathed in flames"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Prismarine Guardian",   "A guardian fish with magical properties"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Shadow Stalker",        "A fish that lurks in darkness"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Radiant Rockfish",      "A rockfish that glows with holy light"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Twilight Marlin",       "A marlin from the boundary of dimensions"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Arcane Salmon",         "A salmon infused with raw magic"));
        FISH_DATABASE.add(new FishEntry(Rarity.EPIC, "Mystic Mackerel",       "A mackerel with prophetic visions"));

        // ── LEGENDARY (10) ────────────────────────────────────────────────────
        FISH_DATABASE.add(new FishEntry(Rarity.LEGENDARY, "Celestial Leviathan", "A massive fish from the stars above"));
        FISH_DATABASE.add(new FishEntry(Rarity.LEGENDARY, "Inferno Shark",       "A shark wreathed in eternal flames"));
        FISH_DATABASE.add(new FishEntry(Rarity.LEGENDARY, "Frost Dragon Pike",   "A pike with the power of ice dragons"));
        FISH_DATABASE.add(new FishEntry(Rarity.LEGENDARY, "Ancient Kraken",      "A legendary tentacled fish of the deep"));
        FISH_DATABASE.add(new FishEntry(Rarity.LEGENDARY, "Phoenix Salmon",      "A salmon that is reborn from ashes"));
        FISH_DATABASE.add(new FishEntry(Rarity.LEGENDARY, "Abyssal Emperor",     "The ruler of the deepest trenches"));
        FISH_DATABASE.add(new FishEntry(Rarity.LEGENDARY, "Sky Serpent",         "A flying fish that soars through clouds"));
        FISH_DATABASE.add(new FishEntry(Rarity.LEGENDARY, "Titan Sturgeon",      "A colossal sturgeon of mythic proportions"));
        FISH_DATABASE.add(new FishEntry(Rarity.LEGENDARY, "Eternal Guardian",    "A fish that has lived for millennia"));
        FISH_DATABASE.add(new FishEntry(Rarity.LEGENDARY, "Prismatic Whale",     "A whale-sized fish of all colors"));

        // ── MYTHICAL (5) ──────────────────────────────────────────────────────
        FISH_DATABASE.add(new FishEntry(Rarity.MYTHICAL, "Golden Dragon Carp",  "A divine carp blessed by ancient dragons"));
        FISH_DATABASE.add(new FishEntry(Rarity.MYTHICAL, "Eternal Phoenix Fish", "A fish born from cosmic flames"));
        FISH_DATABASE.add(new FishEntry(Rarity.MYTHICAL, "Void Emperor",         "The ultimate ruler of all waters"));
        FISH_DATABASE.add(new FishEntry(Rarity.MYTHICAL, "Cosmic Serpent",       "A fish woven from the fabric of space itself"));
        FISH_DATABASE.add(new FishEntry(Rarity.MYTHICAL, "Nebula Whale",         "A whale-fish containing entire galaxies"));
    }

    // ─── Pre-grouped fish by rarity for O(1) lookup ───────────────────────────
    private static final Map<Rarity, List<FishEntry>> FISH_BY_RARITY = new EnumMap<>(Rarity.class);

    static {
        for (Rarity r : Rarity.values()) FISH_BY_RARITY.put(r, new ArrayList<>());
        for (FishEntry e : FISH_DATABASE)  FISH_BY_RARITY.get(e.rarity).add(e);
    }

    private static final Random RNG = new Random();

    // =========================================================================
    // RANDOM SELECTION
    // =========================================================================

    private static Rarity getRandomRarity() {
        double totalWeight = 0;
        for (Rarity r : Rarity.values()) totalWeight += r.weight;
        double roll = RNG.nextDouble() * totalWeight;
        for (Rarity r : Rarity.values()) {
            roll -= r.weight;
            if (roll <= 0) return r;
        }
        return Rarity.COMMON;
    }

    private static FishEntry getRandomFish(Rarity rarity) {
        List<FishEntry> pool = FISH_BY_RARITY.get(rarity);
        return pool.get(RNG.nextInt(pool.size()));
    }

    // =========================================================================
    // ITEM CREATION
    // =========================================================================

    /**
     * Mirrors createFishItem() in fishing_system_v4.js.
     * Uses TROPICAL_FISH as the base material (same as Bedrock).
     */
    private static ItemStack createFishItem(FishEntry fish) {
        ItemStack item = new ItemStack(Material.TROPICAL_FISH, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(fish.rarity.color + fish.name);
            meta.setLore(Arrays.asList(
                    "§7Rarity: " + fish.rarity.color + fish.rarity.displayName,
                    "§7" + fish.desc
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    // =========================================================================
    // GIVE FISH TO PLAYER
    // =========================================================================

    /**
     * Mirrors giveFish() in fishing_system_v4.js.
     */
    private void giveFish(Player player, FishEntry fish) {
        ItemStack fishItem = createFishItem(fish);

        // Add to inventory; drop at feet if full
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(fishItem);
        if (!overflow.isEmpty()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow.get(0));
        }

        // Increment "catches" scoreboard objective
        try {
            org.bukkit.scoreboard.Scoreboard board = plugin.getServer().getScoreboardManager().getMainScoreboard();
            Objective obj = board.getObjective("catches");
            if (obj == null) {
                obj = board.registerNewObjective("catches", "dummy", "Total Fish Caught");
            }
            Score score = obj.getScore(player.getName());
            score.setScore(score.getScore() + 1);
        } catch (Exception ignored) {}

        // Announce mythical catches server-wide
        if (fish.rarity == Rarity.MYTHICAL) {
            plugin.getServer().broadcastMessage(
                    "§6[!] §b" + player.getName() + " §fcaught a "
                    + fish.rarity.color + "Mythical " + fish.name + "§f!");
        } else {
            player.sendMessage("§aYou caught a " + fish.rarity.color + fish.name + "§a!");
        }
    }

    // =========================================================================
    // ZONE CHECK
    // =========================================================================

    private static boolean isInFishingZone(Location loc) {
        return loc.getBlockX() >= ZONE_MIN_X && loc.getBlockX() <= ZONE_MAX_X
                && loc.getBlockY() >= ZONE_MIN_Y && loc.getBlockY() <= ZONE_MAX_Y
                && loc.getBlockZ() >= ZONE_MIN_Z && loc.getBlockZ() <= ZONE_MAX_Z;
    }

    // =========================================================================
    // FISHING EVENT
    // =========================================================================

    /**
     * Intercepts PlayerFishEvent when state is CAUGHT_FISH.
     *
     * Inside the zone: cancels the vanilla drop and awards a custom fish instead.
     * Outside the zone: lets vanilla loot through and sends a zone warning
     *   (at most once every 30 seconds).
     *
     * Mirrors the hook + item monitoring system in fishing_system_v4.js.
     */
    @EventHandler
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;

        Player player = event.getPlayer();

        if (!isInFishingZone(player.getLocation())) {
            // Out-of-zone warning (30-second cooldown)
            long now = System.currentTimeMillis();
            Long last = warnCooldowns.get(player.getUniqueId());
            if (last == null || now - last >= WARN_COOLDOWN_MS) {
                player.sendMessage("§e[!] You are fishing outside the custom fishing zone!");
                player.sendMessage("§7You will get vanilla fish. Use §e/fish §7to teleport to the zone for custom fish.");
                warnCooldowns.put(player.getUniqueId(), now);
            }
            return; // Vanilla loot proceeds normally
        }

        // Inside the zone – cancel vanilla loot and award a custom fish
        if (event.getCaught() instanceof Item) {
            ((Item) event.getCaught()).remove();
        }
        event.setCancelled(true);

        Rarity rarity  = getRandomRarity();
        FishEntry fish = getRandomFish(rarity);
        giveFish(player, fish);

        logger.info("[Fishing v4] Gave " + fish.name + " (" + rarity.name() + ") to " + player.getName());
    }

    // =========================================================================
    // /fish COMMAND
    // =========================================================================

    /**
     * Teleports the player to the centre of the fishing zone.
     * Mirrors the custom:fish command registration in fishing_system_v4.js.
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;
        Location dest = new Location(player.getWorld(), TP_X, TP_Y, TP_Z, 0f, 0f);
        player.teleport(dest);
        player.sendMessage("§aTeleported to fishing zone!");
        player.sendMessage("§7Fish normally — vanilla fish will be replaced with custom fish!");
        return true;
    }
}

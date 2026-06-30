package com.skyblock.dungeon.loot;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Builds actual ItemStack loot for dungeon chest rooms and drops,
 * using DungeonRarityRoller to pick a tier and a per-tier item pool
 * to pick the concrete item. Item pools are intentionally simple
 * placeholders (vanilla materials with a rarity-colored display name)
 * - swap in real custom dungeon item definitions later without
 * changing the calling code in chest/drop spawners.
 */
public final class DungeonLootTable {

    private final DungeonRarityRoller roller;
    private final Random random;
    private final Map<DungeonRarity, List<Material>> itemPools;

    public DungeonLootTable(DungeonRarityRoller roller, Random random) {
        this.roller = roller;
        this.random = random;
        this.itemPools = buildDefaultPools();
    }

    public DungeonLootTable() {
        this(new DungeonRarityRoller(), new Random());
    }

    /**
     * Rolls and builds a single loot item appropriate for the given floor.
     */
    public ItemStack rollLoot(int floorNumber) {
        DungeonRarity rarity = roller.roll(floorNumber);
        return buildItem(rarity, floorNumber);
    }

    /**
     * Rolls a fixed number of loot items at once, e.g. for a chest room
     * that should contain multiple drops.
     */
    public List<ItemStack> rollLoot(int floorNumber, int count) {
        List<ItemStack> results = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            results.add(rollLoot(floorNumber));
        }
        return results;
    }

    private ItemStack buildItem(DungeonRarity rarity, int floorNumber) {
        List<Material> pool = itemPools.get(rarity);
        Material material = pool.get(random.nextInt(pool.size()));

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(rarity.getColoredName() + " §f" + prettifyMaterialName(material) + " §7(Floor " + floorNumber + ")");
            item.setItemMeta(meta);
        }
        return item;
    }

    private String prettifyMaterialName(Material material) {
        String[] parts = material.name().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part.charAt(0)).append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    /**
     * Hand-authored placeholder item pools per rarity tier. These are
     * generic vanilla materials standing in for real custom dungeon
     * gear/items, which can be swapped in later.
     */
    private Map<DungeonRarity, List<Material>> buildDefaultPools() {
        Map<DungeonRarity, List<Material>> pools = new EnumMap<>(DungeonRarity.class);
        pools.put(DungeonRarity.COMMON, List.of(Material.IRON_INGOT, Material.LEATHER, Material.STRING));
        pools.put(DungeonRarity.UNCOMMON, List.of(Material.GOLD_INGOT, Material.REDSTONE, Material.LAPIS_LAZULI));
        pools.put(DungeonRarity.RARE, List.of(Material.DIAMOND, Material.EMERALD, Material.AMETHYST_SHARD));
        pools.put(DungeonRarity.EPIC, List.of(Material.NETHERITE_SCRAP, Material.ECHO_SHARD, Material.NETHER_STAR));
        pools.put(DungeonRarity.LEGENDARY, List.of(Material.NETHERITE_INGOT, Material.DRAGON_BREATH));
        pools.put(DungeonRarity.MYTHICAL, List.of(Material.NETHER_STAR, Material.ENCHANTED_GOLDEN_APPLE));
        return pools;
    }
}

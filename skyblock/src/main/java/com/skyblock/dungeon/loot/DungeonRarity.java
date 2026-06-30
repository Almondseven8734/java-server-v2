package com.skyblock.dungeon.loot;

/**
 * Loot rarity tiers for dungeon drops (mob drops, boss drops, chest rooms).
 *
 * Mirrors the 6-tier weighted model used by FishingSystemV4.Rarity
 * (COMMON -> MYTHICAL) so dungeon loot feels consistent with the rest
 * of the server's loot systems. Base weights below are the floor-1
 * baseline; DungeonRarityRoller shifts weight toward rarer tiers as
 * floor depth increases.
 */
public enum DungeonRarity {

    COMMON   ("Common",    "§7", 50.0),
    UNCOMMON ("Uncommon",  "§a", 27.0),
    RARE     ("Rare",      "§b", 14.0),
    EPIC     ("Epic",      "§5", 6.0),
    LEGENDARY("Legendary", "§6", 2.5),
    MYTHICAL ("Mythical",  "§d", 0.5);

    private final String displayName;
    private final String color;
    private final double baseWeight;

    DungeonRarity(String displayName, String color, double baseWeight) {
        this.displayName = displayName;
        this.color = color;
        this.baseWeight = baseWeight;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getColor() {
        return color;
    }

    public String getColoredName() {
        return color + displayName;
    }

    /** Base (floor-1) weight, out of 100 total across all tiers. */
    public double getBaseWeight() {
        return baseWeight;
    }
}

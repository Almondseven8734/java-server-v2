package com.skyblock.dungeon.loot;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * Rolls a DungeonRarity for a loot drop, with weights shifted toward
 * rarer tiers as floor depth increases.
 *
 * Shift model: each floor beyond 1 transfers a small fraction of
 * COMMON/UNCOMMON weight into RARE/EPIC/LEGENDARY/MYTHICAL, capped so
 * weights never go negative and floor scaling plateaus at a max floor
 * (deeper than that, the curve stays flat rather than degenerating).
 */
public final class DungeonRarityRoller {

    /** Floor depth at which the rarity curve stops getting more generous. */
    private static final int MAX_SCALING_FLOOR = 20;

    /** Per-floor fraction of COMMON/UNCOMMON weight redistributed to rarer tiers. */
    private static final double SHIFT_PER_FLOOR = 0.018;

    private final Random random;

    public DungeonRarityRoller(Random random) {
        this.random = random;
    }

    public DungeonRarityRoller() {
        this(new Random());
    }

    /**
     * Roll a rarity for the given floor number (1-indexed).
     */
    public DungeonRarity roll(int floorNumber) {
        Map<DungeonRarity, Double> weights = weightsForFloor(floorNumber);

        double total = 0.0;
        for (double w : weights.values()) {
            total += w;
        }

        double roll = random.nextDouble() * total;
        double cumulative = 0.0;
        for (DungeonRarity rarity : DungeonRarity.values()) {
            cumulative += weights.get(rarity);
            if (roll <= cumulative) {
                return rarity;
            }
        }
        return DungeonRarity.COMMON; // fallback, should not be reached
    }

    /**
     * Computes the effective weight table for a given floor depth.
     * Exposed for display purposes (e.g. a /dungeon loot odds menu).
     */
    public Map<DungeonRarity, Double> weightsForFloor(int floorNumber) {
        int effectiveFloor = Math.max(1, Math.min(floorNumber, MAX_SCALING_FLOOR));
        double shift = (effectiveFloor - 1) * SHIFT_PER_FLOOR;

        // Total weight pulled from the common end of the curve, then
        // distributed proportionally into the rare end.
        double pulledFromCommon   = Math.min(DungeonRarity.COMMON.getBaseWeight() * shift, DungeonRarity.COMMON.getBaseWeight() * 0.85);
        double pulledFromUncommon = Math.min(DungeonRarity.UNCOMMON.getBaseWeight() * shift, DungeonRarity.UNCOMMON.getBaseWeight() * 0.85);
        double totalPulled = pulledFromCommon + pulledFromUncommon;

        Map<DungeonRarity, Double> weights = new EnumMap<>(DungeonRarity.class);
        weights.put(DungeonRarity.COMMON, DungeonRarity.COMMON.getBaseWeight() - pulledFromCommon);
        weights.put(DungeonRarity.UNCOMMON, DungeonRarity.UNCOMMON.getBaseWeight() - pulledFromUncommon);

        // Distribute the pulled weight into RARE/EPIC/LEGENDARY/MYTHICAL,
        // weighted so the rarest tiers grow proportionally faster.
        double[] gainShare = {0.45, 0.30, 0.18, 0.07}; // RARE, EPIC, LEGENDARY, MYTHICAL
        DungeonRarity[] gainTargets = {
            DungeonRarity.RARE, DungeonRarity.EPIC, DungeonRarity.LEGENDARY, DungeonRarity.MYTHICAL
        };
        for (int i = 0; i < gainTargets.length; i++) {
            weights.put(gainTargets[i], gainTargets[i].getBaseWeight() + totalPulled * gainShare[i]);
        }

        return weights;
    }
}

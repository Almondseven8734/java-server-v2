package com.skyblock.dungeon.combat;

import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;

/**
 * Applies depth-scaled stat buffs to vanilla mobs used as dungeon
 * bosses on non-milestone floors (per design: simple floors get
 * buffed vanilla mobs, milestone floors get full custom scripted
 * bosses - those are handled separately, not by this class).
 *
 * Scaling is linear-ish per floor with a mild compounding curve so
 * deep floors don't become trivially weak relative to player gear
 * progression, without needing per-floor hand tuning.
 */
public final class MobBuffApplicator {

    /** Base health multiplier added per floor beyond floor 1. */
    private static final double HEALTH_SCALE_PER_FLOOR = 0.35;

    /** Base attack damage multiplier added per floor beyond floor 1. */
    private static final double DAMAGE_SCALE_PER_FLOOR = 0.20;

    /** Movement speed multiplier added per floor beyond floor 1, capped to avoid unfair mob speed. */
    private static final double SPEED_SCALE_PER_FLOOR = 0.015;
    private static final double MAX_SPEED_MULTIPLIER = 1.6;

    /** Extra multiplier applied on top of normal scaling for a floor's designated boss entity. */
    private static final double BOSS_BONUS_MULTIPLIER = 2.5;

    /**
     * Applies regular-mob scaling for ambient dungeon mobs on the given floor.
     */
    public void applyAmbientMobScaling(LivingEntity entity, int floorNumber) {
        applyScaling(entity, floorNumber, 1.0);
    }

    /**
     * Applies boss-tier scaling: a stronger multiplier on top of the
     * normal per-floor curve, used for the buffed-vanilla-mob boss on
     * non-milestone floors.
     */
    public void applyBossScaling(LivingEntity entity, int floorNumber) {
        applyScaling(entity, floorNumber, BOSS_BONUS_MULTIPLIER);
    }

    private void applyScaling(LivingEntity entity, int floorNumber, double extraMultiplier) {
        int depthBeyondFirst = Math.max(0, floorNumber - 1);

        double healthMultiplier = (1.0 + depthBeyondFirst * HEALTH_SCALE_PER_FLOOR) * extraMultiplier;
        double damageMultiplier = (1.0 + depthBeyondFirst * DAMAGE_SCALE_PER_FLOOR) * extraMultiplier;
        double speedMultiplier = Math.min(
            1.0 + depthBeyondFirst * SPEED_SCALE_PER_FLOOR,
            MAX_SPEED_MULTIPLIER
        );

        scaleAttribute(entity, Attribute.MAX_HEALTH, healthMultiplier);
        scaleAttribute(entity, Attribute.ATTACK_DAMAGE, damageMultiplier);
        scaleAttribute(entity, Attribute.MOVEMENT_SPEED, speedMultiplier);

        // Heal to full after scaling MAX_HEALTH, otherwise the entity
        // keeps its pre-scaling current health and looks "damaged" on spawn.
        if (entity.getAttribute(Attribute.MAX_HEALTH) != null) {
            entity.setHealth(entity.getAttribute(Attribute.MAX_HEALTH).getValue());
        }
    }

    private void scaleAttribute(LivingEntity entity, Attribute attribute, double multiplier) {
        var instance = entity.getAttribute(attribute);
        if (instance == null) {
            return; // this entity type doesn't have this attribute, skip silently
        }
        instance.setBaseValue(instance.getBaseValue() * multiplier);
    }
}

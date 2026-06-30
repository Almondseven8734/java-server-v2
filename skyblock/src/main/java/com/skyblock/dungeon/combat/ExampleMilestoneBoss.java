package com.skyblock.dungeon.combat;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Arrays;
import java.util.List;

/**
 * A working example/template MilestoneBoss for milestone floors
 * (5, 10, 15, ...) until distinct scripted bosses are authored per
 * floor. Phase thresholds at 75/50/25% health:
 *
 *  - Phase 1 (100-75%): just a buffed vanilla-style brawler, no abilities yet.
 *  - Phase 2 (<=75%): unlocks a telegraphed AoE slowness/weakness pulse.
 *  - Phase 3 (<=50%): widens the pulse radius and speeds the boss up.
 *  - Phase 4 / enrage (<=25%): doubles pulse frequency and the pulse
 *    starts dealing direct damage instead of just debuffing.
 *
 * Caveat (left in deliberately rather than overstating polish): the
 * "telegraph" here is a particle/sound burst fired the same instant
 * the effect applies, not a true windup-then-strike. A real telegraph
 * (warn -> wait -> apply) would need to split this into two scheduled
 * steps; that's left as a follow-up rather than baked in silently.
 */
public class ExampleMilestoneBoss extends MilestoneBoss {

    private static final List<Double> PHASE_THRESHOLDS = Arrays.asList(0.75, 0.5, 0.25);

    private static final long PULSE_COOLDOWN_TICKS_NORMAL = 100L;  // 5s, used in phases 2-3
    private static final long PULSE_COOLDOWN_TICKS_ENRAGE = 50L;   // 2.5s, used in phase 4

    private static final double PULSE_RADIUS_PHASE2 = 4.5;
    private static final double PULSE_RADIUS_PHASE3 = 7.0;
    private static final double PULSE_RADIUS_ENRAGE = 7.0;
    private static final double PULSE_DAMAGE_ENRAGE = 4.0;

    private boolean pulseUnlocked = false;
    private boolean enraged = false;
    private double pulseRadius = PULSE_RADIUS_PHASE2;
    private long pulseCooldown = PULSE_COOLDOWN_TICKS_NORMAL;
    private long ticksSinceLastPulse = 0;

    public ExampleMilestoneBoss(JavaPlugin plugin, LivingEntity entity, int floorNumber) {
        super(plugin, entity, floorNumber, PHASE_THRESHOLDS);
    }

    @Override
    public void onTick() {
        if (!pulseUnlocked) {
            return;
        }
        ticksSinceLastPulse += MilestoneBossTickRate.PERIOD_TICKS;
        if (ticksSinceLastPulse >= pulseCooldown) {
            ticksSinceLastPulse = 0;
            firePulse();
        }
    }

    @Override
    public void onPhaseTransition(double thresholdCrossed) {
        if (thresholdCrossed == 0.75) {
            pulseUnlocked = true;
            pulseRadius = PULSE_RADIUS_PHASE2;
            pulseCooldown = PULSE_COOLDOWN_TICKS_NORMAL;
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.0f, 1.4f);
        } else if (thresholdCrossed == 0.5) {
            pulseRadius = PULSE_RADIUS_PHASE3;
            var speedAttr = entity.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speedAttr != null) {
                speedAttr.setBaseValue(speedAttr.getBaseValue() * 1.25);
            }
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.0f, 1.0f);
        } else if (thresholdCrossed == 0.25) {
            enraged = true;
            pulseRadius = PULSE_RADIUS_ENRAGE;
            pulseCooldown = PULSE_COOLDOWN_TICKS_ENRAGE;
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 0.8f);
            entity.getWorld().spawnParticle(Particle.FLAME, entity.getLocation(), 80, 1.0, 1.0, 1.0, 0.05);
        }
    }

    @Override
    public void onDeath() {
        entity.getWorld().spawnParticle(Particle.EXPLOSION, entity.getLocation(), 6, 1.0, 1.0, 1.0, 0.0);
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_WITHER_DEATH, 1.0f, 1.0f);
    }

    private void firePulse() {
        Location center = entity.getLocation();
        entity.getWorld().spawnParticle(Particle.SONIC_BOOM, center, 1);
        entity.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center, 20, pulseRadius * 0.5, 0.3, pulseRadius * 0.5, 0.0);
        entity.getWorld().playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.0f);

        for (org.bukkit.entity.Entity nearby : entity.getNearbyEntities(pulseRadius, pulseRadius, pulseRadius)) {
            if (!(nearby instanceof Player player)) {
                continue;
            }
            if (player.getLocation().distance(center) > pulseRadius) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1));
            player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0));
            if (enraged) {
                player.damage(PULSE_DAMAGE_ENRAGE, entity);
            }
        }
    }

    /** The tick period this boss is driven at, mirrored from DungeonBossRoomTrigger's scheduler cadence. */
    private static final class MilestoneBossTickRate {
        private static final long PERIOD_TICKS = 10L;
    }
}

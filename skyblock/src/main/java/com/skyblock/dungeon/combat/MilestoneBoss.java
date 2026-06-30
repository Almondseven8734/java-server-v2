package com.skyblock.dungeon.combat;

import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for full custom scripted bosses on milestone floors
 * (every 5th, per design). Unlike MobBuffApplicator's buffed-vanilla
 * approach for ordinary floors, milestone bosses get telegraphed
 * abilities and phase transitions defined by subclasses.
 *
 * Subclasses implement onTick (called periodically while the boss is
 * alive) and onPhaseTransition (called when health crosses a phase
 * threshold). The scheduling itself (how often onTick fires) is left
 * to whatever spawns this boss, since that depends on the floor
 * manager's tick loop - this class only holds the ability/phase logic.
 */
public abstract class MilestoneBoss {

    /** Health-percentage thresholds (descending) at which onPhaseTransition fires once each. */
    private final List<Double> phaseThresholds;
    private final List<Double> firedThresholds = new ArrayList<>();

    protected final JavaPlugin plugin;
    protected final LivingEntity entity;
    protected final int floorNumber;

    protected MilestoneBoss(JavaPlugin plugin, LivingEntity entity, int floorNumber, List<Double> phaseThresholds) {
        this.plugin = plugin;
        this.entity = entity;
        this.floorNumber = floorNumber;
        this.phaseThresholds = new ArrayList<>(phaseThresholds);
        this.phaseThresholds.sort((a, b) -> Double.compare(b, a)); // descending, e.g. [0.75, 0.5, 0.25]
    }

    /**
     * Called periodically while this boss is alive (e.g. every few
     * ticks/seconds, scheduled by the caller). Subclasses implement
     * their attack patterns / telegraphed abilities here.
     */
    public abstract void onTick();

    /**
     * Called exactly once each time the boss's health crosses one of
     * its configured phase thresholds. Subclasses implement phase
     * changes (e.g. new attack patterns, added adds, arena hazards)
     * here.
     *
     * @param thresholdCrossed the threshold (0.0-1.0 fraction of max health) just crossed
     */
    public abstract void onPhaseTransition(double thresholdCrossed);

    /**
     * Called once when the boss dies. Subclasses can override to add
     * a death sequence (e.g. a final explosion, loot burst) - default
     * implementation does nothing, since loot/staircase triggering is
     * handled by BossKillTracker elsewhere, not by this class.
     */
    public void onDeath() {
        // no-op by default
    }

    /**
     * Call this every tick (or on whatever cadence the caller uses)
     * to drive both onTick and automatic phase-transition detection.
     * Centralizing this here means subclasses never need to manually
     * check health thresholds themselves.
     */
    public final void update() {
        if (entity.isDead()) {
            return;
        }

        checkPhaseTransitions();
        onTick();
    }

    private void checkPhaseTransitions() {
        var maxHealthAttr = entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (maxHealthAttr == null) {
            return;
        }
        double maxHealth = maxHealthAttr.getValue();
        double currentHealth = entity.getHealth();
        double fraction = maxHealth <= 0 ? 0 : currentHealth / maxHealth;

        for (double threshold : phaseThresholds) {
            if (fraction <= threshold && !firedThresholds.contains(threshold)) {
                firedThresholds.add(threshold);
                onPhaseTransition(threshold);
            }
        }
    }

    public LivingEntity getEntity() {
        return entity;
    }

    public int getFloorNumber() {
        return floorNumber;
    }
}

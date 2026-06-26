package com.fluxlogic.combat;

import com.fluxlogic.config.FluxConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side combat detection. There is no Fabric "player took damage" event,
 * so we infer combat from things the client can observe directly:
 *
 * <ul>
 *   <li>the player's health dropping between ticks (took damage),</li>
 *   <li>a hostile mob within the configured radius (threat present),</li>
 *   <li>a recent attack input while looking at an entity (dealt damage).</li>
 * </ul>
 *
 * Any one of these refreshes the "last combat signal" timestamp; the
 * {@code PresetManager} applies hysteresis on top so we don't flicker out of
 * combat the instant a skeleton steps behind a wall.
 *
 * <p>It also caches the list of nearby hostiles each sample so
 * {@code TacticalVision} doesn't have to scan the world a second time.
 */
public final class CombatTracker {

    private float lastHealth = Float.NaN;
    private long lastSignalMs = Long.MIN_VALUE;

    private final List<LivingEntity> nearbyHostiles = new ArrayList<>();

    /** Most recent set of hostiles found within the scan radius (read-only view). */
    public List<LivingEntity> nearbyHostiles() {
        return nearbyHostiles;
    }

    public long lastSignalMs() {
        return lastSignalMs;
    }

    /**
     * Sample combat state for this tick.
     *
     * @return {@code true} if a fresh combat signal fired this tick
     */
    public boolean sample(Minecraft mc, FluxConfig cfg, long nowMs) {
        nearbyHostiles.clear();

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            lastHealth = Float.NaN;
            return false;
        }

        boolean signal = false;

        // --- took damage: health decreased since last sample -----------------
        float hp = player.getHealth();
        if (!Float.isNaN(lastHealth) && hp < lastHealth - 0.001f) {
            signal = true;
        }
        lastHealth = hp;

        // --- threat present: scan for hostiles in radius ---------------------
        double r = cfg.presets.hostileScanRadius;
        AABB box = player.getBoundingBox().inflate(r);
        double r2 = r * r;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, CombatTracker::isHostile)) {
            if (mob.distanceToSqr(player) <= r2 && mob.isAlive()) {
                nearbyHostiles.add(mob);
            }
        }
        if (!nearbyHostiles.isEmpty()) {
            signal = true;
        }

        // --- dealt damage: attacking while aimed at a living entity ----------
        if (mc.options.keyAttack.isDown() && mc.crosshairPickEntity instanceof LivingEntity) {
            signal = true;
        }

        if (signal) {
            lastSignalMs = nowMs;
        }
        return signal;
    }

    /** Treat anything implementing the vanilla {@code Enemy} marker as hostile. */
    private static boolean isHostile(Entity e) {
        return e instanceof Enemy && e.isAlive();
    }
}

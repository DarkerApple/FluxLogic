package com.fluxlogic.combat;

import com.fluxlogic.config.ConfigManager;
import com.fluxlogic.config.FluxConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * "Tactical vision": during combat, outline hostiles (and gameplay-critical
 * "essential" mobs in their own colour) so they pop, optionally desaturating
 * the rest of the world.
 *
 * <h2>How the highlight works — and why it's safe</h2>
 * We reuse the engine's existing entity-glow/outline post pass (the same one
 * spectral arrows and the Glowing effect use) by toggling each entity's client
 * glow flag. Colour comes from a pair of client-side scoreboard teams. That
 * means:
 * <ul>
 *   <li>no custom GLSL on the hot path,</li>
 *   <li>renderer-agnostic — works on OpenGL today and survives the Vulkan
 *       toggle, because we never issue GL calls ourselves,</li>
 *   <li>fully reversible — we track exactly what we tagged and clear it.</li>
 * </ul>
 *
 * The fullscreen desaturation (see {@link PostEffectBridge}) is the only
 * renderer-sensitive piece and is off by default.
 */
public final class TacticalVision {

    private static final String TEAM_HOSTILE = "flux_hostile";
    private static final String TEAM_ESSENTIAL = "flux_essential";

    /** Entities we currently have glowing, so we can clear them precisely. */
    private final Set<Entity> glowing = new HashSet<>();

    private boolean active;

    /**
     * @param inCombat whether the preset engine currently reports COMBAT
     */
    public void update(Minecraft mc, boolean inCombat) {
        FluxConfig cfg = ConfigManager.get();
        if (!cfg.tactical.enabled) {
            if (active) clearAll();
            return;
        }

        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            if (active) clearAll();
            return;
        }

        // Tactical vision only engages during combat — otherwise the world
        // looks completely normal.
        if (!inCombat) {
            if (active) {
                clearAll();
                PostEffectBridge.disable(mc);
                active = false;
            }
            return;
        }

        active = true;
        ensureTeams(level.getScoreboard(), cfg);

        // Re-tag every tick (server entity-data syncs would otherwise clear the
        // client glow flag). We rebuild the set each pass.
        Set<Entity> stillGlowing = new HashSet<>();

        double r = Math.max(cfg.presets.hostileScanRadius, cfg.performance.entityCullDistance);
        AABB box = player.getBoundingBox().inflate(r);

        // No player exclusion needed: Player is not a Mob subclass.
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, LivingEntity::isAlive)) {
            boolean hostile = mob instanceof Enemy;
            boolean essential = cfg.tactical.highlightEssentials && isEssential(mob, cfg);

            if (essential) {
                tag(level.getScoreboard(), mob, TEAM_ESSENTIAL, stillGlowing);
            } else if (hostile && cfg.tactical.highlightHostiles) {
                tag(level.getScoreboard(), mob, TEAM_HOSTILE, stillGlowing);
            }
        }

        // Anything we previously lit that no longer qualifies → clear it.
        for (Entity e : glowing) {
            if (!stillGlowing.contains(e) && e.isAlive()) {
                e.setGlowingTag(false);
            }
        }
        glowing.clear();
        glowing.addAll(stillGlowing);

        // Optional fullscreen desaturation (renderer-sensitive, best-effort).
        if (cfg.tactical.desaturateWorld) {
            PostEffectBridge.enable(mc, cfg.tactical.desaturateStrength);
        } else {
            PostEffectBridge.disable(mc);
        }
    }

    private void tag(Scoreboard sb, Entity e, String teamName, Set<Entity> out) {
        try {
            PlayerTeam team = sb.getPlayerTeam(teamName);
            String key = e.getScoreboardName();
            // Respect a server-assigned team: only colour entities that are
            // teamless or already on one of ours.
            PlayerTeam existing = sb.getPlayersTeam(key);
            if (existing == null || existing == team
                    || existing.getName().startsWith("flux_")) {
                if (team != null) {
                    sb.addPlayerToTeam(key, team);
                }
            }
            e.setGlowingTag(true);
            out.add(e);
        } catch (Throwable t) {
            // Worst case we skip colouring this entity; never crash the render.
            ConfigManager.LOG.debug("[FluxLogic] tag failed: {}", t.toString());
        }
    }

    private void ensureTeams(Scoreboard sb, FluxConfig cfg) {
        ensureTeam(sb, TEAM_HOSTILE, nearestTeamColor(cfg.tactical.hostileColor, TeamColor.RED));
        ensureTeam(sb, TEAM_ESSENTIAL, nearestTeamColor(cfg.tactical.essentialColor, TeamColor.AQUA));
    }

    private void ensureTeam(Scoreboard sb, String name, TeamColor color) {
        try {
            PlayerTeam team = sb.getPlayerTeam(name);
            if (team == null) {
                team = sb.addPlayerTeam(name);
            }
            // 26.2: team colour is Optional<TeamColor> (empty = "reset").
            team.setColor(Optional.of(color));
            team.setSeeFriendlyInvisibles(false);
        } catch (Throwable t) {
            ConfigManager.LOG.debug("[FluxLogic] team setup failed: {}", t.toString());
        }
    }

    private boolean isEssential(Entity e, FluxConfig cfg) {
        // `var` on purpose: the id type was repackaged in 26.x, and all we need
        // is its canonical "namespace:path" string form.
        var id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return id != null && cfg.tactical.essentialEntities.contains(id.toString());
    }

    private void clearAll() {
        for (Entity e : glowing) {
            if (e.isAlive()) {
                e.setGlowingTag(false);
            }
        }
        glowing.clear();
        active = false;
    }

    /**
     * Map an "#RRGGBB" string to the nearest of the 16 vanilla team colours
     * (the engine's outline colour is team-driven, so we can't use arbitrary
     * RGB without a renderer mixin). Falls back to {@code fallback} on garbage.
     *
     * <p>26.2 note: team colours are the {@code TeamColor} enum now (colours
     * only — no BOLD-style entries to skip), each carrying its RGB via
     * {@code rgb()}.
     */
    private static TeamColor nearestTeamColor(String hex, TeamColor fallback) {
        Integer rgb = parseHex(hex);
        if (rgb == null) {
            return fallback;
        }
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        TeamColor best = fallback;
        int bestDist = Integer.MAX_VALUE;
        for (TeamColor c : TeamColor.values()) {
            int cc = c.rgb();
            int dr = ((cc >> 16) & 0xFF) - r;
            int dg = ((cc >> 8) & 0xFF) - g;
            int db = (cc & 0xFF) - b;
            int dist = dr * dr + dg * dg + db * db;
            if (dist < bestDist) {
                bestDist = dist;
                best = c;
            }
        }
        return best;
    }

    private static Integer parseHex(String hex) {
        if (hex == null) return null;
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        try {
            return (int) (Long.parseLong(s, 16) & 0xFFFFFF);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

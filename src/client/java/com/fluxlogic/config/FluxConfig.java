package com.fluxlogic.config;

import com.fluxlogic.presets.PerformancePreset;

import java.util.ArrayList;
import java.util.List;

/**
 * The full FluxLogic configuration tree. Plain public fields + no-arg
 * constructor so Gson can (de)serialise it with zero annotations, and so the
 * in-game settings screen can bind directly to fields.
 *
 * <p>Grouped by feature. Each group is independently toggleable — the whole
 * mod is designed so any feature can be off and the rest still works.
 */
public final class FluxConfig {

    /** Bump when the schema changes so {@link ConfigManager} can migrate. */
    public int configVersion = 1;

    public Camera camera = new Camera();
    public Input input = new Input();
    public Presets presets = new Presets();
    public Tactical tactical = new Tactical();
    public Performance performance = new Performance();
    public Compat compat = new Compat();

    // ----------------------------------------------------------------- Camera
    /** "Inertia!" — the smooth-camera feature this mod grew out of. */
    public static final class Camera {
        public boolean enabled = true;

        /** Seconds for yaw to cover half the distance to the target. 0 = off. */
        public float yawHalfLife = 0.035f;
        /** Seconds for pitch half-distance. Usually a touch slower than yaw. */
        public float pitchHalfLife = 0.045f;

        /**
         * Smooth the vertical camera snap when stepping up blocks/stairs.
         * This is the "stair-stepping" fix — the eye height is interpolated
         * instead of teleporting up by half a block.
         */
        public boolean stairStepSmoothing = true;
        public float stairStepHalfLife = 0.08f;

        /** Soft-gate tiny mouse deltas to filter sensor jitter (in raw units). */
        public double mouseDeadzone = 0.0;

        /** Extra low-pass on raw mouse input. 0 = raw, 1 = very heavy. */
        public float mouseSmoothing = 0.0f;

        /** Hard safety cap so smoothing can never feel like input lag in PvP. */
        public float maxCatchUpDegreesPerTick = 50.0f;
    }

    // ------------------------------------------------------------------ Input
    /**
     * Raw input plumbing fixes — distinct from {@link Camera}'s feel filters.
     */
    public static final class Input {
        /**
         * Coalesce the GLFW cursor-move event flood from high-polling-rate
         * mice (1000–8000 Hz) so movement is processed per frame, not per
         * hardware poll. Fixes the "game stutters when I move the mouse while
         * holding left click" bug. Loses no input: deltas are derived from
         * absolute cursor positions, so skipped events fold into the next
         * processed one exactly.
         */
        public boolean stutterFix = true;

        /**
         * Upper bound on cursor-move events actually processed per second
         * (the rest are coalesced). Also the safety valve if the per-frame
         * hook ever fails to apply. Clamped to 125–8000.
         */
        public int maxPollsPerSecond = 1000;
    }

    // ---------------------------------------------------------------- Presets
    public static final class Presets {
        public boolean enabled = true;

        /** Cross-fade time (seconds) when switching presets, to hide the change. */
        public float transitionSeconds = 0.6f;

        /** Stay in COMBAT this long after the last combat signal (ms). */
        public long combatHoldMs = 6000;
        /** No movement for this long ⇒ IDLE (ms). */
        public long idleAfterMs = 4000;

        /** Radius (blocks) to scan for hostile mobs when deciding COMBAT. */
        public double hostileScanRadius = 16.0;

        public PerformancePreset combat = PerformancePreset.defaultCombat();
        public PerformancePreset exploration = PerformancePreset.defaultExploration();
        public PerformancePreset idle = PerformancePreset.defaultIdle();
    }

    // --------------------------------------------------------------- Tactical
    /** Combat "tactical vision": glow hostiles, optionally desaturate the world. */
    public static final class Tactical {
        public boolean enabled = true;

        /** Outline hostile mobs while in COMBAT. Uses the engine's glow pass. */
        public boolean highlightHostiles = true;

        /** ARGB hex for the hostile outline (via a client-side team colour). */
        public String hostileColor = "#FF4040";

        /**
         * Also highlight gameplay-critical mobs in their own colour so they
         * never get lost in the desaturation — Shulkers, villagers, etc.
         */
        public boolean highlightEssentials = true;
        public String essentialColor = "#40C0FF";

        /**
         * Entity types (by registry id) always treated as "essential" and kept
         * highlighted/colourful. Editable so anyone can add their must-sees.
         */
        public List<String> essentialEntities = new ArrayList<>(List.of(
                "minecraft:shulker",
                "minecraft:villager",
                "minecraft:wandering_trader",
                "minecraft:iron_golem",
                "minecraft:allay",
                "minecraft:item",
                "minecraft:experience_orb"
        ));

        /**
         * Fullscreen semi-grayscale of everything that isn't highlighted, so
         * threats pop. OFF by default: it's the single most renderer-sensitive
         * feature (loads a post-process pipeline). See ARCHITECTURE.md.
         */
        public boolean desaturateWorld = false;
        /** 0 = no desaturation, 1 = fully grayscale background. */
        public float desaturateStrength = 0.6f;
    }

    // ------------------------------------------------------------ Performance
    /**
     * Performance here is delivered the <em>compatible</em> way — through
     * vanilla's own supported knobs (entity-distance scaling, particle level,
     * render/sim distance), driven by the preset engine. Heavy chunk/entity
     * render-pipeline culling is intentionally left to a dedicated renderer mod
     * (Sodium) when present, instead of fighting it. See ARCHITECTURE.md.
     */
    public static final class Performance {
        /**
         * Upper bound (blocks) for FluxLogic's own entity scans (combat /
         * tactical-vision). Keeps our per-tick world queries cheap; does not
         * touch the renderer.
         */
        public double entityCullDistance = 64.0;
    }

    // ----------------------------------------------------------------- Compat
    /**
     * FluxLogic never replaces the renderer, so it coexists with Sodium / Iris /
     * VulkanMod. These flags let it politely step back from anything those mods
     * own, to guarantee zero conflicts.
     */
    public static final class Compat {
        /** If Sodium is present, don't touch chunk/entity render internals. */
        public boolean deferToSodium = true;
        /** If a Vulkan renderer is present, disable the OpenGL-only post shader. */
        public boolean deferToVulkanRenderer = true;
        /** If Iris is present, route shader suspend/resume through its API. */
        public boolean deferToIris = true;
    }
}

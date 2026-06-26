package com.fluxlogic.presets;

/**
 * A snapshot of the performance-relevant video options FluxLogic is willing to
 * drive. Deliberately a plain data object decoupled from Minecraft's
 * {@code Options} class: the {@link PresetManager} is the only place that
 * translates these into real game options, so the rest of the mod (and the
 * config file) never depends on Mojang field names that churn between drops.
 *
 * <p>Every field is {@code null}able. {@code null} means "don't touch this
 * option" — so a preset can change only render distance and leave the player's
 * other choices alone. This is central to the "presets you don't notice"
 * goal: the smallest possible set of changes, applied gradually.
 */
public final class PerformancePreset {

    /** Mirrors vanilla graphics modes without importing the enum. */
    public enum Graphics { FAST, FANCY, FABULOUS }

    /** Mirrors vanilla particle setting. */
    public enum Particles { MINIMAL, DECREASED, ALL }

    /** Mirrors vanilla cloud setting. */
    public enum Clouds { OFF, FAST, FANCY }

    public String name = "preset";

    /** Chunk render distance (2..64). {@code null} = leave as-is. */
    public Integer renderDistance;

    /** Simulation distance (5..32). {@code null} = leave as-is. */
    public Integer simulationDistance;

    /** Entity render distance as a percentage (50..500). {@code null} = leave. */
    public Integer entityDistancePercent;

    public Graphics graphics;
    public Particles particles;
    public Clouds clouds;

    /** Entity shadows on/off. */
    public Boolean entityShadows;

    /** Whether to ask compatible shader mods (Iris) to suspend shaders. */
    public Boolean suspendShaders;

    /** Cap FPS while this preset is active ({@code null} = leave; 0 = unlimited). */
    public Integer maxFps;

    /** Bobbing/view-bob toggle — turning it off during combat steadies aim. */
    public Boolean viewBobbing;

    public PerformancePreset() {}

    public PerformancePreset(String name) {
        this.name = name;
    }

    // --- Fluent builders so default presets read like a spec --------------

    public PerformancePreset renderDistance(int v) { this.renderDistance = v; return this; }
    public PerformancePreset simulationDistance(int v) { this.simulationDistance = v; return this; }
    public PerformancePreset entityDistancePercent(int v) { this.entityDistancePercent = v; return this; }
    public PerformancePreset graphics(Graphics v) { this.graphics = v; return this; }
    public PerformancePreset particles(Particles v) { this.particles = v; return this; }
    public PerformancePreset clouds(Clouds v) { this.clouds = v; return this; }
    public PerformancePreset entityShadows(boolean v) { this.entityShadows = v; return this; }
    public PerformancePreset suspendShaders(boolean v) { this.suspendShaders = v; return this; }
    public PerformancePreset maxFps(int v) { this.maxFps = v; return this; }
    public PerformancePreset viewBobbing(boolean v) { this.viewBobbing = v; return this; }

    // --- Built-in defaults -------------------------------------------------

    /**
     * Combat: prioritise frame stability and clarity. Trim render/sim distance,
     * drop clouds and shadows, suspend heavy shaders, steady the view.
     */
    public static PerformancePreset defaultCombat() {
        return new PerformancePreset("combat")
                .renderDistance(8)
                .simulationDistance(8)
                .entityDistancePercent(120)
                .graphics(Graphics.FAST)
                .particles(Particles.DECREASED)
                .clouds(Clouds.OFF)
                .entityShadows(false)
                .suspendShaders(true)
                .viewBobbing(false);
    }

    /** Exploration: a comfortable middle ground for traversal. */
    public static PerformancePreset defaultExploration() {
        return new PerformancePreset("exploration")
                .renderDistance(16)
                .simulationDistance(10)
                .entityDistancePercent(100)
                .graphics(Graphics.FANCY)
                .particles(Particles.ALL)
                .clouds(Clouds.FAST)
                .entityShadows(true)
                .suspendShaders(false)
                .viewBobbing(true);
    }

    /** Idle / scenic: let the world look its best when nothing is happening. */
    public static PerformancePreset defaultIdle() {
        return new PerformancePreset("idle")
                .renderDistance(24)
                .simulationDistance(12)
                .entityDistancePercent(100)
                .graphics(Graphics.FANCY)
                .particles(Particles.ALL)
                .clouds(Clouds.FANCY)
                .entityShadows(true)
                .suspendShaders(false)
                .viewBobbing(true);
    }
}

# FluxLogic

**A client-side adaptive performance & comfort layer for Minecraft 26.2 (Fabric).**

FluxLogic grew out of *Inertia!* — the idea that Minecraft's camera should feel
**silky and comfortable, not jarring**. It keeps that smooth-camera core and
builds an *adaptive* performance system around it: the game quietly tunes itself
to **what you're doing right now** (fighting, exploring, or standing still), and
gives you tactical clarity in combat.

The guiding principle is **"do it differently from Sodium, and never fight it."**
FluxLogic is **not a renderer**. It doesn't rewrite chunk meshing or replace the
graphics pipeline. Instead it's a *coordinator* that sits on top of public game
APIs — which is exactly why it **stacks cleanly with Sodium, Iris, and even
Vulkan renderers** instead of crashing against them.

> ⚠️ **Honest status:** this is a complete, well-structured source project that
> targets **Minecraft 26.2 (Java 25, Fabric)**. It has **not** been compiled or
> launched against a live 26.2 client by the author of this commit (no Java 25
> game environment was available here). Treat it as a *very* strong starting
> point: build it yourself with the [step-by-step Mac guide](BUILDING.md), and
> verify the handful of clearly-flagged API touch-points noted there. The design
> is deliberately structured so the one version-sensitive hook **degrades to a
> no-op** rather than crashing if a signature changed.

---

## Why another performance mod?

Because most "opti" mods do the same thing: replace the renderer (Sodium,
VulkanMod) or the lighting (Starlight). Those are great — but you can only run
**one** renderer, and re-doing that work is both enormous and pointless.

FluxLogic attacks a **different, unclaimed axis**: *almost nobody is fighting
full render distance, fancy graphics, and shaders the entire time they play.*
You need them while building a base at sunset; you don't need them mid-fight in a
cave. FluxLogic spends frames where they matter and reclaims them where they
don't — automatically, and **without you noticing the switch**.

| Most opti mods | FluxLogic |
|---|---|
| Replace the renderer | Drives the *existing* renderer's public options |
| One-and-only (conflict-prone) | Layers on top of Sodium / Iris / VulkanMod |
| Static settings you set once | Adaptive: combat / exploration / idle presets |
| Pure FPS | FPS **and** comfort (camera) **and** clarity (tactical vision) |

See **[ARCHITECTURE.md](ARCHITECTURE.md)** for the full design rationale,
including a survey of how Sodium / VulkanMod / Starlight work and exactly where
FluxLogic chooses to *not* overlap them.

---

## Features

### 🎥 Inertia! — smooth camera
- Frame-rate-independent smoothing of camera yaw/pitch (identical feel at 30 or
  240 FPS).
- **Mouse-jitter filtering** via a soft deadzone — kills sensor micro-noise
  without eating deliberate small movements.
- **Stair-step easing** — smooths the half-block vertical "snap" when you walk up
  stairs/slabs, while leaving jumping/falling/elytra untouched.
- A **hard catch-up cap** so smoothing can never feel like input lag: fast flicks
  snap, slow pans glide. Tuned for comfort/accessibility (motion sickness).

### ⚙️ Adaptive presets — performance you don't notice
- Detects **Combat / Exploration / Idle** from health changes, nearby hostiles,
  attacks, and movement (with hysteresis so it never flickers).
- Each context has a fully-editable preset: render distance, sim distance, entity
  distance, graphics mode, particles, clouds, shadows, view-bob, FPS cap, and
  shader suspend.
- **The switch is engineered to be invisible:** cheap settings change *during*
  the chaos of combat (when you won't notice a cosmetic drop), while expensive
  **render-distance changes are debounced and rate-limited** so you never get the
  jarring chunk-reload churn that naive presets cause.
- Asks **Iris** to suspend shaders in combat through its own API (no internals
  poked), and resumes them after.

### 🎯 Tactical Vision — clarity in combat (the "highlight mobs" idea)
- In combat, **hostiles glow** with an outline using the engine's *existing*
  glow/outline pass — renderer-agnostic, no custom GLSL on the hot path.
- **Essentials keep their own colour** so they never get lost: Shulkers,
  villagers, wandering traders, iron golems, allays, dropped items, XP orbs —
  all fully editable in the config list.
- Optional, **experimental** fullscreen *semi-grayscale* of everything else so
  threats pop (off by default; it's the one renderer-sensitive piece and falls
  back to glow-only automatically under Vulkan).
- Everything is **fully reversible** — FluxLogic tracks exactly what it lit and
  clears it the instant combat ends.

### 🤝 Compatibility first
- Detects Sodium / Iris / VulkanMod / Mod Menu and **defers** to whoever already
  owns a subsystem.
- Never issues raw GL calls → **survives Mojang's OpenGL → Vulkan transition**.
- Client-side only. Safe on multiplayer servers (it only changes *your* client
  options and *your* client-side entity glow flags).

---

## Minecraft 26.2 & the Vulkan question (important)

Minecraft moved to year-based versions in 2026. **26.2 "Chaos Cubed"** (June 16
2026) runs on **Java 25**. About Vulkan:

- Mojang announced (Feb 2026) it is moving Java Edition from **OpenGL → Vulkan**,
  with an in-game **renderer toggle** arriving in **summer-2026 snapshots** (on
  the road to 26.3) before OpenGL is eventually retired.
- **So on 26.2 *stable* today, the renderer is OpenGL.** The Vulkan "preset" you
  may have seen is that upcoming Mojang toggle (snapshots), *not* a 26.2 release
  feature, and *not* the community VulkanMod.

**How FluxLogic handles this:** it is renderer-agnostic by construction — it
never makes GL/Vulkan calls itself. So it runs under OpenGL on 26.2 now and is
designed to keep working when you flip Mojang's Vulkan toggle in snapshots. The
*only* renderer-sensitive feature (the optional desaturation post-shader) detects
a Vulkan renderer and disables itself, leaving the renderer-agnostic glow
highlight in place. Full detail in [ARCHITECTURE.md](ARCHITECTURE.md).

---

## Install (once you've built it)

1. Install **Fabric Loader 0.19.3+** for Minecraft 26.2.
2. Install **[Fabric API](https://modrinth.com/mod/fabric-api)** (0.153.0+26.2).
3. Drop `fluxlogic-0.1.0.jar` into your `mods/` folder.
4. (Recommended) Run it **alongside Sodium + Iris** — FluxLogic handles comfort,
   adaptation and tactical clarity; they handle raw rendering. Best of both.

**Open settings in-game:** bind a key under *Options → Controls → FluxLogic*, or
edit `config/fluxlogic.json` directly (the screen has an "Open config file…"
button). Optional Mod Menu integration snippet is in [BUILDING.md](BUILDING.md).

---

## Build it yourself

You'll need **JDK 25** and a Mac (or any OS). The repo ships a Gradle wrapper
pinned to the right version, so you don't need Gradle installed.

```bash
./gradlew build
# → build/libs/fluxlogic-0.1.0.jar
```

The **full, beginner-friendly Mac walkthrough** — installing JDK 25, running it,
testing in a dev client, and the exact API touch-points to verify for 26.2 — is
in **[BUILDING.md](BUILDING.md)**.

---

## Configuration

Everything is exposed in `config/fluxlogic.json` (created on first run). Highlights:

```jsonc
{
  "camera": {
    "enabled": true,
    "yawHalfLife": 0.035,            // lower = snappier, higher = smoother
    "stairStepSmoothing": true,
    "mouseDeadzone": 0.0,            // raise to filter mouse jitter
    "maxCatchUpDegreesPerTick": 50.0 // safety cap vs. input-lag feel
  },
  "presets": {
    "enabled": true,
    "combatHoldMs": 6000,            // stay "in combat" this long after a hit
    "combat":      { "renderDistance": 8,  "graphics": "FAST",  "suspendShaders": true },
    "exploration": { "renderDistance": 16, "graphics": "FANCY" },
    "idle":        { "renderDistance": 24, "graphics": "FANCY" }
  },
  "tactical": {
    "highlightHostiles": true,
    "hostileColor": "#FF4040",
    "highlightEssentials": true,
    "essentialColor": "#40C0FF",
    "essentialEntities": ["minecraft:shulker", "minecraft:villager", "..."],
    "desaturateWorld": false         // experimental fullscreen grayscale
  }
}
```

Any field set to a preset's value of `null` means *"leave the player's choice
alone"* — so a preset can change only what it needs to.

---

## License

[MIT](LICENSE). Use it, fork it, learn from it.

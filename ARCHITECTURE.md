# FluxLogic — Architecture & Design Notes

This document explains *why* FluxLogic is built the way it is: how the major
optimization mods work, where FluxLogic deliberately does **not** overlap them,
the performance philosophy, the Minecraft 26.2 / Vulkan situation, and the
risk-managed approach to shipping for a version that couldn't be live-tested in
this environment.

---

## 1. How the big optimization mods actually work

A quick, honest survey so the design choices below make sense.

### Sodium (Caffeine MC)
- **Replaces Minecraft's chunk-rendering pipeline** wholesale. Its wins come from:
  - rewritten **chunk meshing** done **asynchronously** on a worker thread pool,
    decoupled from frame rate, so chunk builds don't stall rendering;
  - **region-based** vertex storage and **batched draw calls** to cut CPU→GPU
    overhead and state changes;
  - tighter **frustum + occlusion culling** and modern GPU-friendly buffer usage.
- It owns the render path. Two mods that both rewrite that path **cannot coexist**.

### VulkanMod (xCollateral)
- **Replaces the OpenGL backend with a Vulkan renderer.** Because it changes *how*
  everything is drawn, it is **mutually exclusive with Sodium** and breaks mods
  that issue raw OpenGL calls (e.g. some shader/visual mods). Stacking Sodium +
  VulkanMod **crashes** — they fight over the same pipeline.

### Iris
- A **shader-pack loader** (OptiFine-shader compatible) built to run *with*
  Sodium. It owns the shader pipeline and exposes a small public API to
  enable/disable shaders.

### Starlight / lighting engines
- Rewrite the **light propagation** engine for faster chunk lighting. Another
  "own a subsystem and replace it" mod.

### The pattern
Every one of these **takes ownership of one heavy subsystem and rewrites it**.
That's why they're powerful — and why they conflict. You can run *one* renderer,
*one* lighting engine, *one* shader loader.

---

## 2. FluxLogic's thesis: a different axis

> Re-implementing Sodium's chunk culling "but a bit different" would be a
> multi-thousand-hour effort that **conflicts with Sodium** and helps nobody.

So FluxLogic intentionally attacks an axis **no popular mod owns**:

**Nobody is maxing render distance, fancy graphics, and shaders 100% of the time
they play.** The cost/benefit of those settings depends entirely on *what you're
doing*. FluxLogic makes settings **adaptive** instead of static, and adds two
comfort/clarity systems (smooth camera, tactical highlighting) that are pure
client-feel — orthogonal to rendering.

Crucially, it does all of this through **public, supported game APIs** (the
`Options` system, entity glow flags, scoreboard teams, Fabric events) and **one
tiny, self-disabling render hook** (camera rotation). That means:

- **Zero pipeline ownership** → coexists with Sodium / Iris / VulkanMod.
- **No raw GL/Vulkan calls** → survives Mojang's renderer transition.
- **Composable** → FluxLogic + Sodium + Iris is a strictly better stack than any
  one of them, because they cover *different* problems.

This is the answer to *"don't do what Sodium does — but do better if you can."*
"Better" here isn't faster meshing; it's **spending the frames Sodium gives you
more intelligently**, plus comfort and clarity Sodium doesn't address.

---

## 3. Performance model — where the wins come from

FluxLogic's performance gains are **honest and bounded**. It does not claim to
rewrite rendering. The levers it pulls (all through vanilla's own supported
options) are:

1. **Render distance & simulation distance** — the single biggest FPS/CPU lever.
   Pulled down in combat, restored when calm. Debounced so it never thrashes
   chunk meshing (the naive mistake that makes dynamic-RD mods feel awful).
2. **Entity distance scaling & particle level** — cheap, instant, high-value;
   dropped in combat where you won't notice.
3. **Graphics mode / clouds / shadows / view-bob** — discrete cosmetic cuts,
   switched during the chaos of a fight.
4. **Shader suspend (via Iris API)** — shaders are often the heaviest single
   cost; suspending them mid-fight can multiply frame rate, then they come back.
5. **FPS cap shaping** — optional, for thermal/latency stability.

> **Why "you don't notice it":** two mechanisms. (a) *Timing* — the visible cuts
> happen at the exact moment your attention is on the fight, not the foliage.
> (b) *Debouncing the expensive thing* — render-distance changes (which force
> chunk re-meshing, the most noticeable possible change) only commit after the
> context is stable and are rate-limited; lowering is allowed sooner than
> raising, because raising is the expensive remesh direction.

**Deliberately NOT done by FluxLogic** (left to dedicated mods, by design):
chunk meshing, occlusion culling internals, the lighting engine, the render
backend. When Sodium / a Vulkan renderer is present, FluxLogic detects it and
backs off any overlapping nudge entirely (`ModCompat`).

### Math/logic micro-optimizations
`FastMath` exists for the genuinely hot, per-frame smoothing/culling code:
branch-free clamps/lerps, a correct frame-rate-independent smoothing factor,
short-path angle wrapping, and squared-distance comparisons (never `sqrt` in
entity iteration). These are *real* but *modest* — we don't pretend to out-clever
the JIT on arithmetic it already vectorizes. They keep **our** added code cheap
rather than speeding up vanilla.

---

## 4. The Minecraft 26.2 / Vulkan situation

- **Versioning:** Minecraft adopted `year.drop.hotfix` in 2026. **26.2 "Chaos
  Cubed"** shipped 2026-06-16 and requires **Java 25**.
- **Renderer:** Mojang announced (Feb 2026) the **OpenGL → Vulkan** transition.
  A **toggle** between OpenGL and Vulkan is slated for **summer-2026 snapshots**
  (toward 26.3); OpenGL is removed only once Vulkan is stable.
- **Therefore:** on **26.2 stable**, the renderer is **OpenGL**. A "Vulkan
  preset" is the upcoming Mojang *snapshot* toggle — not a 26.2-release feature,
  and not the third-party VulkanMod.

**FluxLogic's stance:** be renderer-agnostic so the same jar is correct under
OpenGL today and under Mojang's Vulkan toggle later.

- The camera hook modifies **values** (yaw/pitch/Y) the engine then uses to build
  its view — it issues **no GL calls**, so it's renderer-independent.
- The adaptive presets touch **options**, not rendering — renderer-independent.
- Tactical highlight uses the engine's **glow flag** + its existing outline pass
  — renderer-independent.
- The **only** renderer-sensitive feature is the optional desaturation
  post-shader. It is **off by default**, loaded **best-effort via reflection**,
  and **auto-disabled** when a Vulkan renderer is detected, degrading to the
  glow-only highlight (which already makes threats pop strongly).

---

## 5. Module map

```
FluxLogicClient          entrypoint; registers tick + keybind, holds managers
  camera/InertiaController   the smooth-camera + mouse-filter math (pure, testable)
  mixin/client/CameraMixin   the ONE render hook: view smoothing (require=0)
  mixin/client/MouseInputMixin  input hook: mouse-jitter filter (require=0)
  mixin/client/MouseStutterMixin  input hook: click-drag de-stutter (require=0)
  input/MouseDeStutter       event-flood coalescer (pure gate core, testable)
  presets/
    GameContext              COMBAT/EXPLORATION/IDLE + pure resolver (testable)
    PerformancePreset        decoupled data model of the options we drive
    PresetManager            the adaptive engine (debounced, hysteretic)
    IrisBridge               reflective shader suspend/resume (soft dep)
  combat/
    CombatTracker            client-side combat detection + hostile cache
    TacticalVision           glow/colour highlighting (reversible)
    PostEffectBridge         reflective, best-effort desaturation (experimental)
  compat/ModCompat           detect neighbours, decide what to defer
  config/                    FluxConfig (POJO) + ConfigManager (Gson, hot-swap)
  gui/FluxConfigScreen       in-game settings (stable vanilla widgets)
  perf/FastMath              hot-path math helpers (pure, testable)
```

Design rule: **pure logic is isolated from Minecraft** (`FastMath`,
`GameContext`, `PerformancePreset`, most of `FluxConfig`) so it can be reasoned
about and unit-tested without the game, and the engine-touching surface is as
small as possible.

---

## 6. Risk management (shipping to an untested version)

This project targets 26.2 but was **not** compiled/launched against a live 26.2
client here (no Java 25 game environment). The architecture is built to make that
safe:

- **One render hook, and it's fail-safe.** `CameraMixin` uses `require = 0`: if
  Mojang renamed `Camera#setRotation`/`setPosition`, the injector just doesn't
  apply, camera smoothing turns itself off, and **the game still launches** with
  every other feature working.
- **Soft/optional integrations are reflective** (`IrisBridge`,
  `PostEffectBridge`): absent or changed APIs become silent no-ops, never crashes.
- **Everything else uses long-stable public APIs** (`Options.*`,
  `Entity#setGlowingTag`, `Scoreboard`, Fabric events) whose Mojang-mapped names
  have held for many versions.
- **Pure logic is compile-verified** here (FastMath, GameContext,
  PerformancePreset, FluxConfig compile under `javac`).

[BUILDING.md](BUILDING.md) lists the **exact handful of API touch-points to
eyeball** against the 26.2 sources in your IDE, with how to verify each.

# FluxLogic

A tiny client-side Fabric mod for **Minecraft 26.2** that fixes one bug:

> **The game stutters/lags when you move the mouse while holding left click.**

## The problem

High-polling-rate mice (1000–8000 Hz) fire a GLFW cursor event for every
hardware poll. Vanilla runs its full mouse-move handler for *every single
one* — including per-event screen-drag dispatch while a button is held. At
8000 Hz that's thousands of callback executions per frame: stutter.

## The fix

FluxLogic gates `MouseHandler#onMove`: the first event of each frame always
runs; extra events beyond a rate cap (default 1000/s, configurable) are
coalesced. **Zero input is lost** — Minecraft derives movement deltas from
absolute cursor positions, so the next processed event automatically folds in
everything skipped. Total aim distance is bit-identical; only the redundant
per-event work disappears.

All hooks are `require = 0`: if a future Minecraft drop renames a target
method, the mod silently degrades to vanilla behaviour instead of crashing.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) (0.19.3+) and drop
   [Fabric API](https://modrinth.com/mod/fabric-api) for 26.2 into
   `.minecraft/mods/`.
2. Build the jar (below) and drop `fluxlogic-<version>.jar` in `mods/` too.

## Build

Requires git and any JDK 17+ on PATH (Gradle auto-provisions the Java 25
toolchain the build itself needs).

```bash
git clone https://github.com/darkerapple/fluxlogic.git
cd fluxlogic
./gradlew build        # Windows: gradlew.bat build
# → build/libs/fluxlogic-<version>.jar
```

## Stutter Sleuth (built-in diagnostics)

FluxLogic also ships a hitch-forensics module (on by default). Every frame
slower than `sleuth.hitchThresholdMs` (default 45 ms) is dissected:

- **decomposition** — event-poll time vs in-game time vs **OUTSIDE time**
  (wall-clock where the game thread wasn't running at all → OS-level stall:
  paging, thermal throttling, compositor);
- **caught-in-the-act stack** of the render thread mid-hitch;
- **GC time/count** during the hitch, heap, direct buffers, OS free RAM and
  swap movement per window;
- a **CPU speed probe**: a fixed workload timed at launch and every 30 s —
  if `cpuProbe` climbs well above `1.00x of launch`, the CPU is being
  throttled.

Summaries go to the game log and `logs/fluxlogic-sleuth.txt` every
`sleuth.reportEverySeconds` (default 60 s). Toggle in the settings screen.

## Config

`config/fluxlogic.json`:

```jsonc
{
  "configVersion": 3,
  "input": {
    "stutterFix": true,        // the whole mod; turn off to get vanilla input
    "maxPollsPerSecond": 1000  // processed-event cap (125–8000)
  }
}
```

There's also a one-toggle settings screen: bind "Open FluxLogic Settings" in
Controls, or open it via Mod Menu if installed.

To confirm it's working, run with debug logging and watch for
`[FluxLogic] mouse de-stutter: processed N move events, coalesced M`.

## License

MIT.

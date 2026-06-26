# Building FluxLogic into a `.jar` — Mac guide

This is a complete, beginner-friendly walkthrough for compiling FluxLogic on
macOS (Apple Silicon **or** Intel). It also works on Linux/Windows with the
obvious path tweaks. At the end you'll have:

```
build/libs/fluxlogic-0.1.0.jar      ← the mod you drop in mods/
```

> **Why you have to build it:** the jar must be compiled against the actual
> Minecraft **26.2** classes (Java 25), which the Gradle/Loom toolchain downloads
> and decompiles on first build. That can't be pre-baked here. Good news: it's
> three commands once Java 25 is installed.

---

## 0. What you need

| Requirement | Version | Notes |
|---|---|---|
| **JDK** | **25** (exactly — 26.2 requires it) | Temurin/Adoptium recommended |
| Gradle | *none needed* | the repo's `./gradlew` wrapper fetches **9.5.1** |
| Internet | yes | first build downloads MC 26.2, mappings, Fabric, deps |
| Disk | ~2–3 GB free | Gradle caches + decompiled MC |
| RAM | 8 GB+ | the Gradle daemon is set to 3 GB heap |

You do **not** need to install Gradle or Minecraft separately. The wrapper and
Loom handle everything.

---

## 1. Install JDK 25 on macOS

Pick **one** method.

### Option A — Homebrew (simplest)
```bash
# Install Homebrew first if you don't have it: https://brew.sh
brew install --cask temurin@25
```

### Option B — SDKMAN! (easiest to manage multiple JDKs)
```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25-tem      # Temurin 25
sdk use java 25-tem
```

### Option C — Manual
Download the macOS JDK 25 `.pkg` (Apple Silicon = aarch64, Intel = x64) from
<https://adoptium.net/temurin/releases/?version=25> and run the installer.

### Verify
```bash
/usr/libexec/java_home -v 25     # prints the JDK 25 path
java -version                    # should say 25.x
```

If `java -version` doesn't say 25, point your shell at it:
```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
# add those two lines to ~/.zshrc to make it stick
```

---

## 2. Get the code

```bash
git clone https://github.com/darkerapple/fluxlogic.git
cd fluxlogic
# (or, if you already have this branch checked out, just cd into it)
```

---

## 3. Build the jar

```bash
./gradlew build
```

The **first** run is slow (several minutes): Loom downloads Minecraft 26.2,
fetches official Mojang mappings, decompiles the game, and pulls Fabric Loader +
Fabric API. Subsequent builds are fast (cached).

When it finishes:
```bash
ls -lh build/libs/
# fluxlogic-0.1.0.jar          ← THIS is your mod
# fluxlogic-0.1.0-sources.jar  ← source jar (optional, for other devs)
```

Copy `fluxlogic-0.1.0.jar` into your Minecraft instance's `mods/` folder (next to
Fabric API). Done.

> Make sure your launcher profile uses **JDK 25** to *run* the game too — 26.2
> won't launch on older Java.

---

## 4. Test it in a live dev client (recommended)

You don't need a Minecraft account configured for a quick smoke test of loading:

```bash
./gradlew runClient
```

This launches a development Minecraft 26.2 with FluxLogic already loaded. Make a
world, walk around (camera should feel smoother), spawn a few zombies and hit one
(hostiles should glow; settings should shift toward the combat preset), then walk
away (it relaxes back). Check the log for the line:

```
[FluxLogic] Initialised. Neighbours: Sodium=... Iris=... VulkanRenderer=... ModMenu=...
```

---

## 5. Develop in an IDE (IntelliJ IDEA — optional but nice)

1. Install **IntelliJ IDEA Community** (free).
2. *File → Open…* → select the `fluxlogic` folder. Let it import the Gradle
   project (it reads `build.gradle`).
3. Set the Project SDK to **JDK 25** (*File → Project Structure → SDK*).
4. Loom generates run configs automatically — pick **"Minecraft Client"** and hit
   Run.
5. To read the decompiled game (to verify the API touch-points in §7), use
   *Go to Class* (`⌘O`) and type a class like `Camera` or `Options`.

---

## 6. (Optional) Add Mod Menu integration

FluxLogic ships an in-game settings screen reachable via a **keybind** (bind it
under *Options → Controls → FluxLogic*). If you also want it in **Mod Menu**'s
mod list, add this:

**`build.gradle`** — add a Mod Menu dependency (use the build matching 26.2 from
<https://modrinth.com/mod/modmenu/versions>). Note the new no-remap Loom uses
plain `implementation`, not `modImplementation`:
```gradle
implementation "com.terraformersmc:modmenu:<VERSION_FOR_26_2>"
```

**`src/client/java/com/fluxlogic/gui/ModMenuIntegration.java`** — create:
```java
package com.fluxlogic.gui;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return FluxConfigScreen::new;
    }
}
```

**`src/main/resources/fabric.mod.json`** — add the entrypoint back:
```json
"entrypoints": {
  "client": [ "com.fluxlogic.FluxLogicClient" ],
  "modmenu": [ "com.fluxlogic.gui.ModMenuIntegration" ]
}
```

(It's left out by default so a fresh checkout builds without pinning an exact Mod
Menu version.)

---

## 7. ⚠️ Verify these API touch-points against 26.2

This project targets 26.2 but wasn't compiled against a live 26.2 game in the
environment that generated it. The code uses long-stable Mojang-mapped names, but
**before trusting the jar, open the 26.2 sources in your IDE** (§5) and confirm
the items below. Each is a one-line fix if a name drifted.

| Where | What to confirm | If it changed |
|---|---|---|
| `mixin/client/CameraMixin.java` | `Camera#setRotation(float,float)` and `Camera#setPosition(double,double,double)` still exist | update the `method=` descriptors. (Uses `require=0`, so a mismatch just disables camera smoothing — it won't crash.) |
| `presets/PresetManager.java` | `Options` accessors: `renderDistance()`, `simulationDistance()`, `entityDistanceScaling()`, `graphicsMode()`, `particles()`, `cloudStatus()`, `entityShadows()`, `bobView()`, `framerateLimit()` and the enums `GraphicsStatus`/`ParticleStatus`/`CloudStatus` | adjust the accessor/enum names |
| `combat/CombatTracker.java` | `Minecraft#crosshairPickEntity`, `Options#keyAttack`, `Mob`, `Enemy` marker, `Level#getEntitiesOfClass(...)` | adjust field/method names |
| `combat/TacticalVision.java` | `Entity#setGlowingTag(boolean)`, `Scoreboard` (`getPlayerTeam`, `addPlayerTeam`, `getPlayersTeam`, `addPlayerToTeam`), `PlayerTeam#setColor` | adjust names |
| `combat/PostEffectBridge.java` | (reflective — no compile dependency) the post-effect method names tried | add the 26.2 name to the candidate array if desaturation doesn't engage |
| `gui/FluxConfigScreen.java` | `CycleButton`/`Button` builders, `GuiGraphics#drawCenteredString`, `Util.getPlatform().openUri(URI)` | adjust to the current widget signatures |
| `fluxlogic.client.mixins.json` | `compatibilityLevel: "JAVA_25"` accepted by the bundled Mixin | if Mixin rejects it, set it to the highest level it lists |

> Tip: build once (`./gradlew build`). The compiler will point at the exact line
> of anything that needs adjusting — usually zero or one or two small renames,
> since these APIs rarely move.

---

## 8. Troubleshooting

**`Unsupported class file major version` / toolchain errors**
You're not on JDK 25. Re-check §1; set `JAVA_HOME` to the 25 path. You can also
force the toolchain auto-download by adding to `gradle.properties`:
`org.gradle.java.installations.auto-download=true`.

**First build is downloading forever / network errors**
Loom is fetching MC + mappings. On a flaky connection, just re-run `./gradlew
build` — it resumes from cache. Behind a proxy, set `HTTPS_PROXY` and Gradle's
`systemProp.https.proxyHost/Port`.

**`Failed to find official mojang mappings for 26.2`**
You have a `mappings loom.officialMojangMappings()` line in `build.gradle`'s
dependencies. **Remove it.** Minecraft 26.x uses the new no-remap Loom flow
(plugin id `net.fabricmc.fabric-loom`) and develops directly against Mojang's
names, so there is **no `mappings` dependency at all** — adding one causes this
error. (This is already fixed in the committed `build.gradle`.)

**`Could not resolve fabric-api 0.152.2+26.2`**
That exact build wasn't found. Check the current 26.2 Fabric API version at
<https://modrinth.com/mod/fabric-api/versions> and update `fabric_api_version`
in `gradle.properties`. Same idea for `loader_version` and `loom_version`.

**Mixin "could not find method setRotation"**
Expected if Mojang renamed it; it's `require=0` so it's a warning, not a crash.
Update the descriptor per §7 to re-enable camera smoothing.

**Game launches but a feature does nothing**
Check `config/fluxlogic.json` (is the feature `enabled`?) and the log for
`[FluxLogic]` lines. The desaturation post-shader is *off by default* and
experimental — glow highlighting works regardless.

**Apple Silicon native crash on launch**
Make sure you're using an **aarch64** JDK 25 (not x64 under Rosetta) and an
ARM-native launcher; LWJGL needs matching natives.

---

## 9. One-shot recap

```bash
brew install --cask temurin@25
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
git clone https://github.com/darkerapple/fluxlogic.git && cd fluxlogic
./gradlew build          # → build/libs/fluxlogic-0.1.0.jar
./gradlew runClient      # optional: launch a dev client to test
```

That's it. Drop the jar into `mods/` (with Fabric API), and ideally Sodium + Iris
alongside it.

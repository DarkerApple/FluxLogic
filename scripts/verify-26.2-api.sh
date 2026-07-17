#!/usr/bin/env bash
#
# verify-26.2-api.sh — dump the exact Minecraft 26.2 signatures FluxLogic depends
# on, straight from the unobfuscated jar Loom downloaded into its cache.
#
# Minecraft 26.x ships *unobfuscated*, so the jar already has real Mojang names.
# This script (1) LOCATES classes by simple name — catching classes that moved
# packages between 1.21-era names and 26.2 — and (2) prints the members
# FluxLogic calls, so any drifted signature is obvious.
#
# USAGE:
#   1. Run a build first so Loom downloads 26.2:  ./gradlew build
#      (it's fine if compilation fails — we just need the jar cached.)
#   2. ./scripts/verify-26.2-api.sh
#   3. Paste the FULL output back to the FluxLogic author / assistant.
#
set -uo pipefail

echo "== Locating the unobfuscated Minecraft 26.2 jar in caches =="
CANDIDATES=$(
  { find "${HOME}/.gradle/caches" -type f \( -name '*minecraft*26.2*.jar' -o -name '*26.2*minecraft*.jar' \) 2>/dev/null
    find "$(pwd)/.gradle" -type f -name '*minecraft*.jar' 2>/dev/null
  } | grep -vi 'sources' | sort -u || true
)

if [ -z "${CANDIDATES}" ]; then
  echo "!! Could not find a cached 26.2 minecraft jar."
  echo "   Run './gradlew build' once (so Loom downloads it), then re-run this."
  exit 1
fi

JAR=$(echo "${CANDIDATES}" | head -n1)
echo "Using: ${JAR}"
echo

LISTING=$(jar tf "${JAR}" 2>/dev/null || unzip -Z1 "${JAR}" 2>/dev/null)

# ---------------------------------------------------------------------------
# Step 1: locate classes by SIMPLE NAME (they may have moved packages in 26.x)
# ---------------------------------------------------------------------------
echo "================================================================"
echo "## STEP 1 — where do these classes live in 26.2?"
echo "================================================================"
for SIMPLE in ResourceLocation Identifier GuiGraphics DrawContext GraphicsStatus \
              ParticleStatus CloudStatus TeamColor KeyMapping CycleButton Util \
              MouseHandler ChatFormatting PlayerTeam Screen; do
  HITS=$(echo "${LISTING}" | grep -E "/${SIMPLE}\.class$|^${SIMPLE}\.class$" | sed 's/\.class$//; s#/#.#g')
  if [ -n "${HITS}" ]; then
    echo "${SIMPLE}:"
    echo "${HITS}" | sed 's/^/    /'
  else
    echo "${SIMPLE}:    (no class with this simple name — renamed?)"
  fi
done
echo

# ---------------------------------------------------------------------------
# Step 2: dump the members FluxLogic touches. fqcn<TAB>member-regex
#   Class names resolved dynamically from step 1 where they may have moved:
# ---------------------------------------------------------------------------
locate_one() { # simple name -> first fqcn match (prefer net.minecraft over com.mojang)
  echo "${LISTING}" | grep -E "/${1}\.class$" | sed 's/\.class$//; s#/#.#g' \
    | sort | grep -m1 '^net\.minecraft' || \
  echo "${LISTING}" | grep -E "/${1}\.class$" | sed 's/\.class$//; s#/#.#g' | head -n1
}

RL_FQCN=$(locate_one ResourceLocation); [ -z "${RL_FQCN}" ] && RL_FQCN=$(locate_one Identifier)
GG_FQCN=$(locate_one GuiGraphics);      [ -z "${GG_FQCN}" ] && GG_FQCN=$(locate_one DrawContext)
UTIL_FQCN=$(echo "${LISTING}" | grep -E "/Util\.class$" | sed 's/\.class$//; s#/#.#g' | grep '^net\.minecraft' | head -n1)

dump() { # fqcn, regex
  local CLASS="$1" REGEX="$2"
  echo "================================================================"
  echo "## ${CLASS}"
  echo "================================================================"
  if [ -z "${CLASS}" ]; then echo "  !! class not located in step 1"; echo; return; fi
  if javap -p -classpath "${JAR}" "${CLASS}" >/tmp/flux_javap.txt 2>/dev/null; then
    grep -E "${REGEX}" /tmp/flux_javap.txt || echo "  (no members matched '${REGEX}')"
  else
    echo "  !! javap failed for ${CLASS}"
  fi
  echo
}

dump "${RL_FQCN}"                                  "static|toString"
dump "${GG_FQCN}"                                  "drawCenteredString|drawString"
dump "${UTIL_FQCN}"                                "getPlatform|[Pp]latform"
dump "net.minecraft.client.Minecraft"              "[Ss]creen|gameRenderer|isPaused|options|player|level"
dump "net.minecraft.client.Options"                "renderDistance|simulationDistance|entityDistanceScaling|graphics|particles|cloudStatus|entityShadows|bobView|framerateLimit|keyAttack"
dump "net.minecraft.client.MouseHandler"           "onMove|turnPlayer|handleAccumulatedMovement|xpos|ypos"
dump "$(locate_one KeyMapping)"                    "KeyMapping\(|Category"
dump "$(locate_one CycleButton)"                   "builder|onOffBuilder|create\(|withValues|withInitialValue"
dump "$(locate_one PlayerTeam)"                    "setColor|setSeeFriendlyInvisibles|getName"
dump "$(locate_one TeamColor)"                     "."
dump "$(locate_one ChatFormatting)"                "getColor|color|values"
dump "$(locate_one GraphicsStatus)"                "FAST|FANCY|FABULOUS"
dump "$(locate_one ParticleStatus)"                "MINIMAL|DECREASED|ALL"
dump "net.minecraft.world.scores.Scoreboard"       "getPlayerTeam|addPlayerTeam|getPlayersTeam|addPlayerToTeam"
dump "net.minecraft.world.entity.Entity"           "(^|[^a-zA-Z])turn\(|setGlowingTag|getScoreboardName|getType\(|distanceToSqr|getBoundingBox"
dump "net.minecraft.client.gui.screens.Screen"     "render\(|init\(|onClose|addRenderableWidget|minecraft"
dump "net.minecraft.client.KeyMapping"             "consumeClick|Category"
dump "net.minecraft.client.renderer.GameRenderer"  "[Ee]ffect|[Pp]ostChain|[Pp]ostProcess"

# ---------------------------------------------------------------------------
# Step 3: where did Fabric API's keybinding helper go?
# ---------------------------------------------------------------------------
echo "================================================================"
echo "## STEP 3 — Fabric API keybinding module classes in the cache"
echo "================================================================"
find "${HOME}/.gradle/caches" -name 'fabric-key-binding*.jar' -o -name 'fabric-keybinding*.jar' 2>/dev/null \
  | grep -vi sources | head -n5 | while read -r FJ; do
    echo "-- ${FJ}"
    (jar tf "${FJ}" 2>/dev/null || unzip -Z1 "${FJ}" 2>/dev/null) | grep -E 'KeyBinding.*\.class$' | sed 's/^/    /'
done
FAPI=$(find "${HOME}/.gradle/caches" -path '*fabric-api*' -name '*.jar' 2>/dev/null | grep -vi sources | head -n1)
if [ -n "${FAPI:-}" ]; then
  echo "-- ${FAPI}"
  (jar tf "${FAPI}" 2>/dev/null || unzip -Z1 "${FAPI}" 2>/dev/null) | grep -iE 'keybinding.*helper.*\.class$' | sed 's/^/    /'
fi

echo
echo "Done. Paste everything above (from the first '==' line) back for signature lock-in."

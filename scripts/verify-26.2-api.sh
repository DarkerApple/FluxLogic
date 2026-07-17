#!/usr/bin/env bash
#
# verify-26.2-api.sh — dump the exact Minecraft 26.2 signatures FluxLogic
# depends on, straight from the unobfuscated jar Loom downloaded into its
# cache. 26.x ships unobfuscated, so the jar already has real Mojang names.
#
# USAGE:
#   1. Run a build first so Loom downloads 26.2:  ./gradlew build
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
  echo "!! Could not find a cached 26.2 minecraft jar. Run './gradlew build' once, then re-run."
  exit 1
fi
JAR=$(echo "${CANDIDATES}" | head -n1)
echo "Using: ${JAR}"
echo

dump() { # fqcn, regex
  local CLASS="$1" REGEX="$2"
  echo "================================================================"
  echo "## ${CLASS}"
  echo "================================================================"
  if javap -p -classpath "${JAR}" "${CLASS}" >/tmp/flux_javap.txt 2>/dev/null; then
    grep -E "${REGEX}" /tmp/flux_javap.txt || echo "  (no members matched '${REGEX}')"
  else
    echo "  !! class not found — it may have moved/renamed"
  fi
  echo
}

# Everything the stutter fix and its tiny settings screen touch:
dump "net.minecraft.client.MouseHandler"              "onMove|turnPlayer|handleAccumulatedMovement|xpos|ypos"
dump "net.minecraft.client.Minecraft"                 "gui|player|level"
dump "net.minecraft.client.gui.Gui"                   "screen|setScreen"
dump "net.minecraft.client.KeyMapping"                "KeyMapping\(|Category|consumeClick"
dump "net.minecraft.client.gui.components.CycleButton" "onOffBuilder|create\("
dump "net.minecraft.client.gui.components.Button"     "builder|bounds|build"
dump "net.minecraft.client.gui.screens.Screen"        "extractRenderState|init\(|onClose|addRenderableWidget|minecraft|font"
dump "net.minecraft.client.gui.GuiGraphicsExtractor"  "centeredText|text\("
dump "net.minecraft.resources.Identifier"             "fromNamespaceAndPath|toString"
dump "net.minecraft.util.Util"                        "getPlatform"
dump "net.minecraft.client.GameNarrator"              "Narrator|narrator"
dump "com.mojang.text2speech.Narrator"                "."

echo "Done. Paste everything above (from the first '==' line) back for signature lock-in."

#!/usr/bin/env bash
#
# verify-26.2-api.sh — dump the exact Minecraft 26.2 signatures FluxLogic depends
# on, straight from the unobfuscated jar Loom downloaded into its cache.
#
# Minecraft 26.x ships *unobfuscated*, so the jar already has real Mojang names.
# This script finds that jar and prints just the methods/fields FluxLogic calls,
# so any signature that drifted from what the source assumes is obvious.
#
# USAGE:
#   1. Run a build first so Loom downloads 26.2:  ./gradlew build --refresh-dependencies
#      (it's fine if compilation fails afterwards — we just need the jar cached.)
#   2. ./scripts/verify-26.2-api.sh
#   3. Paste the output back to the FluxLogic author / assistant.
#
set -euo pipefail

echo "== Locating the unobfuscated Minecraft 26.2 jar in caches =="
# Search the usual Loom/Gradle cache spots plus the project for a 26.2 minecraft jar.
CANDIDATES=$(
  { find "${HOME}/.gradle/caches" -type f \( -name '*minecraft*26.2*.jar' -o -name '*26.2*minecraft*.jar' \) 2>/dev/null
    find "$(pwd)/.gradle" -type f -name '*minecraft*.jar' 2>/dev/null
  } | grep -vi 'sources' | sort -u || true
)

if [ -z "${CANDIDATES}" ]; then
  echo "!! Could not find a cached 26.2 minecraft jar."
  echo "   Run './gradlew build --refresh-dependencies' once (so Loom downloads it), then re-run this."
  exit 1
fi

JAR=$(echo "${CANDIDATES}" | head -n1)
echo "Using: ${JAR}"
echo

# class<TAB>regex-of-members-to-show
TARGETS=$(cat <<'EOF'
net.minecraft.client.Camera	setRotation|setPosition
net.minecraft.world.entity.Entity	(^|[^a-zA-Z])turn\(|setGlowingTag|getScoreboardName|getType\(|distanceToSqr|getBoundingBox
net.minecraft.client.Options	renderDistance|simulationDistance|entityDistanceScaling|graphicsMode|particles|cloudStatus|entityShadows|bobView|framerateLimit|keyAttack
net.minecraft.client.Minecraft	crosshairPickEntity|gameRenderer|isPaused|setScreen|screen;|options;|player;|level;
net.minecraft.world.scores.Scoreboard	getPlayerTeam|addPlayerTeam|getPlayersTeam|addPlayerToTeam
net.minecraft.world.scores.PlayerTeam	setColor|setSeeFriendlyInvisibles|getName
net.minecraft.client.gui.GuiGraphics	drawCenteredString
net.minecraft.client.gui.components.CycleButton	builder|onOffBuilder|create\(
net.minecraft.Util	getPlatform
net.minecraft.client.renderer.GameRenderer	[Ee]ffect|[Pp]ostChain|[Pp]ostProcess
net.minecraft.core.registries.BuiltInRegistries	ENTITY_TYPE
EOF
)

while IFS=$'\t' read -r CLASS REGEX; do
  [ -z "${CLASS}" ] && continue
  echo "================================================================"
  echo "## ${CLASS}"
  echo "================================================================"
  if javap -p -classpath "${JAR}" "${CLASS}" >/tmp/flux_javap.txt 2>/dev/null; then
    grep -E "${REGEX}" /tmp/flux_javap.txt || echo "  (no members matched '${REGEX}' — class exists, name(s) may have changed)"
  else
    echo "  !! class not found under this name — it may have moved/renamed in 26.2"
  fi
  echo
done <<< "${TARGETS}"

echo "Done. Paste everything above (from the first '==' line) back for signature lock-in."

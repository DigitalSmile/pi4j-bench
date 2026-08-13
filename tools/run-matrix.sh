#!/usr/bin/env bash
# One line per machine: pin the CPU, then run the whole matrix.
#   tools/run-matrix.sh                # lane=mock (amd64 dev box)
#   tools/run-matrix.sh --lane hw      # on the boards, real peripherals wired
set -euo pipefail

LANE=mock
[[ "${1:-}" == "--lane" ]] && LANE="${2:?}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# TODO(Phase 0): sudo "$HERE/tools/pin-env.sh"   # governor=performance, turbo off
# Forked JMH JVMs inherit taskset affinity from the Gradle launcher.
exec taskset -c 2,3 "$HERE/gradlew" --no-daemon benchAll -Plane="$LANE"

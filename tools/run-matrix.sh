#!/usr/bin/env bash
# One line per machine: quiet the box, then run the whole matrix via the Java orchestrator.
#   tools/run-matrix.sh                     # lane=mock (amd64 dev box)
#   tools/run-matrix.sh --lane hw           # on the boards, real peripherals wired
#   tools/run-matrix.sh --lane mock --quick # smoke test
# Everything after the script name is forwarded verbatim to :bench-runner (see Config.java).
# CPU pinning now happens per measurement fork inside the runner (--cpus), so we no longer
# wrap ./gradlew in taskset — only the forked JVMs land on the isolated cores.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ISOL_CPUS="${ISOL_CPUS:-2,3}"

# Quiet the machine (best-effort; needs root — skipped with a note if unavailable).
if [[ "$(id -u)" -eq 0 ]]; then
	ISOL_CPUS="$ISOL_CPUS" "$HERE/tools/pin-env.sh"
elif command -v sudo >/dev/null 2>&1; then
	sudo ISOL_CPUS="$ISOL_CPUS" "$HERE/tools/pin-env.sh" || echo "run-matrix: pin-env failed (continuing unpinned)" >&2
else
	echo "run-matrix: not root and no sudo — skipping pin-env (numbers will be noisier)" >&2
fi

# Gradle uses the current working directory as the project dir, so run from the repo root.
cd "$HERE"
exec ./gradlew --no-daemon :bench-runner:run --args="--cpus $ISOL_CPUS $*"

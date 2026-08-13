#!/usr/bin/env bash
# Build Pi4J V4 (FFM) 5.0.0-SNAPSHOT into the local Maven repo so the bench-v4
# lane can resolve it. The FFM plugin is NOT released to Maven Central (only the
# 4.0.x line is); the suite the plan targets lives on the upstream `main` branch.
#
#   tools/build-pi4j-v4.sh [ref]     # ref defaults to `main`
#
# Requires: git, a JDK 25 on PATH/JAVA_HOME (FFM downcalls need it at runtime too).
set -euo pipefail

REF="${1:-main}"
SRC="${PI4J_SRC:-/mnt/ds/pi4j-upstream}"
REPO="https://github.com/Pi4J/pi4j.git"

if [[ ! -d "$SRC/.git" ]]; then
    git clone "$REPO" "$SRC"
fi
git -C "$SRC" fetch --depth 1 origin "$REF"
git -C "$SRC" checkout -q "$REF"

# Only the modules bench-v4 depends on; -am pulls pi4j-core. skipTests keeps it
# fast and avoids the mock-kernel-driver requirement (that's the bench's job).
"$SRC/mvnw" -B -DskipTests -pl pi4j-core,plugins/pi4j-plugin-ffm -am -f "$SRC/pom.xml" install

echo "Installed to mavenLocal:"
find ~/.m2/repository/com/pi4j -name 'pi4j-*-5.0.0-SNAPSHOT.jar' -not -name '*-sources.jar' -not -name '*-javadoc.jar'

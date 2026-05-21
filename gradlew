#!/bin/sh
# Minimal Gradle wrapper launcher placeholder. Run `gradle wrapper --gradle-version 8.5`
# inside this directory once to materialise the actual wrapper jar + script.
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ ! -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "[quickauth-sdk-android] gradle-wrapper.jar missing." >&2
  echo "Run: gradle wrapper --gradle-version 8.5" >&2
  exit 1
fi
exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"

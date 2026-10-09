#!/usr/bin/env bash
# Regenerate resources/clojurecraft/{blocks,items,packets}.edn from the vanilla 26.1.2 server's
# --reports output. Needs java 25 on PATH (or $JAVA) and the clojure CLI.
#
#   scripts/datagen.sh            # download jar (sha1-checked) → run reports → write EDN
set -eu
DIR=$(cd "$(dirname "$0")/.." && pwd)
JAR_URL=https://piston-data.mojang.com/v1/objects/97ccd4c0ed3f81bbb7bfacddd1090b0c56f9bc51/server.jar
JAR_SHA1=97ccd4c0ed3f81bbb7bfacddd1090b0c56f9bc51
JAR=$DIR/data/local-server/server-26.1.2.jar
REPORTS=$DIR/data/reports
JAVA=${JAVA:-java}

mkdir -p "$DIR/data/local-server"
if [ ! -f "$JAR" ] || [ "$(shasum -a 1 "$JAR" | cut -d' ' -f1)" != "$JAR_SHA1" ]; then
  echo "downloading server.jar"
  curl -fsSL -o "$JAR.tmp" "$JAR_URL"
  [ "$(shasum -a 1 "$JAR.tmp" | cut -d' ' -f1)" = "$JAR_SHA1" ] || { echo "server.jar sha1 mismatch" >&2; exit 1; }
  mv "$JAR.tmp" "$JAR"
fi
if [ ! -f "$REPORTS/reports/blocks.json" ]; then
  echo "running vanilla data generator"
  mkdir -p "$REPORTS"
  (cd "$REPORTS" && "$JAVA" -DbundlerMainClass=net.minecraft.data.Main -jar "$JAR" --reports --output "$REPORTS")
fi
ls -la "$REPORTS/reports"
cd "$DIR" && clojure -M:datagen "$REPORTS/reports" "$DIR/resources/clojurecraft"

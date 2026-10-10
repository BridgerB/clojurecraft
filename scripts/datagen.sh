#!/usr/bin/env bash
# Regenerate every generated table in resources/clojurecraft/ from the vanilla 26.1.2 server:
# the --reports output (blocks, items, packets, entity types), the inner jar's data files
# (version, recipes, item tags, harvest), and, by reflection against the game's own classes
# (clojurecraft.jar-probe), block hardness and tool materials. Needs java 25 on PATH (or
# $JAVA) and the clojure CLI.
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
# The game's classes, booted in their own JVM: the inner jar plus the libraries the bundler ships.
INNER=$REPORTS/inner.jar
LIBS=$REPORTS/libs
if [ ! -f "$INNER" ]; then
  unzip -p "$JAR" "META-INF/versions/26.1.2/server-26.1.2.jar" > "$INNER"
  mkdir -p "$LIBS" && unzip -q -o "$JAR" 'META-INF/libraries/*' -d "$LIBS"
fi
cd "$DIR"
LIBCP=$(find "$LIBS" -name '*.jar' | tr '\n' ':')
"$JAVA" -cp "$INNER:$LIBCP$(clojure -A:datagen -Spath)" clojure.main -m clojurecraft.jar-probe "$REPORTS/probe.json" 2>&1 | grep -v '^\[\|^SLF4J\|^WARNING'
clojure -M:datagen "$REPORTS/reports" "$DIR/resources/clojurecraft" "$JAR" "$REPORTS/probe.json"

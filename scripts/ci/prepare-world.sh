#!/usr/bin/env bash
# Build one gym landing set's world, once: boot a fresh server, find and pregenerate the set's
# landings (clojure -M:gym landings), flush to disk, insist no forceload is left behind, stop,
# and leave the world in cache/worlds/$SET with its landings.edn, ready for actions/cache/save.
#
#   SET=A RCON_PASS=secret scripts/ci/prepare-world.sh
set -euo pipefail
SET=${SET:?set SET}
RCON_PASS=${RCON_PASS:?set RCON_PASS}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export WORLD=$ROOT/cache/worlds/$SET PORT=25565 RCON_PORT=25575 RCON_PASS HEAP_MB=${HEAP_MB:-6144}
rcon() { clojure -M:rcon --host 127.0.0.1 --port "$RCON_PORT" --pass "$RCON_PASS" "$@"; }

rm -rf "$WORLD"
mkdir -p "$WORLD"
"$ROOT/scripts/server.sh" start
trap '"$ROOT/scripts/server.sh" stop' EXIT

clojure -M:gym landings --set "$SET" --plan "$ROOT/ci/gym.edn" --rcon-port "$RCON_PORT" --rcon-pass "$RCON_PASS" \
  --out "$WORLD/landings.edn"
echo "landings: $(cat "$WORLD/landings.edn")"
dupes=$(grep -o '\[[-0-9]* [-0-9]* [-0-9]*\]' "$WORLD/landings.edn" | sort | uniq -d | wc -l)
[ "$dupes" -eq 0 ] || { echo "landings repeat a spot" >&2; exit 1; }
rcon save-all flush
rcon forceload query | tee "$ROOT/forceload.txt"
grep -q 'No force loaded' "$ROOT/forceload.txt" || { echo "a forceload was left behind" >&2; exit 1; }

"$ROOT/scripts/server.sh" stop
trap - EXIT
# what a shard regenerates on start, and logs that would only bloat the cache
rm -rf "$WORLD/server.properties" "$WORLD/server.pid" "$WORLD/server.log" "$WORLD/eula.txt" "$WORLD/logs"
du -sh "$WORLD"

#!/usr/bin/env bash
# dev/viewer/loop.sh: one bot plays the whole chain from spawn on a fresh local world, served by
# --viewer on 4600; when its run ends (win, death that ends it, or the timeout) the world is moved
# aside and a new one starts. Run from the repo root inside `nix develop`. Ctrl-C stops it.
set -u
cd "$(dirname "$0")/../.."
mkdir -p data/viewer-runs
n=0
while true; do
  n=$((n + 1))
  stamp=$(date +%Y%m%d-%H%M%S)
  echo "$(date +%T) run $n: fresh world"
  bb local-server stop || true
  [ -d data/local-world ] && mv data/local-world "data/viewer-runs/world-$stamp"
  # keep the last three old worlds
  ls -dt data/viewer-runs/world-* 2>/dev/null | tail -n +4 | xargs rm -rf
  LEVEL_SEED="$RANDOM$RANDOM" DIFFICULTY=normal bb local-server start
  until=$(clojure -M -e "(require '[clojurecraft.plan :as plan]) (println (plan/current-until))")
  echo "$(date +%T) run $n: Clj from spawn until $until"
  clojure -M:run --port 25571 --name Clj --until "$until" --chain true --timeout-ms 19800000 \
    --record "data/viewer-runs/run-$stamp.edn" --viewer 4600 > "data/viewer-runs/bot-$stamp.log" 2>&1
  echo "$(date +%T) run $n ended: $(grep -m1 '^RESULT' "data/viewer-runs/bot-$stamp.log" | cut -c1-200)"
done

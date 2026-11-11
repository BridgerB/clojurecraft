#!/usr/bin/env bash
for r in Clj_r40 Clj_r02; do
  zstd -dqf "probe-data/$r.edn.zst" -o "out/$r.edn"
  echo "== $r"; clojure -J-Xmx3g -M probe-data/cost.clj "out/$r.edn" 2>&1 | tail -20
  rm -f "out/$r.edn"
done

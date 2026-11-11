#!/usr/bin/env bash
# The bot's own CPU per event: two lane-2 recordings (race 42922583203, 10 bots, 5d894db14)
# replayed with no server under JFR. Everything under out/.
set -x
for r in Clj_r40 Clj_r02; do
  zstd -dqf "probe-data/$r.edn.zst" -o "out/$r.edn"
  echo "$r events $(grep -c . out/$r.edn) ticks $(grep -c ':kind :tick' out/$r.edn) packets $(grep -c ':kind :packet' out/$r.edn)"
  s=$(date +%s%N)
  clojure -J-Xmx2g "-J-XX:StartFlightRecording=filename=out/$r.jfr,settings=profile" -M:replay "out/$r.edn" | tail -c 600
  e=$(date +%s%N)
  echo "$r replay wall-ms $(( (e - s) / 1000000 ))"
  jfr view --width 200 hot-methods "out/$r.jfr" | head -60
  jfr print --events jdk.ExecutionSample --stack-depth 64 "out/$r.jfr" | gzip > "out/$r-samples.txt.gz"
  rm -f "out/$r.edn"
done

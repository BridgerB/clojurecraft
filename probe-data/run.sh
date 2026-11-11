#!/usr/bin/env bash
zstd -dqf probe-data/Clj_r51.edn.zst -o out/r51.edn
for t in 1794404400000 1794404500000 1794407900000; do
  echo "######## at $t"
  clojure -M:dev -m clojurecraft.why out/r51.edn "$t" 2>&1 | sed -n '/ANSWERS/,$p' | grep -E ":choose|:heading/|:exit/|:fortress-escaping|:travel/(in-forest|nearest-forest|warped-chunks|near-fortress|travel |explore )|:dig/digs-left|:relocate/last-intents" 
done
echo "######## legs"
clojure -M:dev probe-data/legs.clj out/r51.edn 2>&1 | tail -150
rm -f out/r51.edn

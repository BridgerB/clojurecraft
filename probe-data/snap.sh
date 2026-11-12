#!/usr/bin/env bash
# Clj_r51 (race 43003539322, lane 3, recorded at 7f4d162) as a real snapshot at its own commit, kept
# only when it recurs; then judged on the fix's base (probe/heading-base) and on the fix.
set -x
R=$PWD
git fetch -q origin probe/heading-base fix/263-heading-floor
clojure -M:simrace --from-recording probe-data/Clj_r51.edn.zst --name real-3-s1-Clj_r51.edn.gz --out out/real --round 43003539322 2>&1 | grep -E "^(REAL|GAP|STORY)" | cut -c1-400 | head -80
ls -la out/real out/real/gaps 2>/dev/null
S="$R/out/real/real-3-s1-Clj_r51.edn.gz"
[ -f "$S" ] || { echo "NO SNAPSHOT: it did not recur"; exit 0; }
git worktree add -q ../base origin/probe/heading-base
git worktree add -q ../fix origin/fix/263-heading-floor
echo "== judge on base $(git -C ../base rev-parse --short HEAD)"
(cd ../base && clojure -M:simrace --judge "$S" --expect recur 2>&1 | grep -E "^(JUDGE|UNEXPECTED|STORY)" | cut -c1-400 | head -80)
echo "== judge on fix $(git -C ../fix rev-parse --short HEAD)"
(cd ../fix && clojure -M:simrace --judge "$S" --expect pass 2>&1 | grep -E "^(JUDGE|UNEXPECTED|STORY)" | cut -c1-400 | head -80)

#!/usr/bin/env bash
# How long a real conversion takes on a busy runner (feat/evidence): race 38962774798 lane 8's
# recordings folded at their own commit, beside <busy> CPU-bound loops at nice 0 (a race lane's
# servers and bots stand in), the conversion at nice <nice>.
#   probe/evidence/fold.sh <nice> <busy> s1/Clj_r04 s1/Clj_r08 ...
set -u
level=$1; busy=$2; shift 2
sha=7c262ffaaf48e2f520ff04876aa8c9bd3241c444
mkdir -p out ev
echo "nproc $(nproc)"; free -m
gh run download 38962774798 --repo BridgerB/clojurecraft -n "race-$sha-8" -D ev
git fetch -q --depth 1 origin "$sha"
git worktree add -q ../at "$sha"
(cd ../at && clojure -P -M:simrace)
paths=""; names=""
for b in "$@"; do
  mkdir -p "../at/rec/$(dirname "$b")"
  zstd -q -d "ev/$b.edn.zst" -o "../at/rec/$b.edn"
  ls -la "../at/rec/$b.edn"
  paths="$paths,rec/$b.edn"; names="$names,real-$(echo "$b" | tr / -).edn.gz"
done
pids=""
for _ in $(seq 1 "$busy"); do (while :; do :; done) & pids="$pids $!"; done
echo "busy loops: $busy; conversion at nice $level"
t0=$(date +%s)
(cd ../at && nice -n "$level" clojure -J-Xmx3g -M:simrace --from-recording "${paths#,}" --name "${names#,}" --out "$OLDPWD/out/snaps" --round 38962774798) | grep -E '^(REAL|GAP)'
echo "WALL $(( $(date +%s) - t0 )) s at nice $level beside $busy busy loops"
# shellcheck disable=SC2086
kill $pids 2>/dev/null || true

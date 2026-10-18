#!/usr/bin/env bash
# Triage: each issue's newest snapshots (bb issue-check fetch) judged on this commit (main).
# Prints one TRIAGE line an issue: how many of its snapshots still recur. Logs under out/.
set -u
mkdir -p out
for n in "$@"; do
  fetched=$(bb issue-check fetch "$n" 2>&1)
  echo "$fetched" > "out/fetch-$n.log"
  files=$(echo "$fetched" | sed -n 's/^fetched //p' | paste -sd, -)
  if [ -z "$files" ]; then echo "TRIAGE $n none: no snapshot fetched"; continue; fi
  clojure -M:simrace --judge "$files" > "out/judge-$n.log" 2>&1
  tot=$(grep -c '^JUDGE ' "out/judge-$n.log")
  rec=$(grep '^JUDGE ' "out/judge-$n.log" | grep -c ':recurs? true')
  sig=$(grep -m1 '^JUDGE ' "out/judge-$n.log" | grep -oE ':signature \[[^]]*\]')
  echo "TRIAGE $n recurs $rec/$tot $sig"
  git checkout -q -- "test/issues/$n" 2>/dev/null || true
  git clean -qfd -- "test/issues/$n"
done

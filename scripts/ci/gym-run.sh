#!/usr/bin/env bash
# One gym run on a running server: the fixture (clojure -M:gym land) piped into the bot, then the
# judge (clojure -M:gym judge) while the bot holds. A run that loses its connection says nothing
# about the goal, so it is run once more on the same landing before the result is kept.
#
#   GOAL=wood RUN=3 BOT=Clj_g3 UNTIL=wood TIMEOUT_MS=150000 LANDINGS=f.edn RCON_PASS=s \
#   STRICT=false COMMIT=sha scripts/ci/gym-run.sh
set -uo pipefail
: "${GOAL:?}" "${RUN:?}" "${BOT:?}" "${UNTIL:?}" "${TIMEOUT_MS:?}" "${LANDINGS:?}" "${RCON_PASS:?}"
STRICT=${STRICT:-false}
COMMIT=${COMMIT:-unknown}
mkdir -p work

attempt() {
  rm -f bot.log land.log landed.edn result.edn work/run.edn
  clojure -M:gym land --goal "$GOAL" --run "$RUN" --name "$BOT" --rcon-port 25575 --rcon-pass "$RCON_PASS" \
      --landings "$LANDINGS" --out landed.edn 2> land.log \
    | clojure -M:run --host 127.0.0.1 --port 25565 --name "$BOT" --until "$UNTIL" --events stdin \
      --timeout-ms "$TIMEOUT_MS" --hold-ms 60000 --record work/run.edn > bot.log 2>&1 &
  local bot=$!
  clojure -M:gym judge --goal "$GOAL" --run "$RUN" --name "$BOT" --rcon-port 25575 --rcon-pass "$RCON_PASS" \
    --bot-log bot.log --landed landed.edn --out result.edn --commit "$COMMIT" --strict "$STRICT"
  local code=$?
  kill "$bot" 2>/dev/null   # the recording already ended at RESULT; the hold was only for the judge
  wait "$bot" 2>/dev/null
  return $code
}

attempt
code=$?
if grep -q ':gym/outcome :disconnect' result.edn 2>/dev/null; then
  echo "disconnected: running once more on the same landing"
  attempt
  code=$?
fi
cat land.log
cat bot.log
exit $code

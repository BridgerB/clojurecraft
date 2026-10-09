#!/usr/bin/env bash
# clojurecraft's own local vanilla 26.1.2 server on the Mac: game 25571, RCON 25581, world in
# data/local-world. (ruststeve uses 25567/25568, steve 25569/25570 - never touch those.)
#
#   ./local-server.sh start     # detached; prints when "Done"
#   ./local-server.sh stop
#   cat data/local-server/rcon.pass   # the RCON password (random, gitignored)
set -eu
DIR=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$DIR/data/local-server"
PASS_FILE=$DIR/data/local-server/rcon.pass
if [ ! -s "$PASS_FILE" ]; then
  (umask 077 && head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$PASS_FILE")
fi
export PORT=25571 RCON_PORT=25581 RCON_PASS=$(cat "$PASS_FILE") WORLD=$DIR/data/local-world
export JAVA=${JAVA:-$(nix build --no-link --print-out-paths nixpkgs#jdk25)/bin/java}
export DIFFICULTY=${DIFFICULTY:-peaceful}
exec "$DIR/scripts/server.sh" "${1:-start}"

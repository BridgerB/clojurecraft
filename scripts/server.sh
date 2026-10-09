#!/usr/bin/env bash
# Start or stop a vanilla 26.1.2 server. Shared by local-server.sh (Mac) and the CI workflow.
#
#   PORT=25565 RCON_PORT=25575 RCON_PASS=secret WORLD=work/world scripts/server.sh start
#   WORLD=work/world scripts/server.sh stop
#
# Env: PORT RCON_PORT RCON_PASS WORLD (required); JAVA (default java), HEAP_MB (default 2048),
# JAR (default data/local-server/server-26.1.2.jar, downloaded and sha1-checked when missing),
# LEVEL_SEED (default typecraft), DIFFICULTY (default peaceful).
set -eu
DIR=$(cd "$(dirname "$0")/.." && pwd)
JAR_URL=https://piston-data.mojang.com/v1/objects/97ccd4c0ed3f81bbb7bfacddd1090b0c56f9bc51/server.jar
JAR_SHA1=97ccd4c0ed3f81bbb7bfacddd1090b0c56f9bc51
JAR=${JAR:-$DIR/data/local-server/server-26.1.2.jar}
JAVA=${JAVA:-java}
HEAP_MB=${HEAP_MB:-2048}
WORLD=${WORLD:?set WORLD}

ensure_jar() {
  mkdir -p "$(dirname "$JAR")"
  if [ ! -f "$JAR" ] || [ "$(shasum -a 1 "$JAR" | cut -d' ' -f1)" != "$JAR_SHA1" ]; then
    echo "downloading server.jar"
    curl -fsSL -o "$JAR.tmp" "$JAR_URL"
    [ "$(shasum -a 1 "$JAR.tmp" | cut -d' ' -f1)" = "$JAR_SHA1" ] || { echo "server.jar sha1 mismatch" >&2; exit 1; }
    mv "$JAR.tmp" "$JAR"
  fi
}

start() {
  : "${PORT:?set PORT}" "${RCON_PORT:?set RCON_PORT}" "${RCON_PASS:?set RCON_PASS}"
  ensure_jar
  mkdir -p "$WORLD"
  cd "$WORLD"
  echo "eula=true" > eula.txt
  cat > server.properties <<PROPS
online-mode=false
allow-flight=true
level-seed=${LEVEL_SEED:-typecraft}
level-type=minecraft:normal
difficulty=${DIFFICULTY:-peaceful}
gamemode=survival
pvp=false
spawn-protection=0
view-distance=6
simulation-distance=4
server-port=$PORT
server-ip=127.0.0.1
pause-when-empty-seconds=0
enable-rcon=true
rcon.port=$RCON_PORT
rcon.password=$RCON_PASS
enforce-secure-profile=false
motd=clojurecraft
PROPS
  nohup "$JAVA" -Xms512M -Xmx${HEAP_MB}M -XX:+UseG1GC -jar "$JAR" nogui > server.log 2>&1 &
  echo $! > server.pid
  echo "server pid $(cat server.pid), waiting for Done"
  for i in $(seq 1 180); do
    if grep -q 'Done (' server.log 2>/dev/null; then echo "server up after ${i}s"; return 0; fi
    if ! kill -0 "$(cat server.pid)" 2>/dev/null; then echo "server died:"; tail -40 server.log; return 1; fi
    sleep 1
  done
  echo "server did not come up in 180s"; tail -40 server.log; return 1
}

stop() {
  cd "$WORLD" 2>/dev/null || return 0
  if [ -f server.pid ] && kill -0 "$(cat server.pid)" 2>/dev/null; then
    kill "$(cat server.pid)"
    for i in $(seq 1 60); do kill -0 "$(cat server.pid)" 2>/dev/null || break; sleep 1; done
    kill -9 "$(cat server.pid)" 2>/dev/null || true
  fi
  rm -f server.pid
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  *) echo "usage: $0 start|stop" >&2; exit 2 ;;
esac

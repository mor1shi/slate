#!/usr/bin/env bash
# Запуск сервера Slate: MongoDB (если установлена локально) + WebSocket-сервер.
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then set -a; . ./.env; set +a; fi
export MONGO_URI="${MONGO_URI:-mongodb://localhost:27017}"
export MONGO_DB="${MONGO_DB:-canvas_collab}"
export WS_PORT="${WS_PORT:-8765}"
export WS_HOST="${WS_HOST:-127.0.0.1}"
for arg in "$@"; do [ "$arg" = "--lan" ] && export WS_HOST=0.0.0.0; done

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

pick_java() {
  local need="$1" cand v
  for cand in "${JAVA_HOME:-}" /usr/lib/jvm/openjdk25 /usr/lib/jvm/openjdk21 \
              /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/openjdk17; do
    [ -n "$cand" ] && [ -x "$cand/bin/java" ] || continue
    v=$("$cand/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
    if [ "${v:-0}" -ge "$need" ]; then echo "$cand/bin/java"; return; fi
  done
  command -v java || true
}

JAVA_BIN=$(pick_java 21)
if [ -z "$JAVA_BIN" ]; then
  echo "Не найден JDK 21+. Установите его или задайте JAVA_HOME." >&2
  exit 1
fi

if ! port_open 27017; then
  MONGOD="${MONGOD:-$HOME/.local/opt/mongodb/bin/mongod}"
  if [ -x "$MONGOD" ]; then
    echo "MongoDB не запущена — стартую $MONGOD"
    DB="$HOME/.local/share/canvasproject/mongo"
    mkdir -p "$DB"
    "$MONGOD" --dbpath "$DB" \
      --logpath "$HOME/.local/share/canvasproject/mongod.log" \
      --bind_ip 127.0.0.1 --fork >/dev/null
    for _ in $(seq 1 20); do port_open 27017 && break; sleep 0.5; done
  else
    echo "MongoDB не отвечает на 127.0.0.1:27017, а mongod не найден." >&2
    echo "Установите MongoDB или поправьте MONGO_URI в .env." >&2
    exit 1
  fi
fi

JAR=java-collab/server/target/canvas-collab-server-1.0.0.jar
if [ ! -f "$JAR" ]; then
  echo "Собираю сервер…"
  ./mvnw -q -f java-collab/server/pom.xml package
fi

echo "Сервер: ws://$WS_HOST:$WS_PORT, MongoDB: $MONGO_URI"
exec "$JAVA_BIN" -jar "$JAR"

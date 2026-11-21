#!/usr/bin/env bash
# Запуск клиента Slate (JavaFX).
set -euo pipefail
cd "$(dirname "$0")"

if [ -f .env ]; then set -a; . ./.env; set +a; fi
export WS_URL="${WS_URL:-ws://localhost:8765}"

pick_java_home() {
  local need="$1" cand v
  for cand in "${JAVA_HOME:-}" /usr/lib/jvm/openjdk21 /usr/lib/jvm/java-21-openjdk-amd64 \
              /usr/lib/jvm/openjdk17; do
    [ -n "$cand" ] && [ -x "$cand/bin/java" ] || continue
    v=$("$cand/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
    if [ "${v:-0}" -ge "$need" ]; then echo "$cand"; return; fi
  done
}

JAVA_HOME_DIR=$(pick_java_home 17)
if [ -z "$JAVA_HOME_DIR" ]; then
  echo "Не найден JDK 17+. Установите его или задайте JAVA_HOME." >&2
  exit 1
fi
export JAVA_HOME="$JAVA_HOME_DIR"

case "$WS_URL" in
  ws://localhost:*|ws://127.0.0.1:*)
    port=${WS_URL##*:}
    if ! (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
      echo "Предупреждение: сервер на порту $port не отвечает. Запустите ./run-server.sh." >&2
    fi
    ;;
esac

echo "Клиент: $WS_URL (JDK: $JAVA_HOME)"
exec ./mvnw -q javafx:run

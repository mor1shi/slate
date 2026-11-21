#!/usr/bin/env bash
# Сборка переносимого клиента: dist/Slate-1.0/ + zip.
set -euo pipefail
cd "$(dirname "$0")"

VER=1.0
NAME=Slate-$VER
OUT=dist/$NAME

JAVA_HOME_DIR=""
for cand in "${JAVA_HOME:-}" /usr/lib/jvm/openjdk21 /usr/lib/jvm/java-21-openjdk-amd64 /usr/lib/jvm/openjdk17; do
  [ -n "$cand" ] && [ -x "$cand/bin/java" ] || continue
  v=$("$cand/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')
  if [ "${v:-0}" -ge 17 ]; then JAVA_HOME_DIR="$cand"; break; fi
done
if [ -z "$JAVA_HOME_DIR" ]; then
  echo "Не найден JDK 17+." >&2
  exit 1
fi
export JAVA_HOME="$JAVA_HOME_DIR"

echo "Сборка…"
./mvnw -q -DskipTests package

rm -rf "$OUT"
mkdir -p "$OUT"
JAR=$(ls target/slate-*.jar | grep -v original | head -1)
cp "$JAR" "$OUT/slate.jar"
cp -r target/libs "$OUT/libs"

cat > "$OUT/Slate.sh" <<'EOF'
#!/usr/bin/env bash
cd "$(dirname "$0")"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
exec "${JAVA_BIN:-java}" -Dfile.encoding=UTF-8 -jar slate.jar "$@"
EOF
chmod +x "$OUT/Slate.sh"

cp README.md "$OUT/README.md"

cd dist
rm -f "$NAME-linux.zip"
if command -v zip >/dev/null 2>&1; then
  zip -qr "$NAME-linux.zip" "$NAME"
  echo "Готово: dist/$NAME-linux.zip"
else
  tar -czf "$NAME-linux.tar.gz" "$NAME"
  echo "Готово: dist/$NAME-linux.tar.gz"
fi

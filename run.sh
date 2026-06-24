#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
JADX_SERVER_JAR="${JADX_SERVER_JAR:-backup/jadx-server-0.1.8-all.jar}"
JADX_SERVER_AUTH_TOKEN="${JADX_SERVER_AUTH_TOKEN:-}"
LOGBACK_CONFIG="${LOGBACK_CONFIG:-$SCRIPT_DIR/logback-local.xml}"

args=(
  -Dlogback.configurationFile="$LOGBACK_CONFIG"
  -Xms256M
  -XX:MaxRAMPercentage=50.0
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:G1PeriodicGCInterval=30000
  -XX:InitiatingHeapOccupancyPercent=45
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=./oom.hprof
)

args+=(
  -jar "$JADX_SERVER_JAR"
  --listen 127.0.0.1:7789
  --upload-dir "$HOME/Documents/jadx-server"
)

if [[ -n "$JADX_SERVER_AUTH_TOKEN" ]]; then
  args+=(--auth-token "$JADX_SERVER_AUTH_TOKEN")
fi
set -x
exec java "${args[@]}" "$@"

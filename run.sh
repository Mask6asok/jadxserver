#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
JADX_SERVER_JAR="${JADX_SERVER_JAR:-$SCRIPT_DIR/build/libs/jadx-server-1.2.0-all.jar}"
JADX_SERVER_AUTH_TOKEN="${JADX_SERVER_AUTH_TOKEN:-}"
JADX_SERVER_UPLOAD_DIR="${JADX_SERVER_UPLOAD_DIR:-$HOME/Documents/jadx-server}"
JADX_SHARED_ENGINE_SLOTS="${JADX_SHARED_ENGINE_SLOTS:-1}"
LOGBACK_CONFIG="${LOGBACK_CONFIG:-$SCRIPT_DIR/logback-local.xml}"

if [[ ! -f "$JADX_SERVER_JAR" ]]; then
  print -u2 "JADX server JAR not found: $JADX_SERVER_JAR"
  print -u2 "Build it with: ./gradlew shadowJar"
  exit 1
fi

if [[ "$JADX_SHARED_ENGINE_SLOTS" != "1" && "$JADX_SHARED_ENGINE_SLOTS" != "2" ]]; then
  print -u2 "JADX_SHARED_ENGINE_SLOTS must be 1 or 2 (got: $JADX_SHARED_ENGINE_SLOTS)"
  exit 2
fi

# This launcher is intentionally locked to the shared APK runtime.  Use the
# environment variable above to choose one or two bounded mutable engines;
# never silently fall back to the memory-heavy CURRENT topology.
for arg in "$@"; do
  case "$arg" in
    --runtime-mode|--runtime-mode=*)
      print -u2 "run.sh only supports --runtime-mode shared"
      exit 2
      ;;
    --shared-engine-slots|--shared-engine-slots=*)
      print -u2 "set JADX_SHARED_ENGINE_SLOTS=1 or 2; do not override it on the command line"
      exit 2
      ;;
  esac
done

args=(
  -Dlogback.configurationFile="$LOGBACK_CONFIG"
  -Xms256M
  -XX:MaxRAMPercentage=40.0
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=200
  -XX:G1PeriodicGCInterval=30000
  -XX:InitiatingHeapOccupancyPercent=40
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=./oom.hprof
)

args+=(
  -jar "$JADX_SERVER_JAR"
  --listen 0.0.0.0:7789
  --upload-dir "$JADX_SERVER_UPLOAD_DIR"
  --runtime-mode shared
  --shared-engine-slots "$JADX_SHARED_ENGINE_SLOTS"
  --max-instances 5
  --max-per-file 5
  --engine-threads 4
)

#if [[ -n "$JADX_SERVER_AUTH_TOKEN" ]]; then
#  args+=(--auth-token "$JADX_SERVER_AUTH_TOKEN")
#fi
set -x
exec java "${args[@]}" "$@"

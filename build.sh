#!/usr/bin/env bash
set -euo pipefail

echo "=== jadx-server build ==="

echo ""
echo "[1/2] Compiling & testing..."
./gradlew build

echo ""
echo "[2/2] Creating fat JAR..."
./gradlew shadowJar

echo ""
echo "=== Build Complete ==="
echo "Fat JAR:  build/libs/jadx-server-0.1.1-all.jar"
echo "Dist:     build/install/jadx-server/"
echo ""
echo "Run:"
echo "  java -jar build/libs/jadx-server-0.1.1-all.jar"
echo "  java -jar build/libs/jadx-server-0.1.1-all.jar --help"

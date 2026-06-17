#!/bin/zsh
java \
  -Xms256M \
  -XX:MaxRAMPercentage=70.0 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:G1PeriodicGCInterval=30000 \
  -XX:InitiatingHeapOccupancyPercent=45 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=./oom.hprof \
  -jar backup/jadx-server-0.1.4-all.jar \
  --listen 127.0.0.1:7789 \
  --upload-dir ~/Documents/jadx-server

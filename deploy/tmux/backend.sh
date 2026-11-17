#!/usr/bin/env bash
set -euo pipefail
set -a
source /etc/ams/backend.env
set +a
cd /opt/ams/current
exec /usr/sbin/runuser -u ams --preserve-environment -- /usr/bin/java \
  -Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 \
  -jar /opt/ams/current/server/app.jar --server.address=127.0.0.1 --server.port=18081 \
  --logging.file.name=/opt/ams/logs/backend.log

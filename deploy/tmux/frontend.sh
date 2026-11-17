#!/usr/bin/env bash
set -euo pipefail
exec /usr/sbin/nginx -c /etc/ams/nginx.conf -g 'daemon off;'

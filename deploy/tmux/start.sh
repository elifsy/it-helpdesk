#!/usr/bin/env bash
set -euo pipefail
test -f /opt/ams/current/server/app.jar
test -f /etc/ams/backend.env
/usr/sbin/nginx -t -c /etc/ams/nginx.conf
tmux has-session -t '=ams-backend' 2>/dev/null || tmux new-session -d -s ams-backend /opt/ams/bin/backend.sh
tmux has-session -t '=ams-frontend' 2>/dev/null || tmux new-session -d -s ams-frontend /opt/ams/bin/frontend.sh
tmux list-sessions -F '#{session_name}: #{session_windows} window(s)'

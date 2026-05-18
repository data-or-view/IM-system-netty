#!/usr/bin/env bash
# =============================================
# IM System — Cluster Shutdown Script
# Gracefully stops all cluster nodes.
# =============================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_DIR="$SCRIPT_DIR/pids"

if [ ! -d "$PID_DIR" ]; then
  echo "[INFO] No PID directory found — cluster is not running."
  exit 0
fi

# Collect all node PIDs
node_pids=()
node_names=()
for pid_file in "$PID_DIR"/*.pid; do
  [ -f "$pid_file" ] || continue
  node_name=$(basename "$pid_file" .pid)
  node_pid=$(cat "$pid_file" 2>/dev/null || echo "")
  if [ -n "$node_pid" ]; then
    node_pids+=("$node_pid")
    node_names+=("$node_name")
  fi
done

if [ ${#node_pids[@]} -eq 0 ]; then
  echo "[INFO] No PID files found — cluster is not running."
  rm -rf "$PID_DIR"
  exit 0
fi

echo "Stopping ${#node_pids[@]} node(s)..."

# Send SIGTERM to all nodes in parallel
for i in "${!node_pids[@]}"; do
  pid="${node_pids[$i]}"
  name="${node_names[$i]}"
  if kill -0 "$pid" 2>/dev/null; then
    echo "  [STOP] $name (PID $pid)"
    kill "$pid" 2>/dev/null || true
  else
    echo "  [SKIP] $name (PID $pid — not running)"
  fi
done

# Wait up to 15 seconds for graceful shutdown
echo "Waiting for processes to exit gracefully..."
total_wait=0
while [ $total_wait -lt 15 ]; do
  all_dead=true
  for pid in "${node_pids[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      all_dead=false
      break
    fi
  done
  if $all_dead; then
    echo "[OK] All nodes stopped gracefully"
    break
  fi
  sleep 1
  total_wait=$((total_wait + 1))
done

# Force kill any remaining
still_running=false
for i in "${!node_pids[@]}"; do
  pid="${node_pids[$i]}"
  name="${node_names[$i]}"
  if kill -0 "$pid" 2>/dev/null; then
    echo "  [FORCE] $name (PID $pid) — did not stop in time, sending SIGKILL"
    kill -9 "$pid" 2>/dev/null || true
    still_running=true
  fi
done

# Clean up PID files
rm -f "$PID_DIR"/*.pid 2>/dev/null || true
rmdir "$PID_DIR" 2>/dev/null || true

# Verify ports are free (optional check)
echo ""
echo "Port status:"
for port in 8081 8088 8084 8089; do
  if lsof -iTCP:"$port" -sTCP:LISTEN -P 2>/dev/null | grep -q .; then
    echo "  [WARN] Port $port is still in use"
  else
    echo "  [OK]   Port $port is free"
  fi
done

if $still_running; then
  echo ""
  echo "[WARN] Some nodes had to be force-killed. Check logs for details."
  exit 1
else
  echo ""
  echo "[OK] Cluster stopped successfully."
  exit 0
fi

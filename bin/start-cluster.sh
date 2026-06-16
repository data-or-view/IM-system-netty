#!/usr/bin/env bash
# =============================================
# IM System — Cluster Startup Script
# Starts two nodes for local cluster testing.
# Both nodes share the same Redis for coordination.
# =============================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

JAR="$PROJECT_DIR/im-server/target/im-server-1.0.0-SNAPSHOT.jar"
LOG_DIR="$PROJECT_DIR/logs"
PID_DIR="$PROJECT_DIR/bin/pids"
CLASSPATH_JAR="$PROJECT_DIR/im-server/target/original-im-server-1.0.0-SNAPSHOT.jar"

REDIS_HOST="${IM_REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${IM_REDIS_PORT:-6379}"
REDIS_USERNAME="${IM_REDIS_USERNAME:-}"
REDIS_PASSWORD="${IM_REDIS_PASSWORD:-difyai123456}"

# Node configurations
NODE1_ID="node-1"
NODE1_WS_PORT=8081
NODE1_HTTP_PORT=8088

NODE2_ID="node-2"
NODE2_WS_PORT=8084
NODE2_HTTP_PORT=8089

# =============================================

# Pre-flight: port conflict check
check_port() {
  local port=$1
  if lsof -iTCP:"$port" -sTCP:LISTEN -P 2>/dev/null | grep -q .; then
    echo "[ERROR] Port $port is already in use by another process."
    echo "        Please free it or change the port config in this script."
    return 1
  fi
  return 0
}

is_running() {
  local pid="$1"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

pids_for_port() {
  local port="$1"
  lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true
}

port_is_owned_by_pid() {
  local port="$1"
  local pid="$2"
  pids_for_port "$port" | grep -qx "$pid"
}

node_is_stable() {
  local pid="$1"
  local ws_port="$2"
  local http_port="$3"
  local checks=0
  while [ "$checks" -lt 3 ]; do
    if ! is_running "$pid"; then
      return 1
    fi
    if ! port_is_owned_by_pid "$ws_port" "$pid"; then
      return 1
    fi
    if ! port_is_owned_by_pid "$http_port" "$pid"; then
      return 1
    fi
    sleep 1
    checks=$((checks + 1))
  done
  is_running "$pid" \
    && port_is_owned_by_pid "$ws_port" "$pid" \
    && port_is_owned_by_pid "$http_port" "$pid"
}

for port in $NODE1_WS_PORT $NODE1_HTTP_PORT $NODE2_WS_PORT $NODE2_HTTP_PORT; do
  check_port "$port" || exit 1
done

mkdir -p "$LOG_DIR" "$PID_DIR"

# ----- Check prerequisites -----

if [ ! -f "$JAR" ]; then
  echo "[ERROR] JAR not found at: $JAR"
  echo "Please run 'mvn package -DskipTests' first."
  exit 1
fi

# Check Redis
if command -v redis-cli &>/dev/null; then
  redis_cli=(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT")
  if [ -n "$REDIS_PASSWORD" ]; then
    if [ -n "$REDIS_USERNAME" ]; then
      redis_cli+=("--user" "$REDIS_USERNAME")
    fi
    redis_cli+=("-a" "$REDIS_PASSWORD")
  fi
  if ! "${redis_cli[@]}" ping &>/dev/null; then
    echo "[WARN] Redis is not reachable at $REDIS_HOST:$REDIS_PORT — cluster features may fail."
  else
    echo "[OK] Redis is reachable"
  fi
else
  echo "[WARN] redis-cli not found — skipping Redis health check."
fi

# ----- Helper: start a single node -----

start_node() {
  local node_id=$1
  local ws_port=$2
  local http_port=$3
  local pid_file="$PID_DIR/${node_id}.pid"
  local log_file="$LOG_DIR/${node_id}.log"

  # Check if already running
  if [ -f "$pid_file" ]; then
    local old_pid
    old_pid=$(cat "$pid_file" 2>/dev/null || echo "")
    if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
      echo "[SKIP] $node_id (PID $old_pid) is already running"
      return 0
    fi
    rm -f "$pid_file"
  fi

  echo "[START] $node_id → WS=$ws_port HTTP=$http_port"

  local extra_opts=""
  # Node-1 initializes the DB schema; subsequent nodes skip it to avoid "table already exists" errors
  if [ "$node_id" != "$NODE1_ID" ]; then
    extra_opts="-Dim.db.schema=none"
  fi

  # Use the im.env=macbook-dev profile (has Redis/MySQL config), override ports and node ID
  # shellcheck disable=SC2086
  nohup java \
    "-Dim.env=macbook-dev" \
    "-Dim.node.id=$node_id" \
    "-Dim.ws.port=$ws_port" \
    "-Dim.http.port=$http_port" \
    "-Dim.redis.host=$REDIS_HOST" \
    "-Dim.redis.port=$REDIS_PORT" \
    "-Dim.redis.username=$REDIS_USERNAME" \
    "-Dim.redis.password=$REDIS_PASSWORD" \
    $extra_opts \
    -jar "$JAR" \
    > "$log_file" 2>&1 < /dev/null &

  local pid=$!
  disown "$pid" 2>/dev/null || true
  echo "$pid" > "$pid_file"
  echo "[OK] $node_id started with PID $pid (log: ${log_file/#$HOME/~})"
}

# ----- Start nodes -----

start_node "$NODE1_ID" "$NODE1_WS_PORT" "$NODE1_HTTP_PORT"
start_node "$NODE2_ID" "$NODE2_WS_PORT" "$NODE2_HTTP_PORT"

# ----- Wait and verify (poll up to 20s for "Server ready") -----

echo ""
echo "Waiting for nodes to be ready..."
all_ok=true
for node_id in "$NODE1_ID" "$NODE2_ID"; do
  pid_file="$PID_DIR/${node_id}.pid"
  log_file="$LOG_DIR/${node_id}.log"
  if [ "$node_id" = "$NODE1_ID" ]; then
    ws_port="$NODE1_WS_PORT"
    http_port="$NODE1_HTTP_PORT"
  else
    ws_port="$NODE2_WS_PORT"
    http_port="$NODE2_HTTP_PORT"
  fi

  if [ ! -f "$pid_file" ]; then
    echo "[FAIL] $node_id — no PID file found"
    all_ok=false
    continue
  fi

  pid=$(cat "$pid_file")
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "[FAIL] $node_id (PID $pid) — process died. Last 20 lines of log:"
    tail -20 "$log_file"
    all_ok=false
    continue
  fi

  # Poll for "Server ready" in log
  waited=0
  while [ $waited -lt 20 ]; do
    if grep -q "Server ready" "$log_file" 2>/dev/null; then
      if node_is_stable "$pid" "$ws_port" "$http_port"; then
        echo "[READY] $node_id (PID $pid) — Server ready, ports listening"
        break
      fi
    fi
    if ! is_running "$pid"; then
      echo "[FAIL] $node_id (PID $pid) — process died during startup. Last 20 lines of log:"
      tail -20 "$log_file"
      all_ok=false
      break
    fi
    sleep 1
    waited=$((waited + 1))
  done

  if [ $waited -ge 20 ]; then
    if grep -q "Server ready" "$log_file" 2>/dev/null; then
      echo "[READY] $node_id (PID $pid) — Server ready (late)"
    else
      echo "[WARN] $node_id (PID $pid) — process is running but 'Server ready' not in log after 20s"
      echo "       Tail log: tail -f ${log_file/#$HOME/~}"
    fi
  fi
done

if $all_ok; then
  echo ""
  echo "========================================"
  echo " Cluster started successfully!"
  echo " Node 1: WS=localhost:$NODE1_WS_PORT | HTTP=localhost:$NODE1_HTTP_PORT"
  echo " Node 2: WS=localhost:$NODE2_WS_PORT | HTTP=localhost:$NODE2_HTTP_PORT"
  echo " Logs: ${LOG_DIR/#$HOME/~}/{${NODE1_ID},${NODE2_ID}}.log"
  echo " PIDs: ${PID_DIR/#$HOME/~}/{${NODE1_ID},${NODE2_ID}}.pid"
  echo "========================================"
  exit 0
else
  echo ""
  echo "[ERROR] Some nodes failed to start. Check logs for details."
  exit 1
fi

#!/usr/bin/env bash
# =============================================
# IM System — Cluster Startup Script
# Starts two nodes for local cluster testing.
# Both nodes share the same Redis, MySQL, MQ, and MinIO.
# Node-1 is the only schema owner; node-2 always starts with schema mode none.
# Default owner mode auto bootstraps a blank DB or validates managed Version 2.
# For a recognized v1.1 DB, use IM_CLUSTER_SCHEMA_OWNER_MODE=migrate.
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
NODE1_SCHEMA_MODE="${IM_CLUSTER_SCHEMA_OWNER_MODE:-auto}"

case "$NODE1_SCHEMA_MODE" in
  auto|migrate) ;;
  *)
    echo "[ERROR] IM_CLUSTER_SCHEMA_OWNER_MODE must be 'auto' or 'migrate' (got: $NODE1_SCHEMA_MODE)."
    exit 1
    ;;
esac

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

  local schema_mode="none"
  if [ "$node_id" = "$NODE1_ID" ]; then
    schema_mode="$NODE1_SCHEMA_MODE"
  fi

  # Use the im.env=macbook-dev profile (has Redis/MySQL config), override ports and node ID
  # Config gives IM_* precedence over -D. Remove an ambient IM_DB_SCHEMA so the
  # per-node property below remains authoritative and node-2 cannot migrate.
  nohup env -u IM_DB_SCHEMA java \
    "-Dim.env=macbook-dev" \
    "-Dim.node.id=$node_id" \
    "-Dim.ws.port=$ws_port" \
    "-Dim.http.port=$http_port" \
    "-Dim.redis.host=$REDIS_HOST" \
    "-Dim.redis.port=$REDIS_PORT" \
    "-Dim.redis.username=$REDIS_USERNAME" \
    "-Dim.redis.password=$REDIS_PASSWORD" \
    "-Dim.db.schema=$schema_mode" \
    -jar "$JAR" \
    > "$log_file" 2>&1 < /dev/null &

  local pid=$!
  disown "$pid" 2>/dev/null || true
  echo "$pid" > "$pid_file"
  echo "[OK] $node_id started with PID $pid, schema=$schema_mode (log: ${log_file/#$HOME/~})"
}

wait_for_node() {
  local node_id=$1
  local ws_port=$2
  local http_port=$3
  local require_v2=$4
  pid_file="$PID_DIR/${node_id}.pid"
  log_file="$LOG_DIR/${node_id}.log"

  if [ ! -f "$pid_file" ]; then
    echo "[FAIL] $node_id — no PID file found"
    return 1
  fi

  pid=$(cat "$pid_file")
  if ! kill -0 "$pid" 2>/dev/null; then
    echo "[FAIL] $node_id (PID $pid) — process died. Last 20 lines of log:"
    tail -20 "$log_file"
    return 1
  fi

  # The schema owner must prove Version 2 before any schema=none node starts.
  waited=0
  while [ $waited -lt 90 ]; do
    schema_ready=true
    if [ "$require_v2" = "true" ]; then
      schema_ready=false
      if grep -Eq "(managed|Managed) schema Version 2|Schema migration to Version 2" "$log_file" 2>/dev/null; then
        schema_ready=true
      fi
    fi
    if $schema_ready && grep -q "Server ready" "$log_file" 2>/dev/null; then
      if node_is_stable "$pid" "$ws_port" "$http_port"; then
        echo "[READY] $node_id (PID $pid) — schema verified, Server ready, ports listening"
        return 0
      fi
    fi
    if ! is_running "$pid"; then
      echo "[FAIL] $node_id (PID $pid) — process died during startup. Last 20 lines of log:"
      tail -20 "$log_file"
      return 1
    fi
    sleep 1
    waited=$((waited + 1))
  done

  echo "[FAIL] $node_id (PID $pid) — Version 2 validation and Server ready not observed after 90s"
  echo "       Tail log: tail -f ${log_file/#$HOME/~}"
  return 1
}

# ----- Start nodes in schema-owner order -----

echo "[SCHEMA] node-1 owner mode: $NODE1_SCHEMA_MODE; node-2 mode: none"
start_node "$NODE1_ID" "$NODE1_WS_PORT" "$NODE1_HTTP_PORT"
echo "Waiting for node-1 Version 2 validation before starting node-2..."
if ! wait_for_node "$NODE1_ID" "$NODE1_WS_PORT" "$NODE1_HTTP_PORT" true; then
  echo "[ERROR] Schema owner did not become ready; node-2 was not started."
  exit 1
fi

start_node "$NODE2_ID" "$NODE2_WS_PORT" "$NODE2_HTTP_PORT"
echo "Waiting for node-2 to be ready with schema mode none..."
all_ok=true
if ! wait_for_node "$NODE2_ID" "$NODE2_WS_PORT" "$NODE2_HTTP_PORT" false; then
  all_ok=false
fi

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

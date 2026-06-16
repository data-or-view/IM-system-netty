#!/usr/bin/env bash
# =============================================
# IM System - Single Backend Restart Script
# Restarts the local development backend node.
# =============================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
PID_DIR="$SCRIPT_DIR/pids"
LOG_DIR="$PROJECT_DIR/logs"
PID_FILE="$PID_DIR/backend.pid"
LOG_FILE="$LOG_DIR/backend.log"
JAR="$PROJECT_DIR/im-server/target/im-server-1.0.0-SNAPSHOT.jar"

IM_ENV_VALUE="${IM_ENV:-macbook-dev}"
NODE_ID="${IM_NODE_ID:-macbook-dev}"
WS_PORT="${IM_WS_PORT:-8083}"
HTTP_PORT="${IM_HTTP_PORT:-8084}"
BUILD=1
FOREGROUND=0
CLEAR_LOGS="${IM_CLEAR_LOGS:-1}"
STOP_TIMEOUT="${IM_STOP_TIMEOUT:-15}"
READY_TIMEOUT="${IM_READY_TIMEOUT:-30}"

usage() {
  cat <<'USAGE'
Usage: bin/restart-backend.sh [options]

Options:
  --no-build              Restart using the existing jar without running Maven.
  --build                 Run Maven package before starting. This is the default.
  --foreground            Start in the foreground instead of background/nohup.
  --keep-logs             Keep existing development log files before restart.
  --clear-logs            Clear existing development log files before restart. This is the default.
  --env <name>            Set -Dim.env. Default: macbook-dev.
  --node-id <id>          Set -Dim.node.id. Default: macbook-dev.
  --ws-port <port>        Set -Dim.ws.port. Default: 8083.
  --http-port <port>      Set -Dim.http.port. Default: 8084.
  -h, --help              Show this help.

Environment overrides:
  IM_ENV, IM_NODE_ID, IM_WS_PORT, IM_HTTP_PORT, IM_CLEAR_LOGS, IM_STOP_TIMEOUT, IM_READY_TIMEOUT

Examples:
  bin/restart-backend.sh
  bin/restart-backend.sh --no-build
  bin/restart-backend.sh --no-build --keep-logs
  bin/restart-backend.sh --foreground --no-build
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --no-build)
      BUILD=0
      shift
      ;;
    --build)
      BUILD=1
      shift
      ;;
    --foreground)
      FOREGROUND=1
      shift
      ;;
    --keep-logs)
      CLEAR_LOGS=0
      shift
      ;;
    --clear-logs)
      CLEAR_LOGS=1
      shift
      ;;
    --env)
      IM_ENV_VALUE="${2:?Missing value for --env}"
      shift 2
      ;;
    --node-id)
      NODE_ID="${2:?Missing value for --node-id}"
      shift 2
      ;;
    --ws-port)
      WS_PORT="${2:?Missing value for --ws-port}"
      shift 2
      ;;
    --http-port)
      HTTP_PORT="${2:?Missing value for --http-port}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[ERROR] Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

mkdir -p "$PID_DIR" "$LOG_DIR"

is_running() {
  local pid="$1"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

pids_for_port() {
  local port="$1"
  lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true
}

stop_pid() {
  local pid="$1"
  local source="$2"
  if ! is_running "$pid"; then
    return 0
  fi

  echo "[STOP] PID $pid ($source)"
  kill "$pid" 2>/dev/null || true
}

collect_backend_pids() {
  {
    if [ -f "$PID_FILE" ]; then
      cat "$PID_FILE" 2>/dev/null || true
    fi
    pids_for_port "$WS_PORT"
    pids_for_port "$HTTP_PORT"
    ps -ef | grep "[i]m-server/target/im-server-1.0.0-SNAPSHOT.jar" | awk '{print $2}' || true
  } | awk 'NF && !seen[$0]++'
}

stop_existing() {
  local pids
  pids="$(collect_backend_pids)"
  if [ -z "$pids" ]; then
    echo "[INFO] No existing backend process found."
    rm -f "$PID_FILE"
    return 0
  fi

  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    stop_pid "$pid" "backend"
  done <<< "$pids"

  local waited=0
  while [ "$waited" -lt "$STOP_TIMEOUT" ]; do
    local any_running=0
    while IFS= read -r pid; do
      [ -n "$pid" ] || continue
      if is_running "$pid"; then
        any_running=1
        break
      fi
    done <<< "$pids"

    if [ "$any_running" -eq 0 ]; then
      echo "[OK] Existing backend stopped."
      rm -f "$PID_FILE"
      return 0
    fi

    sleep 1
    waited=$((waited + 1))
  done

  while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    if is_running "$pid"; then
      echo "[FORCE] PID $pid did not stop in ${STOP_TIMEOUT}s, sending SIGKILL"
      kill -9 "$pid" 2>/dev/null || true
    fi
  done <<< "$pids"
  rm -f "$PID_FILE"
}

clear_dev_logs() {
  if [ "$CLEAR_LOGS" != "1" ]; then
    echo "[INFO] Keeping existing log files."
    return 0
  fi

  echo "[CLEAN] Removing development backend logs from ${LOG_DIR/#$HOME/~}"
  find "$LOG_DIR" -maxdepth 1 -type f \( \
    -name 'backend.log' -o \
    -name 'im-system.log' -o \
    -name 'im-system.*.log' \
  \) -delete
}

build_backend() {
  echo "[BUILD] mvn -pl im-api,im-server -am package -DskipTests"
  (cd "$PROJECT_DIR" && mvn -pl im-api,im-server -am package -DskipTests)
}

assert_jar_exists() {
  if [ ! -f "$JAR" ]; then
    echo "[ERROR] JAR not found: $JAR" >&2
    echo "        Run bin/restart-backend.sh --build or mvn -pl im-api,im-server -am package -DskipTests" >&2
    exit 1
  fi
}

wait_until_ready() {
  local pid="$1"
  local waited=0
  while [ "$waited" -lt "$READY_TIMEOUT" ]; do
    if ! is_running "$pid"; then
      echo "[FAIL] Backend process exited during startup. Last 40 log lines:" >&2
      tail -40 "$LOG_FILE" >&2 || true
      exit 1
    fi

    if grep -q "Server ready" "$LOG_FILE" 2>/dev/null; then
      if backend_is_stable "$pid"; then
        echo "[READY] Backend is ready. PID=$pid, WS=$WS_PORT, HTTP=$HTTP_PORT"
        echo "[LOG] tail -f ${LOG_FILE/#$HOME/~}"
        return 0
      fi
    fi

    sleep 1
    waited=$((waited + 1))
  done

  echo "[FAIL] Backend did not become ready in ${READY_TIMEOUT}s. Last 40 log lines:" >&2
  tail -40 "$LOG_FILE" >&2 || true
  exit 1
}

ports_are_listening() {
  if [ "$WS_PORT" != "disabled" ] && ! pids_for_port "$WS_PORT" | grep -qx "$1"; then
    return 1
  fi
  if [ "$HTTP_PORT" != "disabled" ] && ! pids_for_port "$HTTP_PORT" | grep -qx "$1"; then
    return 1
  fi
  return 0
}

backend_is_stable() {
  local pid="$1"
  local checks=0
  while [ "$checks" -lt 3 ]; do
    if ! is_running "$pid"; then
      return 1
    fi
    if ! ports_are_listening "$pid"; then
      return 1
    fi
    sleep 1
    checks=$((checks + 1))
  done
  is_running "$pid" && ports_are_listening "$pid"
}

start_backend() {
  local java_cmd=(
    java
    "-Dim.env=$IM_ENV_VALUE"
    "-Dim.node.id=$NODE_ID"
    "-Dim.ws.port=$WS_PORT"
    "-Dim.http.port=$HTTP_PORT"
    -jar "$JAR"
  )

  if [ "$FOREGROUND" -eq 1 ]; then
    echo "[START] Foreground backend: env=$IM_ENV_VALUE node=$NODE_ID WS=$WS_PORT HTTP=$HTTP_PORT"
    exec "${java_cmd[@]}"
  fi

  : > "$LOG_FILE"
  echo "[START] Background backend: env=$IM_ENV_VALUE node=$NODE_ID WS=$WS_PORT HTTP=$HTTP_PORT"
  nohup "${java_cmd[@]}" > "$LOG_FILE" 2>&1 < /dev/null &
  local pid=$!
  disown "$pid" 2>/dev/null || true
  echo "$pid" > "$PID_FILE"
  wait_until_ready "$pid"
}

stop_existing
clear_dev_logs

if [ "$BUILD" -eq 1 ]; then
  build_backend
fi

assert_jar_exists
start_backend

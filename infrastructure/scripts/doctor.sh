#!/usr/bin/env bash
#
# Checks that this machine can build and run FinPay locally.
#
# Reports every problem it finds rather than stopping at the first one, and exits non-zero
# if anything required is missing.

set -uo pipefail

cd "$(dirname "$0")/../.."

failures=0
warnings=0

ok()   { printf '  \033[32mok\033[0m    %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; failures=$((failures + 1)); }
warn() { printf '  \033[33mwarn\033[0m  %s\n' "$1"; warnings=$((warnings + 1)); }

echo
echo "FinPay environment check"
echo

# --- Java -------------------------------------------------------------------
if java_home="$(infrastructure/scripts/java-home.sh 2>/dev/null)"; then
  ok "JDK 21 at ${java_home}"
else
  fail "no JDK 21 found (brew install openjdk@21) - the build enforces 21.x"
fi

# --- Maven wrapper ----------------------------------------------------------
if [[ -x ./mvnw ]]; then
  ok "Maven wrapper present and executable"
else
  fail "./mvnw missing or not executable"
fi

# --- Docker -----------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
  fail "docker not installed"
elif ! docker info >/dev/null 2>&1; then
  fail "docker daemon not running (start Docker Desktop)"
else
  ok "docker $(docker version --format '{{.Server.Version}}') running"

  if docker compose version >/dev/null 2>&1; then
    ok "docker compose $(docker compose version --short)"
  else
    fail "docker compose v2 plugin not available"
  fi

  memory_bytes="$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)"
  if [[ "${memory_bytes}" -gt 0 && "${memory_bytes}" -lt 4000000000 ]]; then
    warn "docker has $((memory_bytes / 1024 / 1024 / 1024))GB of memory; 4GB+ recommended"
  fi
fi

# --- Ports ------------------------------------------------------------------
# Only flags ports held by something that is not our own compose project.
check_port() {
  local port="$1" label="$2"
  if ! nc -z 127.0.0.1 "${port}" >/dev/null 2>&1; then
    ok "port ${port} free (${label})"
    return
  fi
  if docker compose ps --format '{{.Ports}}' 2>/dev/null | grep -q ":${port}->"; then
    ok "port ${port} in use by FinPay's own ${label}"
  else
    fail "port ${port} is in use by another process (${label})"
  fi
}

check_port "${POSTGRES_PORT:-5432}" postgres
check_port "${REDIS_PORT:-6379}" redis
check_port "${KAFKA_PORT:-29092}" kafka
check_port "${KAFKA_UI_PORT:-8090}" kafka-ui

# --- Optional ---------------------------------------------------------------
if command -v node >/dev/null 2>&1; then
  node_major="$(node --version | sed 's/^v\([0-9]*\).*/\1/')"
  if [[ "${node_major}" -ge 20 ]]; then
    ok "node $(node --version) (needed from Phase 7)"
  else
    warn "node $(node --version) is older than v20 (needed from Phase 7)"
  fi
else
  warn "node not installed (not needed until Phase 7)"
fi

# --- Local config -----------------------------------------------------------
if [[ -f .env ]]; then
  ok ".env present"
else
  warn "no .env file; compose defaults will be used (make env to create one)"
fi

echo
if [[ "${failures}" -gt 0 ]]; then
  echo "${failures} problem(s) must be fixed, ${warnings} warning(s)."
  exit 1
fi
echo "Environment looks good (${warnings} warning(s))."

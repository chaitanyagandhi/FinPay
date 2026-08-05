#!/usr/bin/env bash
#
# Opens a psql session against one service's database, connecting as that service's own
# role rather than the bootstrap superuser. Connecting the way the service does makes
# permission mistakes visible here instead of at runtime.
#
# Usage:  infrastructure/scripts/db-shell.sh <service> [psql args...]
#         infrastructure/scripts/db-shell.sh wallet
#         infrastructure/scripts/db-shell.sh auth -c '\dt'

set -euo pipefail

cd "$(dirname "$0")/../.."

SERVICES=(auth user wallet transaction payment fraud notification audit)

usage() {
  echo "Usage: $0 <service> [psql args...]" >&2
  echo "Services: ${SERVICES[*]}" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
service="$1"
shift

valid=false
for known in "${SERVICES[@]}"; do
  [[ "${service}" == "${known}" ]] && valid=true
done
if [[ "${valid}" != true ]]; then
  echo "Unknown service: ${service}" >&2
  usage
fi

# shellcheck disable=SC1091
[[ -f .env ]] && source .env

password="${FINPAY_SERVICE_DB_PASSWORD:-finpay}"

if ! docker compose ps --status running --services 2>/dev/null | grep -qx postgres; then
  echo "postgres is not running. Start it with: make up" >&2
  exit 1
fi

exec docker compose exec -e "PGPASSWORD=${password}" postgres \
  psql --host 127.0.0.1 --username "finpay_${service}" --dbname "finpay_${service}" "$@"

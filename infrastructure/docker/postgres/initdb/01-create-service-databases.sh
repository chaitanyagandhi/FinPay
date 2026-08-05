#!/bin/bash
#
# Creates one database and one owner role per FinPay service.
#
# FinPay follows database-per-service: no service may read or write another service's
# tables. Locally that is enforced with a dedicated role per database plus an explicit
# REVOKE CONNECT ... FROM PUBLIC, so a service configured with the wrong credentials
# fails to connect rather than silently reading a neighbour's data.
#
# This script is executed by the postgres entrypoint only on first initialisation of an
# empty data directory. To re-run it: docker compose down -v && docker compose up -d.
#
# The passwords here are local development values only. Production deployments provide
# a distinct secret per service through the platform's secret store.

set -euo pipefail

SERVICES="auth user wallet transaction payment fraud notification audit"
SERVICE_PASSWORD="${FINPAY_SERVICE_DB_PASSWORD:?FINPAY_SERVICE_DB_PASSWORD must be set}"

for service in ${SERVICES}; do
  db="finpay_${service}"
  role="finpay_${service}"

  echo "Creating database ${db} owned by role ${role}"

  psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
    --set=role="${role}" --set=password="${SERVICE_PASSWORD}" --set=db="${db}" <<-'EOSQL'
		CREATE ROLE :"role" WITH LOGIN PASSWORD :'password';
		CREATE DATABASE :"db" WITH OWNER = :"role" ENCODING = 'UTF8';
	EOSQL

  psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
    --set=role="${role}" --set=db="${db}" <<-'EOSQL'
		-- Only the owning service may connect.
		REVOKE CONNECT ON DATABASE :"db" FROM PUBLIC;
		GRANT ALL PRIVILEGES ON DATABASE :"db" TO :"role";
		-- All FinPay timestamps are stored and compared in UTC.
		ALTER DATABASE :"db" SET timezone TO 'UTC';
	EOSQL

  # The public schema is owned by the bootstrap superuser by default; hand it to the
  # service role so Flyway can create objects without superuser rights.
  psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${db}" \
    --set=role="${role}" <<-'EOSQL'
		ALTER SCHEMA public OWNER TO :"role";
		REVOKE ALL ON SCHEMA public FROM PUBLIC;
		GRANT ALL ON SCHEMA public TO :"role";
	EOSQL
done

echo "Created ${SERVICES} databases"

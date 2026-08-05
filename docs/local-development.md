# Local Development

How to build FinPay and run its supporting infrastructure on your own machine.

> **Scope.** At this point in the build the repository contains the Maven multi-module
> project, the backing services (PostgreSQL, Redis, Kafka, Kafka UI), and the config server.
> The remaining application services join `docker-compose.yml` in the steps that implement
> them.

---

## Prerequisites

| Tool | Version | Needed for |
| ---- | ------- | ---------- |
| JDK | **21.x exactly** | The build enforces `[21,22)` and fails on any other major version |
| Docker Desktop | 4.x with Compose v2 | Backing services |
| Make | any | The command shortcuts below (optional but assumed here) |
| Node.js | 20+ | Frontend only, from Phase 7 |

Maven itself is not required — the repository ships a script-only Maven wrapper (`./mvnw`)
pinned to 3.9.16, which downloads the right Maven on first use.

### Installing JDK 21

```bash
brew install openjdk@21
```

Homebrew's `openjdk@21` is keg-only, which means it is deliberately not linked into your
`PATH` and `/usr/libexec/java_home` cannot see it. You do not need to fix that: every
`make` target resolves a JDK 21 automatically via `infrastructure/scripts/java-home.sh`.
If you run `./mvnw` directly, set it yourself:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
```

---

## First run

```bash
make doctor   # verify prerequisites, daemon state and port availability
make env      # create .env from .env.example (optional)
make up       # start the infrastructure and wait until every container is healthy
make verify   # full Maven build: format check, tests, coverage
```

`make up` blocks until all four containers report healthy, so when it returns the stack is
genuinely ready — no arbitrary sleeps needed in scripts or tests.

Expect the first `make up` to take a minute or two while images download, and the first
`make verify` to take longer while Maven populates `~/.m2`.

---

## Commands

Run `make` with no arguments for the current list.

### Infrastructure

| Command | Effect |
| ------- | ------ |
| `make up` | Start everything; wait for health |
| `make images` | Rebuild service images — needed after changing service source |
| `make down` | Stop containers, **keep** all data |
| `make stop` | Pause containers without removing them |
| `make restart` | `down` then `up`, keeping data |
| `make reset` | Delete every volume and start fresh (prompts first) |
| `make ps` | Container state, health and published ports |
| `make health` | One-line health summary per container |
| `make logs` | Follow all logs (`make logs CONTAINER=kafka` for one) |

### Build

| Command | Effect |
| ------- | ------ |
| `make verify` | Format check, unit tests, integration tests, coverage — run this before committing |
| `make build` | Compile and package without the full verify cycle |
| `make test` | Unit tests only |
| `make format` | Apply Spotless formatting |
| `make clean` | Remove Maven build output |

A single module and its dependencies:

```bash
./mvnw -pl wallet-service -am clean verify
```

### Shells

| Command | Effect |
| ------- | ------ |
| `make db` | `psql` into `finpay_wallet` as the `finpay_wallet` role |
| `make db SERVICE=auth` | Same for any of auth, user, wallet, transaction, payment, fraud, notification, audit |
| `make redis` | Authenticated `redis-cli` session |
| `make topics` | List Kafka topics |
| `make config` | Print what the config server serves (`PROFILE=local`, `APP=wallet-service`) |

`make db` connects as the service's own role rather than the superuser, so a permissions
mistake shows up in your shell rather than at runtime.

---

## What runs where

| Service | Host address | Inside the compose network |
| ------- | ------------ | -------------------------- |
| PostgreSQL | `localhost:5432` | `postgres:5432` |
| Redis | `localhost:6379` | `redis:6379` |
| Kafka | `localhost:29092` | `kafka:9092` |
| Kafka UI | http://localhost:8090 | `kafka-ui:8080` |
| Config server | http://localhost:8888 | `config-server:8888` |

Kafka advertises two listeners. Use `localhost:29092` from your IDE, tests and host
processes; containers use `kafka:9092`. Mixing them up produces a connection that succeeds
during metadata fetch and then fails on produce, which is a confusing failure mode worth
recognising.

Host ports for application services are reserved but not yet in use: 8888 config server,
8761 service registry, 8080 gateway, 8081–8088 the domain services.

---

## Databases

FinPay uses database-per-service. `infrastructure/docker/postgres/initdb/` creates eight
databases inside the single local Postgres instance:

`finpay_auth`, `finpay_user`, `finpay_wallet`, `finpay_transaction`, `finpay_payment`,
`finpay_fraud`, `finpay_notification`, `finpay_audit`

Each is owned by a role of the same name, with `CONNECT` revoked from `PUBLIC`, so one
service cannot reach another's data even by accident. Each service role owns its `public`
schema so Flyway can migrate without superuser rights.

**The init script only runs against an empty data directory.** Changing
`FINPAY_SERVICE_DB_PASSWORD` or editing the init script has no effect on an existing
volume — run `make reset` to re-initialise.

Locally these share one Postgres instance for speed and memory; a production deployment
would run a separate instance per service. The isolation rule is enforced either way.

---

## Configuration

### Local overrides

Every value in `docker-compose.yml` has a working default, so the stack starts with no
`.env` file. `.env.example` documents everything that is overridable — copy it with
`make env` when you need to change a port or a password.

`.env` is git-ignored. `.env.example` is committed and must never contain a real secret.

### The config server

Services fetch their configuration from the config server rather than carrying their own
copy of shared settings. It runs on port 8888 and reads from `classpath:/config` inside its
own jar, so nothing external has to be reachable for the platform to start.

| File | Applies to |
| ---- | ---------- |
| `config/application.yml` | Every service, every environment |
| `config/application-local.yml` | Services run on the host (`localhost:29092`, `localhost:6379`) |
| `config/application-docker.yml` | Services run as containers (`kafka:9092`, `redis:6379`) |

Per-service files (`auth-service.yml`, `wallet-service.yml`, …) are added as each service is
implemented. Spring Cloud Config layers them: profile-specific values override shared
defaults, and service-specific files override `application.yml`.

Inspect what a service would receive:

```bash
make config                              # application, docker profile
make config PROFILE=local                # host-facing addresses
make config APP=wallet-service PROFILE=docker
```

Or directly — the endpoint pattern is `/{application}/{profile}`:

```bash
curl -u finpay:finpay http://localhost:8888/application/docker
```

**Authentication.** Every configuration endpoint requires HTTP Basic auth
(`CONFIG_SERVER_USERNAME` / `CONFIG_SERVER_PASSWORD`, defaulting to `finpay:finpay`
locally). `/actuator/health` is deliberately anonymous so container healthchecks and
Kubernetes probes work without credentials.

**Secrets are never stored here.** Sensitive values are written as `${ENV_VAR}` placeholders
and served unresolved; the consuming service resolves them from its own environment. A unit
test (`ServedConfigurationTest`) fails the build if a key matching password/secret/token ever
gains a literal value.

**Changing served configuration** means rebuilding the image, since the files ship inside the
jar:

```bash
make images && make up
```

Production would point the server at a dedicated Git configuration repository instead of the
bundled files; the backend is selected by profile precisely so that swap needs no code
change.

---

## Troubleshooting

**`make doctor` reports no JDK 21.** Install it with `brew install openjdk@21`. The build
refuses other versions on purpose so that every machine and CI produce identical bytecode.

**`docker daemon not running`.** Start Docker Desktop and wait for the whale icon to settle.

**A port is already in use.** `make doctor` names the port. Either stop the other process
or override the port in `.env` (`POSTGRES_PORT`, `REDIS_PORT`, `KAFKA_PORT`,
`KAFKA_UI_PORT`).

**Kafka will not start after changing `KAFKA_CLUSTER_ID`.** The broker refuses to open log
directories formatted with a different cluster id. Either restore the previous id or run
`make reset`.

**A service database is missing or has the wrong password.** The init script runs once, on
an empty volume. `make reset` re-creates everything.

**Maven fails on formatting.** Run `make format`, then `make verify` again. Spotless is
bound to the `validate` phase deliberately, so unformatted code cannot be committed
accidentally.

**Everything is confusing and you want a clean slate.**

```bash
make reset
make clean
make verify
```

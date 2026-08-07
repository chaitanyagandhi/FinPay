# Local Development

How to build FinPay and run its supporting infrastructure on your own machine.

> **Scope.** At this point in the build the repository contains the Maven multi-module
> project, the backing services (PostgreSQL, Redis, Kafka, Kafka UI), the config server, the
> service registry, and the API gateway. The domain services join `docker-compose.yml` in the
> steps that implement them.

---

## Prerequisites

| Tool | Version | Needed for |
| ---- | ------- | ---------- |
| JDK | **21.x exactly** | The build enforces `[21,22)` and fails on any other major version |
| Docker Desktop | **4.44.x (Docker Engine 28.x)** | Backing services and Testcontainers — see below |
| Make | any | The command shortcuts below (optional but assumed here) |
| Node.js | 20+ | Frontend only, from Phase 7 |

Maven itself is not required — the repository ships a script-only Maven wrapper (`./mvnw`)
pinned to 3.9.16, which downloads the right Maven on first use.

### Docker Engine must be 28.x, not 29.x

Integration tests use Testcontainers, which hardcodes Docker API version **1.32** in its
client. Docker Engine 29 raised its minimum accepted API version to **1.44** and rejects
anything older with `400 Bad Request`. Testcontainers reports that as
`Could not find a valid Docker environment` — which reads like a misconfigured machine but
is purely a version mismatch: the `docker` CLI keeps working perfectly throughout.

Nothing configurable works around it. `DOCKER_HOST`, `DOCKER_API_VERSION`, the raw engine
socket and a `testcontainers.properties` override were all tried; Testcontainers pins 1.32
internally. Testcontainers 1.21.3, the newest release, still bundles the affected
docker-java 3.4.2.

Until Testcontainers ships a fix, stay on Docker Desktop **4.44.1** (Engine 28.3.2) and turn
off automatic updates, or every integration test in the project stops running:

```bash
docker version --format 'engine={{.Server.Version}} minAPI={{.Server.MinAPIVersion}}'
# want: engine=28.x
```

Downgrading is safe: named volumes and built images survive.

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
| Service registry | http://localhost:8761 | `service-registry:8761` |
| API gateway | http://localhost:8080 | `api-gateway:8080` |
| Prometheus | http://localhost:9091 | `prometheus:9090` |
| Jaeger | http://localhost:16686 | `jaeger:16686` |
| Service management ports | *not published* | `<service>:9090` |

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

## Service discovery

A standalone Eureka server runs on port 8761. Services register there and resolve each other
by logical name, so an instance can move or scale out without any caller holding an address.

The dashboard is at http://localhost:8761 and requires the same style of HTTP Basic
credentials as the config server (`EUREKA_USERNAME` / `EUREKA_PASSWORD`, defaulting to
`finpay:finpay`). `/actuator/health` is anonymous for healthchecks and probes.

Inspect the registry directly:

```bash
curl -u finpay:finpay -H 'Accept: application/json' http://localhost:8761/eureka/apps
```

**Nothing registers yet.** The registry is running and verified, but the domain services
become discovery clients as they are implemented, so the registry is empty until Phase 2.

**Two deliberate choices:**

- *The registry is not a config-server client.* Discovery is what everything else uses to
  find anything at all; making it wait on another service to start would turn one outage into
  two. Its configuration is local.
- *Self-preservation is off locally, on by default.* Self-preservation stops Eureka evicting
  instances when it loses many heartbeats at once, which protects a real cluster during a
  network partition. With a handful of local instances the same behaviour leaves dead
  services listed as UP for a long time, so `docker-compose.yml` disables it. The application
  default stays `true`, which is what a deployment wants.

---

## API gateway

Everything a client touches goes through `http://localhost:8080`. It is the only application
port published to the host; the domain services are reachable only inside the compose network.

### Routes

| Path prefix | Service |
| ----------- | ------- |
| `/api/v1/auth/**` | auth-service |
| `/api/v1/users/**`, `/api/v1/beneficiaries/**` | user-service |
| `/api/v1/wallets/**` | wallet-service |
| `/api/v1/transactions/**`, `/api/v1/statements/**` | transaction-service |
| `/api/v1/payments/**`, `/api/v1/payment-requests/**` | payment-service |
| `/api/v1/admin/fraud/**` | fraud-service |
| `/api/v1/notifications/**`, `/api/v1/notification-preferences/**` | notification-service |
| `/api/v1/admin/audit-events/**` | audit-service |

Targets are `lb://` URIs resolved against Eureka, so an instance can move or scale out without
a route change. Automatic discovery-based routing is **off**: routes are declared explicitly,
because deriving them from the registry would publish every service the moment it registers.

### What to expect right now

No domain service exists yet, so a routed path returns **503** — the route matches but the
load balancer finds no instance:

```bash
curl -i http://localhost:8080/api/v1/wallets/me     # 503, route exists, no instance
curl -i http://localhost:8080/api/v1/does-not-exist # 404, no route
```

The gateway itself registers with Eureka, so it appears in the dashboard as `API-GATEWAY`.
That is currently the only registration.

### Service-internal paths are refused

Wallet operations such as reserve, release, credit and finalize-debit live under `/internal`
and move money without the checks the public API applies. They must never be reachable from
outside:

```bash
curl -i -X POST http://localhost:8080/internal/v1/wallets/1/reserve  # 404
```

Two independent layers enforce this: no route maps `/internal/**`, and a filter ahead of
routing rejects any path containing an `internal` segment — including one reached by traversal
such as `/api/v1/../internal/...`. The response is 404 rather than 403 so it does not confirm
the path exists. The check is per segment, so a legitimate path like
`/api/v1/internal-transfers` is unaffected.

### Not yet at the gateway

Token validation (Phase 2), request-ID propagation and the shared error format (Step 8),
security headers and CORS (Step 70), and rate limiting (Step 68). The `/actuator/gateway`
endpoint that lists routes and targets stays unexposed until the gateway is authenticated,
since it maps the internal topology.

---

## Request tracing, errors and logs

These three are one mechanism, provided by `finpay-platform-web`. A service gets all of it by
adding the dependency — there is nothing to configure per service.

### Request ids

Every response carries `X-Request-Id`:

```bash
curl -i http://localhost:8080/api/v1/wallets/me | grep -i x-request-id
```

The gateway mints one when a caller sends none, adopts a well-formed one when they do, and
**forwards it to the service it routes to**. Each service puts it in its logging context, so
every line that request produces carries it as a field. One identifier therefore spans the
gateway, the payment service, the wallet service and everything else a single transfer touches.

Send your own to follow a specific call:

```bash
curl -i -H 'X-Request-Id: my-trace-1' http://localhost:8080/api/v1/wallets/me
```

An inbound id is only reused if it is at most 64 characters of letters, digits, `-` and `_`.
Anything else is replaced, because the value ends up in log output and a caller must not be
able to inject newlines and forge log entries.

### Error envelope

Every service and the gateway return the same shape:

```json
{
  "timestamp": "2026-08-07T00:16:29.559094092Z",
  "status": 503,
  "error": "Service Unavailable",
  "code": "SERVICE_UNAVAILABLE",
  "message": "The service is temporarily unavailable. Please retry.",
  "path": "/api/v1/wallets/me",
  "requestId": "my-trace-1"
}
```

`code` is the stable, machine-readable field clients branch on. `requestId` is the same value
as the header, so a bug report that quotes it can be traced directly to the log lines.

Responses never contain a stack trace, an exception type or message, SQL, or an internal host
name. Unanticipated failures are logged in full at ERROR and reported to the caller as a bare
`INTERNAL_ERROR` — the request id is what connects the two.

Services declare their own error codes (`INSUFFICIENT_FUNDS`, `WALLET_FROZEN`, …) as enums
implementing `ErrorCode`. Only protocol-level codes live in the shared `PlatformErrorCode`, so
the shared module never accumulates the whole platform's vocabulary.

### Structured logging

Containers log one JSON object per line in ECS format, with the request id as a field:

```bash
docker compose logs config-server --tail=5
```

Running locally the human-readable format is kept — JSON is for log collectors, not for a
developer reading a terminal. The switch is the `docker` profile, so it needs no code change.

---

## Observability

### Actuator is on a port you cannot reach from outside

Every service binds actuator to port **9090**, and `docker-compose.yml` deliberately does not
publish it. Metrics, health detail and gateway route topology are operator surface, so they
are isolated by port rather than by a path rule or a shared credential:

```bash
curl -i http://localhost:8080/actuator/prometheus   # 404 - not served on the public port
docker compose exec api-gateway wget -qO- http://localhost:9090/actuator/health
```

Exposed endpoints are `health`, `info`, `metrics` and `prometheus` — nothing else. `env` and
`beans` return 404 even on the management port, because they would dump resolved configuration.

On the config server and the registry, `health` is anonymous so container healthchecks and
Kubernetes probes work without secrets; metrics require credentials, which is why the scrape
config carries them.

### Metrics

Prometheus scrapes all three services every 15s and is browsable at http://localhost:9091.

```bash
curl 'http://localhost:9091/api/v1/targets?state=active'
curl 'http://localhost:9091/api/v1/query?query=jvm_memory_used_bytes'
```

Every metric is tagged `application=<service name>`, so one query can compare services.
Targets are listed explicitly rather than discovered through Eureka: with discovery, a service
that was never deployed looks identical to one that has died, and that is precisely the case
an operator needs to notice.

### Tracing

Services export OpenTelemetry spans over OTLP to Jaeger, browsable at http://localhost:16686.

```bash
curl http://localhost:8080/api/v1/wallets/me      # generate a trace
curl -s http://localhost:16686/api/services       # then look for it
```

The code uses Micrometer's vendor-neutral observation API bridged onto OpenTelemetry, so
swapping Jaeger for Tempo or any other OTLP backend is a configuration change.

Sampling is 100% locally, where volume is low and a missing trace costs an afternoon.
`TRACING_SAMPLE_PROBABILITY` lowers it. Jaeger stores traces in memory here — it is a
development aid, not a system of record, so a restart discards them.

Note that inside `@SpringBootTest`, Spring Boot switches metrics export off unless a test is
annotated `@AutoConfigureObservability`; without it the Prometheus endpoint is simply absent.

---

## API documentation

One Swagger page at the edge covers every service: **http://localhost:8080/swagger-ui.html**

The picker lists all eight domain services. Each entry loads through the gateway
(`/v3/api-docs/<service>`), which proxies to that service's own `/v3/api-docs` — so a reader
never needs a service's internal address, and the browser never makes a cross-origin request.

**Until a service exists, its entry returns 503.** That is the same signal as an unavailable
API route: the route is defined, no instance is registered.

### What every service gets for free

A service opts in by adding a springdoc starter. Everything below then comes from
`finpay-platform-web` with no annotation in any controller:

| Provided centrally | Why it is not left to each service |
| ------------------ | ---------------------------------- |
| `ApiError` schema | The published contract must match what the shared handler actually returns |
| 400 / 401 / 403 / 404 / 500 responses on every operation | Documented once, so no endpoint quietly omits them or invents its own shape |
| `bearerAuth` JWT security scheme | One way to authenticate, described identically everywhere |
| Server URL = the gateway | A client calling a service directly bypasses routing, auth and rate limiting |

An endpoint that documents a status itself keeps its own version — the shared defaults only
fill gaps.

Override per service with `finpay.openapi.*` (`title`, `description`, `version`, `public-url`),
or set `finpay.openapi.enabled=false` to opt out.

The config server and the registry deliberately publish **no** OpenAPI document. They expose
framework endpoints rather than a FinPay API, and a Swagger page describing nothing is worse
than none.

---

### Container images

All services share one parameterised build, `infrastructure/docker/service.Dockerfile`,
selected by `MODULE` and `SERVICE_PORT` build arguments. Images are multi-stage (Maven build,
JRE runtime), run as a non-root `finpay` user, and size the heap from the container limit
rather than host memory.

Rebuild after changing service source or served configuration:

```bash
make images && make up
```

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

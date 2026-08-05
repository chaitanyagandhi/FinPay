# FinPay

A cloud-native digital wallet and peer-to-peer payment platform built with Java 21 and Spring Boot microservices.

> **Status: in early development.** The repository currently contains project scaffolding only.
> See [Local Development Status](#local-development-status) for exactly what exists today.

---

## Overview

FinPay is a simulated digital wallet platform in the spirit of Venmo, PayPal, and Cash App. It exists to
demonstrate production-grade backend engineering: distributed transactions, financial correctness, and
operability — not to move real money.

**User capabilities (planned):**

- Create and manage a wallet
- Add funds from a simulated bank account
- Send and receive money
- Withdraw funds
- Request payments from other users
- Manage beneficiaries
- View transaction history and download statements
- Receive payment notifications
- Initiate refunds
- Configure transaction limits

**Administrative capabilities (planned):**

- Review suspicious transactions
- Freeze and unfreeze wallets
- View audit logs and failed payments
- Manage transaction limits
- Inspect system health and operational metrics

### Engineering focus

The interesting problems here are not the CRUD endpoints. They are:

- Preventing double spending under concurrent access to the same wallet
- Guaranteeing idempotent payment operations across retries and duplicate submissions
- Maintaining double-entry ledger consistency as the auditable source of truth
- Coordinating distributed transactions with a saga and compensating actions
- Handling partial failures and timeouts between services
- Supporting end-to-end auditability
- Securing financial APIs
- Providing observability across service boundaries

---

## Planned Architecture

FinPay is a set of independently deployable services, each owning its own PostgreSQL database. Business
state propagates asynchronously over Kafka; synchronous calls are used only where an immediate answer is
required.

```text
finpay/
├── api-gateway            Single entry point, routing, token validation, rate limiting
├── auth-service           Registration, login, JWT issuance, refresh token rotation
├── user-service           Profiles, beneficiaries, KYC simulation, preferences
├── wallet-service         Balances, reservations, debit/credit, freeze, optimistic locking
├── transaction-service    Immutable transactions, double-entry ledger, statements
├── payment-service        Transfers, idempotency, saga orchestration, refunds
├── fraud-service          Velocity checks, risk scoring, manual-review flagging
├── notification-service   Kafka consumers, in-app and simulated email delivery
├── audit-service          Immutable audit events, searchable history
├── service-registry       Eureka server
├── config-server          Centralized configuration
├── frontend               React + TypeScript single-page application
├── infrastructure/        docker, k8s, monitoring, scripts
├── docs/                  Architecture, saga, ledger, security, API, testing
├── docker-compose.yml
├── pom.xml
└── README.md
```

### Architectural rules

- One database per service; no service reads another service's tables.
- No shared JPA entities and no shared module containing business logic.
- Kafka events for state propagation; OpenFeign only when a synchronous response is necessary.
- Every write endpoint that can be submitted twice supports an `Idempotency-Key`.
- Every service exposes health, readiness, metrics, and API documentation endpoints.
- Internal wallet operations (reserve, release, credit, finalize-debit) are never routed through the
  public gateway.

### Financial integrity rules

- Money is `BigDecimal` with explicit scale and rounding. Never `double` or `float`.
- Every completed money movement writes balanced double-entry ledger entries — total debits equal total
  credits.
- The ledger, not the wallet balance column, is the auditable financial record.
- Wallet invariant: `current_balance = available_balance + reserved_balance`, with no negative available
  or reserved balance.
- Currencies use ISO 4217 codes; timestamps are stored in UTC; public identifiers are UUIDs.

### Payment flow (planned)

A peer-to-peer transfer is orchestrated as a saga with explicit compensation:

```text
1. Payment Service validates the request
2. Fraud Service evaluates the transaction
3. Wallet Service reserves sender funds
4. Transaction Service creates a pending transaction
5. Wallet Service credits the receiver
6. Wallet Service finalizes the sender debit
7. Transaction Service writes balanced ledger entries
8. Payment Service marks the payment completed
9. Notification and audit events are published
```

Compensation covers fraud rejection, reservation failure, credit failure, persistence failure, timeout
after reservation, and duplicate event delivery. Kafka consumers are idempotent.

---

## Tech Stack

### Backend

Java 21 · Spring Boot 3.x · Maven · Spring Cloud Gateway · Spring Security · JWT access and refresh
tokens · Spring Data JPA · PostgreSQL · Flyway · Apache Kafka · Redis · OpenFeign · Resilience4j ·
Spring Boot Actuator · Micrometer · Prometheus · OpenTelemetry · Swagger/OpenAPI · MapStruct · Lombok
(sparingly) · Redis-backed rate limiting

### Testing

JUnit 5 · Mockito · AssertJ · Testcontainers (PostgreSQL, Kafka, Redis) · WireMock · Awaitility · k6 for
load and concurrency tests

### Frontend

React · TypeScript (strict) · Vite · React Router · TanStack Query · Zustand · React Hook Form · Zod ·
Tailwind CSS · shadcn/ui · Recharts · Axios via a centralized API client

### Infrastructure

Docker · Docker Compose · Kubernetes manifests · GitHub Actions · Prometheus · Grafana · Jaeger or
Grafana Tempo · Kafka UI · Adminer/pgAdmin · MailHog

---

## Local Development Status

Nothing is runnable yet. The repository currently contains:

- Git repository initialized on `main`
- `.gitignore`, `.editorconfig`, `.java-version`
- This README

**Not yet present:** Maven modules, Docker Compose, any service implementation, the frontend, and CI.

### Prerequisites (for upcoming steps)

- JDK 21
- Docker and Docker Compose
- Node.js 20+ (frontend, from Phase 7)

Setup and run instructions will be added in `docs/local-development.md` as the build progresses.

### Build order

The project is built in numbered steps, one commit per step:

| Phase | Scope |
| ----- | ----- |
| 0 | Repository setup, Maven skeleton, Docker Compose foundation, dev scripts |
| 1 | Config Server, Eureka, API Gateway, logging, error format, observability |
| 2 | Auth Service and User Service |
| 3 | Wallet core: balances, reservations, optimistic locking |
| 4 | Transactions and double-entry ledger |
| 5 | Payment orchestration: idempotency, outbox, saga, compensation, refunds |
| 6 | Fraud, notifications, audit |
| 7 | Frontend |
| 8 | Production hardening, CI/CD, Kubernetes, load tests |

---

## Documentation

Planned documents (added as the corresponding phases land):

```text
docs/architecture.md        docs/payment-saga.md    docs/ledger-design.md
docs/security.md            docs/api.md             docs/local-development.md
docs/testing.md             docs/deployment.md      docs/adr/
```

---

## Disclaimer

FinPay is a **portfolio and educational project**. All financial operations are **simulated**:

- No real bank accounts, cards, or payment processors are integrated.
- No real money is moved, held, or settled.
- Bank funding and withdrawal flows are mock implementations.
- Email delivery is simulated locally.

This project makes **no claim** of PCI DSS, SOC 2, HIPAA, or any banking or regulatory compliance, and it
has not been independently audited. Do not use it to handle real financial data or real customer
information.

---

## License

Not yet specified.

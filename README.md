

# Event Ledger

A distributed financial event processing system composed of two microservices.

## Architecture

```
Browser / Client ──→  Event Gateway API (port 8080)
                             │
                        REST (sync)
                             │
                             ▼
                      Account Service (port 8081)
```

### Event Gateway API (public-facing, port 8080)

Receives financial transaction events, validates input, enforces idempotency, stores event records, and calls the Account Service to apply transactions.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/events` | Submit a transaction event |
| `GET` | `/events/{id}` | Retrieve a single event by ID |
| `GET` | `/events?account={accountId}` | List events for an account (ordered by event timestamp) |
| `GET` | `/health` | Health check |

### Account Service (internal, port 8081)

Manages account state — balances and transaction history. Only called by the Gateway.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/accounts/{accountId}/transactions` | Apply a transaction |
| `GET` | `/accounts/{accountId}/balance` | Get current balance |
| `GET` | `/accounts/{accountId}` | Get account details and transaction history |
| `GET` | `/health` | Health check |

### Key Design Decisions

- **Idempotency**: `eventId` is the primary key in both services. Duplicate submissions return the original response without side effects.
- **Out-of-order tolerance**: Events are stored and listed by `eventTimestamp`, not arrival time. Balance is computed as `SUM(CREDITs) - SUM(DEBITs)` over all stored transactions, so arrival order does not affect correctness.
- **Resiliency**: The Gateway wraps every Account Service call with a **Resilience4j Circuit Breaker**. After 50% failure rate over a 10-call window, the circuit opens for 10 seconds and returns `503 Service Unavailable` immediately. `GET /events/{id}` and `GET /events?account=...` continue to work from the Gateway's own H2 database.
- **Distributed tracing**: A `X-Trace-ID` header is generated at the Gateway edge (or forwarded if provided by the client) and propagated to every Account Service call. Both services inject the trace ID into their MDC so every log line carries it.
- **Structured logging**: Both services use `logstash-logback-encoder` to emit JSON logs containing `timestamp`, `level`, `service`, `traceId`, and message.
- **Metrics**: Custom Micrometer counters (`gateway.events.submitted`, `gateway.events.duplicate`, `account.transactions.applied`) plus Prometheus endpoint at `/actuator/prometheus`.

---

## Prerequisites

- Java 17+
- Maven 3.8+ (`mvn`)
- Docker + Docker Compose (for the containerised path)

---

## Running with Docker Compose (Recommended)

```bash
# From the repository root
docker-compose up --build
```

Both services start. The Gateway waits for the Account Service health check to pass before starting.

Verify:
```bash
curl http://localhost:8080/health
curl http://localhost:8081/health
```

Stop:
```bash
docker-compose down
```

---

## Running Locally (without Docker)

Open two terminals.

**Terminal 1 — Account Service:**
```bash
cd account-service
mvn spring-boot:run
# Starts on port 8081
```

**Terminal 2 — Event Gateway:**
```bash
cd event-gateway
mvn spring-boot:run
# Starts on port 8080
```

---

## Running the Tests

**Event Gateway tests** (includes integration, resiliency, trace propagation, and graceful-degradation tests):
```bash
cd event-gateway
mvn test
```

**Account Service tests** (unit/integration tests for all core functionality):
```bash
cd account-service
mvn test
```

---

## Example Usage

### Submit an event
```bash
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z",
    "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
  }'
```

### Retrieve an event
```bash
curl http://localhost:8080/events/evt-001
```

### List events for an account (ordered by event timestamp)
```bash
curl "http://localhost:8080/events?account=acct-123"
```

### Get account balance
```bash
curl http://localhost:8081/accounts/acct-123/balance
```

### Get account details with full transaction history
```bash
curl http://localhost:8081/accounts/acct-123
```

---

## Resiliency Pattern: Circuit Breaker

The Gateway uses **Resilience4j Circuit Breaker** on every call to the Account Service.

**Why Circuit Breaker over other patterns:**

A circuit breaker protects both the caller and the failing service simultaneously. When the Account Service is down or slow, a timeout+retry pattern would still queue up threads waiting for each retry. A bulkhead isolates resources but does not provide fast-fail for clients. The circuit breaker detects sustained failure and short-circuits immediately, returning a clean `503` to the client without holding threads, giving the Account Service time to recover.

**Configuration** (`application.yml`):
| Parameter | Value | Meaning |
|-----------|-------|---------|
| `slidingWindowSize` | 10 | Track last 10 calls |
| `failureRateThreshold` | 50% | Open after >50% fail |
| `waitDurationInOpenState` | 10s | Stay open for 10 seconds |
| `permittedCallsInHalfOpen` | 3 | Probe with 3 calls before closing |
| `slowCallDurationThreshold` | 4s | Calls >4s count as failures |

**Graceful degradation when circuit is open:**
- `POST /events` → `503 Service Unavailable`
- `GET /events/{id}` → `200 OK` (reads from Gateway's local H2)
- `GET /events?account=...` → `200 OK` (reads from Gateway's local H2)

---

## Observability

- **Structured JSON logs**: Every log line includes `traceId`, `timestamp`, `level`, `service`
- **Health endpoints**: `GET /health` (Gateway), `GET /health` (Account Service)
- **Actuator**: `GET /actuator/health`, `GET /actuator/metrics`
- **Prometheus**: `GET /actuator/prometheus`
- **Circuit breaker state**: `GET /actuator/circuitbreakers` (Gateway)

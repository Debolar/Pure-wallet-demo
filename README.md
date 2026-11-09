# Purse

A demo digital wallet. People open a wallet, add money (a demo top-up), send money to other wallets, and see a statement of every naira in and out. Staff use a read-only **admin page** to see all wallets and transactions, and to check that **the books balance**: no money has been created or lost.

The app code is done. Your job is the DevOps work, this time **all the way to production standard**: security hardening, monitoring, autoscaling and backups.

```
Browser ──▶ Purse (Java 21, Spring Boot) ──▶ PostgreSQL
            serves the website AND the API
```

**Demo only:** all money is fictional. No bank, card or real payment is involved.

## Why a money app is different

The previous apps could tolerate small mistakes. A wallet can't. Three rules protect the money (read the comments in `WalletService.java`):

1. **Atomic.** A transfer's debit, credit and records are one database transaction. If anything fails, all of it is undone. Money is never half-moved.
2. **Locked.** Both wallets are locked (`SELECT ... FOR UPDATE`) in a fixed order before the balance check. Two transfers at the same instant can't both spend the same naira, even with 8 copies of the app running. The fixed order prevents deadlocks.
3. **Idempotent.** The client sends an `Idempotency-Key` header. If the same request arrives twice (a retry after a timeout, a double click), it is applied once.

Also:
- **Money is stored in kobo as whole numbers** (`BIGINT`), never as decimals. `100000` means ₦1,000.00.
- **Double-entry ledger.** Every transfer writes a minus line and a plus line. The admin summary checks that all balances add up to all deposits.
- **The admin page is read-only.** Staff can see everything but can't edit a balance. Every change must be a recorded transaction, so the books always explain themselves.
- **Tokens are stored hashed** (SHA-256) and compared in constant time. The real token is shown to the owner once.

## Project structure

```
purse/
├── pom.xml                     Maven build file: Java version, libraries, how to build the .jar
├── src/main/java/dev/purse/
│   ├── PurseApplication.java   Starts the app
│   ├── WalletService.java      The money rules (read this one)
│   ├── WalletController.java   Wallet API routes
│   ├── AdminController.java    Admin API: summary, wallets, transactions (needs X-Admin-Key)
│   ├── InfoController.java     /api/info
│   ├── Schema.java             Creates the tables on startup
│   ├── Tokens.java             Random IDs, secrets, hashing
│   ├── Dtos.java               JSON shapes in and out
│   └── ApiException.java, ApiErrors.java   Error handling
├── src/main/resources/
│   ├── application.properties  Settings (read from environment variables)
│   └── static/                 The website, plus admin.html (the admin page)
├── src/test/java/dev/purse/    Automated tests (JUnit), including concurrency tests
├── docs/learning-log.md        Your notes, one row per stage
└── .env.example
```

**Java vocabulary you'll meet:** Maven (`mvn`) is Java's build tool, like `pip` + `pytest` together. `pom.xml` is like `requirements.txt` + `pyproject.toml`. `mvn test` runs the tests. `mvn package` builds one file, `target/purse.jar`, that contains the app and all its libraries. A `.jar` runs with `java -jar purse.jar`.

## Stage 0: run it locally (no Java install needed)

You don't need Java on your laptop. Docker runs Maven for you. In Git Bash, from the `purse` folder:

```bash
# Run the tests (the first run downloads libraries, so give it a few minutes)
MSYS_NO_PATHCONV=1 docker run --rm -v "$(pwd -W)":/app -v purse-m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21 mvn -B test

# Run the app at http://localhost:8080
MSYS_NO_PATHCONV=1 docker run --rm -p 8080:8080 -e ADMIN_API_KEY=pick-a-local-key \
  -v "$(pwd -W)":/app -v purse-m2:/root/.m2 -w /app \
  maven:3.9-eclipse-temurin-21 mvn -B spring-boot:run
```

- `MSYS_NO_PATHCONV=1` stops Git Bash rewriting `/app` into a Windows path.
- `purse-m2` is a named volume that keeps the downloaded libraries, so the second run is fast.
- Tests should end with `BUILD SUCCESS` and `Tests run: 19, Failures: 0, Errors: 0`.

**Try it:** open a wallet, copy the ID and token, add ₦50,000. Open a second wallet in a private window and send money between them. Then open the **admin page** at http://localhost:8080/admin.html, unlock it with `pick-a-local-key`, and check the books balance. Or from the terminal:

```bash
curl -H "X-Admin-Key: pick-a-local-key" http://localhost:8080/api/admin/summary
```

Without `DATABASE_URL`, the app uses an in-memory database, so data is lost when it stops.

## Configuration

| Variable | Default | What it does |
|---|---|---|
| `DATABASE_URL` | *(in-memory H2)* | e.g. `jdbc:postgresql://db:5432/purse`. Note the `jdbc:` prefix: Java's format |
| `DB_USER` | `sa` | Database user |
| `DB_PASSWORD` | *(empty)* | **Secret.** Database password |
| `ADMIN_API_KEY` | *(empty)* | **Secret.** Unlocks the admin page and admin API. Empty = admin switched off |
| `APP_VERSION` | `dev` | Shown in the footer. Set it from your pipeline |
| `APP_ENV` | `local` | Environment name shown in the footer |

The app listens on **port 8080** (the Python apps used 8000).

## API reference

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/wallets` | Open a wallet: `{"name","email"}`. Returns `id` and `token` (shown once) |
| GET | `/api/wallets/{id}` | Balance. Needs `X-Wallet-Token` |
| GET | `/api/wallets/{id}/transactions?limit=20` | Statement. Needs `X-Wallet-Token` |
| POST | `/api/wallets/{id}/deposits` | Demo top-up: `{"amount": kobo}`. Optional `Idempotency-Key` |
| POST | `/api/wallets/{id}/transfers` | Send: `{"toWallet","amount","note"}`. Optional `Idempotency-Key` |
| GET | `/api/admin/summary` | Totals and `booksBalance`. Needs `X-Admin-Key` |
| GET | `/api/admin/wallets?limit=50` | All wallets, highest balance first. Needs `X-Admin-Key` |
| GET | `/api/admin/transactions?limit=50` | Latest transactions across all wallets. Needs `X-Admin-Key` |
| GET | `/api/info` | Version, environment, database |
| GET | `/actuator/health/liveness` | Liveness probe |
| GET | `/actuator/health/readiness` | Readiness probe (fails if the database is unreachable) |
| GET | `/actuator/prometheus` | Metrics for Prometheus |

Status codes: `201` created, `200` repeat of an earlier request (with header `Idempotent-Replay: true`), `401` wrong wallet token or admin key, `404` recipient not found, `409` insufficient funds or idempotency key reused, `422` invalid input, `503` admin switched off.

**Metrics the app adds** (on top of request counts and response times):
`purse_transfers_total{outcome="success|insufficient_funds|replayed|invalid"}`, `purse_deposits_total`, `purse_wallets_opened_total`, `purse_auth_failures_total` (a security signal: lots of these = someone guessing tokens).

---

# Your DevOps stages

For every stage: **map** it (say what it does and why), **predict** (write the file or explain the given one before running it), **run and verify**, **break it on purpose**, **log it** in `docs/learning-log.md`.

### 1. Repository
- [ ] GitHub repo `purse`, `.gitattributes`, ruleset Active with an empty bypass list

### 2. Docker  *(new: multi-stage build)*
- [ ] `Dockerfile` with two stages: a **build** stage (`maven:3.9-eclipse-temurin-21`) that runs `mvn package`, and a small **run** stage (`eclipse-temurin:21-jre`) that copies only `purse.jar`. Non-root user, HEALTHCHECK, port 8080
- [ ] `.dockerignore` (keep out `target/`, `.git`, `.env`)
- [ ] `docker-compose.yml` with PostgreSQL, a named volume, and the secrets read from `.env`
- [ ] Check data survives `docker compose down` and `up`

### 3. CI
- [ ] `actions/setup-java` (Temurin 21, Maven cache), `mvn -B verify` on PRs
- [ ] On merge: build image, Trivy scan, push `ghcr.io/<you>/purse:<sha>`; `test` as a required check

### 4. Kubernetes
- [ ] Namespace `purse`; Secrets `db-credentials` and `admin-key` created with `kubectl` (never in Git)
- [ ] PostgreSQL StatefulSet with a PVC; app Deployment (2 replicas) with liveness and readiness probes on the actuator paths
- [ ] Break it: delete the database Pod and watch readiness go to 0/1, then recover

### 5. Ingress
- [ ] Traefik Ingress at `wallet.localhost`

### 6. GitOps
- [ ] Argo CD Application watching `k8s/`, with automated sync, prune and self-heal

### 7. Security hardening  *(new)*
- [ ] Traefik middlewares: rate limit (stricter on `/transfers`) and security headers
- [ ] Pod `securityContext`: run as non-root, read-only root filesystem, drop all capabilities
- [ ] CPU and memory requests and limits
- [ ] NetworkPolicy: only the app may reach PostgreSQL
- [ ] Prove it: a burst of requests gets `429`; a test Pod can't reach the database

### 8. Monitoring (SRE)  *(new)*
- [ ] Install `kube-prometheus-stack` with Helm (Prometheus, Grafana, Alertmanager)
- [ ] ServiceMonitor scraping `/actuator/prometheus`
- [ ] Grafana dashboard: requests per second, error rate, p95 latency, transfers by outcome, auth failures
- [ ] Alerts: error rate above 1%, readiness down, spike in auth failures
- [ ] Write one SLO: "99.5% of transfer requests succeed in under 500 ms over 30 days"

### 9. Autoscaling and load test  *(new)*
- [ ] metrics-server; HorizontalPodAutoscaler (2 to 8 replicas on CPU)
- [ ] Load test with k6: many concurrent transfers; watch Pods being added in Grafana
- [ ] After the test, `/api/admin/summary` must still say `"booksBalance": true`

### 10. Backup and restore  *(new)*
- [ ] CronJob running `pg_dump` every night to a PVC
- [ ] Restore drill: delete the database, restore from backup, check balances and `booksBalance`
- [ ] Write down how long the restore took (your recovery time)

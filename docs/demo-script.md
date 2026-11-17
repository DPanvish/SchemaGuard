# SchemaGuard — End-to-End Portfolio Demo Script

This script provides an end-to-end, reproducible walkthrough of SchemaGuard for technical evaluations, portfolio demonstrations, and contributor onboarding.

---

## Prerequisites

* **Java 21** (`java -version`)
* **Maven 3.9+** (`mvn -version`)
* **Node.js 18+** & **npm** (for the UI console)
* **Docker** (optional, required only for `--sandbox true` container replay)

---

## 1. Zero-Config Build & Regression Suite

Run a clean build from repository root:

```bash
# Compile core engine, CLI, REST API, and run JUnit 5 regression test suite
mvn clean package
```

All unit tests across canonical schema normalization, schema diffing, AST extraction, dependency contract verification, and the 12 risk rules (`SG001`–`SG012`) will execute and pass.

---

## 2. CLI Safety Demonstrations

SchemaGuard provides root wrapper scripts (`./schemaguard.sh` on Linux/macOS and `.\schemaguard.cmd` on Windows) that automatically wrap the CLI JAR.

### Demo 1: Safe Additive Migration (`PASS` — Exit Code 0)
Add a nullable column `preferred_language` to `public.customers`.

```bash
# Linux / macOS:
./schemaguard.sh check \
  --run-id demo-safe \
  --base-dir examples/sample-company/baseline-migrations \
  --candidate-dir examples/sample-company/safe-candidate \
  --contracts test-fixtures/sample-contracts/service-contracts.yaml \
  --policy test-fixtures/sample-contracts/policy.yaml

# Windows:
.\schemaguard.cmd check ^
  --run-id demo-safe ^
  --base-dir examples/sample-company/baseline-migrations ^
  --candidate-dir examples/sample-company/safe-candidate ^
  --contracts test-fixtures/sample-contracts/service-contracts.yaml ^
  --policy test-fixtures/sample-contracts/policy.yaml
```

* **Expected Decision:** `PASS`
* **Exit Code:** `0`
* **Rationale:** Safe additive change with backward compatibility; zero contract-breaking hazards detected.

---

### Demo 2: Contract-Breaking Dropped Column (`BLOCK` — Exit Code 1)
Attempt to drop `email` from `public.customers` while `billing-api` and `notification-worker` declare active read contracts on it.

```bash
# Linux / macOS:
./schemaguard.sh check \
  --run-id demo-unsafe \
  --base-dir examples/sample-company/baseline-migrations \
  --candidate-dir examples/sample-company/unsafe-candidate \
  --contracts test-fixtures/sample-contracts/service-contracts.yaml \
  --policy test-fixtures/sample-contracts/policy.yaml
```

* **Expected Decision:** `BLOCK`
* **Exit Code:** `1`
* **Violations:**
  * `SG001` (`CRITICAL`): Destructive drop of column `public.customers.email`.
  * `SG008` (`CRITICAL`): Breaking service contract for `billing-api` and `notification-worker`.
* **Remediation Output:** Step-by-step Expand–Migrate–Verify–Contract rollout instructions.

---

### Demo 3: Unsupported SQL Statement (`REVIEW_REQUIRED` — Exit Code 3)
Analyze a migration containing administrative SQL (`VACUUM FULL`) under strict policy mode.

```bash
# Linux / macOS:
./schemaguard.sh check \
  --run-id demo-unsupported \
  --base-dir examples/sample-company/baseline-migrations \
  --candidate-dir test-fixtures/invalid-migrations \
  --contracts test-fixtures/sample-contracts/service-contracts.yaml \
  --policy test-fixtures/sample-contracts/policy.yaml
```

* **Expected Decision:** `REVIEW_REQUIRED`
* **Exit Code:** `3`
* **Violations:**
  * `SG010` (`REVIEW_REQUIRED`): SQL statement outside supported analysis scope.
* **Fail-Closed Guarantee:** SchemaGuard never silently passes unfamiliar syntax.

---

### Demo 4: Destructive DML Without Backup Strategy (`SG009`)
Run a migration containing `DELETE FROM public.customers;` or `TRUNCATE TABLE public.customers;` without a declared backup strategy.

* **Expected Finding:** `SG009` (`REVIEW_REQUIRED` — Destructive data modification without declared backup strategy).
* **Remediation Output:** Document a verified snapshot/restore strategy or relocate data modification to an auditable batch job.

---

## 3. Inspecting Persisted Report Artifacts

SchemaGuard writes multi-format reports into `.schemaguard/runs/<run-id>/`:

* `report.md`: Human-readable Markdown summary with severity tags and remediation guides.
* `report.json`: Machine-readable payload for custom CI tooling and API consumers.
* `report.sarif`: OASIS SARIF 2.1.0 standard format for native GitHub Code Scanning integration.

View reports via the CLI:
```bash
./schemaguard.sh report --run demo-unsafe --format markdown
```

---

## 4. Interactive Web Console (Studio UI)

Launch the SchemaGuard Studio to inspect migrations visually:

```bash
# Navigate to the UI module and start Vite dev server
cd schemaguard-ui
npm install
npm run dev
```

Open `http://localhost:5173` in your browser to explore:
* **Interactive Multi-Buffer Studio:** Live editor for Candidate SQL, Baseline SQL, `service-contracts.yaml`, and `policy.yaml`.
* **6 Pre-loaded Studio Scenarios:** Instant reproduction of Appendices B.1 (Safe Additive), B.2 (Contract Break), B.3 (Data-Dependent NULL), Unsupported SQL, Non-Concurrent Index, and Historical Tampering.
* **Blast Radius Dependency Graph:** Visual node-link diagram mapping Services $\rightarrow$ Tables $\rightarrow$ Columns.
* **Structural Diff & Findings Inspector:** Entity-level side-by-side diff with evidence chips and remediation snippets.
* **Dual-Mode Backend:** Seamlessly switches between the Spring Boot REST API (`http://localhost:8080/api/v1`) and deterministic in-browser analysis when running offline.

---

## 5. REST API Service

To run the standalone Spring Boot metadata and analysis API:

```bash
# Launch Spring Boot API on port 8080
mvn spring-boot:run -pl schemaguard-api
```

Available REST Endpoints:
* `POST /api/v1/projects` — Register project configurations.
* `POST /api/v1/projects/{id}/analyses` — Trigger deterministic analysis runs.
* `GET  /api/v1/analyses/{id}` — Fetch run decisions, scores, and status.
* `GET  /api/v1/analyses/{id}/findings` — Retrieve structured findings and remediations.
* `GET  /api/v1/rules` — Inspect rule definitions (`SG001`–`SG012`).

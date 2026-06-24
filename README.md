# RCA Platform

Multi-service Root Cause Analysis pipeline: HAR upload → observability stitch → rule-based bottleneck classification → LLM summary.

## Services

| Module | Port | Role |
|---|---|---|
| `rca-ingestion-service` | 8081 | HAR upload, Kafka publish |
| `rca-analyzer-worker` | 8082 | Observability + heuristics + Gemini |
| `rca-query-service` | 8083 | Job/report API |
| `rca-cli` | — | CLI commands |

## Phase 5 — Observability (current)

**Live:** Kibana (`monitoring*` — perf/trace + ERROR logs)  
**Stubbed:** Graylog (`GRAYLOG_STUB=true`), Grafana/Influx (`GRAFANA_STUB=true`)

Classification uses **HAR + real Kibana** only. Stub Graylog/Grafana data is excluded from scoring and Gemini evidence.

### Setup

1. Copy `.env.example` → `.env` and fill Mongo, Redpanda, Gemini, Kibana vars.
2. Quote wildcards in `.env` (zsh glob trap):
   ```bash
   KIBANA_INDEX_PATTERN='monitoring*'
   KIBANA_ERROR_INDEX='monitoring*'
   GRAYLOG_STUB=true
   GRAFANA_STUB=true
   ```
3. Connect VPN and verify Kibana:
   ```bash
   ./scripts/verify-log-apis.sh
   ```
4. Start services (from repo root):
   ```bash
   mvn spring-boot:run -pl rca-ingestion-service -am
   mvn spring-boot:run -pl rca-analyzer-worker -am
   mvn spring-boot:run -pl rca-query-service -am
   ```

Optional: set `RCA_ENV_FILE=/path/to/.env` if `.env` is not at repo root.

### Smoke test

```bash
# Upload HAR (leave from/to empty for auto window from HAR)
mvn -q -pl rca-cli exec:java -Dexec.args="analyze --har /path/to/capture.har"

# Or via HTTP
curl -F "har=@capture.har" http://localhost:8081/api/v1/rca/analyze

# Poll report
mvn -q -pl rca-cli exec:java -Dexec.args="poll <jobId> 120"
```

**Expected:** `kibana=N` with N > 0, real pod name (not `rca-pod-*`), `observabilitySources: kibana=live graylog=stub grafana=stub`.

### Kibana scripts

| Script | Purpose |
|---|---|
| `scripts/verify-log-apis.sh` | Auth + monitoring/error index + RCA-shaped query |
| `scripts/discover-kibana-error-index.sh` | Find ERROR index pattern on VPN |

### Bottleneck categories

| Code | Source (Phase 5) |
|---|---|
| A — Network | HAR timings + Kibana `networkTimeMs` |
| B — Backend | Kibana `totalWaitTimeMs` / `totalExecTimeMs` |
| C — Database/ES | Kibana `esTimeMs`, `timedOut` |
| D — Exception | Kibana `level=ERROR` |

Graylog (C) and Grafana (B) rules activate when stubs are disabled in a future phase.

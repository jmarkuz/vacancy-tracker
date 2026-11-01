# Architecture Decision Records

Format: each ADR has a status (Accepted / Proposed), context, decision and consequences. Ambiguities found during work are recorded here and implementation continues with the chosen default. Only irreversible or scope-changing decisions are escalated to the owner.

Index:
- ADR-001 Modular monolith
- ADR-002 Java 21 and Spring Boot 3.x
- ADR-003 Spring Data JDBC over JPA
- ADR-004 PostgreSQL 16 with Flyway migrations
- ADR-005 Frontend: Vite + TypeScript + Preact, served by Spring Boot
- ADR-006 Scheduling, single-run lock and SSE progress
- ADR-007 Source policy: no direct scraping of LinkedIn or other forbidden sources
- ADR-008 Rule-based scoring in the MVP; LLM scoring optional later
- ADR-009 One Criterion model for profiles and view filters
- ADR-010 Profile versioning: keep every previous version
- ADR-011 Salary normalization and exchange-rate source
- ADR-012 Upsert rules: automation never overwrites user data
- ADR-013 Deduplication pipeline
- ADR-014 Expiry rule
- ADR-015 Search: PostgreSQL full-text search
- ADR-016 Export formats and encoding
- ADR-017 Authentication: none locally, single token on a VPS
- ADR-018 Adapter resilience: retries, rate limits, circuit breaker
- ADR-019 Deployment: docker compose with .env secrets
- ADR-020 Language policy: Ukrainian for discussion, English for repository content
- ADR-021 Data model identifiers and time zones
- ADR-022 Testing strategy and coverage gate
- ADR-023 Status transition table

---

## ADR-001 Modular monolith
Status: Accepted

**Context.** Single user, one deployable unit, small team (one developer). Microservices would add operational cost with no benefit.

**Decision.** One Spring Boot application split into packages by bounded context: `vacancy`, `collection` (sources, pipeline, scheduler), `matching` (profiles, criteria, rule engine), `export`, `notification`, `web`. Packages depend on each other only through explicit service interfaces. `web` depends on the others; no package depends on `web`.

**Consequences.** Simple deployment. Boundaries are enforced by convention and by an ArchUnit test added in M5 (see PLAN.md). Extraction into services later is possible but not planned.

---

## ADR-002 Java 21 and Spring Boot 3.x
Status: Accepted

**Context.** Requirements include `@Scheduled`, SSE, Actuator, Flyway, Testcontainers and a mature ecosystem for Jsoup, Rome and Jakarta Mail. Virtual threads in Java 21 suit blocking HTTP and IMAP calls.

**Decision.** Java 21 (LTS), Spring Boot 3.x (latest 3.x patch at the start of M1), Maven.

**Consequences.** Virtual threads may be enabled for the collection pool. Build and CI need JDK 21.

---

## ADR-003 Spring Data JDBC over JPA
Status: Accepted

**Context.** The domain is mostly aggregates with simple relations (Vacancy with sources and matches, SearchProfile with criteria, Run with source stats). Operations need explicit SQL: upserts with `ON CONFLICT`, JSONB criteria, `tsvector` search, keyset pagination on 2000+ rows. JPA brings lazy loading, a session cache and dirty checking that complicate idempotent upserts and make SQL behavior harder to predict.

**Decision.** Spring Data JDBC for repositories; explicit SQL where needed. JSONB columns for `criteria.value`, `matchReasons` and `SavedView.criteria`, mapped with a small converter.

**Consequences.** More SQL to write by hand; fewer hidden queries; no N+1 surprises. Entity lifecycle is simpler for upserts. Revisit only if the number of aggregates grows significantly.

---

## ADR-004 PostgreSQL 16 with Flyway migrations
Status: Accepted

**Context.** Full-text search (ADR-015), JSONB, `ON CONFLICT` upserts and Testcontainers support are all needed. The brief fixes PostgreSQL 16.

**Decision.** PostgreSQL 16. Schema changes only through Flyway versioned migrations in `src/main/resources/db/migration`. No `ddl-auto` in any profile.

**Consequences.** Every schema change is a reviewable SQL file. Integration tests run the same migrations on Testcontainers.

---

## ADR-005 Frontend: Vite + TypeScript + Preact, served by Spring Boot
Status: Accepted

**Context.** The UI needs keyboard triage, a drag-and-drop Kanban, a rule builder and live preview. No heavy UI kits are allowed. Bundle size matters on a cheap VPS.

**Decision.** Vite + TypeScript + Preact. No UI component library. The Vite build output is copied into the Spring Boot static resources so the deployment is a single artifact. Charts are simple SVG (no chart library), per the brief.

**Consequences.** Full control over accessibility and styling via the design tokens (see `design/tokens.css`). We write our own small components (table, drawer, bottom sheet, command palette). Drag-and-drop uses the native HTML drag API plus a keyboard alternative.

---

## ADR-006 Scheduling, single-run lock and SSE progress
Status: Accepted

**Context.** Collection runs on a schedule and on demand. Two runs at once would race on upserts and exceed per-source limits. The Refresh button needs live per-source progress.

**Decision.**
- `@Scheduled` with a cron expression from configuration; a per-profile schedule override is allowed (see search-profiles.md).
- A single collection-run lock: a PostgreSQL advisory lock (`pg_try_advisory_lock`) plus an in-process guard. A second trigger while a run is active returns the active run ID instead of starting another run.
- Progress is published as Server-Sent Events from `/api/runs/{id}/events`.
- Graceful shutdown: the scheduler stops accepting new runs; the current run finishes its current source and records `status = interrupted` if it cannot finish.

**Consequences.** Simple and reliable on one instance. Multi-instance deployment is out of scope.

---

## ADR-007 Source policy: no direct scraping of LinkedIn or other forbidden sources
Status: Accepted

**Context.** The brief forbids direct scraping of LinkedIn and bypassing anti-bot protection. Research (see `sources.md`) confirms LinkedIn's User Agreement prohibits automated collection and has been enforced in court (hiQ, Mantheos). Other large boards have similar terms. Scraping is also fragile.

**Decision.** Only the sources listed as Tier 1 (official APIs and feeds), Tier 2 (the user's own email alerts) and, where allowed, Tier 3 (generic HTML with a written terms check) are implemented. Forbidden: direct scraping, CAPTCHA bypass, anti-bot evasion, logging in with the user's account, proxy rotation. The forbidden list is in `sources.md`.

**Consequences.** LinkedIn coverage comes only through email alerts the user creates. Some sources (for example We Work Remotely) have conflicting terms and stay `check` until the owner confirms the use case.

---

## ADR-008 Rule-based scoring in the MVP; LLM scoring optional later
Status: Accepted

**Context.** An LLM score is attractive (JobSync-like matching) but adds cost, latency, privacy concerns (job data sent to a third party) and non-determinism. The brief says "no data sent to third parties other than requests to the sources themselves".

**Decision.** Score is computed by the deterministic rule engine (see search-profiles.md). An LLM-based score can be added in a later milestone as an opt-in feature with an explicit privacy notice. It will be stored as a separate score so the rule score stays reproducible.

**Consequences.** Scores are explainable and testable. Matching quality depends on the user's criteria.

---

## ADR-009 One Criterion model for profiles and view filters
Status: Accepted

**Context.** The brief requires that any view can become a search profile in one click and vice versa.

**Decision.** `Criterion` is one type used by `SearchProfile` rules, Inbox/Pipeline filters and `SavedView`. A view stores its criteria as JSON in the Criterion format. Only `mode` differs in use: filter views use `require`/`exclude` implicitly; "save as search profile" keeps the modes the user sets in the builder.

**Consequences.** One validation and one evaluation path for the rule engine. A small mapping is needed for UI-only filters (for example "has next action") that are expressed as `field = nextActionAt, operator = exists`.

---

## ADR-010 Profile versioning: keep every previous version
Status: Accepted

**Context.** The brief says every change to a profile keeps the previous version. `VacancyMatch` stores `profileVersion`, so a score can be traced to the rules that produced it.

**Decision.** `SearchProfile` has a `version` integer incremented on each change. Each save inserts a row into `search_profile_version` (`profile_id`, `version`, `snapshot JSONB`, `saved_at`). The current row in `search_profile` is the latest version. Import of a profile creates a new version.

**Consequences.** Storage grows slowly (profiles change rarely). Restoring an old version is a new version with copied content. History is shown in the profile screen.

---

## ADR-011 Salary normalization and exchange-rate source
Status: Accepted (rate source is a default; configurable)

**Context.** Salaries come in different currencies, periods (month/year) and gross/net bases. Criteria compare salaries after normalizing to the profile's currency. Rates must be cached and updated regularly. A source is needed that is free, public and does not require a key.

**Decision.**
- Normalization steps: convert the period to yearly (month × 12), convert the currency with the cached rate, compare on the yearly gross amount. Net salaries are not converted to gross; a criterion on net salary only matches net postings and is otherwise handled by `missingValuePolicy`.
- Default rate source: European Central Bank daily reference rates (public XML feed, no key, EUR base; cross rates computed). Rate source is configurable; the rate table keeps `source` and `fetched_at`.
- Rates are refreshed once a day; the last known rate is used when the feed is unavailable and a warning is logged.

**Consequences.** Currencies outside the ECB list are not converted: the criterion is treated as missing data. Rate source can be replaced by a config entry.

**Open question (non-blocking):** the owner may prefer another rate source. Default is ECB.

---

## ADR-012 Upsert rules: automation never overwrites user data
Status: Accepted

**Context.** The brief lists fields automation must never change: status, notes, tags, priority, statusHistory, nextAction*, contact.

**Decision.**
- Automation writes only: `lastSeenAt`, `sources[]` (VacancySource rows), `matchScore` (derived), `isNew` (set to `true` for newly inserted vacancies; cleared by user action), and fields in the normalized set that are not in `userEditedFields[]`: `description`, `salaryMin`, `salaryMax`, `currency`, `salaryPeriod`.
- Every user edit of a field adds the field name to `userEditedFields[]`. A user can reset a field to automatic values, which removes it from the list.
- Status changes by automation are limited to the expiry rule (ADR-014) and only from `New` or `Saved`.

**Consequences.** Upsert is deterministic and tested by unit tests against the rule list in the brief.

---

## ADR-013 Deduplication pipeline
Status: Accepted

**Context.** The same job appears in several sources. The brief defines the order of checks.

**Decision.** Ordered dedup:
1. Canonical URL equal (after stripping `utm_*`, `gclid`, `fbclid`, `ref`, `source` and similar tracking parameters, lowercasing host, removing trailing slash and fragment).
2. Same `(adapter, externalId)`.
3. Fuzzy match on normalized company + normalized title + location, with a similarity threshold configured in `application.yml` (default 0.9 on Jaro-Winkler, reviewed in M2).

When a duplicate is found, the vacancy gets a new `VacancySource` row instead of a new vacancy. Dedup match type is stored in the source row for debugging.

**Consequences.** Fuzzy matching can merge two different jobs with the same title at the same company and location; the threshold is conservative and a manual "split" action is added in M2.

---

## ADR-014 Expiry rule
Status: Accepted

**Context.** The brief: if a vacancy has not been seen for N consecutive runs and its status is New or Saved, it becomes Expired.

**Decision.** `N = 3` by default, configurable. A run counts as "seen" if the vacancy was returned by at least one source that completed successfully in that run. Failed sources do not count as "not seen": a vacancy whose only source failed keeps its counter unchanged. Expiry is a `StatusEvent` with note `auto-expired`.

**Consequences.** A source outage never expires vacancies.

---

## ADR-015 Search: PostgreSQL full-text search
Status: Accepted

**Context.** Full-text search over title, company and description is needed (plus the Ctrl/Cmd+K palette). Data volume is thousands of rows, not millions.

**Decision.** A generated `tsvector` column with a GIN index, `simple` configuration (language-neutral, since the tool is universal and has no language-specific stemming). Search uses `websearch_to_tsquery`.

**Consequences.** No extra search service. No stemming: "developer" does not match "developers". Acceptable for MVP; a language-specific configuration can be added per profile later.

---

## ADR-016 Export formats and encoding
Status: Accepted

**Context.** Exports go to Excel, LLMs and scripts. The brief requires CSV (UTF-8 with BOM), XLSX and a full JSON backup including profiles.

**Decision.**
- CSV: UTF-8 with BOM, RFC 4180 quoting, `;` is NOT used (comma is the default; a separator option is added in M1).
- XLSX: Apache POI with streaming writer (SXSSF) for large sets.
- JSON: full backup document with `schemaVersion`, vacancies, sources, profiles with versions, criteria, saved views, status history.
- Export scope: current filter (as the same Criterion JSON) or all records; column selection is stored in `SavedView.visibleColumns`.
- JSON/CSV import validates each row and returns an error report per row. Import never executes criteria; it stores them after validation.

**Consequences.** Excel users get a BOM so non-ASCII text opens correctly.

---

## ADR-017 Authentication: none locally, single token on a VPS
Status: Accepted

**Context.** Local use needs no login. A VPS needs protection.

**Decision.**
- Profile `local` (default): no authentication, binds to `127.0.0.1` by default.
- Profile `vps`: a single access token from `APP_ACCESS_TOKEN` (in `.env`), sent as an HTTP-only cookie after a one-time entry page, or as `Authorization: Bearer` for scripts. HTTPS is terminated by a reverse proxy (instructions in docs, M5).
- The token is compared in constant time. Failed attempts are rate limited.

**Consequences.** Single-user model only. No user accounts, no password reset flows.

---

## ADR-018 Adapter resilience: retries, rate limits, circuit breaker
Status: Accepted

**Context.** The brief requires custom User-Agent, per-domain rate limiting, ETag / If-Modified-Since, exponential backoff on 429/5xx, timeouts and a circuit breaker.

**Decision.**
- One shared HTTP client with a custom User-Agent (`VacancyTracker/<version> (+personal use)`), connect timeout 5 s, read timeout 20 s.
- Per-domain token bucket rate limiter. Per-source limits are configurable and default to conservative values (for example Remotive: max 4 requests per day, enforced by the limiter and a daily quota).
- Retries: exponential backoff with jitter on 429 and 5xx, max 4 attempts. `Retry-After` is honored.
- ETag and If-Modified-Since stored in `SourceState` and sent on the next request.
- Circuit breaker per adapter (Resilience4j or a small in-house implementation): opens after 5 consecutive failures, half-open after 15 minutes. State is persisted in `SourceState.consecutiveFailures`.
- A failure in one adapter is caught at the adapter boundary and recorded in `RunSourceStat`; other adapters continue.

**Consequences.** Adapters are isolated. Tests use WireMock to simulate 429 and 5xx.

---

## ADR-019 Deployment: docker compose with .env secrets
Status: Accepted

**Context.** Single-host deployment on a local machine or cheap VPS. Secrets must not be committed.

**Decision.** `docker compose up` starts `app` (Spring Boot image) and `postgres:16` with a named volume. Secrets (API keys, IMAP password, Telegram token, access token) come only from `.env`. The repository commits only `.env.example`. Backups: a `backup` service runs `pg_dump` daily into a volume; the UI offers JSON export (ADR-016).

**Consequences.** Reproducible setup. Secrets are never printed in logs (log masking for keys in URLs, see ADR-018 and Jooble note in `sources.md`).

---

## ADR-020 Language policy
Status: Accepted

**Context.** The owner communicates in Ukrainian. Repository content is read by tools and possibly by others.

**Decision.** All code, comments, identifiers, documentation, commit messages and CHANGELOG entries are in English. Conversation with the owner (summaries, questions, approval requests) is in Ukrainian.

**Consequences.** Mixed-language discussion is possible only in chat, never in files.

---

## ADR-021 Data model identifiers and time zones
Status: Accepted

**Decision.**
- Primary keys: `UUID` (generated in the application, v7 if available, otherwise v4) to make imports and exports merge-safe.
- Timestamps: `timestamptz` stored in UTC; the UI renders in the user's local zone. Scheduled cron uses the configured zone (`app.timezone`, default: system zone).
- Money: `numeric(14,2)`; currency is ISO 4217 code. Rates `numeric(18,8)`.

**Consequences.** Export files carry ISO 8601 timestamps with offset.

---

## ADR-022 Testing strategy and coverage gate
Status: Accepted

**Decision.**
- Unit tests: URL canonicalization, dedup, salary parsing, upsert rules, expiry.
- Rule engine tests: all operators and modes, `missingValuePolicy`, salary and currency normalization, recalculation on profile change, several profiles at once.
- Adapter tests: WireMock fixtures only, no real network, including criteria-to-parameter translation.
- Integration tests: Testcontainers PostgreSQL 16 with Flyway migrations.
- Coverage: at least 80% for core packages (`vacancy`, `collection`, `matching`), enforced by JaCoCo in Maven `verify`.
- Frontend: Vitest for store and rule builder logic; Playwright smoke test in M5.

**Consequences.** CI must run Docker for Testcontainers.

---

## ADR-023 Status transition table
Status: Accepted

**Context.** The brief lists statuses and terminal states but not which transitions are allowed. Without a table, any status could be set from any other.

**Decision.** Transitions are defined in `VacancyStatus` (code is the source of truth; this table mirrors it):

| From | Allowed next |
|---|---|
| New | Saved, Applied, Dismissed, Hidden, Expired |
| Saved | Applied, Dismissed, Withdrawn, Expired |
| Applied | Screening, Rejected, Withdrawn |
| Screening | Interview, Test task, Rejected, Withdrawn |
| Interview | Test task, Offer, Rejected, Withdrawn |
| Test task | Interview, Offer, Rejected, Withdrawn |
| Offer | Accepted, Rejected, Withdrawn |
| Accepted | none |
| Rejected, Withdrawn, Expired, Dismissed, Hidden | New (restore only) |

Automation may set only Expired (from New or Saved), per ADR-014.

**Consequences.** Invalid transitions fail with a domain exception. Restoring a terminal vacancy goes through New, so the history stays linear.


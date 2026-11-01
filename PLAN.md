# PLAN

Development plan and progress tracker. Source of truth for tasks; the same list is in [tasks.csv](tasks.csv) for import into GitHub Projects or Jira.

Conventions:
- Task ID: `T-NNN`. Checkbox `[ ]` open, `[x] YYYY-MM-DD` done.
- Estimates: `S` up to half a day, `M` up to one day. No task is larger than one day; larger work is split.
- Definition of Done: code and tests green, no lint errors, acceptance criteria met, checkbox dated here, entry in [CHANGELOG.md](CHANGELOG.md), one commit using Conventional Commits.
- Decisions and ambiguities go to [docs/decisions.md](docs/decisions.md).

## MVP cut

The MVP is the smallest set after which the tool already delivers value: vacancies are collected automatically from official APIs, shown in an Inbox, triaged by keyboard, tracked in a table with status history, and scored by one search profile.

MVP = T-011 to T-016, T-018, T-019, T-022, T-027 to T-031, T-034, T-037, T-041 to T-046, T-048, T-049, T-050, T-052, T-054, T-055, T-058.

Everything else is post-MVP (filters/views polish, export, Kanban, email, Telegram, hardening).

## M0 Plan & Design

| Done | ID | Title | Est | Depends on | Acceptance criteria |
|---|---|---|---|---|---|
| [x] 2026-11-01 | T-001 | Research: comparable solutions | S | - | `docs/research.md` lists each solution, what we borrow, what we reject |
| [x] 2026-11-01 | T-002 | Sources: verified terms, limits, capabilities | M | T-001 | `docs/sources.md` has URL, auth, limits, attribution, risks and status for every source; capability matrix present. Status `check` where official pages were not read (see T-027) |
| [x] 2026-11-01 | T-003 | Architecture: C4, pipeline, ERD | M | T-001 | `docs/architecture.md` contains Mermaid context, container, pipeline and ERD |
| [x] 2026-11-01 | T-004 | ADRs | M | T-001 | `docs/decisions.md` has ADR-001 to ADR-022 with status, context, decision, consequences |
| [x] 2026-11-01 | T-005 | Search profile schema and example | M | T-004 | `docs/search-profiles.md` covers schema, operators, modes, missingValuePolicy, score formula, adapter translation; `docs/examples/search-profile.example.yml` is neutral |
| [x] 2026-11-01 | T-006 | PLAN.md, tasks.csv, CHANGELOG | S | T-001..T-005 | Files exist; tasks in both files match |
| [x] 2026-11-01 | T-007 | Design tokens | S | T-004 | `design/tokens.css` defines colors (light and dark), typography, spacing, radii as custom properties |
| [x] 2026-11-01 | T-008 | Dashboard mockup | M | T-007 | `design/dashboard.html` is clickable: Dashboard, Inbox, Pipeline (table and Kanban), Vacancy drawer, Sources & Runs, Search profiles with rule builder and preview; theme toggle; no dependencies except Google Fonts |
| [x] 2026-11-01 | T-009 | Design README | S | T-008 | `design/README.md` covers rationale, empty, loading and error states, keyboard shortcuts |
| [x] 2026-11-01 | T-010 | Project README | S | T-003 | `README.md` covers vision, repository structure, run placeholder |

Phase 0 ended with an approval request to the owner. Approved 2026-11-01.

## M1 Core tracker

| Done | ID | Title | Est | Depends on | Acceptance criteria |
|---|---|---|---|---|---|
| [x] 2026-11-01 | T-011 | Maven bootstrap: Java 21, Spring Boot 3.x, package skeleton, JaCoCo, lint | S | T-010 | `mvn verify` green with a smoke test; packages from architecture.md exist |
| [x] 2026-11-01 | T-012 | docker compose with PostgreSQL 16 and `.env.example` | S | T-011 | `docker compose up` starts app and db; `/actuator/health` is UP |
| [x] 2026-11-01 | T-013 | Flyway baseline schema: vacancy, vacancy_source, status_event, saved_view | M | T-011 | Migration applies on Testcontainers PostgreSQL 16; matches ERD |
| [ ] | T-014 | Vacancy domain and status machine | M | T-011 | Allowed transitions tested; terminal states enforced; every change produces a StatusEvent |
| [x] 2026-11-01 | T-015 | Vacancy REST API: create, read, update, list with paging | M | T-013, T-014 | Endpoints work; invalid input returns 422 with field errors; integration test |
| [ ] | T-016 | Upsert rules and userEditedFields | M | T-014 | Unit tests prove automation never changes status, notes, tags, priority, statusHistory, nextAction*, contact; edited fields are recorded |
| [x] 2026-11-01 | T-017 | Canonical URL utility | S | T-011 | Strips utm_* and tracking parameters; lowercases host; unit tests |
| [x] 2026-11-01 | T-018 | Frontend bootstrap: Vite, TypeScript, Preact, app shell, tokens | M | T-011, T-007 | `npm run build` output is served by Spring at `/`; layout works at 360 px width with 16 px gutter |
| [x] 2026-11-01 | T-019 | Pipeline table: sorting, column selection, sticky header, keyset paging | M | T-015, T-018 | 2000+ rows scroll smoothly; sort is stable; column choice persists in URL or view |
| [ ] | T-020 | Criterion model and filter evaluation for views | M | T-015 | Criteria translate to SQL for status, source, country, workMode, score, dates; URL state round-trips |
| [x] 2026-11-01 | T-021 | Filter chips and saved views | M | T-019, T-020 | Filters stored in URL; a saved view can be reopened and edited |
| [x] 2026-11-01 | T-022 | Vacancy drawer: view, edit, status change, notes | M | T-015, T-018 | Status change creates StatusEvent; edited fields are marked and protected from upsert |
| [x] 2026-11-01 | T-023 | Manual add by URL with JSON-LD JobPosting | M | T-017, T-015 | Fixture pages parsed; fallback to a manual form when no JSON-LD |
| [x] 2026-11-01 | T-024 | CSV export: UTF-8 with BOM, column selection, current filter | S | T-020 | Test checks BOM and quoting; Cyrillic and accented text intact |
| [x] 2026-11-01 | T-025 | XLSX export with streaming writer | S | T-024 | File opens; row count equals the filtered set |
| [x] 2026-11-01 | T-026 | JSON backup export and JSON/CSV import with per-row errors | M | T-015 | Round-trip test; error report lists row numbers and reasons |

## M2 Collection

| Done | ID | Title | Est | Depends on | Acceptance criteria |
|---|---|---|---|---|---|
| [ ] | T-027 | Re-verify source terms against official pages | S | T-010 | Every row in `docs/sources.md` is `ok` or `blocked`, with verification date and official link |
| [x] 2026-11-01 | T-028 | HTTP client: User-Agent, timeouts, per-domain rate limit, ETag, If-Modified-Since | M | T-011 | WireMock tests: headers sent; 304 handled; per-domain limit enforced |
| [x] 2026-11-01 | T-029 | Retries with backoff, Retry-After, circuit breaker, SourceState | M | T-028 | 429 and 5xx retried with backoff; breaker opens after 5 failures and recovers half-open |
| [x] 2026-11-01 | T-030 | Adapter SPI, capability declaration, WireMock fixture harness | S | T-028 | Contract test every adapter must pass |
| [ ] | T-031 | Adapter: ats-greenhouse | M | T-027, T-030 | Fixture-based tests; criteria-to-request translation tested |
| [ ] | T-032 | Adapter: ats-lever | S | T-027, T-030 | As T-031 |
| [ ] | T-033 | Adapter: ats-ashby | S | T-027, T-030 | As T-031 |
| [ ] | T-034 | Adapter: arbeitnow | M | T-027, T-030 | As T-031; visa filter translated when criterion exists |
| [ ] | T-035 | Adapter: adzuna | M | T-027, T-030 | Key from `.env`; country validated against documented list; key never appears in logs |
| [ ] | T-036 | Adapter: jooble | M | T-027, T-030 | POST body built; key masked in logs and errors |
| [ ] | T-037 | Adapter: remotive with daily quota and attribution | S | T-027, T-030 | At most 4 calls per day enforced; attribution stored with each vacancy |
| [ ] | T-038 | Adapter: himalayas | S | T-027, T-030 | Pagination with limit 20; 429 handled |
| [ ] | T-039 | Adapter: jobicy | S | T-027, T-030 | `count` parameter used; fixtures tested |
| [ ] | T-040 | Generic RSS/Atom adapter (Rome) | M | T-030 | Fixture feed parsed; HTML in items sanitized |
| [ ] | T-041 | Normalization: HTML cleanup, salary parsing, workMode, country, seniority | M | T-017 | Unit tests for edge cases: missing salary, period words, currency symbols |
| [x] 2026-11-01 | T-042 | Dedup: canonical URL, externalId, fuzzy company+title+location | M | T-016, T-041 | Tests for each rule in order; duplicates add VacancySource rows |
| [x] 2026-11-01 | T-043 | Collection pipeline orchestration and RunSourceStat | M | T-029, T-041, T-042 | A failing adapter does not stop others; per-source stats stored |
| [ ] | T-044 | Run lock, cron scheduler, graceful shutdown | M | T-043 | Concurrent trigger returns the active run; shutdown finishes or marks run interrupted |
| [ ] | T-045 | Expiry detection | S | T-043 | Expires after N=3 successful runs without sighting; failed sources do not count |
| [ ] | T-046 | SSE progress and Refresh button with summary toast | M | T-044, T-018 | Per-source progress live; toast reads "+N new, M updated, K sources failed" and links to Inbox |
| [ ] | T-047 | Sources & Runs screen with health and "Refresh this source" | M | T-044, T-018 | Health shown as ok, warn or error with text, not color alone |
| [ ] | T-048 | Inbox with keyboard triage (J/K/S/X/O) | M | T-043, T-018 | Full triage possible without mouse; focus visible |

## M3 Matching & Insights

| Done | ID | Title | Est | Depends on | Acceptance criteria |
|---|---|---|---|---|---|
| [ ] | T-049 | SearchProfile model and versioning | M | T-013 | Each save creates a version row; previous versions are readable |
| [ ] | T-050 | Rule engine: operators, modes, missingValuePolicy | M | T-020, T-049 | Matrix tests cover every operator and mode and every policy |
| [ ] | T-051 | Salary normalization and ECB rate cache | M | T-041 | Period and currency conversion tested; missing rate treated as missing data |
| [ ] | T-052 | Score formula, matchReasons, VacancyMatch storage | M | T-050, T-051 | Score in 0..100; monotonic property test; reasons reference criterion IDs |
| [ ] | T-053 | Profile recalculation job and "Recalculate scores" button | S | T-052 | Background job; user statuses unchanged after recalculation |
| [ ] | T-054 | Profile API with criterion validation | M | T-049, T-050 | Operator not allowed for field returns field-level error |
| [ ] | T-055 | Profile list and rule builder: reorder, duplicate | M | T-054, T-018 | Reorder by keyboard; duplicate creates an independent copy |
| [ ] | T-056 | Live preview: counts and sample vacancies | M | T-052, T-055 | Counts equal engine output; no VacancyMatch rows written by preview |
| [ ] | T-057 | Profile YAML/JSON import and export | S | T-054 | Round-trip test; example file is imported only when the user imports it |
| [ ] | T-058 | Onboarding wizard on first run | S | T-055 | Creates the first profile; no profile created automatically from example |
| [ ] | T-059 | Allowlist and blocklist, global and per profile | S | T-052 | Blocked company hidden; allowlisted company gets bonus |
| [ ] | T-060 | Dashboard screen | M | T-052, T-046 | KPI cards open filtered Inbox or Pipeline; charts are plain SVG |
| [ ] | T-061 | Kanban with drag-and-drop and keyboard alternative | M | T-019, T-016 | Status change by drag and by keyboard; both create StatusEvent |
| [ ] | T-062 | Full-text search and Ctrl/Cmd+K palette | M | T-013, T-018 | Postgres FTS returns ranked results; palette opens with shortcut |
| [ ] | T-063 | Bulk actions with Undo | S | T-019 | Undo restores previous statuses for the whole batch |

## M4 Email & notifications

| Done | ID | Title | Est | Depends on | Acceptance criteria |
|---|---|---|---|---|---|
| [ ] | T-064 | IMAP reader for a dedicated folder, mark as processed | M | T-043 | Fixture mailbox; no message processed twice |
| [ ] | T-065 | Email parser framework and LinkedIn alert parser | M | T-064 | Parser tested on saved email fixtures; one parser per sender |
| [ ] | T-066 | Parsers for Jobs.cz, StartupJobs.cz, Indeed | M | T-065 | Fixtures per sender; unknown sender is skipped and logged |
| [ ] | T-067 | Telegram digest, disabled by default | S | T-052 | Digest only for scores at or above notifyMinScore; off unless enabled |

## M5 Hardening

| Done | ID | Title | Est | Depends on | Acceptance criteria |
|---|---|---|---|---|---|
| [ ] | T-068 | Testcontainers integration suite and 80% core coverage gate | M | T-043, T-052 | JaCoCo gate fails the build below 80% for vacancy, collection, matching |
| [ ] | T-069 | ArchUnit package boundary test | S | T-011 | No package depends on `web` |
| [ ] | T-070 | Daily pg_dump backup service | S | T-012 | Backup file appears in the volume daily; restore documented |
| [ ] | T-071 | VPS access token and login rate limit | M | T-012 | Requests without token get 401; repeated failures are rate limited |
| [ ] | T-072 | Structured logs with key masking, run metrics | S | T-043 | Test proves no API key appears in logs |
| [ ] | T-073 | Tier 3 generic HTML adapter, approved sites only | M | T-030, T-027 | Adapter runs only for sites with a recorded terms check; robots.txt honored |
| [ ] | T-074 | Playwright smoke test | S | T-055, T-048 | Smoke test runs in CI |
| [ ] | T-075 | Deployment docs: VPS and reverse proxy HTTPS; run instructions | S | T-012, T-070 | Steps followed on a clean machine produce a running instance |

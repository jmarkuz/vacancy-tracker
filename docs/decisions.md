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
- ADR-024 Frontend test tooling and accepted dev-only advisories
- ADR-025 missingValuePolicy penalize means a weight penalty
- ADR-026 Automatic inbox hiding and its limits
- ADR-027 to ADR-038: see the records below (profile import, company lists, mail, Telegram, backup, token, logs, HTML and feed adapters, review follow-ups)
- ADR-039 Review 2026-11-02: host check, row locks, manual vacancies, web links, keyboard commits
- ADR-040 Resume and the fit score
- ADR-041 Claude adapter and the AI opt-in
- ADR-042 Tailored documents, versions and application packages
- ADR-043 Correspondence: job mail, links to applications, status suggestions
- ADR-044 Visa sponsorship from the posting text
- ADR-045 New sources from the owner's list
- ADR-046 design, dark first

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

**Implementation note (T-015).** The vacancy repository uses `JdbcClient` with explicit SQL and maps rows to a `VacancyState` record; no Spring Data repository interfaces are used yet. The aggregate is rebuilt with `Vacancy.restore(VacancyState)`.

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

**Implementation note (T-044).** The single-run lock is an in-process guard in `RunCoordinator`, plus a `run` row in status `running`. The PostgreSQL advisory lock from the decision above is not used yet: it needs a dedicated session for the whole run and only matters with more than one instance. Shutdown asks the run to stop between sources, waits 30 seconds, and then marks the run `interrupted`.

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

**Implementation note (T-049).** A version snapshot stores the full editable content of the profile (name, enabled, sources, schedule, thresholds, criteria) as JSON, so an old version reads back with the same shape. The `criterion` table is keyed by `(profile_id, id)`: the criterion id is the stable, user-visible text id that match reasons refer to, so it is unique per profile and not a global UUID as the first ERD sketch showed. A rejected save changes nothing: validation runs before any write, and the whole save is one transaction.

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

**Implementation note (T-051).** The ECB daily XML feed is read through the shared HTTP client once at startup (optional) and then at 16:30 Europe/Berlin on working days; the stored rates (`exchange_rate`, V7) are held in memory. Cross rates go through EUR. A currency without a rate, and an unknown period (for example hourly), make the salary missing data. A failed refresh logs a warning and keeps the last known rates. Salary criteria carry their own currency, so the vacancy's salary is converted into the currency of the criterion it is compared with; there is no separate profile currency. The ECB must be cited as the source wherever a converted amount is displayed.

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

**Implementation note (T-042).** Fuzzy matching uses `JaroWinklerSimilarity` from Apache commons-text on the key `title | location` (lower-cased), among vacancies with the same company. Candidates are capped at 200 per lookup.

**Revision (review, 2026-11-02).** Jaro-Winkler on the whole key scored "Software Engineer | Berlin" against "... | London" at 0.94 and "Engineer I" against "Engineer II" at 0.97, so distinct openings merged and the second one never appeared. The fuzzy rule now needs (a) compatible places: either is unknown, they are equal, or one contains the other, and (b) titles equal after dropping punctuation and "(m/f/d)", or equal except for one word that differs by a typo (at most two edits, six letters or more, no digits). Levels and variants in other words are different vacancies.

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

**Consequences.** Adapters are isolated. Tests simulate 429 and 5xx with a local HTTP server (see ADR-022).

**Pipeline rule for 304 (T-028).** A 304 Not Modified response means the source is unchanged since the last successful run. The pipeline treats every vacancy previously seen on that source as seen in this run, so expiry (ADR-014) is not triggered by an unchanged feed.

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
- HTTP adapter tests (T-028): a local JDK `HttpServer` serves fixtures instead of WireMock, to avoid another test dependency. The adapter contract is the same: no real network.
- Coverage: at least 80% for core packages (`vacancy`, `collection`, `matching`), enforced by JaCoCo in Maven `verify`.
- Frontend: Vitest for store and rule builder logic; Playwright smoke test in M5.

**Consequences.** CI must run Docker for Testcontainers.

**Coverage gate (T-068).** `mvn verify` runs a JaCoCo check for each of `vacancy`, `collection` and `matching` (all their subpackages together) and fails the build when line coverage of one falls below `jacoco.minimum.line` (0.80). Integration tests with a real PostgreSQL in Testcontainers are part of the measured run. At the time of writing the modules stand at 97.7%, 91.4% and 90.9%. Only lines are gated; branch coverage is reported (about 77%) but not enforced. `web`, `export` and `notification` are measured but not gated. Adapter tests use a local JDK HTTP server instead of WireMock (see the note above).


**Smoke test (T-074).** `e2e/` holds a Playwright test (separate package, so the frontend dependencies stay small) that drives the packaged app with its UI on an empty PostgreSQL: first run and the wizard, the dashboard counts, Inbox triage with the keyboard, the board, the command palette, and every main screen loading, failing on any script error in the browser. `npm run test:local` in `e2e/` runs it on a developer machine (Docker for PostgreSQL, the built jar, installed Google Chrome); `.github/workflows/ci.yml` runs it in CI as the `smoke` job next to the `backend` (Maven verify) and `frontend` (typecheck, tests, build) jobs. Playwright is pinned to 1.55.1, the first version without the browser-download advisory.

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

---

## ADR-024 Frontend test tooling and accepted dev-only advisories
Status: Accepted (revisit when the toolchain moves to Node 20 or later)

**Context.** The Definition of Done needs green tests for the UI. The local toolchain is Node 18. Vite 6 is the newest line that runs on Node 18. `npm audit` reports a high-severity advisory in Preact 10.27.0 to 10.27.2. The fix is 10.27.3, which is applied. It also reports moderate and critical advisories in the test runner: `@vitest/mocker` (Vitest 3 line) and `tinypool` 1.x. Their fixed versions require Vitest 4, which needs a newer Node.

**Decision.**
- Unit tests use Vitest 3.2.7 with jsdom 26 (`npm test`). Covered: criteria model, URL routing, status transition mirror, theme storage handling, API error messages, salary formatting.
- Preact is pinned to 10.27.3 (patched).
- The Vitest and tinypool advisories are accepted for now. They affect only the test runner, which is never shipped in the Docker image or served to the browser.

**Consequences.** Re-run `npm audit` and move to Vitest 4 as soon as the local and CI Node version is 20 or later.

---

## ADR-025 missingValuePolicy penalize means a weight penalty
Status: Accepted

**Context.** The first version of docs/search-profiles.md said that `penalize` makes a missing value "count as not satisfied", which for `prefer` meant no bonus and for `avoid` no penalty. That is the same as `ignore` for two of the four modes. The score formula in the same document already added the weight of every penalize-policy criterion with a missing field to the negative sum.

**Decision.** A missing value with policy `penalize` subtracts the criterion's weight from the score, in every mode, and never filters the vacancy. `hide` filters regardless of mode. `ignore` skips the criterion. `exists` and `not_exists` are never missing.

**Consequences.** `penalize` has its own effect, so a user can say "an unknown salary is worse than a known good one" without hiding it. The rule engine and the score formula agree.

---

## ADR-026 Automatic inbox hiding and its limits
Status: Accepted

**Context.** A profile's `inboxMinScore` and its filters decide whether a vacancy belongs in the Inbox (docs/search-profiles.md section 4). ADR-012 says automation never overwrites status. Without any automatic change the threshold would have no effect, and a user could not trust a profile edit not to override their own decisions.

**Decision.** Scoring (during a run, and in the recalculation job) may move a vacancy between New and Hidden, under these rules:
- A New vacancy becomes Hidden when no applicable profile accepts it: every applicable profile either filters it out or scores it below that profile's `inboxMinScore`. One accepting profile is enough. With no applicable profile nothing is filtered.
- A vacancy that was hidden automatically becomes New again when it qualifies.
- Automation acts only while the vacancy's whole status history consists of automatic events (status event notes starting with `auto-`). When the user has changed the status even once, including hiding or restoring a vacancy, automation never touches it again.
- Automatic changes keep the `isNew` flag, because the user has not seen the vacancy.

**Consequences.** Recalculation after a profile change can hide or restore vacancies, but never reverses a decision of the user. The history shows every automatic change with its note.


## ADR-027 Strict profile import and company lists

Status: Accepted (T-057)

**Context.** The profile file format has `companyLists`, but allow and block lists are built in T-059 and have no storage yet.

**Decision.**
- Import is strict: an unknown key anywhere, including `companyLists`, is rejected with its path. Silently dropping data on import would hide a typo or a lost setting.
- The example file keeps `companyLists` commented out until T-059, which then makes the key valid.
- A file with the name of an existing profile (case-insensitive) creates a new version of that profile, as in docs/search-profiles.md section 6; otherwise a new profile.
- YAML is read with Jackson's YAML parser, which does not instantiate arbitrary classes from tags. JSON files are read by the same parser.

**Consequences.** Exported files always import cleanly. Importing a file with company lists fails until T-059.

## ADR-028 Company allowlist and blocklist

Status: Accepted (T-059)

**Context.** docs/search-profiles.md section 2.6 says an allowlisted company is always shown and gets a bonus, and a blocklisted one is always filtered out, globally or per profile. It does not say what wins when the lists and the rules disagree.

**Decision.**
- A blocklisted company is filtered out for the profile: no score, hidden, reason of kind `blocklist`. A block in any scope wins over an allow in any scope.
- An allowlisted company gets `+10` (`app.matching.allowlist-bonus`) with a reason of kind `allowlist`, and is not hidden for scoring below `inboxMinScore`. It does not override `require` and `exclude` rules: those are the user's explicit rules about the vacancy, so "always shown" is limited to the score threshold.
- Companies compare without case and extra spaces, and match whole names only ("Acme" is not "Acme Corp").
- An entry is global (`profile_id` null) or belongs to one profile and is deleted with it. Adding an existing entry changes nothing.
- A change to a list starts a background recalculation, like a profile change.
- A profile file carries only that profile's own lists (`companyLists`). Importing a file with the key replaces them; a file without it leaves them alone. Global lists are not part of a profile file.
- The live preview uses global lists only, because a draft has no id yet.

**Consequences.** Lists are exact-name matches, so spelling variants need separate entries. The preview can differ slightly from the saved result for a profile with its own lists.

## ADR-029 IMAP reader: read only, processed state in the database

Status: Accepted (T-064)

**Context.** Alert emails come from the user's own mailbox (docs/sources.md Tier 2). A message must not be handled twice, and the tool must not damage the mailbox.

**Decision.**
- The reader never deletes, moves or expunges. By default it opens the folder read-only and fetches with `peek`, so it does not even mark messages as read.
- "Processed" is stored in the database (`processed_mail`), keyed by the Message-ID header, or by folder, UID validity and UID when there is none. This holds even on servers that cannot store flags, and when a message is copied to another folder.
- With `app.mail.imap.mark-processed=true` the folder is opened read-write and handled messages also get the user flag `VacancyTrackerProcessed`, where the server allows user flags. The flag is for the user's convenience; the database decides.
- A handler answers PROCESSED (done or deliberately skipped) or RETRY. Only PROCESSED is remembered, so a message that could not be handled yet is offered again.
- At most `max-per-run` (default 50) messages are offered per read, oldest first; the rest waits for the next read. Bodies are capped at 2 million characters and attachments are never read.
- The password comes from `.env` only. Error text shows the server's message but never the password, and TLS with host name checking is the default.
- Disabled unless `app.mail.imap.enabled=true` and a host and user are set.

**Consequences.** A message handled while the database was reset would be handled again. IMAP IDLE (push) is not used; the reader is polled. Which folder holds the alerts is the user's choice (default "Job alerts").

## ADR-030 Email sources: one parser per sender, no expiry

Status: Accepted (T-065)

**Context.** Alert emails are read once (ADR-029). A vacancy found only in an email is not listed again, so the expiry rule (ADR-014) would expire it after three runs although nothing is known about the posting.

**Decision.**
- Each sender has one `MailParser` and one adapter (`email-<sender>`). A parser reads only the email content; it never opens a link, never logs in to the sender's site and rewrites job links to their plain form without tracking parameters.
- An adapter handles mail of its own sender, leaves mail of another known sender for that sender's adapter (RETRY), and logs and marks as processed mail of a sender nobody handles, so unknown mail cannot pile up.
- An email that its parser accepts but finds no vacancies in is processed and logged as a warning, because a changed layout would otherwise be retried forever.
- Vacancies whose only sources are email sources do not take part in expiry. The user closes them by hand.
- The vacancy's posting date is the date of the email.

**Consequences.** Email vacancies stay in the Inbox or Saved until the user acts. A changed email layout shows as a warning in the log and as no new vacancies from that source.

## ADR-031 Telegram digest

Status: Accepted (T-067)

**Context.** The brief offers an optional Telegram digest, off by default. It is the one place where data about vacancies leaves the machine for a third party other than the sources.

**Decision.**
- Off unless `app.notifications.telegram.enabled=true` and both a bot token and a chat id are set. Both come from `.env` only.
- One message after each collection run, built from vacancies in status New whose best score among enabled profiles reaches that profile's own `notifyMinScore`. Filtered vacancies and vacancies of disabled profiles are never included.
- The message contains only the score, title, company, place and link of each vacancy, as plain text (no Telegram markup, so titles cannot inject formatting or links). Descriptions, notes and contacts are never sent.
- A vacancy is announced once. It is remembered only after Telegram accepted the message, so a failed send is retried after the next run. At most `max-items` (10) vacancies go in one message; the rest wait for the next one. The message stays under Telegram's 4096 characters.
- Only vacancies first seen in the last `max-age-days` (3) are included, so enabling the digest does not send the whole backlog.
- The bot token is part of Telegram's request address, so it is never logged: errors show only the exception type or Telegram's own description.
- The digest runs in its own thread after the run is finished; it never delays or fails a run.

**Consequences.** Whoever can read the chat sees the vacancy titles. A vacancy that scores above its threshold only after a later recalculation is announced after the next run. There is no "send a test message" button yet.

## ADR-032 Backup service

Status: Accepted (T-070)

**Context.** ADR-019 promised a `backup` service that runs `pg_dump` daily. The data is the user's own job search history, which is hard to recreate.

**Decision.**
- A `backup` service in docker-compose uses the `postgres:16` image and a small shell script (`ops/backup/backup.sh`). It backs up at start and then every `BACKUP_INTERVAL_SECONDS` (one day), in PostgreSQL's custom format, into the `backups` volume.
- A dump becomes a backup only after it was written completely and read back with `pg_restore --list`. Failures are logged and retried at the next interval; the container does not exit.
- Retention is by age (`BACKUP_KEEP_DAYS`, 14 days). Only files named like its own dumps are deleted.
- Restore is documented and tested against a real compose stack (docs/operations.md). The script has a Docker test (`ops/backup/test-backup.sh`).

**Consequences.** The volume is on the same machine as the database, so it does not protect against losing the machine; copying files off the host is up to the user, and the JSON export is a second, independent route. Backups run at fixed intervals from the container start, not at a chosen hour. A restore needs the app stopped.

## ADR-033 Access token: how ADR-017 is implemented

Status: Accepted (T-071)

**Decision.**
- The `vps` profile adds a filter that requires the token on every `/api/` request. The token is `Authorization: Bearer <token>` (scripts) or the `vt_token` cookie (browser). A token in a query string is never accepted. The static pages, `/actuator/health` and the auth endpoints are open: they hold no data, and the single-page app shows a sign-in page when the API says 401.
- `POST /api/auth/login` checks the token and sets the cookie: HTTP-only, `SameSite=Strict`, path `/`, 30 days, `Secure` when the request came over HTTPS (directly, or through a proxy that sends `X-Forwarded-Proto`). `POST /api/auth/logout` clears it. `GET /api/auth/status` tells the page whether a token is needed and whether it is signed in. The page never stores the token.
- The token is compared as SHA-256 digests with `MessageDigest.isEqual`, so timing does not reveal how much matched. The app refuses to start in the `vps` profile when `APP_ACCESS_TOKEN` is missing or shorter than 16 characters. The token is never logged.
- Rate limit: after 5 wrong tokens (login or API) from one client address within 10 minutes the client gets 429 with `Retry-After` until its oldest failure leaves the window. While blocked, even the correct token is refused, so guessing cannot be paced under the limit. A correct token clears the client's failures. Requests with no token at all do not count. The table is in memory, capped at 10,000 addresses, and a restart clears it.
- The client address comes from the connection; behind the reverse proxy Tomcat takes it from `X-Forwarded-For` only when the connection is from a private address (its default trusted proxies), so a client cannot fake it.
- The `local` profile has no token and the status endpoint says so.

**Consequences.** A cookie plus `SameSite=Strict` is the protection against cross-site requests; there is no separate CSRF token. The cookie holds the token itself, so anyone who can read it (not scripts on the page, but the browser profile or a proxy log that records cookies) has the token. Rotating the token means changing `.env` and restarting; everyone signs in again. The limit is per address, so an attacker with many addresses is slowed, not stopped: the token must be long and random. The app must not be reachable except through the proxy, or the forwarded address can be forged. HTTPS comes from the proxy (T-075).

## ADR-034 Log masking and structured logs

Status: Accepted (T-072)

**Context.** Keys travel in query strings (Adzuna), headers (Himalayas), paths (Telegram, Jooble) and the access token travels in headers and cookies. Exception messages and log lines often quote the whole address. ADR-019 promised that secrets are never printed.

**Decision.**
- `LogMasking.mask` hides secrets in two layers: known shapes (a key, token, password or signature in a query string; bearer and basic authorization values; a password inside an address; a Telegram bot address; the session cookie; a Jooble key in a path) and every secret value read from the configuration, matched as exact text. Properties whose name ends in password, secret, token or key are registered at startup (`SecretRegistration`), so a new setting named in the same way is covered without code. Values shorter than 6 characters are not registered. A masked value is `***`.
- Every console log line goes through it: the message and the stack trace in text mode, and every text value (message, stack trace, key-value fields) in JSON mode.
- The local profile prints readable text. The `vps` profile prints one JSON object per line (logstash format) for a log collector.
- Messages that are stored or shown, not only logged, are masked at the source: `UpstreamException` masks its message (it goes to `source_state`, `run_source_stat` and the UI), and a source address that cannot be parsed no longer puts the address into the exception.
- Run metrics are log lines: one per source (fetched, new, updated, duplicates, errors, duration) and one summary per run (status, sources, totals, failed sources, duration). In the vps profile the numbers are JSON fields.

**Consequences.** A secret that is neither shaped like the patterns nor configured through a property with such a name (for example one typed into a notes field by the user and then logged) is not masked. Masking is best effort on the way out; the main protection stays not to put secrets into messages. Logs written by other libraries are masked too, because masking is in the output, not in our log calls. The run metrics are not exposed through Actuator or Micrometer; there is no metrics endpoint.

## ADR-035 Generic HTML adapter: approved sites only

Status: Accepted (T-073)

**Context.** Tier 3 reads career pages that have no API. The brief forbids reading sites whose terms or `robots.txt` do not allow it, and requires a written terms check per site.

**Decision.**
- One adapter, `html`, driven by configuration. The repository ships no site and none is enabled by default.
- A site is requested only when it is enabled and its configuration records a terms check (date and terms page). The check itself is written in docs/sources.md ("Approved sites") before the configuration is added; the adapter cannot verify that, it only refuses sites with no record.
- `robots.txt` is read first, for every site and every run, and honoured as RFC 9309 describes: the group of the most specific matching agent (`VacancyTracker`), longest rule wins, Allow wins a tie, `*` and `$`. A 4xx answer allows. A 5xx or no answer blocks and counts as a failure, so the owner sees it. A page that is disallowed is never requested. `Crawl-delay` (capped at 30 s) is waited between pages.
- Pages are fetched without validators: a "not modified" answer would hide which vacancies still exist, and the adapter parses the page itself.
- The adapter opens list pages only, never a vacancy page, at most 5 pages per site and 200 cards per page, at the shared one request per second per host.
- Vacancies use the source id `html` for all sites so that the run statistics and the expiry rule (ADR-014) agree on the source; the site is the first part of the external id.

**Consequences.** A site that is blocked by `robots.txt` is skipped with a warning, not an error. Selectors break when the page changes; the result is no vacancies or a failed source, never wrong ones, because cards without a title and a real link are dropped. Health is per source (`html`), not per site; the log names the site. Whether a site's terms really allow reading is the owner's judgement and is recorded, not enforced.

## ADR-036 Feed adapter, country detection, and what stays undetected

**Context.** Closing the open rows of the plan (T-020, T-035, T-040, T-041) raised four small questions.

**Decision.**
- **RSS and Atom (T-040).** One adapter, `rss`, with Rome 2.1.0. It follows the rule of ADR-035: no feed is shipped, and a feed is requested only when it is enabled and its configuration records a terms check. DOCTYPE declarations are refused, so an external entity in a feed cannot read a local file. The item text is reduced to plain text. Feeds are machine interfaces, so `robots.txt` is not consulted; the conditional cache (ETag) applies.
- **Country (T-041).** The country is set at ingestion only when a whole comma-separated part of the location is a country name in one of ten languages (JDK locale data) or one of a few aliases (UK, USA). Two-letter codes are not read because they clash with US state codes, "Georgia" is excluded, a name that differs by language is dropped, and a location naming two countries gives nothing. A wrong country would hide vacancies through a `require` rule, so a missing one is the safer error.
- **Seniority (T-041).** Not detected. The levels are the user's own list (docs/search-profiles.md), and any built-in word list would be hardcoded data the brief forbids. A profile expresses seniority with `title` keywords, which the live preview shows at once.
- **Adzuna countries (T-035).** Codes are checked against the 19 the public documentation lists; the official page has no machine-readable list, so the set is the known one, and a code outside it fails the source before any request (it also keeps the value safe in the URL path).
- **Views (T-020).** `score` and `postedAt` join the filterable fields.

**Consequences.** Locations such as "Praha" or "Berlin" alone give no country; the user can still filter by `location`. The Adzuna list must be updated by hand if Adzuna adds a country.

## ADR-037 Review follow-ups: what changed in collection and import

**Context.** A review of the whole code base (2026-11-02) found problems in the collection pipeline that cost data silently. This record lists the decisions; the code is in the commit.

**Decision.**
- **No validators for multi-request sources.** Greenhouse, Lever, Ashby, Arbeitnow, Himalayas, Adzuna and RSS ask for the full answer every run. With ETags, one board answering "not modified" while another changed left the first board's vacancies unrefreshed and, after three runs, expired; and the validators were saved before the pipeline had stored anything, so a failure later in the run lost the data until the source changed. Remotive and Jobicy (one request, rate-limited) keep conditional requests. The cost is bandwidth only.
- **Mail is marked processed after it is stored.** `FetchOutcome.afterStored` runs once every posting of the run was written without a storage error; until then the alert mail is offered again (the pipeline de-duplicates).
- **A partly read source is not a clean read.** `FetchOutcome.withWarning` keeps the postings that were read, records the problem as the source's error message, and so the run does not count for expiry (ADR-014). The HTML adapter now treats a `robots.txt` refusal as a failed site, not a silent success, and the HTML and RSS adapters report partial failures this way.
- **A misconfigured source cannot stop the run.** `IllegalArgumentException` and `IllegalStateException` from an adapter are recorded as that source's failure.
- **Redirects.** The HTTP client no longer follows redirects itself. The fetcher follows up to three, only on the same host, so a request header (a key) and the pacing stay with the host they were meant for. The HTML adapter follows none: its `robots.txt` check covers the configured address only. The size cap now refuses an answer over 5 MB instead of cutting it (a cut JSON document fails later as "invalid").
- **Windowed sources do not expire vacancies.** Remotive, Jobicy, Arbeitnow, Himalayas, Adzuna, RSS and HTML show part of what is open, so a posting that left the window is not known to be gone. Like email vacancies they are closed by hand. Greenhouse, Lever and Ashby list whole boards and still expire.
- **Import by URL cannot reach the server's own network.** Loopback, private, link-local, carrier-grade NAT and unique-local addresses are refused for the address and each redirect. `app.import.allow-private-hosts=true` disables the check. A name that changes its answer between the check and the request is not covered.
- **Preview uses the edited profile's own company lists** when the editor passes its id.

**Consequences.** More bytes are downloaded per run for the multi-request sources (a few hundred kilobytes). A vacancy from a windowed source stays New until the user closes it.

## ADR-038 Full review (2026-11-02): decisions that change behavior

**Decision.**
- **Automation never hides what the user added.** A vacancy with no source (created or imported by hand) is skipped by the Inbox rule (ADR-026). Before, a recalculation could move it to Hidden.
- **A run always ends.** An error that is not an upstream failure closes the run as interrupted and publishes the end of the stream. At start-up, runs still marked running are marked interrupted (`app.collection.sweep-stale-runs-on-startup`, default true).
- **Quota sources are called once.** Remotive and Jobicy are fetched without retries (`HttpFetcher.getOnce`), because a retry is another call that the stored budget does not count. The next scheduled run is the retry.
- **The ECB feed is fetched whole,** not with validators (same reason as ADR-037).
- **Cross-site writes are refused.** A request that changes data and carries an `Origin` other than its own `Host` gets 403 (`OriginFilter`). Browsers send `Origin` on every cross-site POST, which closes the local-instance hole where a web page could start a run or import a profile. Requests without `Origin` are unchanged. `/api/auth/status` counts a wrong token against the same limit as `/login`.
- **The dashboard counts applications from the status history** (first time a vacancy reached Applied); the `vacancy.applied_at` column was never written.
- **Exports read in a stable order** (first sighting), so a collection running during a backup cannot make a row slip between pages; XLSX cells are cut at Excel's 32,767-character limit.
- **Lower-case/NULL logic in view filters:** `keywords not_contains` treats a missing description as "no match", so the negation holds.
- **Later in the same day, the items first left open were closed:** Jobicy is spaced one hour from the last call (`CallBudget.tryAcquireSpaced`), not per clock hour; a match session reloads the profiles when one is saved meanwhile (`SearchProfileService.generation`); the funnel and the weekly count ignore a stage that a bulk undo took back; a search for `node.js` also matches the whole token; the alert parser takes company and place only from a block that holds one posting's link; a redirect target is fetched without validators and none are stored.
- **After the next review:** spaced calls take turns under an advisory lock; the funnel orders an undo entry after the change it took back when both have the same time; the alert parser, in a block shared by several postings, takes only the text between a posting's link and the next one; the recalculation poller waits about 20 seconds for a job before saying none is running.
- **Still not changed:** a search for `c#` matches by the prefix `c`; a vacancy already scored by a run that read the old profile just before a save is corrected by the recalculation that the save starts.

## ADR-039 Review 2026-11-02: host check, row locks, manual vacancies, web links, keyboard commits

Status: Accepted (T-076). Findings and their state: [code-review.md](code-review.md).

**Context.** An independent review of the whole code base and a clean build on current tools (Docker Engine 29, Node 26) found a way for any web page to read the local API, data lost to races between background jobs and the user, and keyboard paths that changed statuses by accident.

**Decision.**
- **Host check.** `HostFilter` answers every request (pages, API, Actuator; the check does not depend on the path, so no path spelling can pass around it) only for loopback host names (`localhost`, `127.0.0.1`, `[::1]`) and the names in `app.allowed-hosts` (`APP_ALLOWED_HOSTS`; `docker-compose.vps.yml` sets it to `DOMAIN`). DNS rebinding makes a hostile page same-origin with the app, so the Origin check alone (ADR-038) cannot stop it; the Host header still carries the hostile name. A request without a Host header (HTTP/1.0, tests) is judged by the server name.
- **Row locks.** Every read-modify-write of a vacancy reads it with `SELECT … FOR UPDATE`: user edits and status changes, bulk changes, ingest, the inbox rule and expiry. They serialise per row, so a background job can no longer write back a status or note the user changed after the job read the row. Column-scoped updates were the alternative; the lock keeps the aggregate model of ADR-003 and costs only waiting on the same row.
- **Manual vacancies.** Creating or importing a vacancy by hand writes a first history entry, from nothing to New, noted "added by hand". It is not automatic, so the inbox rule (ADR-026) never hides the vacancy, also after a source starts listing it. The "no source row" test of ADR-038 stays as a second guard.
- **Web links only.** A posting link is stored only when it is an http or https address with a host (`CanonicalUrl.webLink`). Sources drop other links, manual creation answers 422, an import row is rejected with the field `url`. The UI also opens only http(s) links (`safeLink`), for rows stored before this change.
- **Salary edits in CSV.** A CSV row that changes the salary marks `salary` as edited, like a description (ADR-012).
- **Same-board openings.** Fuzzy de-duplication (ADR-013) skips a candidate the same adapter already lists under another id: one board's ids are distinct openings.
- **Next action.** A PATCH sets the next-action date and note independently.
- **Keyboard commits.** Selects that change data (status in the drawer and on the board, priority) only choose; a button applies. Screens reload their data after a Refresh instead of being re-mounted, so drafts and selections survive. The Inbox selection is a vacancy id read through refs. Shortcuts fall back to the physical key (`e.code`) when the layout types non-Latin letters.
- **Build.** Testcontainers 1.21.4 (Docker 29 needs it), the SPA-serving auth test no longer needs a UI build, and Vitest workers on Node 22+ run with `--no-experimental-webstorage`.

**Consequences.** A background job may wait a few milliseconds on a row the user is saving. Rows stored earlier with a non-web link keep it in the database but the UI does not open it. Open findings are in T-092.

## ADR-044 Visa sponsorship from the posting text

Status: Accepted (T-078). Gap G7 of [gap-analysis.md](gap-analysis.md).

**Context.** `vacancy.visa_sponsorship` existed since V1 and profiles could already name the field, but nothing ever filled it, so every rule on it fell to its missing-value policy. the compared platform makes sponsorship a headline filter.

**Decision.**
- `VisaSponsorship.detect(title, description)` reads the posting's own words: **false** for a refusal ("unable to sponsor", "no visa sponsorship", "must already have the right to work", German, Ukrainian and Russian equivalents), **true** for an offer ("visa sponsorship available", "we sponsor work visas", "visa support", "relocation and visa", German, Polish, Ukrainian and Russian equivalents), **null** otherwise. A refusal wins over an offer in the same text ("sponsorship is not available, but visa support for family members").
- It is a derived fact like the description: set when a vacancy is discovered, when automation refreshes the description, and when the user edits it. A Java migration (V12) fills it once for the vacancies stored before.
- Profiles read it as the `visaSponsorship` fact; views filter it as a yes/no field (`equals true|false`, `exists`, `not_exists`). Unknown is never true or false.
- The phrase lists are language data, not a list of jobs, countries or technologies, so the rule "no domain data in the code" (README) holds, as for country names (ADR-036).

**Consequences.** Postings in other languages or with unusual wording stay unknown; a profile decides how to treat unknown. A wrong "false" is possible when a posting mentions the right to work for another reason; the drawer shows the value so the user can see it.

## ADR-045 New sources from the owner's list

Status: Accepted (T-088, T-089). The checks are in [sources.md](sources.md), section "Owner's list".

**Context.** The owner listed 65 channels and sites (Telegram, Facebook, LinkedIn, job boards). The README's Compliance section forbids scraping sites that prohibit it, logging in to the owner's accounts, CAPTCHA bypass and proxy rotation.

**Decision.**
- A source is connected only when an official API or feed exists and neither its terms nor `robots.txt` forbid reading it: **Djinni** (RSS, shipped as a configured feed), **Remote OK** and **Working Nomads** (public JSON APIs, new adapters `remoteok` and `workingnomads`). Both APIs return a window of current jobs, so their vacancies never expire automatically (ADR-037). Remote OK requires a link back: each vacancy keeps the Remote OK page as its link and the source is named.
- Where the terms forbid automated access, or contradict an invitation on a developer page (DOU, Jobgether, Jobico), the source is **blocked** until the owner has the site's consent in writing; the record says whom to ask. A server that refuses non-browser clients (Golang Projects) is protection and is not worked around.
- **Telegram**: no reading of channels or groups, neither through `t.me/s/` previews (Telegram's terms forbid scraping) nor by signing in as the owner. Instead `telegram-forward` reads the owner's own bot with `getUpdates`: the owner forwards posts in the official app, which Telegram's Bot Developer Terms allow as data "submitted directly and voluntarily". Only messages from the owner's chat are used; the offset is stored after the vacancies are stored. The forwarded post's channel becomes the link (`t.me/<channel>/<id>`) and the company unless the text names one ("Компанія: …").
- **Facebook** groups and pages and **LinkedIn** groups are blocked. LinkedIn job alerts by email remain (`email-linkedin`), and LinkedIn mail about applications is handled by the correspondence reader (ADR-043).
- Email alerts of other sites are noted per site; a parser is written once a real alert mail is available as a fixture, as for the existing ones (ADR-030).

**Consequences.** Coverage of Ukrainian boards depends on Djinni and on what the owner forwards; DOU needs a consent first. The Telegram route needs one action per post from the owner, which is also what keeps it compliant.

## ADR-040 Resume and the fit score

Status: Accepted (T-080, T-081). Gaps G1 and G2 of [gap-analysis.md](gap-analysis.md).

**Context.** The full cycle starts with knowing how well a vacancy fits the owner. Profiles (ADR-008) score against rules the owner writes, not against the resume. the compared platform's core feature is "upload your resume once" and a match per job. The README forbids technologies and roles in the code.

**Decision.**
- **A new module, `application`,** for the resume, the fit, AI documents, packages and correspondence. It may use `vacancy`, `matching`, `collection` and `common`; nothing but `web` uses it (ArchitectureTest), and it has the same 80% line-coverage gate as the other core modules.
- **The resume** is uploaded as PDF (Apache PDFBox 3.0.8, approved by the owner on 2026-11-02), DOCX (Apache POI, already present), TXT or Markdown, or pasted; up to 5 MB. The format is decided by the file's bytes, not the browser's claim. A scanned PDF without text is refused with an explanation (no OCR). One resume is active; the original file is kept for download.
- **Keywords, not a dictionary.** Keywords are proposed from the text: the items under a "Skills"-like heading, then terms recognised by their shape (C#, Node.js, CI/CD, PostgreSQL, AWS, S3) or as capitalised words inside an enumeration but not at its start. The owner edits the list; that list is what the score uses. The only built-in words are common words, months and countries to leave out, as for country detection (ADR-036).
- **The fit** (0..100) is deterministic and explained. The vacancy "asks" for its skill-like terms plus every resume keyword it mentions; coverage is the share the resume has; score = 85 × coverage, +15 when a keyword is in the title, -15 when the vacancy asks for more years than the resume shows (years from merged date ranges or "N years"). Reasons: matched skills, missing skills, title, years, little text. Stored per vacancy for a resume revision; any change of the resume or its keywords raises the revision.
- **Freshness.** A resume change and the end of every collection run start a background pass over stale fits; reading a fit computes it first when it is stale, so the Inbox, the Pipeline and the drawer never show a fit older than the resume. A fit is also stale when the vacancy's title or description changed since it was scored: each fit stores an MD5 of the text it read (V18, compared in SQL with PostgreSQL's `md5` of the same text).
- The fit is separate from the profile score: profiles filter and rank by the owner's rules, the fit says how the resume compares. Neither changes a status.

**Consequences.** Shape-based terms also catch some non-skills (a city in a list, an all-caps word); the owner prunes the resume keywords, and the missing list is advice, not a verdict. Synonyms are not merged (k8s and Kubernetes are two terms).

## ADR-041 Claude adapter and the AI opt-in

Status: Accepted (T-082). Gaps G3 and G4 of [gap-analysis.md](gap-analysis.md).

**Context.** The owner wants resumes and cover letters adapted by Claude, through either the official `claude -p` CLI under their subscription or an API key, without the app ever extracting or passing the subscription's OAuth token. ADR-008 kept AI out of scoring; the brief allows no data to third parties except what the owner turns on. AI must be off by default and need an explicit opt-in before a resume or a letter reaches Claude.

**Decision.**
- One interface, `AiClient`, with three implementations chosen by `app.ai.provider` (`off` by default): **`cli`** runs the official CLI in print mode with no tools (`--tools ""`), no MCP servers (`--strict-mcp-config`), no slash commands, no saved session, only the settings of an empty temporary folder (`--setting-sources project`, so the owner's own hooks and plugins do not run), our own system prompt, and the prompt on standard input. The CLI authenticates itself; the app removes `ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN` from its environment so the subscription pays and no key reaches it. **`api`** uses the official Anthropic Java SDK (3.3.0, approved by the owner) with `ANTHROPIC_API_KEY`, model `claude-opus-5-5`, effort set explicitly (`medium`), and the server-side refusal fallback (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`); a remaining refusal is reported, never shown as a document; a cut answer is marked. **`fake`** answers from the prompt itself, for tests and the end-to-end test.
- **Consent.** `AiService` is the only door. It refuses (HTTP 409, nothing sent) unless the provider is configured **and** the owner has switched AI on in Settings after reading the notice; the consent is stored per provider (`ai_consent`, V15), so switching from `cli` to `api` asks again, and it can be withdrawn at any time.
- **What is sent:** only on Generate, the resume text, the chosen vacancy and the owner's instructions. Mail is never sent to Claude. Classification of mail stays rule-based (ADR-043).
- Prompts keep the rules in the system prompt and the data in tags; the vacancy text is marked as data and instructions inside it are to be ignored (it comes from the internet).

**Consequences.** `cli` works only where Claude Code is installed and signed in (running the jar on the owner's computer), not in the Docker image; `api` works everywhere but costs per request. The CLI's answer format can change between versions; the adapter reads plain text only. The fake provider must never be set for real use; Settings shows it by name.

## ADR-042 Tailored documents, versions and application packages

Status: Accepted (T-083; packages in T-084, T-085).

**Decision.**
- A generated text is a **draft**: it is returned to the browser and never stored. The owner reads it, edits it and saves it; each save is the next **version** of that kind (resume or cover letter) for that vacancy (`tailored_document`, V15). Versions are never changed or deleted, so what was sent stays known. `origin` records whether a version started from an AI draft; provider and model are kept only then.
- Without AI the same editor starts from the resume text (or the last version) or a cover-letter template, so the flow does not depend on AI.
- Downloads: DOCX (plain, ATS-friendly: headings, bullets and bold only), Markdown or text; file names carry the kind, version and company.
- **Packages.** Preparing an application fixes which versions go with it (or the uploaded resume file as it is) and the page where it is submitted (`application_package`, V16; an http(s) link only, by default the vacancy's own). Without a tailored version the package keeps its own copy of the resume as it was at that moment (V18), so replacing the resume later does not change what an application was sent with; a pasted resume is copied as its text and goes into the ZIP as `resume.docx` and `resume.txt`. The ZIP holds the documents as DOCX plus Markdown or text, the vacancy and a checklist. The UI downloads it and opens the page in a new tab; **the app never fills in or submits a form** (the README's rule against logging in to job sites holds).
- **"I submitted"** marks the package submitted (once; repeating it changes nothing) and moves the vacancy to Applied through the shortest chain of allowed transitions (`StatusPath`; New goes through Applied directly, a dismissed vacancy through New), with the package reference and the versions in the history note. A vacancy already past Applied keeps its status. The package and its versions stay linked to the application.

## ADR-043 Correspondence: job mail, links to applications, status suggestions

Status: Accepted (T-086, T-087). Gap G6 of [gap-analysis.md](gap-analysis.md).

**Context.** After applying, the owner follows answers in their mailbox: confirmations, rejections, interview invitations, test tasks, offers, and LinkedIn's notices about applications and messages. The IMAP reader (ADR-029) read one alert folder; answers arrive elsewhere (usually INBOX).

**Decision.**
- The existing reader gets `readFolderDeferred(folder, keyPrefix, handler)`: the same read-only connection and limits for other folders (`app.mail.replies.folders`, default `INBOX`), always read-only, with its own processed keys (prefix `reply:`), so one message can serve the alert adapters and the correspondence reader. A scheduled pass runs every 15 minutes when IMAP is configured; "Check mail now" runs one at once; an `.eml` file can be imported by hand.
- **Classification is rule-based** (`MailClassifier`), in English, Ukrainian, Russian, Polish and German: rejection, offer, test task, interview, application viewed, application received, recruiter message, job alert, other job mail, or not about jobs. Rejection is checked first ("thank you for applying … unfortunately" is a rejection); an alert subject is checked before everything. **Mail is never sent to an AI.**
- **Only job mail is stored** (`correspondence`, V17): sender, display name, subject, date, the first 600 characters of the text, the category, the link and the suggestion. Mail that is not about jobs is skipped and not kept. Job alerts are left to the alert sources (ADR-030).
- **Linking** (`MailLinker`) scores each vacancy the owner has acted on (not the untouched Inbox, not dismissed, hidden or expired ones): the company name in the subject or the sender's name (+4) or in the text (+3), the sender's domain matching the company or its posting (+3), the vacancy title in the mail (+2), and +1 when the owner applied. Legal forms ("Inc.", "ТОВ") and punctuation are ignored; public mail domains never count. The best candidate with at least 3 points wins and the evidence is stored as the reason; otherwise the mail stays unlinked and the owner can link it.
- **Suggestions**: a rejection suggests Rejected (from Applied on); an offer, a test task or an interview suggests that status when it is ahead of the current one (an interview after a test task counts); a confirmation suggests Applied from New or Saved. Nothing changes until the owner accepts; accepting moves through the allowed transitions (`StatusPath`) with the subject in the history note; dismissing keeps the status.

**Consequences.** Wording outside the phrase lists falls back to "job mail" without a suggestion. Linking can miss a mail from an agency or an ATS address that names neither the company nor the title; the owner links it by hand. Reading INBOX means the app sees all mail in it: only job mail is kept, and the reader never changes the mailbox.

## ADR-046 design, dark first

Status: Accepted (T-090).

**Context.** The owner asked for the third-party theme from a public design collection, copied into the project as design file, with the UI updated and dark as the main theme, keeping every keyboard scenario.

**Decision.**
- `design file` at the repository root is the unchanged file from `design-md/` (MIT, a public design collection), with a source and licence section added at the end. It analyses a marketing site, so the app takes its palette, type and shapes, not its page layouts: deep indigo-navy surfaces, a pale violet accent, the deep teal as the success colour, warm off-grey ink, hairline borders, Inter variable at 460/540, 8px buttons, 6px inputs, 12px cards, 16px dialogs. No logo, portrait or third-party name appears in the app.
- **Dark is the default**: the tokens' `:root` holds the dark values, `data-theme="light"` and `data-theme="system"` (light only when the system prefers it) override them, the page starts with `data-theme="dark"` (no flash), and a stored choice wins. The toggle cycles dark, light, system.
- The keyboard inventory in [code-review.md](code-review.md) is the regression list; none was removed. The design README now states the real board and rule-builder keys (Alt+arrows), the Move buttons and the layout-independent letters. The selected Inbox row and palette option keep an outline in Windows High Contrast (`forced-colors`).

**Consequences.** Light-theme users see a change of accent (navy instead of indigo). The Phase 0 mockup keeps its layout with the new tokens.

## ADR-047 AI presets and base documents

Status: Accepted (T-093). Specification: [specs/M7-ai-presets.md](specs/M7-ai-presets.md).

**Context.** Generated documents were written from the resume and short instructions typed each time (ADR-042). The owner wants to set up once what they look for and how documents should be adapted, and wants AI to adapt their own base resume and cover letter rather than write new ones.

**Decision.**
- A **preset** (`ai_preset`, V19) holds a name, the Main goal prompt, weighted parameters of the goal, the resume and cover letter prompts, the base resume and the base cover letter. There is always at least one preset and exactly one default (partial unique index); the last preset cannot be deleted, and deleting the default makes the next one the default.
- **Defaults live in resources** (`resources/presets/default/`): sections D1 to D7 of the specification word for word, the weights as JSON. A preset named Default is created from them on first use (at start-up and whenever the table is empty), so the defaults are never compiled into code. The guardrail (D7) is not a preset field: it is put in front of every generation and cannot be edited.
- **One source of truth for the resume.** The base resume is a row of the `resume` table, not a copy. The default preset's base resume is the active resume of the Resume screen: uploading on either screen changes the same row, making another preset the default makes its base resume active, and resetting the default preset's base resume to the template leaves no active resume. When the Default preset is created, the resume already active becomes its base resume. A preset without a stored base resume shows the D5 template.
- **Weights** are whole numbers from 0 to 1000; their sum need not be 100. Code normalises them (share = weight / sum) and the screen shows each share. At least one weight must be above zero.
- **Placeholders.** A `{{…}}` mark in the main goal, a prompt or a base document means the default text was not filled in; generation of the documents that use that field is refused and the screen lists what to fill in. Saving such text is allowed.
- **Backup.** Modules other than export add sections to the JSON backup through `common.BackupSection`; presets are written with their base resume (text, and the uploaded file in Base64) and restored by id or name, the default one becoming the default again. The schema version stays 1: older backups have no `presets` field and import as before.

**Consequences.** An old resume row stays in the table when a new one is uploaded, as before; presets may share one base resume after Duplicate. Fit scores (ADR-040) follow the default preset's base resume, because it is the active resume.

## ADR-048 Generation from the base draft, with a snapshot

Status: Accepted (T-094). Specification: [specs/M7-ai-presets.md](specs/M7-ai-presets.md).

**Context.** With presets (ADR-047) the documents should be adaptations of the owner's own base resume and cover letter, never new texts, and it must stay known later what a sent version was made from even after the preset changes.

**Decision.**
- **Input.** The system prompt is the guardrail (D7, from resources, not editable) followed by the preset's document prompt. The user message holds the data in tags: `<goal>` (Main goal prompt and each parameter's share of the weights), `<base_resume>` for a resume; `<base_cover_letter>` and, as the source of facts, `<base_resume>` for a cover letter; `<vacancy>` (title, company, location, description); optional `<instructions>`; and the task as the last line. The vacancy is one data block: anything in any data that looks like one of these tags is turned into `[tag]`, so a posting cannot close its block and add a task; a test sends such a posting and checks the prompt.
- **Readiness.** Generation of a document is refused (422, with a pointer to the Presets screen) while the main goal, the weights, its prompt or its base draft still has a placeholder or the base resume is the template.
- **Use as is** stores the base draft itself as the next version (origin `base`) without AI; it only needs a filled base draft, so it works with AI off.
- **Snapshot.** Every draft and every "use as is" stores a `generation_snapshot` (V20): preset id and name, guardrail, main goal, weights, document prompt, base draft, the base resume given with a cover letter, and the stored resume a resume came from. A saved version points at it (`tailored_document.snapshot_id`), and an edited copy of a version keeps the pointer. Snapshots are never changed, so editing or deleting the preset later leaves the history as it was. A snapshot of another vacancy or kind is refused on save.
- The drawer compares the base draft with the AI draft line by line (added lines marked +, removed lines marked −, not by colour alone) before the owner edits and saves.
- The consent notice in Settings and README Security list what is sent now: the vacancy, the preset's goal, weights and prompt, the base documents and the owner's instructions; Use as is sends nothing.

**Consequences.** A draft that is never saved leaves its snapshot behind; snapshots are small and deleted with the vacancy. The old "write from the resume" prompts are gone: a cover letter without a filled base cover letter cannot be generated.

## ADR-049 Employer match and Goal match

Status: Accepted (T-095). Specification: [specs/M7-ai-presets.md](specs/M7-ai-presets.md).

**Context.** The quick fit (ADR-040) compares keywords. The owner wants to see how well a document covers what the employer asks for, and how well the vacancy fits their own goal, with numbers that mean the same before and after a document is tailored and that the model cannot inflate.

**Decision.**
- **Checklist.** With AI on, one call per vacancy text returns the core problem of the hire and up to 15 requirements with a priority (must-have, important, nice-to-have) and the vacancy's own terms. It is stored by the MD5 of title and description (`vacancy_checklist`, V21); the server numbers the requirements r1, r2, …
- **Employer match.** For a document text, a call proposes a status (covered, partial, missing) and a word-for-word quote per requirement. The server checks every quote: a quote that is not in the document (whitespace aside) makes the requirement missing; a quote found only in a Skills section or a "Skills:" line makes it at most partial, because covered needs proof in experience or projects. The score is computed in code: round(100 × Σ(weight × status) / Σ(weight)), weights 3/2/1, statuses 1/0.5/0. The checked statuses are cached by the checklist's hash and the text's hash (`document_match`), so an unchanged text always has the same score and a tailored text is scored on the same checklist as its base.
- **Goal match.** One call per vacancy text and goal (main goal plus weights, by hash; `goal_match`) rates each parameter of the preset 0..100 with a reason; code averages them with the normalised weights. A deal-breaker the vacancy hits is a separate warning, not part of the score. Generating documents does not change it.
- **Staleness.** A changed vacancy text no longer matches the stored checklist: the drawer shows that the earlier analysis no longer applies (no checklist, no Employer match) and that the Goal match comes from the earlier text, and the next run computes both again.
- **When AI is called.** The lists keep the quick fit (no AI). The drawer reads what is known without AI; with AI on it runs the analysis once, cached on the server, showing that it is working. Without AI the drawer shows the quick fit, labelled as a quick estimate. The consent text and README Security say what the analysis sends.
- Every prompt starts with the guardrail and keeps the vacancy, the checklist and the document in their own data blocks (ADR-048).

**Consequences.** Opening a vacancy with AI on costs up to four calls the first time (checklist, goal, base resume, base cover letter) and none afterwards while the texts stay the same. A model that quotes loosely loses points rather than gaining them.

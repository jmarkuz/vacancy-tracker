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
- ADR-046 Superhuman-inspired design, dark first

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

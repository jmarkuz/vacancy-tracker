# Interview prep: Vacancy Tracker

Ukrainian version: [interview-prep.uk.md](interview-prep.uk.md).

See also (Ukrainian): [project-deep-dive.uk.md](project-deep-dive.uk.md), a functional catalog of the whole project, and [star-stories.md](star-stories.md), seven STAR stories with English scripts.

A working document for presenting and defending this project: what it does, which architecture and technical decisions were taken, what each one cost, and where to point in the source when asked "show me". Written in English because the repository's language policy is English ([ADR-020](../decisions.md#adr-020-language-policy)) and the interviews will most likely be in English.

Every code reference is a link to a file and line range at the state of this document (2026-11-16). If a file moves, `docs/tech/` is the place to fix the links.

## How to use this document

| You have | Read |
|---|---|
| 2 minutes | [1. The pitch](#1-the-pitch) |
| 10 minutes | 1, [2. Numbers](#2-the-project-in-numbers), [3. Architecture](#3-architecture-in-one-page), [8. Honest limitations](#8-honest-limitations-and-what-i-would-do-next) |
| 30 minutes | Add the case studies you expect to be asked about (section 5), and the [Q&A bank](#9-question-bank) |
| A "show me the code" round | Use [10. Where is X?](#10-where-is-x) and the **Code** line of each case study |

Each case study (C1 to C20) has the same shape: **Problem**, **Decision**, **Trade-off**, **Code**, **Tests**, and **If they push** (the follow-up question and the honest answer). The case studies are written so that each stands alone.

Contents:
1. [The pitch](#1-the-pitch)
2. [The project in numbers](#2-the-project-in-numbers)
3. [Architecture in one page](#3-architecture-in-one-page)
4. [Decision index (ADR to code)](#4-decision-index-adr-to-code)
5. [Case studies](#5-case-studies)
6. [Cross-cutting themes (where each senior-level topic lives)](#6-cross-cutting-themes)
7. [Demo script (5 minutes)](#7-demo-script-5-minutes)
8. [Honest limitations and what I would do next](#8-honest-limitations-and-what-i-would-do-next)
9. [Question bank](#9-question-bank)
10. [Where is X?](#10-where-is-x)

---

## 1. The pitch

**30 seconds.** Vacancy Tracker is a self-hosted job-search tool for one person. It collects vacancies on a schedule from official APIs, feeds and the user's own job-alert emails, removes duplicates, scores every vacancy against the user's search profiles with an explained list of reasons, and tracks each application through a pipeline with a full status history. The code contains no roles, technologies or countries: all of that is data in the user's profiles.

**2 minutes (add this).** It is a Java 21 / Spring Boot 3.5 modular monolith on PostgreSQL 16 with a Preact + TypeScript front end that is built into the same jar. The interesting engineering is not the CRUD. It is:

1. **A collection pipeline that survives bad sources**: per-source isolation, retries with backoff and jitter, a persisted circuit breaker, per-host pacing, a persisted call budget for quota-limited APIs, and an expiry rule that never treats a failed source as "the job is gone" (C5, C6, C7).
2. **Automation that never overwrites the user**: background jobs, imports and the user all write the same vacancy rows, so every read-modify-write takes a row lock and automation is restricted to a short list of fields (C3).
3. **An explainable rule engine**: scores are deterministic and every point has a stated reason; the same `Criterion` model is used for scoring, for list filters (compiled to parameterised SQL) and for saved views (C9).
4. **A compliance-first source policy**: no scraping of sites that forbid it, no logging in to job sites, no CAPTCHA bypass; LinkedIn coverage comes only from the user's own alert emails (C13).
5. **Optional AI with hard boundaries**: off by default, explicit consent per provider, the AI can only propose and the application checks and computes (quotes verified, scores calculated in code, facts diffed against the base documents) (C14).
6. **Security for a local-first app**: DNS-rebinding protection, cross-site write refusal, constant-time token comparison with a lockout, SSRF guard on import by URL, log masking (C12).

**The one-sentence design philosophy**: *deterministic and explainable where it matters, defensive at every boundary, and the user's data is never changed by a machine behind their back.*

---

## 2. The project in numbers

| What | Value | Source |
|---|---|---|
| Backend | 238 main Java files, about 20,500 lines; 141 test files, about 15,800 lines | `find backend/src -name '*.java'` on 2026-11-16 |
| Frontend | 99 TypeScript/TSX source files, 82 test files, no UI library | `find frontend/src` |
| Schema | 27 SQL migrations plus one Java migration ([`V12`](../../backend/src/main/java/db/migration/V12__Detect_visa_sponsorship.java)) | [`db/migration`](../../backend/src/main/resources/db/migration) |
| Architecture decisions | 54 ADRs | [decisions.md](../decisions.md) |
| Source adapters | 13 adapter packages (Greenhouse, Lever, Ashby, Arbeitnow, Remotive, Himalayas, Jobicy, Remote OK, Working Nomads, Adzuna, RSS/Atom, generic HTML, Telegram forward) plus 4 e-mail alert parsers | [`collection/adapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter), [`collection/mail`](../../backend/src/main/java/com/vacancytracker/collection/mail) |
| Quality gates in every `mvn verify` | Checkstyle (fails on violation), JaCoCo 80% line coverage for `vacancy`, `collection`, `matching`, `application`, ArchUnit module rules, Testcontainers integration tests on PostgreSQL 16 | [`pom.xml`](../../backend/pom.xml), [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java) |
| CI | 4 jobs: plan/CSV consistency, backend verify, frontend typecheck + tests + build + `npm audit`, Playwright smoke test against the packaged jar | [`ci.yml`](../../.github/workflows/ci.yml) |
| Deployment | Docker Compose: `app`, `postgres`, `backup` (daily `pg_dump`), and `caddy` (HTTPS) on a VPS; secrets only in `.env` | [`docker-compose.yml`](../../docker-compose.yml), [`docker-compose.vps.yml`](../../docker-compose.vps.yml) |

Coverage detail worth knowing: the gate measures **lines** only (branch coverage is reported, about 77% at the time of [ADR-022](../decisions.md#adr-022-testing-strategy-and-coverage-gate), not enforced), and `web`, `export` and `notification` are measured but not gated.

---

## 3. Architecture in one page

Full diagrams (C4 context and container, pipeline flowchart, ERD) are in [architecture.md](../architecture.md). This section is the version to say out loud.

### 3.1 Shape

```
                     ┌──────────────────────────── one Spring Boot jar ────────────────────────────┐
 Browser  ── REST/SSE ─►  web  ─────────────────────────────────────────────────────────────────┐    │
 (Preact SPA,            (controllers, filters, SSE)                                           │    │
  served by the jar)       │ uses all modules; nothing uses web                                │    │
                           ▼                                                                   │    │
   collection ──► matching ──► vacancy ◄── export                  application ────────────────┘    │
   (adapters,     (profiles,    (aggregate,   (CSV/XLSX/JSON)     (resume, fit, AI documents,      │
    pipeline,      rule engine,  status table,                     packages, mail tracking)         │
    HTTP, mail)    scoring)      search)       notification                                          │
                                               (Telegram, opt-in)        common (ids, URLs, logging) │
                     └──────────────────────────── PostgreSQL 16 (Flyway) ──────────────────────────┘
```

### 3.2 The module rule, in one line

`web` may use everything; **nothing** may use `web`; `vacancy` and `common` depend on nothing above them; `matching` → `vacancy`; `collection`, `export`, `notification`, `application` → `vacancy` + `matching` (+ `collection` for the last two); no cycles. This is not a convention, it is a failing test ([C1](#c1-a-modular-monolith-with-enforced-boundaries)).

### 3.3 The life of a vacancy

1. **Trigger**: cron (`0 0 6,18 * * *` by default, [`CollectionScheduler`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionScheduler.java#L16-L17)) or the Refresh button → [`RunCoordinator.start`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java#L54-L68) (one run at a time).
2. **Fetch** per adapter with isolation, [`CollectionPipeline.runAdapter`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L178-L215).
3. **Normalize** ([`Normalizer`](../../backend/src/main/java/com/vacancytracker/collection/normalize/Normalizer.java), [`SalaryParser`](../../backend/src/main/java/com/vacancytracker/collection/normalize/SalaryParser.java), [`CountryDetector`](../../backend/src/main/java/com/vacancytracker/collection/normalize/CountryDetector.java)) and **canonicalize the URL** ([`CanonicalUrl`](../../backend/src/main/java/com/vacancytracker/common/CanonicalUrl.java#L27-L62)).
4. **Deduplicate** and **ingest** in one transaction: [`IngestService.ingest`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/IngestService.java#L36-L73).
5. **Score** against every enabled profile: [`MatchService.matchWith`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L102-L126) → [`RuleEngine`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L54-L63) → [`Scorer`](../../backend/src/main/java/com/vacancytracker/matching/Scorer.java#L38-L89), then the inbox rule moves a *New* vacancy to *Hidden* only if no profile accepts it.
6. **Expire** vacancies not seen in 3 clean runs: [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L34-L46).
7. **Publish** progress over SSE: [`RunEventBus`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunEventBus.java#L43-L62).
8. The user triages (Inbox), applies (package), and the **mail reader** links answers back to the application and *suggests* status changes ([C13](#c13-mail-tracking-read-only-idempotent-rule-based)).

---

## 4. Decision index (ADR to code)

A one-line map. The full text of each record is in [decisions.md](../decisions.md); the case studies below go deeper on the ones marked with a C-number.

| ADR | Decision | The price paid | Where in code | Case |
|---|---|---|---|---|
| [ADR-001](../decisions.md#adr-001-modular-monolith) | Modular monolith, boundaries enforced by ArchUnit | No independent scaling or deployment of modules | [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java#L23-L66) | C1 |
| [ADR-002](../decisions.md#adr-002-java-21-and-spring-boot-3x) | Java 21, Spring Boot 3.x, Maven | Build and CI need JDK 21 | [`pom.xml`](../../backend/pom.xml) | |
| [ADR-003](../decisions.md#adr-003-spring-data-jdbc-over-jpa) | Explicit SQL through `JdbcClient`, not JPA | More hand-written SQL; no lazy-loading magic | [`VacancyRepository`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L35-L82) | C2 |
| [ADR-004](../decisions.md#adr-004-postgresql-16-with-flyway-migrations) | PostgreSQL 16, Flyway only, no `ddl-auto` | Every schema change is a reviewed file | [`db/migration`](../../backend/src/main/resources/db/migration) | |
| [ADR-005](../decisions.md#adr-005-frontend-vite--typescript--preact-served-by-spring-boot) | Vite + TypeScript + Preact, no UI kit, served by the jar | Own small components (palette, drawer, board) | [`frontend/src`](../../frontend/src/) | C18 |
| [ADR-006](../decisions.md#adr-006-scheduling-single-run-lock-and-sse-progress) | Scheduled + on-demand runs, single-run lock, SSE progress | Single instance only (see note in C5) | [`RunCoordinator`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java), [`RunEventBus`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunEventBus.java) | C5 |
| [ADR-007](../decisions.md#adr-007-source-policy-no-direct-scraping-of-linkedin-or-other-forbidden-sources) | Source policy: official APIs and feeds, the user's own e-mails; no scraping of forbidden sites | Less coverage (no LinkedIn scraping) | [sources.md](../sources.md) | C13 |
| [ADR-008](../decisions.md#adr-008-rule-based-scoring-in-the-mvp-llm-scoring-optional-later) | Deterministic rule scoring, never an LLM score | Quality depends on the user's rules | [`Scorer`](../../backend/src/main/java/com/vacancytracker/matching/Scorer.java) | C9 |
| [ADR-009](../decisions.md#adr-009-one-criterion-model-for-profiles-and-view-filters) | One `Criterion` model for profiles, filters and saved views | A small mapping for UI-only filters | [`CriterionCompiler`](../../backend/src/main/java/com/vacancytracker/matching/CriterionCompiler.java#L53-L83) | C9 |
| [ADR-010](../decisions.md#adr-010-profile-versioning-keep-every-previous-version) | Every profile save is a new immutable version | Storage grows (slowly) | [`SearchProfileService`](../../backend/src/main/java/com/vacancytracker/matching/SearchProfileService.java) | C10 |
| [ADR-011](../decisions.md#adr-011-salary-normalization-and-exchange-rate-source) | Salary normalized to yearly gross and converted with ECB rates | Currencies outside ECB are "missing data" | [`RateSalaryConverter`](../../backend/src/main/java/com/vacancytracker/matching/rates/RateSalaryConverter.java) | C9 |
| [ADR-012](../decisions.md#adr-012-upsert-rules-automation-never-overwrites-user-data) | Automation never overwrites user data | Field-by-field bookkeeping (`userEditedFields`) | [`Vacancy.applyAutomated`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L115-L136) | C3 |
| [ADR-013](../decisions.md#adr-013-deduplication-pipeline) | Three-rule deduplication, revised after a review | A conservative fuzzy rule can miss a duplicate | [`Deduplicator`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L34-L103) | C8 |
| [ADR-014](../decisions.md#adr-014-expiry-rule) | Expiry only after N clean runs; a failed source never expires anything | Windowed sources never auto-expire | [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L31-L70) | C7 |
| [ADR-015](../decisions.md#adr-015-search-postgresql-full-text-search) | PostgreSQL full-text search, `simple` config, GIN index | No stemming | [`VacancySearchService`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancySearchService.java#L28-L56) | C11 |
| [ADR-016](../decisions.md#adr-016-export-formats-and-encoding) | CSV (UTF-8 BOM), streaming XLSX, JSON backup with schema version | Excel-friendly at the cost of a BOM | [`XlsxExporter`](../../backend/src/main/java/com/vacancytracker/export/XlsxExporter.java), [`BackupWriter`](../../backend/src/main/java/com/vacancytracker/export/BackupWriter.java#L37-L55) | C17 |
| [ADR-017](../decisions.md#adr-017-authentication-none-locally-single-token-on-a-vps), [ADR-033](../decisions.md#adr-033-access-token-how-adr-017-is-implemented) | No auth locally; one constant-time-checked token plus lockout on a VPS | Single-user model; the cookie *is* the token | [`AuthFilter`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthFilter.java#L33-L60), [`AccessToken`](../../backend/src/main/java/com/vacancytracker/web/auth/AccessToken.java) | C12 |
| [ADR-018](../decisions.md#adr-018-adapter-resilience-retries-rate-limits-circuit-breaker) | HTTP resilience: backoff, pacing, conditional requests, circuit breaker | Hand-rolled instead of a library | [`HttpFetcher`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L113-L162), [`SourceHealth`](../../backend/src/main/java/com/vacancytracker/collection/health/SourceHealth.java) | C6 |
| [ADR-022](../decisions.md#adr-022-testing-strategy-and-coverage-gate) | Test strategy and an 80% line gate | Lines only; some modules ungated | [`pom.xml`](../../backend/pom.xml), [`AbstractIntegrationTest`](../../backend/src/test/java/com/vacancytracker/support/AbstractIntegrationTest.java) | C19 |
| [ADR-023](../decisions.md#adr-023-status-transition-table) | Explicit status transition table in code | Restoring a terminal vacancy goes through *New* | [`VacancyStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyStatus.java#L27-L42) | C4 |
| [ADR-025](../decisions.md#adr-025-missingvaluepolicy-penalize-means-a-weight-penalty), [ADR-026](../decisions.md#adr-026-automatic-inbox-hiding-and-its-limits) | `penalize` means a weight penalty; automatic hiding with strict limits | More rules to explain | [`RuleEngine.effect`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L85-L100), [`MatchService.applyInboxRule`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L128-L152) | C9, C10 |
| [ADR-028](../decisions.md#adr-028-company-allowlist-and-blocklist) | Company allow/block lists; block beats allow, allow never overrides user rules | Exact-name matching only | [`CompanyLists`](../../backend/src/main/java/com/vacancytracker/matching/CompanyLists.java) | C9 |
| [ADR-029](../decisions.md#adr-029-imap-reader-read-only-processed-state-in-the-database), [ADR-030](../decisions.md#adr-030-email-sources-one-parser-per-sender-no-expiry) | IMAP reader is read-only; processed state lives in the database; one parser per sender | Reader is polled, not push (no IMAP IDLE) | [`ImapMailReader`](../../backend/src/main/java/com/vacancytracker/collection/mail/ImapMailReader.java#L132-L230) | C13 |
| [ADR-034](../decisions.md#adr-034-log-masking-and-structured-logs) | Log masking in two layers (shapes + configured secret values) | Best effort for free-text secrets | [`LogMasking`](../../backend/src/main/java/com/vacancytracker/common/LogMasking.java#L15-L60) | C12 |
| [ADR-035](../decisions.md#adr-035-generic-html-adapter-approved-sites-only), [ADR-036](../decisions.md#adr-036-feed-adapter-country-detection-and-what-stays-undetected) | Generic HTML and feed adapters only for sites with a recorded terms check; `robots.txt` honoured per RFC 9309 | Selectors break when pages change | [`HtmlSiteAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/html/HtmlSiteAdapter.java#L100-L135), [`RobotsRules`](../../backend/src/main/java/com/vacancytracker/collection/adapter/html/RobotsRules.java) | C13 |
| [ADR-037](../decisions.md#adr-037-review-follow-ups-what-changed-in-collection-and-import), [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior) | Review fixes: no validators for multi-request sources, same-host redirects, partial reads are not clean reads, runs always end | More bytes per run | [`CollectionPipeline`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L74-L130) | C5, C6 |
| [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits) | Host check, row locks, manual vacancies, web links only | A background job may wait on a row the user is saving | [`HostFilter`](../../backend/src/main/java/com/vacancytracker/web/HostFilter.java#L25-L70) | C2, C12 |
| [ADR-040](../decisions.md#adr-040-resume-and-the-fit-score) | Deterministic resume fit score | Shape-based keywords catch some non-skills | [`ResumeFitScorer`](../../backend/src/main/java/com/vacancytracker/application/fit/ResumeFitScorer.java#L31-L93) | C15 |
| [ADR-041](../decisions.md#adr-041-claude-adapter-and-the-ai-opt-in) | One `AiClient` interface (`cli`, `api`, `fake`); AI off by default; consent per provider | `cli` only works where Claude Code is installed | [`AiService`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L22-L90) | C14 |
| [ADR-042](../decisions.md#adr-042-tailored-documents-versions-and-application-packages) | Drafts are never stored; saved versions are immutable and numbered; packages freeze what was sent; the app never submits a form | More tables | [`DocumentService`](../../backend/src/main/java/com/vacancytracker/application/documents/DocumentService.java#L137-L215), [`PackageService`](../../backend/src/main/java/com/vacancytracker/application/packaging/PackageService.java#L132-L160) | C14, C16 |
| [ADR-043](../decisions.md#adr-043-correspondence-job-mail-links-to-applications-status-suggestions) | Mail classification is rule-based, mail is never sent to AI; suggestions need the user's accept | Phrase lists need upkeep | [`MailClassifier`](../../backend/src/main/java/com/vacancytracker/application/mail/MailClassifier.java#L60-L80), [`MailLinker`](../../backend/src/main/java/com/vacancytracker/application/mail/MailLinker.java#L45-L88) | C13 |
| [ADR-044](../decisions.md#adr-044-visa-sponsorship-from-the-posting-text) | Visa sponsorship detected from posting text; a refusal beats an offer | Unusual wording stays unknown | [`VisaSponsorship`](../../backend/src/main/java/com/vacancytracker/vacancy/VisaSponsorship.java) | C20 |
| [ADR-047](../decisions.md#adr-047-ai-presets-and-base-documents) to [ADR-049](../decisions.md#adr-049-employer-match-and-goal-match) | Presets, snapshots, Employer match and Goal match with code-computed scores | AI calls on first open of a vacancy | [`AnalysisService`](../../backend/src/main/java/com/vacancytracker/application/analysis/AnalysisService.java#L244-L258) | C14 |
| [ADR-050](../decisions.md#adr-050-resume-pdf-of-at-most-two-pages), [ADR-051](../decisions.md#adr-051-deterministic-fact-check-before-saving) | Resume PDF at most two pages; deterministic fact check before saving | The fact check is literal by design | [`ResumePdf`](../../backend/src/main/java/com/vacancytracker/application/documents/ResumePdf.java), [`FactCheck`](../../backend/src/main/java/com/vacancytracker/application/documents/FactCheck.java) | C14, C16 |
| [ADR-052](../decisions.md#adr-052-beacon-design-light-first) to [ADR-054](../decisions.md#adr-054-liquid-glass-over-graphite) | Beacon, Graphite and Liquid Glass design, all under measured contrast targets | Manual screen-reader checks not yet done | [`contrast.test.ts`](../../frontend/src/styles/contrast.test.ts) | C18 |

---

## 5. Case studies

### C1. A modular monolith with enforced boundaries

**Problem.** One developer, one user, one deployable. Microservices would add operational cost with no benefit, but a monolith without boundaries turns into a ball of mud.

**Decision.** One Spring Boot application split by bounded context into `vacancy`, `collection`, `matching`, `export`, `notification`, `application`, `web`, `common` ([ADR-001](../decisions.md#adr-001-modular-monolith)). The dependency rules are executable: ArchUnit runs in every build and fails on a forbidden dependency, a cycle, a controller outside `web`, or a new top-level package that has no rule.

**Trade-off.** No independent scaling or deployment. Accepted: the load is one person, and the boundaries make extraction possible later without a rewrite. Cross-module features use narrow seams instead of reaching across: presets join the JSON backup through a tiny [`BackupSection`](../../backend/src/main/java/com/vacancytracker/common/BackupSection.java#L12-L18) interface in `common`, so `export` does not depend on `application`.

**Code.**
- Rules: [`ArchitectureTest.java`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java#L23-L66) (`onlyDependsOn` helper, one rule per module, `nothingDependsOnWeb`, `modulesHaveNoCycles`, `webIsTheOnlyPlaceWithControllers`).
- Seam example: [`BackupSection`](../../backend/src/main/java/com/vacancytracker/common/BackupSection.java#L12-L18) implemented by [`PresetBackup`](../../backend/src/main/java/com/vacancytracker/application/preset/PresetBackup.java#L26) and consumed by [`BackupWriter`](../../backend/src/main/java/com/vacancytracker/export/BackupWriter.java#L37-L55) through `ObjectProvider<BackupSection>`.
- Each module has a `package-info.java`, for example [`collection/package-info.java`](../../backend/src/main/java/com/vacancytracker/collection/package-info.java).

**Tests.** [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java).

**If they push: "Why not microservices?"** Because the system has one writer, one database and one deployment, and the hard problems here (idempotent ingestion, consistent status history) are *easier* inside one transaction boundary. The module rules mean I can still say where the seams would be: `collection` is the obvious first extraction because it is I/O-bound and failure-prone.

---

### C2. Explicit SQL over JPA, and row locks instead of optimistic magic

**Problem.** The domain is a few aggregates with simple relations, but the operations need exact SQL: `ON CONFLICT` upserts, JSONB, `tsvector` search, stable keyset-style paging, and reads that lock the row for the duration of a read-modify-write.

**Decision.** `JdbcClient` with explicit SQL, mapped by hand to a `VacancyState` record; the domain object `Vacancy` is rebuilt with `Vacancy.restore(state)` and written back with `toState()`. No lazy loading, no session cache, no dirty checking to reason about ([ADR-003](../decisions.md#adr-003-spring-data-jdbc-over-jpa)). The ADR title says "Spring Data JDBC", but its own implementation note records that the repository ended up on plain `JdbcClient` with no Spring Data interfaces (see [section 8](#8-honest-limitations-and-what-i-would-do-next)).

**Row locks.** A background job (ingest, inbox rule, expiry) and the user (edit, status change) can touch the same vacancy at the same moment. Before, a job that had read the row could write back a stale status or lose a note. Now every read-modify-write reads the row with `SELECT ... FOR UPDATE` and runs in a transaction ([ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits)): they serialise per row.

**Trade-off.** A background job may wait a few milliseconds for a row the user is saving. The alternative, column-scoped updates, avoids the wait but gives up the aggregate model; the lock keeps one write path.

**Code.**
- Lock read: [`VacancyRepository.findByIdForUpdate`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L96-L102).
- Upsert: [`VacancyRepository.save`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L35-L82).
- Locked read-modify-write in the service: [`VacancyService.lockForChange`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L127-L130), used by [`update`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L132-L157), [`changeStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L159-L166), [`moveTo`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L173-L188).
- The same pattern in the background paths: [`IngestService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/IngestService.java#L51-L52), [`MatchService.matchWith`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L103-L104), [`ExpiryService.expire`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L72-L80).
- Restore/convert seam: [`Vacancy.restore` / `toState`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L86-L113).

**Tests.** [`ReviewFixesTest`](../../backend/src/test/java/com/vacancytracker/web/ReviewFixesTest.java), [`InboxRuleTest`](../../backend/src/test/java/com/vacancytracker/matching/InboxRuleTest.java), [`VacancyTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VacancyTest.java); integration tests run the real migrations on Testcontainers.

**If they push: "Why not `@Version` optimistic locking?"** Optimistic locking makes the loser retry or fail. Here the loser is a background job that should simply wait, and the user must never see a spurious conflict error. A short pessimistic lock on one row is the simpler and more predictable fit. Contention is tiny (one user), so the lock is almost never held against anyone.

---

### C3. Automation never overwrites what the user wrote

**Problem.** The same vacancy is rewritten by every collection run (new description, salary, last-seen date), while the user edits notes, tags, priority, status, next action and contact. A naive upsert destroys the user's work.

**Decision.** Automation may write only a short whitelist: `lastSeenAt`, sources, the derived score, the unseen flag, and the *normalized* fields `description` and salary, and those only if the user has not edited them. Each user edit of such a field adds its name to `userEditedFields`; the automated update checks that set. A posting that states no salary never erases a salary an earlier sighting gave ([ADR-012](../decisions.md#adr-012-upsert-rules-automation-never-overwrites-user-data)).

**Trade-off.** Bookkeeping per field, and an edited field no longer follows the source until the user resets it. That is the point.

**Code.**
- The whitelist and the check: [`Vacancy.applyAutomated`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L115-L136) and the value object [`AutomatedFields`](../../backend/src/main/java/com/vacancytracker/vacancy/AutomatedFields.java).
- Marking an edit: [`Vacancy.editDescription`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L176-L180) and `editSalary` just below it.
- The one place automation may change a *status*: expiry (from *New*/*Saved* only, note `auto-expired`, [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L72-L80)) and the inbox rule (C10).
- A CSV import that changes the salary also marks `salary` as edited ([`VacancyService.importCsvState`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L205-L236)) so the next run does not revert a correction.

**Tests.** [`VacancyTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VacancyTest.java), [`ReviewFixesTest`](../../backend/src/test/java/com/vacancytracker/web/ReviewFixesTest.java), [`CollectionPipelineTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/CollectionPipelineTest.java).

**If they push: "How do you know the rule list is complete?"** The rule is structural: `applyAutomated` takes an `AutomatedFields` record that *cannot express* a status, a note, a tag or a contact. A reviewer can see in one type what automation is allowed to touch.

---

### C4. The status model: a table, a path finder, and an append-only history

**Problem.** An application moves through thirteen statuses. Without a table any status could be set from any other; some events (an interview invitation while still *Applied*, "I submitted" from *Saved*) imply several steps.

**Decision.**
- The allowed transitions are one `EnumMap` in the enum itself, and `Vacancy.changeStatus` throws `InvalidStatusTransitionException` for anything else. Terminal states (*Rejected*, *Withdrawn*, *Expired*, *Dismissed*, *Hidden*) can only be restored to *New*; *Accepted* has no way out ([ADR-023](../decisions.md#adr-023-status-transition-table)).
- Every change appends a `StatusEvent`; the history is the audit trail and the source of truth for the dashboard's funnel and weekly counts (the column `applied_at` was never written, see [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior)).
- [`StatusPath.between`](../../backend/src/main/java/com/vacancytracker/vacancy/StatusPath.java#L23-L48) is a breadth-first search over the same table: it returns the *shortest chain of legal transitions*, so a compound change is recorded as ordinary steps and the history stays linear. [`VacancyService.moveTo`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L173-L188) writes each step one microsecond apart (the database's timestamp resolution) so their order is preserved.
- Undo exists, but as an explicit, separate operation ([`Vacancy.undoStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L153-L161)) that checks the vacancy is still in the status it expects.

**Trade-off.** Restoring a terminal vacancy passes through *New*, which loses the "where was it" shortcut; in exchange the history never contains an impossible transition.

**Code.** [`VacancyStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyStatus.java#L27-L65), [`Vacancy.changeStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L139-L147), [`StatusPath`](../../backend/src/main/java/com/vacancytracker/vacancy/StatusPath.java), [`BulkStatusService`](../../backend/src/main/java/com/vacancytracker/vacancy/BulkStatusService.java#L47-L110) (bulk change with Undo and per-id skip reasons). The front end mirrors the table in [`statuses.ts`](../../frontend/src/domain/statuses.ts#L5), and a unit test keeps the mirror honest.

**Tests.** [`VacancyStatusTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VacancyStatusTest.java), [`StatusPathTest`](../../backend/src/test/java/com/vacancytracker/vacancy/StatusPathTest.java), [`BulkStatusServiceTest`](../../backend/src/test/java/com/vacancytracker/vacancy/BulkStatusServiceTest.java), front-end [`statuses.test.ts`](../../frontend/src/domain/statuses.test.ts).

**If they push: "Why BFS for a graph this small?"** Because the table is data and will change. A hand-written chain would silently rot the moment a transition is added; BFS derives the path from the table that is already the source of truth.

---

### C5. The collection run: isolation, a single-run lock, and live progress

**Problem.** Fifteen-odd sources with different reliability; two simultaneous runs would race on upserts and exceed per-source limits; the Refresh button needs live per-source feedback.

**Decision.**
- **Adapter contract**: [`SourceAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/SourceAdapter.java#L10-L19) has three methods (`id`, `serverSideFields`, `fetch`). `fetch` returns a [`FetchOutcome`](../../backend/src/main/java/com/vacancytracker/collection/adapter/FetchOutcome.java#L9-L38), a record that can also carry *work to run once everything is stored* (`afterStored`) and a *warning for a partial read* (`withWarning`).
- **Isolation**: [`runAdapter`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L178-L215) catches `UpstreamException`, `DataAccessException` and misconfiguration (`IllegalArgumentException` / `IllegalStateException`) *per adapter* and records the failure; the other adapters continue. A vacancy that cannot be stored counts as an error and does not stop the run ([`store`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L224-L244)).
- **A run always ends**: [`execute`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L79-L97) closes the run as `interrupted` in a `finally` and publishes the end of the SSE stream, so a bug cannot leave a run "running" forever; [`StaleRunSweeper`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/StaleRunSweeper.java#L32) fixes runs left over by a crash at start-up.
- **Single-run lock**: [`RunCoordinator.start`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java#L54-L68) is `synchronized`, remembers the active run id, and submits the work to a **single-thread executor**. A second trigger gets the active run's id with `started = false` (the API turns that into 200 vs 202 in [`RunController`](../../backend/src/main/java/com/vacancytracker/web/RunController.java#L83)).
- **Graceful shutdown**: [`RunCoordinator.shutdown`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java#L80-L94) asks the pipeline to stop between sources, waits 30 s, then marks the run `interrupted`.
- **Progress**: [`RunEventBus`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunEventBus.java#L43-L84) keeps each run's events and replays them to a late subscriber before attaching it, so the UI sees the full history even if it connects mid-run; it keeps the last 20 finished runs.
- **Observability**: one structured log line per source and one per run, with key-value fields in the `vps` profile ([`logSource`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L133-L155)).

**Trade-off (be upfront about this).** [ADR-006](../decisions.md#adr-006-scheduling-single-run-lock-and-sse-progress) proposed a PostgreSQL advisory lock plus an in-process guard. What shipped is the in-process guard plus a `run` row in status `running`; the ADR's own implementation note says the advisory lock "only matters with more than one instance". So the lock is correct for **one instance**, which is the designed deployment, and it would have to change before a second instance is possible.

**Tests.** [`CollectionPipelineTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/CollectionPipelineTest.java), [`ExpiryAndRunLockTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/ExpiryAndRunLockTest.java), [`RunEventBusTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/RunEventBusTest.java), [`RunControllerTest`](../../backend/src/test/java/com/vacancytracker/web/RunControllerTest.java).

**If they push: "What if the process dies mid-run?"** Ingest is per posting and idempotent (dedup absorbs a repeat), alert e-mails are only marked processed *after* storing (C13), and the start-up sweeper marks orphaned `running` rows as interrupted. The run is therefore safe to repeat.

---

### C6. HTTP resilience, built small and tested without a network

**Problem.** Sources fail, throttle and stall. A robust client needs retries, politeness, quota discipline and protection from slow or oversized answers, without a framework the project would have to learn and trust.

**Decision.** One shared client, [`HttpFetcher`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L113-L162), with:
- **Retry with exponential backoff and jitter** on network errors, 429 and 5xx, up to 4 attempts; `Retry-After` wins when present ([`RetryPolicy.delayMillis`](../../backend/src/main/java/com/vacancytracker/collection/http/RetryPolicy.java#L30-L35); capped at 60 s).
- **Per-host pacing**: [`DomainRateLimiter.acquire`](../../backend/src/main/java/com/vacancytracker/collection/http/DomainRateLimiter.java#L21-L28) hands out time slots per host (a minimum spacing, one request per second by default), rather than a literal token bucket, which is simpler and sufficient.
- **A persisted circuit breaker**: [`SourceHealth`](../../backend/src/main/java/com/vacancytracker/collection/health/SourceHealth.java#L18-L72) opens after 5 consecutive failures for 15 minutes; the state is a row in `source_state`, so it survives a restart and is visible in the UI. `allowRequest` is checked before every adapter run.
- **A persisted call budget** for quota-limited APIs: [`CallBudget.tryAcquire`](../../backend/src/main/java/com/vacancytracker/collection/http/CallBudget.java#L20-L36) is a single atomic `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE calls < max RETURNING`, so two threads cannot both take the last call. [`tryAcquireSpaced`](../../backend/src/main/java/com/vacancytracker/collection/http/CallBudget.java#L40-L58) serialises callers for one adapter with `pg_advisory_xact_lock` and spaces calls from the actual last call, not from a clock-hour bucket.
- **Bounded bodies with a deadline**: answers over 5 MB are refused (not silently truncated, since a cut JSON document would fail later as "invalid"), and a **watchdog closes the stream** when the body deadline passes, because the request timeout only covers the headers ([`readBounded`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L216-L242)).
- **Redirects**: the client never follows redirects itself; the fetcher follows up to three, **same host only**, so a key header and the pacing stay with the host they were meant for ([`fetch`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L113-L162)).
- **Conditional requests** (ETag / Last-Modified) are used only for single-request sources ([`ConditionalCache`](../../backend/src/main/java/com/vacancytracker/collection/http/ConditionalCache.java#L20-L30)). For multi-request sources they were *removed* after a review found a real bug: one board answering "not modified" while another changed left the first board's vacancies unrefreshed, and after three runs they expired; validators were also saved before the pipeline had stored anything ([ADR-037](../decisions.md#adr-037-review-follow-ups-what-changed-in-collection-and-import)).
- **Quota sources are called once**: `getOnce` has no retries, because a retry is another call the stored budget does not count ([ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior)).

**Trade-off.** Hand-rolled instead of Resilience4j. The ADR allowed either; the in-house pieces are small (the retry policy, rate limiter, call budget, health tracker and cache are each about 100 lines or fewer), fully unit-testable with a fake `Sleeper`, and persisted in the same database. The cost is that I own the bugs, which the review found and fixed.

**Tests.** [`RetryAndRateLimitTest`](../../backend/src/test/java/com/vacancytracker/collection/http/RetryAndRateLimitTest.java), [`CallBudgetTest`](../../backend/src/test/java/com/vacancytracker/collection/http/CallBudgetTest.java), [`BodyDeadlineTest`](../../backend/src/test/java/com/vacancytracker/collection/http/BodyDeadlineTest.java), [`HttpFetcherTest`](../../backend/src/test/java/com/vacancytracker/collection/http/HttpFetcherTest.java), [`SourceHealthTest`](../../backend/src/test/java/com/vacancytracker/collection/health/SourceHealthTest.java). They run against a local JDK `HttpServer` and fixtures; **no test touches the real network** ([ADR-022](../decisions.md#adr-022-testing-strategy-and-coverage-gate)).

**If they push: "Why did the review remove ETag support for some sources?"** It is the best story in the project about caching and correctness. A conditional request answers "nothing changed" for *one URL*, but the expiry rule reasons about *which vacancies were seen in a run*. With several boards behind one adapter, "not modified" on one board is indistinguishable from "gone" for the others. Correctness beat bandwidth.

---

### C7. Expiry that cannot be fooled by an outage

**Problem.** "Not seen for N runs means the job is gone" is wrong whenever the source, not the job, is the problem.

**Decision.** A vacancy in *New* or *Saved* expires only after **3 clean runs** in which a source that listed it completed without error and did not return it ([`CANDIDATES` query](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L34-L46); N is `app.collection.expiry-runs`). Consequences encoded in the SQL and the code:
- A failed source does not count as "not seen" (`s.error_message IS NULL`), so an outage never expires anything ([ADR-014](../decisions.md#adr-014-expiry-rule)).
- A **partial read** is not a clean read: [`FetchOutcome.withWarning`](../../backend/src/main/java/com/vacancytracker/collection/adapter/FetchOutcome.java#L35-L38) keeps the postings that were read but records the problem as an error message.
- **Windowed sources** (Remotive, Jobicy, Arbeitnow, Himalayas, Adzuna, RSS, HTML, Remote OK, Working Nomads, Telegram forward) show only part of what is open, and e-mail sources list a message once, so their vacancies are excluded from expiry and closed by hand ([`WINDOWED_SOURCES`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L31-L32), `adapter NOT LIKE 'email-%'`). Only whole-board ATS sources (Greenhouse, Lever, Ashby) expire.
- A `304 Not Modified` counts every previously seen vacancy of that source as seen ([`touchAdapter`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L189-L191)).
- Each expiry is its own transaction with a row lock and re-checks the status before writing ([`expire`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L72-L80)).

**Trade-off.** A windowed-source vacancy stays *New* until the user closes it. A stale card is annoying; a wrongly expired live job is a missed opportunity.

**Tests.** [`ExpiryAndRunLockTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/ExpiryAndRunLockTest.java), [`CollectionPipelineTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/CollectionPipelineTest.java).

---

### C8. Deduplication, and how a review made it less clever

**Problem.** The same job appears on several boards with different URLs.

**Decision.** Three ordered rules, first match wins ([`Deduplicator.match`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L34-L48)): canonical URL, then `(adapter, externalId)`, then a fuzzy rule within the same company. A duplicate adds a `vacancy_source` row to the existing vacancy instead of creating a new one, so a vacancy remembers every source ([`IngestService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/IngestService.java#L51-L72)). The URL canonicalization strips tracking parameters, lower-cases the host and drops fragments and trailing slashes ([`CanonicalUrl`](../../backend/src/main/java/com/vacancytracker/common/CanonicalUrl.java#L41-L80)); the unique index `vacancy_canonical_url_uk` (in [`V1__baseline.sql`](../../backend/src/main/resources/db/migration/V1__baseline.sql)) enforces "at most one vacancy per canonical URL" in the database, not only in code.

**What the review changed.** The first version used Jaro-Winkler on the whole `title | location` key at a 0.9 threshold. It scored "Software Engineer | Berlin" against "... | London" at 0.94 and "Engineer I" against "Engineer II" at 0.97, so distinct openings merged and the second never appeared. The fuzzy rule now requires **compatible places** ([`sameLocation`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L72-L75)) and titles that are **equal after normalisation, or equal except for one word that differs by a typo** (6+ letters, no digits, at most two edits; [`titleScore`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L82-L103)). It also skips a candidate that the *same adapter* already lists under a different id: on one board, distinct ids are distinct openings ([line 60](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L59-L62)).

**Trade-off.** The conservative rule will miss some true duplicates (a reworded title). For a job tracker, showing a duplicate is cheaper than hiding a different job.

**Tests.** [`DeduplicatorTest`](../../backend/src/test/java/com/vacancytracker/collection/dedup/DeduplicatorTest.java), [`CanonicalUrlTest`](../../backend/src/test/java/com/vacancytracker/common/CanonicalUrlTest.java), [`NormalizerTest`](../../backend/src/test/java/com/vacancytracker/collection/normalize/NormalizerTest.java).

**If they push: "How would you do this at scale?"** Block on a key (company), compare within the block (what the code does with a cap of 200 candidates per lookup), and move to MinHash/LSH or a trigram index if blocks grew. At thousands of rows the simple version is right.

---

### C9. An explainable rule engine, one model for scoring and filtering

**Problem.** The tool must contain no domain data (no roles, technologies, countries, thresholds), yet rank vacancies in a way the user can trust and debug.

**Decision.** The user writes **criteria**: a field, an operator, a value, a *mode* (`require`, `prefer`, `avoid`, `exclude`), a weight and a `missingValuePolicy` (`ignore`, `penalize`, `hide`).
- [`RuleEngine`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L54-L63) evaluates each criterion against a map of **facts** ([`VacancyFacts.of`](../../backend/src/main/java/com/vacancytracker/matching/VacancyFacts.java#L25)) to a three-valued verdict, `SATISFIED`, `NOT_SATISFIED` or `MISSING` ([`verdict`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L102-L121)), then derives an effect ([`effect`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L85-L100)). The three-valued logic is the important part: "the posting does not state a salary" is *not* the same as "the salary is too low", and the policy decides what to do about it ([ADR-025](../decisions.md#adr-025-missingvaluepolicy-penalize-means-a-weight-penalty)).
- [`Scorer`](../../backend/src/main/java/com/vacancytracker/matching/Scorer.java#L38-L89) turns effects into `score = clamp(round(100 × (positive − negative + bonus + absorb) / (maxPositive + absorb + bonus)), 0, 100)`. It uses **integer arithmetic** (`(200 × n + d) / (2 × d)`, line 87) so rounding is exact. A filtered vacancy has *no score*, and every point and every filter has a human-readable [`MatchReason`](../../backend/src/main/java/com/vacancytracker/matching/MatchReason.java). A property to mention: adding a satisfied `prefer` criterion can never lower the score.
- **Company lists**: a blocklisted company is filtered out and wins over any allow; an allowlisted one gets a bonus and is not hidden for a low score, but it does **not** override the user's own `require`/`exclude` rules ([ADR-028](../decisions.md#adr-028-company-allowlist-and-blocklist), [`CompanyLists`](../../backend/src/main/java/com/vacancytracker/matching/CompanyLists.java)).
- **Salary** is compared on the yearly gross amount in the *criterion's* currency, using cached ECB rates; an unknown currency or period is "missing", never guessed ([`RateSalaryConverter`](../../backend/src/main/java/com/vacancytracker/matching/rates/RateSalaryConverter.java), [`ExchangeRateService`](../../backend/src/main/java/com/vacancytracker/matching/rates/ExchangeRateService.java), [ADR-011](../decisions.md#adr-011-salary-normalization-and-exchange-rate-source)).
- **One model, two evaluators.** The *same* `Criterion` is also compiled to a parameterised SQL `WHERE` clause for Inbox/Pipeline filters and saved views ([`CriterionCompiler.compile`](../../backend/src/main/java/com/vacancytracker/matching/CriterionCompiler.java#L53-L83)). Values are always bound as parameters, never concatenated, and a `NULL` is coalesced to `FALSE` so unknown data never silently satisfies a filter ([ADR-009](../decisions.md#adr-009-one-criterion-model-for-profiles-and-view-filters)). The returned [`SqlFragment`](../../backend/src/main/java/com/vacancytracker/common/SqlFragment.java#L9) carries SQL and parameters together.
- **Validation before evaluation**: [`ProfileValidator`](../../backend/src/main/java/com/vacancytracker/matching/ProfileValidator.java#L117) checks fields, operators (per field kind) and value shapes; a profile import is *strict* (an unknown key anywhere is rejected with its path) and YAML is read by a parser that does not instantiate classes from tags ([ADR-027](../decisions.md#adr-027-strict-profile-import-and-company-lists), [`ProfileTransfer`](../../backend/src/main/java/com/vacancytracker/web/ProfileTransfer.java)).

**Trade-off.** Matching quality depends entirely on the user's rules (the price of "no domain data in code"). The mitigation is feedback: a live preview shows what a draft profile would do ([`ProfilePreviewService`](../../backend/src/main/java/com/vacancytracker/matching/ProfilePreviewService.java)), and a wizard builds the first profile.

**Tests.** [`RuleEngineTest`](../../backend/src/test/java/com/vacancytracker/matching/RuleEngineTest.java) (every operator and mode, missing-value policies), [`ScorerTest`](../../backend/src/test/java/com/vacancytracker/matching/ScorerTest.java), [`CriterionCompilerTest`](../../backend/src/test/java/com/vacancytracker/matching/CriterionCompilerTest.java), [`CompanyListsTest`](../../backend/src/test/java/com/vacancytracker/matching/CompanyListsTest.java), [`RateSalaryConverterTest`](../../backend/src/test/java/com/vacancytracker/matching/rates/RateSalaryConverterTest.java), [`ProfileValidatorTest`](../../backend/src/test/java/com/vacancytracker/matching/ProfileValidatorTest.java).

**If they push: "Why not an LLM to score?"** Non-determinism, cost, latency, and sending job data to a third party; the project promises that nothing leaves the machine except requests to sources ([ADR-008](../decisions.md#adr-008-rule-based-scoring-in-the-mvp-llm-scoring-optional-later)). Where an LLM *is* used (C14), it only proposes and the application computes and verifies.

---

### C10. Profile versions, background recalculation, and the limits of automatic hiding

**Problem.** Changing a profile should re-score everything, must not race with a running collection, and must never override a decision the user made.

**Decision.**
- **Versions**: every save, including a restore, inserts an immutable snapshot row; each `VacancyMatch` stores the `profileVersion` that produced it, so a score can be traced to its rules ([ADR-010](../decisions.md#adr-010-profile-versioning-keep-every-previous-version)). A rejected save changes nothing: validation runs first and the whole save is one transaction ([`SearchProfileService`](../../backend/src/main/java/com/vacancytracker/matching/SearchProfileService.java#L14-L60)).
- **Generation counter**: after each *committed* profile change, an `AtomicLong` is incremented in an `afterCommit` callback; a long-running [`MatchSession`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L67-L87) compares it per vacancy and reloads the profiles, so a run does not write scores from an old profile version over a recalculation's fresh ones.
- **Recalculation** is a single background job with progress that the UI polls ([`RecalculationService`](../../backend/src/main/java/com/vacancytracker/matching/RecalculationService.java#L60-L118)).
- **The inbox rule** ([`MatchService.applyInboxRule`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L128-L164)) may move a vacancy between *New* and *Hidden*, but only if **all** of these hold: the status is *New* or *Hidden*; the vacancy has at least one source (a hand-added one is never touched); its entire history consists of automatic events (note starts with `auto-`); and no applicable profile accepts it. A single user action in the history permanently takes the vacancy out of automation's reach ([ADR-026](../decisions.md#adr-026-automatic-inbox-hiding-and-its-limits), [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior), [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits)).

**Tests.** [`InboxRuleTest`](../../backend/src/test/java/com/vacancytracker/matching/InboxRuleTest.java), [`RecalculationServiceTest`](../../backend/src/test/java/com/vacancytracker/matching/RecalculationServiceTest.java), [`RecalculationIntegrationTest`](../../backend/src/test/java/com/vacancytracker/matching/RecalculationIntegrationTest.java), [`SearchProfileServiceTest`](../../backend/src/test/java/com/vacancytracker/matching/SearchProfileServiceTest.java).

---

### C11. Full-text search without a search service

**Problem.** Search over title, company and description, plus a Ctrl/Cmd+K palette, for a few thousand rows.

**Decision.** A **generated `tsvector` column with a GIN index** in the database ([`V1__baseline.sql`](../../backend/src/main/resources/db/migration/V1__baseline.sql)), `simple` configuration (language-neutral, no stemming), ranked by `ts_rank` plus boosts for a title (+1.0) or company (+0.5) hit, with `ts_headline` snippets ([`VacancySearchService.search`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancySearchService.java#L28-L56)). User input is converted by [`SearchQuery.toTsQuery`](../../backend/src/main/java/com/vacancytracker/vacancy/SearchQuery.java#L27) so that **only letters and digits survive**: no input can inject tsquery syntax. Every word is a prefix match, all must match, at most 8 terms of 40 characters; a dotted term like `node.js` also matches as a whole token.

**Trade-off and an honest note.** No stemming ("developer" does not match "developers"), and a search for `c#` matches by the prefix `c` (acknowledged as "still not changed" in [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior)). [ADR-015](../decisions.md#adr-015-search-postgresql-full-text-search) says the query uses `websearch_to_tsquery`; the code builds its own sanitized prefix query for `to_tsquery` instead, so the ADR text and the code differ on this point. Say so if asked; the code is the truth.

**Tests.** [`SearchQueryTest`](../../backend/src/test/java/com/vacancytracker/vacancy/SearchQueryTest.java), [`VacancySearchServiceTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VacancySearchServiceTest.java).

---

### C12. Security for an app that runs on `localhost`

A local-first app has an unusual threat model: there is *no login* in the default profile, so the attacker is a **web page open in the user's own browser**.

| Threat | Defence | Code |
|---|---|---|
| **DNS rebinding**: a hostile page re-resolves its name to `127.0.0.1` and becomes same-origin with the local API. The `Origin` check cannot stop it, because `Origin` and `Host` both carry the attacker's name | `HostFilter` answers every request, any path, only for loopback names and names in `APP_ALLOWED_HOSTS` (the VPS domain); the check is deliberately not limited to `/api/` so no path spelling can bypass it | [`HostFilter`](../../backend/src/main/java/com/vacancytracker/web/HostFilter.java#L25-L70), [`HostFilterTest`](../../backend/src/test/java/com/vacancytracker/web/HostFilterTest.java) |
| **Cross-site writes**: a page POSTs to the local API | `OriginFilter` refuses a state-changing request whose `Origin` host differs from `Host` | [`OriginFilter`](../../backend/src/main/java/com/vacancytracker/web/OriginFilter.java#L22-L50), [`OriginFilterTest`](../../backend/src/test/java/com/vacancytracker/web/OriginFilterTest.java) |
| **Token guessing** (VPS profile) | Token compared as SHA-256 digests with `MessageDigest.isEqual` (constant time); the app refuses to start with a token shorter than 16 characters; after 5 wrong tokens from one address in 10 minutes the address gets 429 with `Retry-After`, and *even the correct token is refused while blocked*, so guessing cannot be paced under the limit; a request with no token at all does not count as a guess | [`AccessToken`](../../backend/src/main/java/com/vacancytracker/web/auth/AccessToken.java#L8-L25), [`AuthFilter`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthFilter.java#L33-L60), [`FailureLimiter`](../../backend/src/main/java/com/vacancytracker/web/auth/FailureLimiter.java#L18-L60) |
| **Auth bypass through path tricks** | The filter decides on the path Spring *routes* on (`UrlPathHelper`: decoded, dot-segments resolved, `;params` removed), not the raw URI, because `/api;x=1/vacancies` routes to `/api/vacancies` but does not start with `/api/` | [`AuthFilter.shouldNotFilter`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthFilter.java#L33-L40) |
| **Cookie theft / CSRF** | `HttpOnly`, `SameSite=Strict`, `Secure` when HTTPS (directly or via `X-Forwarded-Proto`); a token in a query string is never accepted | [`AuthController.cookie`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthController.java#L86-L90) |
| **SSRF on "import by URL"** | Only http(s); loopback, link-local, site-local, multicast, carrier-grade NAT and unique-local addresses are refused for the address *and each redirect*; an `allow-private-hosts` switch exists for the owner | [`UrlFetcher.requireAllowed` / `isInternal`](../../backend/src/main/java/com/vacancytracker/collection/UrlFetcher.java#L97-L125), [`ImportUrlTest`](../../backend/src/test/java/com/vacancytracker/web/ImportUrlTest.java) |
| **`javascript:` / `data:` links from a source** | Only http(s) links with a host are stored ([`CanonicalUrl.webLink`](../../backend/src/main/java/com/vacancytracker/common/CanonicalUrl.java#L27-L40)); the UI also opens only http(s) ([`safeLink`](../../frontend/src/domain/links.ts#L5)) for rows stored before the rule | [`CanonicalUrlTest`](../../backend/src/test/java/com/vacancytracker/common/CanonicalUrlTest.java), [`links.test.ts`](../../frontend/src/domain/links.test.ts) |
| **XSS and clickjacking** | A strict CSP (`default-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`), `X-Frame-Options`, `nosniff`, `Referrer-Policy: no-referrer`; no `dangerouslySetInnerHTML` in the UI; a code-scanning alert on DOM text was fixed | [`SecurityHeadersFilter`](../../backend/src/main/java/com/vacancytracker/web/SecurityHeadersFilter.java#L22-L48), [`SecurityHeadersFilterTest`](../../backend/src/test/java/com/vacancytracker/web/SecurityHeadersFilterTest.java) |
| **XXE in feeds and pages** | DOCTYPE declarations are refused in the feed adapter | [`RssAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/rss/RssAdapter.java#L29) |
| **Secrets in logs** | Two layers: known shapes (query-string keys, bearer/basic values, a Telegram bot URL, the session cookie) *and* every secret value read from configuration, matched as exact text; properties named `*password`, `*secret`, `*token`, `*key` register themselves at start-up, so a new setting is covered without code; messages that are stored or shown (not only logged) are masked at the source | [`LogMasking`](../../backend/src/main/java/com/vacancytracker/common/LogMasking.java#L15-L60), [`SecretRegistration`](../../backend/src/main/java/com/vacancytracker/common/logging/SecretRegistration.java), [`LogMaskingTest`](../../backend/src/test/java/com/vacancytracker/common/LogMaskingTest.java), [`LogOutputTest`](../../backend/src/test/java/com/vacancytracker/common/logging/LogOutputTest.java) |
| **Container hardening** | `cap_drop: [ALL]`, `no-new-privileges`, memory limits, app bound to `127.0.0.1`; on a VPS only Caddy is exposed | [`docker-compose.yml`](../../docker-compose.yml), [`docker-compose.vps.yml`](../../docker-compose.vps.yml) |

**Known limits (stated in [ADR-033](../decisions.md#adr-033-access-token-how-adr-017-is-implemented) and [ADR-034](../decisions.md#adr-034-log-masking-and-structured-logs)).** The cookie *is* the token, so anyone who can read the browser profile or a proxy log that records cookies has it; the failure table is in memory (a restart clears it) and per address, so an attacker with many addresses is slowed, not stopped (hence the 16-character minimum); a secret that is neither shaped like the patterns nor in a `*key`-style property is not masked; a name that changes its DNS answer between the SSRF check and the request is not covered.

**If they push: "How did you find the DNS-rebinding hole?"** An independent review ([code-review.md](../code-review.md), finding F1, critical): the Origin check alone was believed sufficient. The lesson recorded in [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits) is that a same-origin check is only as strong as the guarantee that *the origin is yours*, which the Host header must also prove.

---

### C13. Mail tracking: read-only, idempotent, rule-based

**Problem.** Two jobs from the user's mailbox: (a) *alert* e-mails from LinkedIn, Jobs.cz, StartupJobs and Indeed as a source that does not scrape those sites, and (b) *answers* to applications (confirmations, rejections, interview invitations, offers), linked back to the vacancy.

**Decisions.**
- **The reader cannot damage the mailbox.** It opens folders read-only by default, fetches with `peek` (does not even set the "read" flag), never deletes, moves or sends, never reads attachments, asks the server to filter by date so older mail is never listed, caps bodies and the number of messages per read, and never logs the password ([`ImapMailReader`](../../backend/src/main/java/com/vacancytracker/collection/mail/ImapMailReader.java#L132-L230), [ADR-029](../decisions.md#adr-029-imap-reader-read-only-processed-state-in-the-database)).
- **"Processed" lives in the database** (table `processed_mail`, keyed by Message-ID, or folder + UID validity + UID), not in mailbox flags, so it works on servers that cannot store flags and when a message is copied. A handler answers `PROCESSED` or `RETRY` ([`MailHandler`](../../backend/src/main/java/com/vacancytracker/collection/mail/MailHandler.java#L9-L13)); only `PROCESSED` is remembered.
- **At-least-once delivery, made idempotent.** A message is marked processed only *after* its vacancies were stored: the adapter returns a `FetchOutcome.afterStored(...)` callback that the pipeline runs once every posting was written without a storage error ([`CollectionPipeline.runAdapter`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L197-L204)). If storing failed, the same mail is offered again and de-duplication absorbs the repeat. The same pattern is used for the Telegram update offset ([`TelegramForwardAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/telegram/TelegramForwardAdapter.java#L81-L94)).
- **One parser per sender**, reading only the e-mail content: it never opens a link, never logs in, and rewrites job links to their plain form. A parser that accepts a mail but finds no vacancies logs a warning (a changed layout would otherwise retry forever) ([`LinkedInAlertParser`](../../backend/src/main/java/com/vacancytracker/collection/mail/LinkedInAlertParser.java), [`EmailAlertAdapter`](../../backend/src/main/java/com/vacancytracker/collection/mail/EmailAlertAdapter.java#L20), [ADR-030](../decisions.md#adr-030-email-sources-one-parser-per-sender-no-expiry)).
- **Classification is rule-based, in five languages, and checked in a deliberate order**: an alert subject first (alerts often contain words like "interview"), then rejection before anything else ("thank you for applying ... unfortunately" is a rejection) ([`MailClassifier.classify`](../../backend/src/main/java/com/vacancytracker/application/mail/MailClassifier.java#L60-L80)). **Mail is never sent to an AI**, and only *job* mail is stored (sender, subject, date and the first 600 characters), everything else is skipped ([ADR-043](../decisions.md#adr-043-correspondence-job-mail-links-to-applications-status-suggestions)).
- **Linking is a transparent score, not a guess.** [`MailLinker.link`](../../backend/src/main/java/com/vacancytracker/application/mail/MailLinker.java#L45-L88): company name in the subject or sender +4 (or in the body +3), sender domain matching the company or its posting +3 (a job board's domain counts for nothing, because one sender writes about many vacancies), title in the mail +2, +1 if the user applied; a threshold of 3 must be met and the evidence string is stored with the link. Otherwise the mail stays unlinked for the user to link.
- **Suggest, never act.** A rejection suggests *Rejected*, an interview suggests *Interview*, and so on; nothing changes until the user accepts, and accepting walks the legal path through [`StatusPath`](../../backend/src/main/java/com/vacancytracker/vacancy/StatusPath.java#L23-L48).
- **Compliance by design** ([ADR-007](../decisions.md#adr-007-source-policy-no-direct-scraping-of-linkedin-or-other-forbidden-sources), [ADR-045](../decisions.md#adr-045-new-sources-from-the-owners-list)): no scraping of forbidden sites; Telegram is read only through the owner's *own bot* with forwarded posts (which Telegram's bot terms allow), never by reading channels; Facebook and LinkedIn groups are blocked; the generic HTML adapter requires a *recorded* terms check and honours `robots.txt` per RFC 9309 (a 5xx counts as a failure, a disallowed page is never requested) ([`HtmlSiteAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/html/HtmlSiteAdapter.java#L100-L135), [`RobotsRules`](../../backend/src/main/java/com/vacancytracker/collection/adapter/html/RobotsRules.java)). [sources.md](../sources.md) records the terms check for every source.

**Tests.** [`ImapMailReaderTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/ImapMailReaderTest.java), [`ProcessedMailRepositoryTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/ProcessedMailRepositoryTest.java), [`SenderParsersTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/SenderParsersTest.java) and [`LinkedInAlertParserTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/LinkedInAlertParserTest.java) with real-shaped fixtures in [`test/resources/mail`](../../backend/src/test/resources/mail), [`MailClassifierTest`](../../backend/src/test/java/com/vacancytracker/application/mail/MailClassifierTest.java), [`MailLinkerTest`](../../backend/src/test/java/com/vacancytracker/application/mail/MailLinkerTest.java), [`CorrespondenceSyncTest`](../../backend/src/test/java/com/vacancytracker/application/mail/CorrespondenceSyncTest.java), [`HtmlSiteAdapterTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/html/HtmlSiteAdapterTest.java), [`RobotsRulesTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/html/RobotsRulesTest.java).

**If they push: "Why e-mail alerts instead of scraping LinkedIn?"** The terms prohibit it, it has been enforced in court, and it is fragile. Alerts the user already receives give coverage with no legal exposure. It also keeps the design honest: the tool *never logs in* to a job site.

---

### C14. AI with a short leash

**Problem.** The owner wants resumes and cover letters adapted by Claude, but with no hidden data flow, no hallucinated facts, and no score a model can inflate.

**Decisions.**
1. **Off by default, and two gates.** [`AiService`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L22-L90) is the *only door*. A call is refused (HTTP 409) **before anything leaves the machine** unless the provider is configured *and* the owner has switched AI on in Settings after reading the exact notice of what is sent. Consent is stored per provider (`ai_consent`), so switching from `cli` to `api` asks again, and it can be withdrawn at any time ([`complete`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L80-L88), [`consent`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L60-L71)). The notice text is a constant next to the code ([`NOTICE`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L28-L33)) and is repeated in the README.
2. **A sandboxed CLI provider.** The `cli` implementation runs the official `claude` in print mode with **no tools, no MCP servers, no slash commands, no saved session, settings from an empty temp folder only**, the prompt on stdin, and **`ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` removed from the child's environment**, so the subscription pays and the app never sees or passes a token ([`commandLine`](../../backend/src/main/java/com/vacancytracker/application/ai/ClaudeCliClient.java#L58-L66), [`complete`](../../backend/src/main/java/com/vacancytracker/application/ai/ClaudeCliClient.java#L69-L112)). A timeout kills the process; the temp folder is deleted in `finally`. The `api` provider uses the official SDK; a refusal is reported, never shown as a document ([`AnthropicApiClient`](../../backend/src/main/java/com/vacancytracker/application/ai/AnthropicApiClient.java#L28-L95)). A `fake` provider answers from the prompt for tests and the end-to-end suite ([`FakeAiClient`](../../backend/src/main/java/com/vacancytracker/application/ai/FakeAiClient.java#L25)).
3. **Prompt-injection hygiene.** Rules live in the system prompt; data lives in tagged blocks; the vacancy text comes from the internet and is marked as data. Anything in any data that *looks like one of our tags* is rewritten to `[tag]` so a posting cannot close its own block and add a task ([`Prompts.neutralise` / `block`](../../backend/src/main/java/com/vacancytracker/application/documents/Prompts.java#L94-L100), built by [`Prompts.adapt`](../../backend/src/main/java/com/vacancytracker/application/documents/Prompts.java#L37-L57)); [`PresetGenerationApiTest`](../../backend/src/test/java/com/vacancytracker/web/PresetGenerationApiTest.java#L93-L116) sends a posting that contains `</vacancy>` and "Ignore all previous instructions" and asserts that the block stays closed exactly once and the text arrives as `[vacancy]`.
4. **The model proposes, the code decides.**
   - **Employer match**: the model proposes a status and a quote per requirement; the server then **verifies every quote is literally in the document** (whitespace aside); a quote not found makes the requirement *missing*; a quote found only in a Skills section caps it at *partial* ([`QuoteCheck.place`](../../backend/src/main/java/com/vacancytracker/application/analysis/QuoteCheck.java#L30-L78), [`AnalysisService.check`](../../backend/src/main/java/com/vacancytracker/application/analysis/AnalysisService.java#L244-L258)). The score itself is computed in code: `round(100 × Σ(weight × status) / Σ(weight))` with weights 3/2/1 and statuses 1/0.5/0 ([`EmployerMatch.score`](../../backend/src/main/java/com/vacancytracker/application/analysis/EmployerMatch.java#L20-L34)). "A model that quotes loosely loses points rather than gaining them."
   - **Caching by content hash**: checklist, match and goal results are keyed by the MD5 of the text they read (a content fingerprint, not a security use), so an unchanged text always gets the same score and a tailored text is scored on the *same* checklist as its base ([`AnalysisService.hash`](../../backend/src/main/java/com/vacancytracker/application/analysis/AnalysisService.java#L283-L296)).
   - **Fact check before saving**: [`FactCheck`](../../backend/src/main/java/com/vacancytracker/application/documents/FactCheck.java#L19-L50) diffs a generated text against the *base documents* and reports numbers, dates, employers, titles and technologies that are not in the base. Saving a draft with findings is refused (422) unless the user confirms *those exact findings*; a new finding needs a new confirmation ([ADR-051](../decisions.md#adr-051-deterministic-fact-check-before-saving)). The check is deliberately literal, a bias towards truth.
5. **Immutable provenance.** A generated text is a *draft* that is never stored. Each draft stores a `generation_snapshot` (preset, guardrail, goal, weights, prompt, base draft); a saved version points at it and is never changed, so it stays known what a sent document was made from even if the preset changes later ([`GenerationSnapshot`](../../backend/src/main/java/com/vacancytracker/application/documents/GenerationSnapshot.java#L14), [`DocumentService.save`](../../backend/src/main/java/com/vacancytracker/application/documents/DocumentService.java#L185-L214), [ADR-048](../decisions.md#adr-048-generation-from-the-base-draft-with-a-snapshot)). The guardrail is not a preset field: it is read from resources and cannot be edited ([`PresetDefaults`](../../backend/src/main/java/com/vacancytracker/application/preset/PresetDefaults.java#L13), [`presets/default/guardrail.md`](../../backend/src/main/resources/presets/default/guardrail.md)).
6. **A human submits.** The application package fixes which versions go with an application; the ZIP holds the documents and a checklist; the app opens the page in a new tab and **never fills in or submits a form**. "I submitted" moves the vacancy to *Applied* through `StatusPath` with the package and versions in the history note ([`PackageService.markSubmitted`](../../backend/src/main/java/com/vacancytracker/application/packaging/PackageService.java#L132-L160), [ADR-042](../decisions.md#adr-042-tailored-documents-versions-and-application-packages)).

**Trade-off.** More moving parts than "call the API and show the text", and the first open of a vacancy with AI on costs up to four calls (checklist, goal, base resume, base cover letter), none afterwards while the texts are unchanged ([ADR-049](../decisions.md#adr-049-employer-match-and-goal-match)).

**Tests.** [`ClaudeCliClientTest`](../../backend/src/test/java/com/vacancytracker/application/ai/ClaudeCliClientTest.java) (with a fake `claude` main class, [`FakeClaudeMain`](../../backend/src/test/java/com/vacancytracker/application/ai/FakeClaudeMain.java)), [`AnthropicApiClientTest`](../../backend/src/test/java/com/vacancytracker/application/ai/AnthropicApiClientTest.java), [`QuoteCheckTest`](../../backend/src/test/java/com/vacancytracker/application/analysis/QuoteCheckTest.java), [`FactCheckTest`](../../backend/src/test/java/com/vacancytracker/application/documents/FactCheckTest.java), [`AiDocumentsApiTest`](../../backend/src/test/java/com/vacancytracker/web/AiDocumentsApiTest.java), [`FactCheckApiTest`](../../backend/src/test/java/com/vacancytracker/web/FactCheckApiTest.java), and the browser test [`full-cycle.spec.ts`](../../e2e/tests/full-cycle.spec.ts) (fake AI only).

**If they push: "How do you stop the model inventing a skill?"** I do not try to *prevent* it, I make it *visible and costly*: the guardrail tells it to use only the base documents; then a deterministic check lists every number, date, employer, title or technology that is not in those documents, and saving waits for the user. The score cannot be inflated, because it is computed from quotes the server verified.

---

### C15. A deterministic resume fit score

**Problem.** The user wants to see how a vacancy compares with their resume, fast, for every row in a list, without an AI call.

**Decision.** [`ResumeFitScorer.score`](../../backend/src/main/java/com/vacancytracker/application/fit/ResumeFitScorer.java#L31-L93) is a pure function: the vacancy "asks" for its skill-like terms plus every resume keyword it mentions; `coverage = matched / asked`; `score = 85 × coverage`, `+15` if a resume keyword is in the title, `−15` if the vacancy asks for more years than the resume shows, clamped to 0..100, with reasons for matched skills, missing skills, title, years and thin text. Keywords are *proposed* from the resume (a "Skills" heading, then term shapes such as `C#`, `Node.js`, `CI/CD`), and the **user edits that list**, so there is no built-in skill dictionary ([ADR-040](../decisions.md#adr-040-resume-and-the-fit-score), [`Terms`](../../backend/src/main/java/com/vacancytracker/application/resume/Terms.java#L24)). The file format is decided by the bytes (`%PDF`, `PK`), not by the browser's claim ([`ResumeTextExtractor`](../../backend/src/main/java/com/vacancytracker/application/resume/ResumeTextExtractor.java#L76-L78)).

**Staleness handled without a job framework.** A fit is stale when the resume (or its keywords) changed, or when the vacancy's text changed since it was scored. Each fit stores an MD5 of the text it read, compared in SQL with PostgreSQL's `md5` of the same text, so reading a fit first recomputes it if needed; a resume change and the end of every run (via the `RunEventBus` done-listener) start a background pass over stale fits ([`ResumeFitService`](../../backend/src/main/java/com/vacancytracker/application/fit/ResumeFitService.java#L60-L107)).

**Trade-off.** Shape-based terms catch some non-skills (a city in a list), and synonyms are not merged (`k8s` and `Kubernetes` are two terms). The "missing" list is advice, not a verdict, and the UI says "N of M skills", never a percentage that sounds like a probability ([ADR-052](../decisions.md#adr-052-beacon-design-light-first)).

**Tests.** [`ResumeFitScorerTest`](../../backend/src/test/java/com/vacancytracker/application/fit/ResumeFitScorerTest.java), [`TermsTest`](../../backend/src/test/java/com/vacancytracker/application/resume/TermsTest.java), [`ResumeTextExtractorTest`](../../backend/src/test/java/com/vacancytracker/application/resume/ResumeTextExtractorTest.java), [`ResumeApiTest`](../../backend/src/test/java/com/vacancytracker/web/ResumeApiTest.java).

---

### C16. A resume PDF that is real text, at most two pages

**Decision.** [`ResumePdf.render`](../../backend/src/main/java/com/vacancytracker/application/documents/ResumePdf.java#L47-L84) writes the PDF with PDFBox (already a dependency): A4, one column, real selectable text (what applicant tracking systems read), no tables, icons or text boxes, **Liberation Sans embedded as a subset** (SIL OFL) so Cyrillic and Latin render the same everywhere; a character with no glyph becomes `?` instead of failing. The page count is *measured after every render* ([`pages`](../../backend/src/main/java/com/vacancytracker/application/documents/ResumePdf.java#L85)); a saved resume version stores its PDF, and a text whose PDF has 3+ pages is refused (422) and nothing is stored. After an over-long AI draft, AI is asked to shorten *at most twice*, then the user edits ([`DocumentService.shorten`](../../backend/src/main/java/com/vacancytracker/application/documents/DocumentService.java#L107-L127)); an uploaded PDF is used unchanged if it has 1-2 pages ([ADR-050](../decisions.md#adr-050-resume-pdf-of-at-most-two-pages)).

**Trade-off.** The layout is plain on purpose; a designed layout would need a different renderer and its own ADR.

**Tests.** [`ResumePdfTest`](../../backend/src/test/java/com/vacancytracker/application/documents/ResumePdfTest.java), [`ResumePdfApiTest`](../../backend/src/test/java/com/vacancytracker/web/ResumePdfApiTest.java).

---

### C17. Exports and backups that can be restored

**Decision.**
- **CSV**: UTF-8 with BOM and RFC 4180 quoting so Excel opens non-ASCII text correctly ([`VacancyExporter`](../../backend/src/main/java/com/vacancytracker/export/VacancyExporter.java#L36)); a **hand-written minimal CSV parser** handles quoted fields, doubled quotes, CRLF/LF and an optional BOM ([`CsvParser`](../../backend/src/main/java/com/vacancytracker/export/CsvParser.java#L6)). A CSV import *merges* into existing vacancies and keeps fields the user edited by hand, with a per-row error report ([`VacancyImporter`](../../backend/src/main/java/com/vacancytracker/export/VacancyImporter.java)).
- **XLSX**: Apache POI's **streaming** `SXSSFWorkbook` so large sets do not live in memory; cells are cut at Excel's 32,767-character limit ([`XlsxExporter`](../../backend/src/main/java/com/vacancytracker/export/XlsxExporter.java#L26)).
- **JSON backup** with a `schemaVersion`, vacancies and status history, plus **sections contributed by other modules** through [`BackupSection`](../../backend/src/main/java/com/vacancytracker/common/BackupSection.java#L12-L18) (presets, with the uploaded base resume file in Base64). Older backups without a section import as before, so the schema version stays 1 ([`BackupWriter`](../../backend/src/main/java/com/vacancytracker/export/BackupWriter.java#L37-L55), [ADR-047](../decisions.md#adr-047-ai-presets-and-base-documents)).
- **Stable reads**: exports read in a stable order (first sighting), so a run that adds vacancies during a backup cannot make a row slip between pages ([ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior)).
- **Database backup**: a `backup` container runs `pg_dump` in custom format, accepts a dump only after it was fully written *and read back* with `pg_restore --list`, keeps files by age, and never deletes files it did not create; restore is documented and tested against a real compose stack ([`backup.sh`](../../ops/backup/backup.sh), [`test-backup.sh`](../../ops/backup/test-backup.sh), [operations.md](../operations.md), [ADR-032](../decisions.md#adr-032-backup-service)).

**Tests.** [`BackupRoundTripTest`](../../backend/src/test/java/com/vacancytracker/export/BackupRoundTripTest.java), [`VacancyCsvExportTest`](../../backend/src/test/java/com/vacancytracker/export/VacancyCsvExportTest.java), [`VacancyXlsxExportTest`](../../backend/src/test/java/com/vacancytracker/export/VacancyXlsxExportTest.java), [`CsvParserTest`](../../backend/src/test/java/com/vacancytracker/export/CsvParserTest.java).

**Honest limit.** The backup volume is on the same host as the database, so it does not protect against losing the machine; the JSON export is the second, independent route ([ADR-032](../decisions.md#adr-032-backup-service)).

---

### C18. A front end built for the keyboard and for low vision

**Decisions.**
- **Preact + TypeScript (strict), no UI library, charts as SVG**; the Vite build is copied into the jar, so the deployment is one artifact ([ADR-005](../decisions.md#adr-005-frontend-vite--typescript--preact-served-by-spring-boot)).
- **Logic out of components.** Rules live in pure modules under [`domain/`](../../frontend/src/domain) with unit tests, for example keyboard triage ([`triage.ts`](../../frontend/src/domain/triage.ts#L1-L35)), the status table mirror ([`statuses.ts`](../../frontend/src/domain/statuses.ts#L5)), the line diff between a base and a tailored document ([`diff.ts`](../../frontend/src/domain/diff.ts#L13)), and URL-stored filter state ([`router.ts`](../../frontend/src/router.ts#L12)).
- **Keyboard-first Inbox** (`J`/`K` move, `S` save, `X` dismiss, `O` open, `Enter` details). Details that came from a review: the shortcut falls back to the **physical key** (`e.code`) when the typed letter is not Latin, so it works with a Ukrainian or Russian layout, but *not* for layouts like Dvorak that have the Latin letter ([`actionForKey`](../../frontend/src/domain/triage.ts#L19-L35)); shortcuts are ignored inside form fields and dialogs, and never steal Enter/Space from a focused button ([`isTypingTarget`, `activatesOnKey`](../../frontend/src/domain/triage.ts#L56-L71)); selection is a **vacancy id** held in a ref, not a row index, so fast typing cannot act on the wrong row ([`Inbox.tsx` state and refs](../../frontend/src/screens/Inbox.tsx#L40-L58), [key handler](../../frontend/src/screens/Inbox.tsx#L162)).
- **No accidental data changes**: a `<select>` that changes status commits on every arrow key on Windows, so selects only *choose* and a **Move** button applies ([`VacancyDrawer`](../../frontend/src/screens/VacancyDrawer.tsx#L176)); a finished Refresh reloads screen data through a `dataVersion` counter instead of re-mounting, so an unsaved note, the bulk Undo toast and the selection survive ([`App.tsx`](../../frontend/src/App.tsx#L65)).
- **Accessibility as tests, not intentions**: a unit test reads `tokens.css` and checks 58 text, border and focus pairs against 7:1 (text) and 3:1 (borders) targets, and another checks that glass fills still meet them ([`contrast.test.ts`](../../frontend/src/styles/contrast.test.ts), [`liquidGlass.test.ts`](../../frontend/src/styles/liquidGlass.test.ts)); match bars are focusable `meter` elements with the value spoken ([`MatchBar`](../../frontend/src/components/MatchBar.tsx#L4)); glass turns solid under Reduced transparency, Increased contrast and forced colors.
- **Offline by design**: system font stacks, no font or analytics requests, so the app loads nothing from the internet ([ADR-052](../decisions.md#adr-052-beacon-design-light-first), [ADR-053](../decisions.md#adr-053-graphite-apple-uber-and-bugatti-references-over-beacon)).

**Tests.** 82 Vitest files, plus Playwright specs in [`e2e/tests`](../../e2e/tests) (smoke, full application cycle with fake AI, preset scenario, Inbox filter, Beacon design).

**Honest limit.** The manual checks named in [ADR-052](../decisions.md#adr-052-beacon-design-light-first) and [ADR-054](../decisions.md#adr-054-liquid-glass-over-graphite) (a screen reader, real 200% zoom, users with low vision) are *not* automated and have *not* been done; only contrast, keyboard tabs, text size and 640 px reflow are covered by tests.

---

### C19. A testing strategy with teeth

| Layer | Approach | Where |
|---|---|---|
| Unit | Pure logic with no Spring: dedup, salary parsing, rule engine, scorer, status table, ghost heuristics, fact check, mail classifier | [`backend/src/test`](../../backend/src/test/java/com/vacancytracker/) |
| Integration | **One PostgreSQL 16 Testcontainer per test JVM**, shared across classes; Spring caches application contexts across classes, so a per-class container would leave cached contexts pointing at a stopped database; Flyway migrations run at context start; each test truncates tables first | [`AbstractIntegrationTest`](../../backend/src/test/java/com/vacancytracker/support/AbstractIntegrationTest.java#L10-L34) |
| Adapter contracts | Every adapter is tested with **recorded fixtures** against a local JDK `HttpServer`; no real network, ever | e.g. [`GreenhouseContractTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/greenhouse/GreenhouseContractTest.java), [`LeverContractTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/lever/LeverContractTest.java), [`AdapterContractTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/AdapterContractTest.java), fixtures in [`test/resources/fixtures`](../../backend/src/test/resources/fixtures) |
| Architecture | ArchUnit rules on every build | [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java) |
| Schema | A baseline-schema test and a test for the Java data migration | [`BaselineSchemaTest`](../../backend/src/test/java/com/vacancytracker/BaselineSchemaTest.java), [`CardLinkRepairMigrationTest`](../../backend/src/test/java/com/vacancytracker/vacancy/CardLinkRepairMigrationTest.java) |
| Time | A controllable clock instead of `Instant.now()` | [`MutableClock`](../../backend/src/test/java/com/vacancytracker/support/MutableClock.java) |
| Interactions | Tests added after the 2026-11-02 review for races and feature interactions that single-feature tests missed | [`ReviewFixesTest`](../../backend/src/test/java/com/vacancytracker/web/ReviewFixesTest.java), [`FullCycleIntegrationTest`](../../backend/src/test/java/com/vacancytracker/web/FullCycleIntegrationTest.java) |
| Front end | Vitest + jsdom for logic and components; `npm audit --omit=dev --audit-level=high` in CI | [`frontend`](../../frontend) |
| End to end | Playwright against the *packaged jar* on an empty PostgreSQL; fails on any browser script error; fake AI and a test letter only | [`e2e/tests`](../../e2e/tests) |
| Gates | Checkstyle fails the build; JaCoCo 80% line gate per core module | [`pom.xml`](../../backend/pom.xml) |

**A story that shows maturity.** The first independent review ran the documented build on a clean machine and found that *the build itself did not run on current tools*: Testcontainers 1.21.3 could not talk to Docker Engine 29, and one backend test needed a UI build that the CI backend job never made ([code-review.md](../code-review.md), B1 and B2). Both were fixed (Testcontainers 1.21.4; the test now asserts the auth filter does not refuse the page). Green CI on one developer's machine is not the same as a reproducible build.

---

### C20. Domain heuristics, kept honest

Two small features show how to add domain knowledge *without* hard-coding jobs, countries or technologies.

- **Ghost-job signals** ([`GhostJob.assess`](../../backend/src/main/java/com/vacancytracker/vacancy/GhostJob.java#L28-L51)): three signs computed from the user's *own data*, with no outside service, each with a stated reason: posted 30+ days ago, listed 30+ days since first seen, no salary. Two of three make a vacancy "likely". It is advice, not a verdict; a vacancy with no posting date is judged by the other two signs only; and a profile can use it as a rule (`ghostSignals gte 2`) via [`VacancyFacts`](../../backend/src/main/java/com/vacancytracker/matching/VacancyFacts.java#L37).
- **Visa sponsorship** ([`VisaSponsorship.detect`](../../backend/src/main/java/com/vacancytracker/vacancy/VisaSponsorship.java#L40)): reads the posting's own words, returns `false` for a refusal, `true` for an offer, `null` otherwise, in English, German, Polish, Ukrainian and Russian. **A refusal beats an offer in the same text** ("sponsorship is not available, but visa support for family members"). Unknown is *never* true or false, so a profile decides how to treat unknown. It is derived at discovery, on a description refresh and on a user edit ([`Vacancy.applyAutomated`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L124-L127)), and a Java migration (V12) back-filled existing rows ([`V12__Detect_visa_sponsorship.java`](../../backend/src/main/java/db/migration/V12__Detect_visa_sponsorship.java)). The phrase lists are language data, not a list of jobs, so the "no domain data in code" rule holds ([ADR-044](../decisions.md#adr-044-visa-sponsorship-from-the-posting-text)).
- **Country detection** takes a country only when a whole comma-separated part of the location is a country name (ten languages from JDK locale data), excludes "Georgia", ignores two-letter codes (they clash with US states) and drops anything ambiguous; the reasoning is that **a wrong country would hide vacancies through a `require` rule, so a missing one is the safer error** ([`CountryDetector`](../../backend/src/main/java/com/vacancytracker/collection/normalize/CountryDetector.java#L17), [ADR-036](../decisions.md#adr-036-feed-adapter-country-detection-and-what-stays-undetected)).

**Tests.** [`GhostJobTest`](../../backend/src/test/java/com/vacancytracker/vacancy/GhostJobTest.java), [`GhostJobApiTest`](../../backend/src/test/java/com/vacancytracker/web/GhostJobApiTest.java), [`VisaSponsorshipTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VisaSponsorshipTest.java), [`CountryDetectorTest`](../../backend/src/test/java/com/vacancytracker/collection/normalize/CountryDetectorTest.java), [`SalaryParserTest`](../../backend/src/test/java/com/vacancytracker/collection/normalize/SalaryParserTest.java).

---

## 6. Cross-cutting themes

Where each topic that interviewers ask about lives in this codebase.

| Topic | What to say | See |
|---|---|---|
| **Concurrency** | A single-thread executor plus a `synchronized` coordinator for runs; a background recalculation job; SSE emitters with replay; a `generation` counter that lets a long job notice a concurrent profile change; row locks for read-modify-write; `pg_advisory_xact_lock` to serialise spaced API calls | C2, C5, C10, [`CallBudget`](../../backend/src/main/java/com/vacancytracker/collection/http/CallBudget.java#L40-L58) |
| **Transactions** | Service methods are `@Transactional`; ingest of one posting is one transaction; expiry and matching run **one transaction per vacancy** (via `TransactionTemplate`) so one bad row never undoes the others; imports are per-row transactions | [`IngestService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/IngestService.java#L36), [`MatchService.session`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L84), [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L64-L70), [`VacancyService.importState`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L190-L199) |
| **Idempotency** | Dedup absorbs repeats; mail and Telegram offsets are committed *after* storing; the call budget is an atomic upsert; "I submitted" is a no-op the second time; adding an existing company-list entry changes nothing | C5, C8, C13 |
| **Resilience** | Per-adapter isolation, retry/backoff/jitter, persisted circuit breaker, bounded and time-limited bodies, same-host redirects, a run that always ends, a start-up sweeper | C5, C6 |
| **Data integrity** | Unique canonical-URL index, `CHECK` constraint on status, `ON CONFLICT` upserts, an append-only history, immutable versions and snapshots | [`V1__baseline.sql`](../../backend/src/main/resources/db/migration/V1__baseline.sql), C3, C4, C14 |
| **API design** | Problem-detail (RFC 9457 style) error bodies with field errors; 202 vs 200 for "started" vs "already running"; SSE for progress; strict import validation with per-row reports | [`ApiExceptionHandler`](../../backend/src/main/java/com/vacancytracker/web/ApiExceptionHandler.java), [`RunController`](../../backend/src/main/java/com/vacancytracker/web/RunController.java#L83) |
| **Security** | Threat model for a local-first app (the browser is the attacker), constant-time compare, lockout, SSRF guard, CSP, log masking | C12 |
| **Observability** | Structured per-source and per-run log lines with key-value fields (JSON in the `vps` profile), secrets masked at the output, health endpoint only; **no metrics endpoint** (stated gap) | [`CollectionPipeline`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L133-L155), [`logback-spring.xml`](../../backend/src/main/resources/logback-spring.xml) |
| **Testing** | Contract tests on fixtures, Testcontainers, ArchUnit, coverage gate, Playwright | C19 |
| **Build, CI/CD, ops** | Maven `verify` as the single gate; four CI jobs; Compose with hardened containers; daily verified backups; a documented restore | C17, C19, [`ci.yml`](../../.github/workflows/ci.yml) |
| **Domain modelling** | Aggregate with an explicit state table; value objects (`AutomatedFields`, `FetchOutcome`, `SqlFragment`); records for immutable data | C3, C4 |
| **Process** | 54 ADRs, a plan with task IDs kept in sync with a CSV by a CI job, a changelog per task, independent code reviews whose findings are tracked | [PLAN.md](../../PLAN.md), [code-review.md](../code-review.md) |

---

## 7. Demo script (5 minutes)

Run with `docker compose up -d --build` (set `POSTGRES_PASSWORD` in `.env` first) and open <http://127.0.0.1:8080>. The screenshots in the [README](../../README.md#screenshots) are a fallback.

1. **Wizard and profile (45 s).** First run: the wizard builds a search profile. Show the live preview: a draft profile scores real vacancies *before* it is saved, with the reasons. Point at the point of the design: no roles or countries in code, everything is profile data.
2. **Refresh (45 s).** Press Refresh: live per-source progress over SSE. Mention that one source failing does not stop the others, and show the per-source status on the Sources screen. *(Code: C5, C6.)*
3. **Inbox triage (60 s).** Keyboard only: `J`/`K`, `S`, `X`, `O`. Open a vacancy: score badge with reasons, the "Likely ghost" flag with its reasons, visa sponsorship as a fact, a source list showing a merged duplicate. *(Code: C8, C9, C20.)*
4. **Pipeline and history (45 s).** Table and Kanban, a status change, the history, a bulk change with Undo. The UI offers only the legal next statuses (it mirrors the table), and the server refuses anything else. *(Code: C4.)*
5. **Dashboard (30 s).** The funnel and the "Time to follow up" list (applications quiet for 10 days). Note the numbers come from the status history.
6. **Mail (45 s).** The Mail screen: an answer linked to an application *with the reason*, and a *suggested* status change that waits for Accept. Stress read-only IMAP. *(Code: C13.)*
7. **AI, if asked (60 s).** Settings shows AI **off**; the consent notice lists exactly what is sent. With the fake provider: Generate, line diff against the base, the fact-check findings blocking save until confirmed, Employer match "Base 62% → Tailored 78%", the two-page PDF, the package ZIP. The app opens the application page; **the user submits**. *(Code: C14, C16.)*
8. **Close with the numbers**: 54 ADRs, ArchUnit-enforced boundaries, no test touches the real network, and the list of honest limitations below.

---

## 8. Honest limitations and what I would do next

Saying these first is a strength: every item below is documented in the repository, so none is a surprise to a reviewer.

| Limitation | Evidence | What I would do |
|---|---|---|
| **Single-instance only.** The run lock is in-process; the PostgreSQL advisory lock proposed in [ADR-006](../decisions.md#adr-006-scheduling-single-run-lock-and-sse-progress) was not implemented | [`RunCoordinator`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java#L54-L68), the "Implementation note (T-044)" in [ADR-006](../decisions.md#adr-006-scheduling-single-run-lock-and-sse-progress) | Take `pg_try_advisory_lock` on a dedicated connection for the whole run, and move the failure limiter and `RunEventBus` out of process memory |
| **ADR text and code differ in three places**: [ADR-003](../decisions.md#adr-003-spring-data-jdbc-over-jpa) says Spring Data JDBC but the code is plain `JdbcClient`; [ADR-015](../decisions.md#adr-015-search-postgresql-full-text-search) says `websearch_to_tsquery` but the code builds a sanitized `to_tsquery`; [ADR-018](../decisions.md#adr-018-adapter-resilience-retries-rate-limits-circuit-breaker) says "token bucket" but the limiter is slot spacing | [`VacancyRepository`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L21-L35), [`VacancySearchService`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancySearchService.java#L41-L45), [`DomainRateLimiter`](../../backend/src/main/java/com/vacancytracker/collection/http/DomainRateLimiter.java#L21-L28) | Add a "Revision" paragraph to each ADR (as [ADR-013](../decisions.md#adr-013-deduplication-pipeline) already does) so the record matches the code |
| **The `c#` search matches the prefix `c`**; no stemming | [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior) "Still not changed", [ADR-015](../decisions.md#adr-015-search-postgresql-full-text-search) | Per-profile text-search configuration, and treat `c#`/`c++` as whole tokens |
| **Open review backlog (T-092)**: removing an ATS board expires its vacancies after three runs; `Retry-After` as an HTTP date is ignored; tokens containing cookie-hostile characters cannot sign in; company-list changes do not reach a run in progress; the start-up sweep can mark a run started in the first minute as interrupted; palette and unsaved-rules edge cases; no component tests for Inbox, drawer, board and palette. *The body-read deadline (F7) is already in code: [`HttpFetcher.BODY_DEADLINE`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L28-L29) with [`BodyDeadlineTest`](../../backend/src/test/java/com/vacancytracker/collection/http/BodyDeadlineTest.java), but the T-092 checkbox in [PLAN.md](../../PLAN.md) is still open and [code-review.md](../code-review.md) still lists F7 as open, so the documents lag the code.* | [code-review.md](../code-review.md), [PLAN.md](../../PLAN.md) line for T-092 | Close T-092 item by item and update both documents |
| **Jooble adapter is blocked** (no key and no API documentation yet) | T-036 in [PLAN.md](../../PLAN.md) | Wait for the provider; the adapter contract and masking for a key in a path already exist |
| **Currencies outside the ECB list are "missing data"**; rates refresh once per working day | [ADR-011](../decisions.md#adr-011-salary-normalization-and-exchange-rate-source) | A second rate source behind the same converter interface |
| **Manual accessibility checks are not done** (screen reader, real 200% zoom, low-vision users) | [ADR-052](../decisions.md#adr-052-beacon-design-light-first), [ADR-054](../decisions.md#adr-054-liquid-glass-over-graphite) | Run the manual checklist from the design hand-off before calling the design accepted |
| **The `cli` AI provider needs Claude Code installed and signed in**, so it does not work in the Docker image; the `api` provider costs per request | [ADR-041](../decisions.md#adr-041-claude-adapter-and-the-ai-opt-in) | Use `api` in containers; keep `cli` for running the jar on a desktop |
| **No metrics endpoint**: run metrics are log lines | [ADR-034](../decisions.md#adr-034-log-masking-and-structured-logs) | Micrometer counters and a Prometheus endpoint behind the token |
| **Coverage gate is lines only; `web`, `export`, `notification` are not gated** | [ADR-022](../decisions.md#adr-022-testing-strategy-and-coverage-gate), [`pom.xml`](../../backend/pom.xml) | Add branch coverage for the rule engine and gate `export` |
| **The mailbox reader is polled** (every 15 minutes), not push | [ADR-029](../decisions.md#adr-029-imap-reader-read-only-processed-state-in-the-database), [ADR-043](../decisions.md#adr-043-correspondence-job-mail-links-to-applications-status-suggestions) | IMAP IDLE, with a fallback to polling |
| **Backups share the host with the database** | [ADR-032](../decisions.md#adr-032-backup-service) | An off-host copy (object storage) of the dump |

**What is deliberately not here, and how to say it.** There is no Kafka, no Kubernetes, no cloud provider and no microservices. That is a *decision*, not a gap: the workload is one user's job search, so a single container with PostgreSQL is the right size ([ADR-001](../decisions.md#adr-001-modular-monolith)). If asked how it would evolve: the collection pipeline would become the first extracted service behind a queue (its stages are already pure functions around `fetch` and `upsert`, see [architecture.md](../architecture.md)), runs would take a distributed lock, and `RunEventBus` would move to a broker.

---

## 9. Question bank

Short answers; the link is where to point.

**Architecture**
1. *Why a modular monolith?* One user, one deployable, one database; boundaries are enforced by tests so extraction stays possible. [C1]
2. *How do you keep the modules from tangling?* ArchUnit in every build fails on a forbidden dependency, a cycle or an unknown package. [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java#L23-L66)
3. *What would you extract first?* `collection`: I/O-bound, failure-prone, and its stages are pure functions around fetch and upsert. [C5]
4. *How would you scale it out?* Distributed run lock, shared limiter state, a broker for progress events (section 8).

**Data and persistence**
5. *Why not JPA?* Explicit upserts, JSONB, `tsvector` and row locks are easier to reason about in SQL, with no lazy loading or session cache. [C2]
6. *How do you avoid lost updates?* `SELECT ... FOR UPDATE` on every read-modify-write, and automation limited to a field whitelist. [C2, C3]
7. *How is schema change managed?* Flyway only, no `ddl-auto`; migrations run in tests on Testcontainers; one Java migration back-fills a derived fact. [C19, C20]
8. *Why PostgreSQL full-text and not Elasticsearch?* Thousands of rows, no extra service; a GIN index on a generated column. [C11]

**Reliability**
9. *What happens when a source is down?* It is isolated, retried with backoff and jitter, trips a persisted circuit breaker after 5 failures, and never causes expiry. [C5, C6, C7]
10. *What if the app crashes mid-run?* Ingest is idempotent, e-mail and Telegram offsets are committed after storing, and a start-up sweeper closes orphaned runs. [C5, C13]
11. *How do you handle API quotas?* An atomic upsert budget in the database, calls without retries for quota sources, and spacing measured from the real last call. [C6]
12. *Why did you drop ETags for some sources?* "Not modified" for one URL cannot be told apart from "gone" for the others behind the same adapter; correctness over bandwidth. [C6]

**Algorithms and logic**
13. *How does the score work?* Weighted prefer/avoid criteria with a normalised integer formula, explicit missing-value policies, and a reason for every point. [C9]
14. *How is dedup done and what went wrong first?* Three ordered rules; the first fuzzy rule merged distinct openings, so it now needs compatible places and near-identical titles. [C8]
15. *Why a BFS over the status table?* The table is data; paths are derived from it so they cannot rot. [C4]

**Security**
16. *What is your threat model for a localhost app?* The browser: DNS rebinding and cross-site writes; so Host and Origin checks on every request. [C12]
17. *How do you protect the token?* Digest compare in constant time, lockout after 5 failures including for the correct token, 16-character minimum, `HttpOnly` `SameSite=Strict` cookie. [C12]
18. *SSRF on import by URL?* Scheme check plus blocking internal address ranges for the address and each redirect; the DNS-race window is documented. [C12]
19. *What stops secrets reaching the logs?* Masking at the output by shape and by exact configured value, and masking of stored or shown messages at the source. [C12]

**AI**
20. *How do you control what is sent to the model?* Off by default, consent per provider with the exact notice, a single service door that refuses before anything leaves, tagged data blocks with injection neutralisation. [C14]
21. *How do you prevent hallucinated facts?* Guardrail, then a deterministic diff of numbers, dates, employers, titles and technologies against the base documents; saving waits for the user. [C14]
22. *Why is the score computed in code?* So a model cannot inflate it: quotes are verified literally and the formula is deterministic. [C14]

**Quality and process**
23. *How do you test adapters without the network?* Fixtures served by a local JDK `HttpServer`. [C19]
24. *What does your CI enforce?* Plan/CSV sync, Maven verify (tests, Checkstyle, coverage gate, architecture rules), frontend typecheck/tests/build/audit, and a Playwright smoke test against the packaged jar. [C19]
25. *What did independent review find?* DNS rebinding, lost updates between background jobs and the user, an auto-hidden manual vacancy, keyboard paths that changed data by accident, and a build that failed on current tools; all tracked in [code-review.md](../code-review.md). [C2, C12, C18, C19]

**Behavioural**
26. *What would you do differently?* Keep ADRs and code in lock-step from the start (three drift points), implement the cross-instance lock early if multi-instance were ever a goal, and run the manual accessibility checks before declaring the design done.
27. *What are you proudest of?* The "automation never overwrites the user" invariant, enforced by types and row locks, and a compliance-first source policy that shaped the architecture instead of being added at the end.

---

## 10. Where is X?

| Looking for | Go to |
|---|---|
| The status table | [`VacancyStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyStatus.java#L27-L42) |
| What automation may write | [`Vacancy.applyAutomated`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L115-L136), [`AutomatedFields`](../../backend/src/main/java/com/vacancytracker/vacancy/AutomatedFields.java) |
| The upsert SQL | [`VacancyRepository.save`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L35-L82) |
| Row-lock read | [`VacancyRepository.findByIdForUpdate`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L96-L102) |
| The adapter interface | [`SourceAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/SourceAdapter.java#L10-L19) |
| One adapter end to end | [`GreenhouseAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/greenhouse/GreenhouseAdapter.java#L25) with [`GreenhouseContractTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/greenhouse/GreenhouseContractTest.java) and [`greenhouse-acme.json`](../../backend/src/test/resources/fixtures/greenhouse-acme.json) |
| The pipeline | [`CollectionPipeline`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L74-L130) |
| Dedup | [`Deduplicator`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L34-L103) |
| Expiry | [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L31-L70) |
| Retry/backoff | [`RetryPolicy`](../../backend/src/main/java/com/vacancytracker/collection/http/RetryPolicy.java#L30-L35), [`HttpFetcher`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L113-L162) |
| Circuit breaker | [`SourceHealth`](../../backend/src/main/java/com/vacancytracker/collection/health/SourceHealth.java#L18-L72) |
| Call budget | [`CallBudget`](../../backend/src/main/java/com/vacancytracker/collection/http/CallBudget.java#L20-L58) |
| SSE progress | [`RunEventBus`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunEventBus.java#L43-L84), [`RunController.events`](../../backend/src/main/java/com/vacancytracker/web/RunController.java#L67-L68) |
| Rule engine and scoring | [`RuleEngine`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L54-L121), [`Scorer`](../../backend/src/main/java/com/vacancytracker/matching/Scorer.java#L38-L89) |
| Criterion to SQL | [`CriterionCompiler`](../../backend/src/main/java/com/vacancytracker/matching/CriterionCompiler.java#L53-L83) |
| Inbox auto-hide rule | [`MatchService.applyInboxRule`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L128-L164) |
| Full-text search | [`VacancySearchService`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancySearchService.java#L28-L56), [`SearchQuery`](../../backend/src/main/java/com/vacancytracker/vacancy/SearchQuery.java#L27) |
| Host / Origin / token security | [`HostFilter`](../../backend/src/main/java/com/vacancytracker/web/HostFilter.java#L25-L70), [`OriginFilter`](../../backend/src/main/java/com/vacancytracker/web/OriginFilter.java#L22-L50), [`AuthFilter`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthFilter.java#L33-L60) |
| SSRF guard | [`UrlFetcher`](../../backend/src/main/java/com/vacancytracker/collection/UrlFetcher.java#L97-L125) |
| Log masking | [`LogMasking`](../../backend/src/main/java/com/vacancytracker/common/LogMasking.java#L15-L60) |
| IMAP reader | [`ImapMailReader`](../../backend/src/main/java/com/vacancytracker/collection/mail/ImapMailReader.java#L132-L230) |
| Mail classification and linking | [`MailClassifier`](../../backend/src/main/java/com/vacancytracker/application/mail/MailClassifier.java#L60-L80), [`MailLinker`](../../backend/src/main/java/com/vacancytracker/application/mail/MailLinker.java#L45-L88) |
| AI consent and call gate | [`AiService`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L22-L90) |
| Sandboxed Claude CLI | [`ClaudeCliClient`](../../backend/src/main/java/com/vacancytracker/application/ai/ClaudeCliClient.java#L58-L112) |
| Prompt-injection hygiene | [`Prompts`](../../backend/src/main/java/com/vacancytracker/application/documents/Prompts.java#L94-L100) |
| Quote verification and match score | [`QuoteCheck`](../../backend/src/main/java/com/vacancytracker/application/analysis/QuoteCheck.java#L30-L78), [`EmployerMatch`](../../backend/src/main/java/com/vacancytracker/application/analysis/EmployerMatch.java#L20-L34) |
| Fact check | [`FactCheck`](../../backend/src/main/java/com/vacancytracker/application/documents/FactCheck.java#L19-L50) |
| Resume PDF | [`ResumePdf`](../../backend/src/main/java/com/vacancytracker/application/documents/ResumePdf.java#L47-L84) |
| Resume fit score | [`ResumeFitScorer`](../../backend/src/main/java/com/vacancytracker/application/fit/ResumeFitScorer.java#L31-L93) |
| Backups and exports | [`BackupWriter`](../../backend/src/main/java/com/vacancytracker/export/BackupWriter.java#L37-L55), [`XlsxExporter`](../../backend/src/main/java/com/vacancytracker/export/XlsxExporter.java), [`backup.sh`](../../ops/backup/backup.sh) |
| Keyboard triage | [`triage.ts`](../../frontend/src/domain/triage.ts#L1-L71) |
| Contrast tests | [`contrast.test.ts`](../../frontend/src/styles/contrast.test.ts) |
| Architecture rules | [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java#L23-L66) |
| Integration test base | [`AbstractIntegrationTest`](../../backend/src/test/java/com/vacancytracker/support/AbstractIntegrationTest.java#L10-L34) |
| Schema | [`db/migration`](../../backend/src/main/resources/db/migration), ERD in [architecture.md](../architecture.md) |
| Decisions, plan, review | [decisions.md](../decisions.md), [PLAN.md](../../PLAN.md), [code-review.md](../code-review.md), [sources.md](../sources.md) |

Related documents: [architecture.md](../architecture.md) (diagrams), [search-profiles.md](../search-profiles.md) (the profile and criterion schema and the score formula), [sources.md](../sources.md) (terms, limits and status of each source), [deployment.md](../deployment.md), [operations.md](../operations.md), [gap-analysis.md](../gap-analysis.md), [specs/M7-ai-presets.md](../specs/M7-ai-presets.md).

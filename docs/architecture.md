# Architecture

Status: Phase 0 (M0). Decisions are recorded in [decisions.md](decisions.md). Profile and criterion details are in [search-profiles.md](search-profiles.md).

## 1. C4: System context

```mermaid
C4Context
    title System context: Vacancy Tracker

    Person(user, "Job seeker (single user)", "Triages vacancies, manages the pipeline, configures search profiles")

    System(tracker, "Vacancy Tracker", "Self-hosted job search tool: collects, scores, tracks and exports vacancies")

    System_Ext(ats, "ATS job boards", "Greenhouse, Lever, Ashby public APIs")
    System_Ext(boards, "Job APIs and feeds", "Arbeitnow, Adzuna, Jooble, Remotive, Himalayas, Jobicy, RSS / Atom feeds")
    System_Ext(mail, "User mailbox (IMAP)", "Job alert emails from LinkedIn, Jobs.cz, StartupJobs.cz, Indeed and others")
    System_Ext(ecb, "ECB rate feed", "Daily reference exchange rates")
    System_Ext(telegram, "Telegram Bot API", "Optional digest notifications (disabled by default)")

    Rel(user, tracker, "Uses", "HTTPS (browser)")
    Rel(tracker, ats, "Fetches postings", "HTTPS, public, no key")
    Rel(tracker, boards, "Fetches postings", "HTTPS, API keys where required")
    Rel(tracker, mail, "Reads alert emails", "IMAP over TLS")
    Rel(tracker, ecb, "Fetches rates", "HTTPS")
    Rel(tracker, telegram, "Sends digest", "HTTPS")
```

Note: direct scraping of job sites is not part of the context by design (ADR-007).

## 2. C4: Container

```mermaid
C4Container
    title Containers: Vacancy Tracker

    Person(user, "User", "Browser on desktop or phone")

    Container_Boundary(app, "vacancy-tracker (docker compose)") {
        Container(web, "Web UI", "Vite, TypeScript, Preact", "Static assets served by the backend: Dashboard, Inbox, Pipeline, Sources, Profiles, Export")
        Container(api, "Backend", "Java 21, Spring Boot 3.x", "REST API, SSE progress, scheduler, collection pipeline, rule engine, export, notifications, Actuator")
        ContainerDb(db, "PostgreSQL 16", "Flyway migrations, FTS, JSONB", "Vacancies, profiles, criteria, runs, source state, rates")
    }

    System_Ext(sources, "External sources", "APIs, feeds, IMAP, ECB, Telegram")

    Rel(user, web, "Uses", "HTTPS")
    Rel(web, api, "REST and SSE", "JSON / text/event-stream")
    Rel(api, db, "Reads and writes", "JDBC")
    Rel(api, sources, "Fetches and notifies", "HTTPS / IMAP")
```

Deployment units: `app` (backend with bundled static UI) and `postgres` (with a named volume). A `backup` job runs `pg_dump` daily into a volume.

## 3. Modular monolith packages

| Package | Responsibility | Depends on |
|---|---|---|
| `vacancy` | Vacancy model, statuses, StatusEvent, upsert rules, search, expiry | `common` |
| `collection` | Adapters, HTTP client, rate limits, circuit breaker, pipeline stages, scheduler, run lock, SSE progress | `vacancy`, `matching` (interfaces) |
| `matching` | SearchProfile, Criterion, rule engine, score, VacancyMatch, recalculation, currency rates | `vacancy` |
| `export` | CSV, XLSX, JSON backup and import with validation | `vacancy`, `matching` |
| `notification` | Telegram digest (disabled by default) | `vacancy`, `matching` |
| `web` | REST controllers, SSE endpoints, static UI serving, auth filter | all of the above |
| `common` | Time, IDs, error model, logging and masking helpers | none |

Rule: no package depends on `web`. Enforced by an ArchUnit test (M5 task).

## 4. Collection pipeline

```mermaid
flowchart LR
    A[Schedule or Refresh button] --> L{Run lock free?}
    L -- no --> R[Return active run id]
    L -- yes --> P[Build request params from enabled profiles]
    P --> F[Fetch per adapter<br/>rate limit, ETag, retries, circuit breaker]
    F -->|failure| FS[Record RunSourceStat error<br/>continue other sources]
    F --> N[Normalize<br/>HTML cleanup, salary, currency, period<br/>workMode, country, seniority]
    N --> C[Canonicalize URL<br/>strip utm and tracking params]
    C --> D{Dedup<br/>1 canonical URL<br/>2 source externalId<br/>3 fuzzy company+title+location}
    D -->|duplicate| M[Add VacancySource<br/>merge into existing vacancy]
    D -->|new| I[Insert vacancy]
    M --> U[Upsert rules<br/>never touch user fields]
    I --> U
    U --> Ma[Match for each active profile<br/>require / exclude filter<br/>prefer / avoid score<br/>matchReasons]
    Ma --> S[Store VacancyMatch per profile<br/>matchScore = best]
    S --> E[Expiry detection<br/>N unseen successful runs<br/>status New or Saved]
    E --> Done[Finish run, publish summary, SSE done event]
```

Stage contracts:
- Each stage takes and returns a `CandidateVacancy` value object; stages are pure functions except fetch and upsert.
- Each stage is unit-testable with fixtures.
- Failures inside fetch are isolated per source; failures in later stages for one vacancy are recorded and do not stop the run.

## 5. Scheduler and run lock

- `@Scheduled` with a cron expression (`app.collection.cron`). Per-profile schedule overrides are supported by merging into the global schedule: the run for a profile includes only the sources it enables.
- Lock: PostgreSQL advisory lock plus an in-process flag. A trigger during an active run returns the active run.
- Graceful shutdown: no new runs; the active run finishes its current source and is marked `interrupted` if needed.

## 6. ERD

```mermaid
erDiagram
    VACANCY ||--o{ VACANCY_SOURCE : "seen in"
    VACANCY ||--o{ VACANCY_MATCH : "scored by"
    VACANCY ||--o{ STATUS_EVENT : "history"
    SEARCH_PROFILE ||--o{ CRITERION : "has"
    SEARCH_PROFILE ||--o{ VACANCY_MATCH : "produces"
    SEARCH_PROFILE ||--o{ SEARCH_PROFILE_VERSION : "versions"
    SEARCH_PROFILE ||--o{ COMPANY_LIST : "may own"
    RUN ||--o{ RUN_SOURCE_STAT : "contains"
    SOURCE_STATE ||--o{ RUN_SOURCE_STAT : "measured by"
    SAVED_VIEW }o--o{ CRITERION : "criteria as JSON"
    EXCHANGE_RATE }o--|| SEARCH_PROFILE : "used for currency of"

    VACANCY {
        uuid id PK
        text title
        text company
        text url
        text canonical_url
        text location
        text country
        text work_mode
        text employment_type
        text seniority
        numeric salary_min
        numeric salary_max
        text currency
        text salary_period
        text_array tags
        text work_language
        boolean visa_sponsorship
        boolean relocation_support
        text description
        text status
        smallint priority
        numeric match_score
        boolean is_new
        timestamptz posted_at
        timestamptz first_seen_at
        timestamptz last_seen_at
        timestamptz applied_at
        timestamptz next_action_at
        text next_action_note
        text contact
        text notes
        text_array user_edited_fields
        tsvector search_vector
        timestamptz created_at
        timestamptz updated_at
    }
    VACANCY_SOURCE {
        uuid id PK
        uuid vacancy_id FK
        text adapter
        text external_id
        text url
        timestamptz first_seen_at
        timestamptz last_seen_at
    }
    VACANCY_MATCH {
        uuid vacancy_id PK
        uuid profile_id PK
        int profile_version
        int score
        jsonb match_reasons
        timestamptz matched_at
    }
    STATUS_EVENT {
        uuid id PK
        uuid vacancy_id FK
        text from_status
        text to_status
        timestamptz at
        text note
    }
    SEARCH_PROFILE {
        uuid id PK
        text name
        boolean enabled
        text_array sources
        jsonb thresholds
        jsonb schedule_override
        int version
        timestamptz updated_at
    }
    SEARCH_PROFILE_VERSION {
        uuid profile_id PK
        int version PK
        jsonb snapshot
        timestamptz saved_at
    }
    CRITERION {
        uuid id PK
        uuid profile_id FK
        text field
        text operator
        jsonb value
        text mode
        smallint weight
        text missing_value_policy
        text label
    }
    COMPANY_LIST {
        text company PK
        text list_type PK
        uuid profile_id PK "nullable = global"
    }
    RUN {
        uuid id PK
        text trigger
        timestamptz started_at
        timestamptz finished_at
        text status
    }
    RUN_SOURCE_STAT {
        uuid id PK
        uuid run_id FK
        text adapter
        int fetched
        int new_count
        int updated
        int duplicates
        int errors
        int duration_ms
        text error_message
    }
    SOURCE_STATE {
        text adapter PK
        boolean enabled
        text etag
        timestamptz last_success_at
        int consecutive_failures
    }
    SAVED_VIEW {
        uuid id PK
        text name
        jsonb criteria
        text sort
        text_array visible_columns
    }
    EXCHANGE_RATE {
        text base PK
        text quote PK
        date rate_date PK
        numeric rate
        text source
        timestamptz fetched_at
    }
```

Notes on the ERD:
- `VACANCY_MATCH` has a composite key (`vacancy_id`, `profile_id`); the row is replaced on recalculation, so there is no history of scores, only of profile versions.
- `COMPANY_LIST.profile_id` is nullable: null means the entry is global.
- `CRITERION` is stored per profile; criteria of `SAVED_VIEW` live in JSON (ADR-009).
- Primary key types are UUID (ADR-021).

## 7. Frontend structure

- Single-page app with hash-free routing and URL-stored filter state (`?status=New&score>=70`).
- Screens: Dashboard, Inbox, Pipeline (Table / Kanban), Vacancy drawer, Sources & Runs, Search profiles, Settings & Export.
- Live progress: `EventSource` on `/api/runs/{id}/events`.
- Design tokens: `design/tokens.css`; mockup: `design/dashboard.html`.

## 8. Security summary

- Local profile: no auth, bound to `127.0.0.1`.
- VPS profile: one access token from `.env`, HTTPS through a reverse proxy.
- Secrets only from `.env`; keys in URLs are masked in logs.
- No trackers, no third-party analytics, no data sent anywhere except to the configured sources and the optional Telegram bot.

# Changelog

All notable changes are recorded here. Format: date, task ID, short description. English only.

## [Unreleased]

### 2026-11-01 — M1 core tracker
- T-021: Filter chips and saved views in the Pipeline: criteria are in the URL (filter=<json>), each chip removes or clears, and the add form offers the fields and operators the backend accepts. Saved views: pick one to apply its filter, sort and columns; update it when the state changed; delete it; or save the current filter as a new view. Checked with a seeded view and a filtered URL (screenshot). Not yet: exclude mode in the UI, saving a new view while another view is selected, and browser-level click tests.
- T-022: Vacancy drawer: opens from a Pipeline row or title and is kept in the URL (?open=<id>). Shows facts, status changes (only transitions the server allows), editable description and notes, priority, status history and a link to the original. Edited fields are marked, and the backend test proves that a description edit is recorded in userEditedFields. Bottom sheet below 760 px, Escape closes it. Checked by API calls and screenshots at 1280 and 360 px; click and keyboard paths were not exercised in a browser.
- T-019: Pipeline table in the UI: sortable columns (the sort is in the URL), column picker (choice in the URL, at least one column kept), sticky header, paging of 100 rows, loading and error states with retry, empty state, and phone cards instead of the table below 760 px. Checked against 2,500 seeded vacancies: sorted page loads from the API in about 80 ms; screenshots at 1280 and 360 px. Smooth scrolling was not measured, and the frontend has no unit tests yet (typecheck and build only).
- T-018: Frontend bootstrap in `frontend/`: Vite 6, Preact 10, TypeScript (strict). App shell with hash routing, theme toggle (system, light, dark) with guarded storage access, and placeholder screens that name the task that builds them. The production bundle goes into the Spring static folder; the Docker build compiles the UI in its own stage. `frontend/src/styles/tokens.css` is a copy of `design/tokens.css`; keep them in sync. Checked at 360 px width with a headless browser: 16 px gutter, bottom navigation, no horizontal page scroll.
- T-020 (partial): Added country: stored, exported (CSV, XLSX, backup), importable and filterable. Only score is still missing from the T-020 acceptance list.
- T-021 (backend part): Saved views API: `/api/views` CRUD (unique name, criteria validated on save), and `GET /api/views/{id}/vacancies` to apply a view. UI (chips, URL state) not yet started.
- T-020 (partial): Criterion filters for views: `GET /api/vacancies?filter=<json>&sort=field:asc|desc` compiled to parameterized SQL. Supported fields: title, company, location, description, keywords, workMode, status, priority, salary, tags, source, firstSeenAt, lastSeenAt, nextActionAt. Modes require and exclude; missing values never satisfy a positive operator. Not yet: country and score (no column or score until M3), URL state (frontend).
- T-026: JSON backup: `GET /api/export/backup.json` (schemaVersion 1) with every vacancy and its status history. Import: `POST /api/import/vacancies` accepts the JSON backup or CSV with a header; rows are validated one by one, invalid rows are reported with row and field, and valid rows are stored. Unsupported version or unknown CSV column gives 422.
- T-025: XLSX export: `GET /api/export/vacancies.xlsx` with the same filter and columns as CSV. Apache POI streaming workbook (SXSSF), numeric salaries kept as numbers.
- T-024: CSV export: `GET /api/export/vacancies.csv` with optional status filter and column selection. UTF-8 with BOM, CRLF, RFC 4180 quoting, streamed in batches. Test isolation: each integration test starts with an empty database.
- T-023: Manual import by URL: `POST /api/vacancies/import-url` fetches the page (http/https only, custom User-Agent, timeouts, 2 MB cap) and reads schema.org JobPosting from JSON-LD (including @graph). 422 when no data, 502 when the source fails. Vacancies gain location and workMode.
- T-011: Maven bootstrap in `backend/`: Java 21, Spring Boot 3.5.6, package skeleton, Checkstyle lint (`config/checkstyle.xml`), JaCoCo, health smoke test.
- T-012: `backend/Dockerfile` (multi-stage, non-root), `docker-compose.yml` (app and PostgreSQL 16 with volume and healthcheck), `.env.example`. App port bound to 127.0.0.1.
- T-013: Flyway baseline `V1__baseline.sql`: vacancy, vacancy_source, status_event, saved_view; FTS generated column; status check. Datasource from environment. Testcontainers PostgreSQL 16 base test.
- T-014: `VacancyStatus` with the transition table (ADR-023), `Vacancy` aggregate, `StatusEvent`, `InvalidStatusTransitionException`.
- T-015: Vacancy REST API under `/api/vacancies`: list with status filter and paging, get, create, partial update, status change, history. Errors as RFC 9457 problem details: 404, 409 for invalid transitions, 422 with field errors.
- T-016: Upsert rules: `Vacancy.applyAutomated(AutomatedFields)` writes only lastSeenAt, and description and salary unless user-edited.
- T-017: `CanonicalUrl`: lowercases scheme and host, drops default ports, fragments, trailing slash, utm_* and tracking parameters.

### 2026-11-01 — Phase 0: plan and design (M0)
- T-001: `docs/research.md`: comparable solutions and what we borrow.
- T-002: `docs/sources.md`: source table, adapter capabilities matrix, forbidden sources.
- T-003: `docs/architecture.md`: C4 context and container, package layout, pipeline, ERD (Mermaid).
- T-004: `docs/decisions.md`: ADR-001 to ADR-023.
- T-005: `docs/search-profiles.md` and `docs/examples/search-profile.example.yml`.
- T-006: `PLAN.md` and `tasks.csv`.
- T-007: `design/tokens.css`: light and dark design tokens.
- T-008: `design/dashboard.html`: static clickable mockup with fake data.
- T-009: `design/README.md`: design rationale, states, keyboard shortcuts.
- T-010: `README.md`: vision, structure, run instructions placeholder.

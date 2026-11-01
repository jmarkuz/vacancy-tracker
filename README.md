# Vacancy Tracker

A personal, self-hosted job search tool for a single user. It collects vacancies from official APIs, public feeds and your own job alert emails on a schedule, removes duplicates, scores each vacancy against your search profiles with explained reasons, and tracks every application through a pipeline with a full status history. Data can be exported to CSV, XLSX or JSON.

The code contains no roles, technologies, countries or salary thresholds. Everything is defined by you as data in search profiles.

Status: built through the plan (PLAN.md), run on your own computer or on a VPS. See [PLAN.md](PLAN.md) for what is done and what is open.

## Features

- Automatic and on-demand collection ("Refresh" button with live per-source progress)
- Inbox with keyboard triage (J/K, S, X, O) and score badges with reasons
- Pipeline as table or Kanban, with status history and bulk actions with Undo
- Search profiles built from rules (require, prefer, avoid, exclude), live preview, versions
- Filters and saved views using the same rule model as search profiles
- Full-text search and Ctrl/Cmd+K command palette
- Export to CSV (UTF-8 with BOM), XLSX and full JSON backup; import with per-row error reports
- Optional Telegram digest (disabled by default)

## Quick start

```sh
cp .env.example .env        # set POSTGRES_PASSWORD
docker compose up -d --build
```

Open <http://127.0.0.1:8080>. For a server with HTTPS and an access token see [docs/deployment.md](docs/deployment.md); for backups and restore see [docs/operations.md](docs/operations.md).

## Sources and compliance

Only official APIs and feeds, plus job alert emails you receive, are used. Direct scraping of LinkedIn and other sites that prohibit it, CAPTCHA bypass, logging in with your account and proxy rotation are forbidden by design. See [docs/sources.md](docs/sources.md).

## Repository structure

```
README.md                      this file
PLAN.md                        development plan and progress (source of truth)
tasks.csv                      the same tasks for GitHub Projects or Jira
CHANGELOG.md                   entries per completed task
docs/
  research.md                  comparable solutions and what we borrow
  sources.md                   sources, terms, limits, adapter capabilities
  architecture.md              C4 context and container, pipeline, ERD (Mermaid)
  decisions.md                 architecture decision records (ADRs)
  search-profiles.md           profile and criterion schema, score formula
  deployment.md                run locally, run on a VPS with HTTPS
  operations.md                backups and restore
  examples/
    search-profile.example.yml neutral example, never loaded automatically
e2e/
  tests/smoke.spec.ts          Playwright smoke test (run: cd e2e && npm ci && npm run test:local)
ops/
  backup/                      backup script and its Docker test
design/
  tokens.css                   design tokens (light and dark)
  dashboard.html               static clickable mockup with fake data
  README.md                    design rationale, states, keyboard shortcuts
```

Also in the repository: `backend/` (Java 21, Spring Boot, Maven), `frontend/` (Vite, TypeScript, Preact; built into the backend jar), `docker-compose.yml` and `docker-compose.vps.yml` (HTTPS and the access token), `ops/caddy/` (the proxy settings), `.github/workflows/ci.yml` and `.env.example`.

## Tech stack

- Backend: Java 21, Spring Boot 3.5, Maven, PostgreSQL 16, Flyway, plain SQL through `JdbcClient`, Jsoup, Jakarta Mail (IMAP), Apache POI (XLSX), Actuator; Checkstyle, JaCoCo (80% line gate for the core modules) and ArchUnit in every build
- Frontend: Vite, TypeScript (strict), Preact, no UI library; Vitest for the logic; built into static assets served by the backend
- End-to-end: a Playwright smoke test in `e2e/`
- Deployment: Docker Compose (`app`, `postgres`, `backup`, and `caddy` on a VPS); secrets only in `.env`

## Tests

```sh
cd backend && mvn verify                   # unit and integration tests (needs Docker), style, coverage gate, architecture
cd frontend && npm ci && npm test          # logic tests; `npm run typecheck` for types
cd e2e && npm ci && npm run test:local     # browser smoke test; needs the built jar, Docker and Chrome
```

## Running it

See [docs/deployment.md](docs/deployment.md).

# Vacancy Tracker

A personal, self-hosted job search tool for a single user. It collects vacancies from official APIs, public feeds and your own job alert emails on a schedule, removes duplicates, scores each vacancy against your search profiles with explained reasons, and tracks every application through a pipeline with a full status history. Data can be exported to CSV, XLSX or JSON.

The code contains no roles, technologies, countries or salary thresholds. Everything is defined by you as data in search profiles.

Status: Phase 0 (plan and design). No production code yet. See [PLAN.md](PLAN.md).

## Features (planned)

- Automatic and on-demand collection ("Refresh" button with live per-source progress)
- Inbox with keyboard triage (J/K, S, X, O) and score badges with reasons
- Pipeline as table or Kanban, with status history and bulk actions with Undo
- Search profiles built from rules (require, prefer, avoid, exclude), live preview, versions
- Filters and saved views using the same rule model as search profiles
- Full-text search and Ctrl/Cmd+K command palette
- Export to CSV (UTF-8 with BOM), XLSX and full JSON backup; import with per-row error reports
- Optional Telegram digest (disabled by default)

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

Planned after Phase 0 (not yet created): `backend/` (Java 21, Spring Boot 3.x, Maven), `frontend/` (Vite, TypeScript, Preact), `docker-compose.yml`, `.env.example`.

## Tech stack (planned)

- Backend: Java 21, Spring Boot 3.x, Maven, PostgreSQL 16, Flyway, Spring Data JDBC, Jsoup, Rome, Jakarta Mail, Actuator
- Frontend: Vite, TypeScript, Preact; built into static assets served by the backend
- Deployment: `docker compose up` (app and PostgreSQL with a volume); secrets only in `.env`

## How to run

Not available yet. This section will be filled in by task T-075 once the application exists.

## Language

Repository content (code, comments, docs, commits, changelog) is in English. See [docs/decisions.md](docs/decisions.md) ADR-020.

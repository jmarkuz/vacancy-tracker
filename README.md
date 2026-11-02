# Vacancy Tracker

A personal, self-hosted job search tool for a single user. It collects vacancies from official APIs, public feeds and your own job alert emails on a schedule, removes duplicates, scores each vacancy against your search profiles with explained reasons, and tracks every application through a pipeline with a full status history. Data can be exported to CSV, XLSX or JSON.

The code contains no roles, technologies, countries or salary thresholds. Everything is defined by you as data in search profiles.

Status: every planned task is done except a Jooble adapter (T-036), which is blocked until Jooble provides a key and its API documentation. It runs on your own computer or on a VPS. See [PLAN.md](PLAN.md) for the full list and [CHANGELOG.md](CHANGELOG.md) for what changed when.

## How it works

1. **Collect.** Sources run on a schedule (or when you press Refresh). Each source is isolated: one failing source does not stop the others, and a run always ends with a summary.
2. **Normalize and deduplicate.** Titles, companies, places, salaries (with currency) and dates are brought to one shape. The same job from several sources is merged into one vacancy that remembers every source.
3. **Score.** Every enabled search profile scores each vacancy with the rules you wrote. The result shows the score and the reasons behind it. Rules that exclude a vacancy hide it from that profile.
4. **Triage and track.** The Inbox shows new matches for you to save or dismiss. Saved vacancies move through the pipeline (Saved, Applied, Screening, Test task, Interview, Offer, Accepted, Rejected, Withdrawn, and so on). Every change is kept in the history.
5. **Export.** Take your data out at any time as CSV, XLSX or a full JSON backup.

## Features

- Resume upload (PDF, DOCX, TXT, Markdown or pasted) and a fit score 0..100 for every vacancy with the reasons: matched and missing skills, title, years
- Resume and cover letter adapted to one vacancy: a draft from Claude (off by default, explicit opt-in; the official CLI under your subscription or an API key) or your own text, edited before it is saved as a numbered version, downloadable as DOCX
- Automatic and on-demand collection ("Refresh" button with live per-source progress)
- Inbox with keyboard triage (J/K, S, X, O) and score badges with reasons
- Pipeline as a table or a Kanban board, with status history, follow-up dates and bulk actions with Undo
- Dashboard: inbox and active counts, top matches, next actions, a funnel and applications per week
- Search profiles built from rules (require, prefer, avoid, exclude), live preview, versions and a step-by-step wizard for the first one
- Company allow and block lists, which a profile can use
- Filters and saved views using the same rule model as search profiles
- Full-text search and Ctrl/Cmd+K command palette
- Export to CSV (UTF-8 with BOM), XLSX and full JSON backup; import with per-row error reports (a CSV import merges into existing vacancies and keeps the fields you edited by hand)
- Optional Telegram digest after a run (disabled by default)
- Light and dark theme, keyboard-first screens

## Sources

Each source is an adapter that is switched on and configured in `.env` (see `.env.example`):

| Kind | Sources |
|---|---|
| Official public APIs | Greenhouse, Lever, Ashby (company job boards you configure), Arbeitnow, Remotive, Himalayas, Jobicy, Remote OK, Working Nomads, Adzuna (free key) |
| Feeds | Djinni (shipped), and any RSS or Atom feed you configure after you have checked its terms |
| Telegram | Posts you forward to your own bot; the tool never reads channels itself (Telegram's terms forbid it) |
| Your job alert emails | LinkedIn, Jobs.cz, StartupJobs, Indeed. The tool reads alert emails from a mailbox folder you choose over IMAP; it never logs in to the job sites |
| Generic HTML | Only sites you configure with the terms recorded and `robots.txt` checked (off by default) |

Each source's terms, limits and status are recorded in [docs/sources.md](docs/sources.md). Some sources are blocked until their terms have been read in a browser, and the tool does not bypass protection against automated access.

## Security

- The app runs on `127.0.0.1` by default. On a VPS it sits behind Caddy with HTTPS and an access token; after five wrong tokens from one address that address is blocked for ten minutes.
- Writes from another site are refused, so a web page open in your browser cannot start a run or import data. The app answers only to `localhost`, `127.0.0.1` and the host names in `APP_ALLOWED_HOSTS` (the VPS domain), so a hostile page cannot reach a local instance through DNS rebinding.
- Links from sources and imports are stored and opened only when they are `http` or `https` addresses.
- Secrets (database password, access token, source keys, the Anthropic API key, IMAP and Telegram credentials) come only from `.env`. They are masked in logs.
- Importing a profile or vacancies from a URL refuses private and local addresses unless you allow them.
- **AI is off by default.** With `APP_AI_PROVIDER` unset nothing can reach Claude. When it is set to `cli` (the official Claude Code CLI under your own subscription) or `api` (`ANTHROPIC_API_KEY`), still nothing is sent until you switch AI on in Settings after reading what is sent; you can switch it off at any time. Even then text leaves only when you press Generate: your resume, the vacancy you chose and your instructions. Mail and the rest of your data are never sent to Claude. The app never reads, copies or passes the CLI's OAuth token: the CLI signs in by itself, runs without tools, MCP servers or your own settings, and does not receive your API key.
- Mail: the IMAP reader only reads, never deletes, moves or sends. Of the folders it reads, only job-related mail is stored (sender, subject, date and the first part of the text); everything else is skipped and not kept.
- Nothing is sent to third parties except to the sources themselves. Telegram (digest and forwarded posts) and AI are opt-in.

See [docs/deployment.md](docs/deployment.md) for the details.

## Quick start

```sh
cp .env.example .env        # set POSTGRES_PASSWORD
docker compose up -d --build
```

Open <http://127.0.0.1:8080>. For a server with HTTPS and an access token see [docs/deployment.md](docs/deployment.md); for backups and restore see [docs/operations.md](docs/operations.md).

## Compliance

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

- Backend: Java 21, Spring Boot 3.5, Maven, PostgreSQL 16, Flyway, plain SQL through `JdbcClient`, Jsoup, Rome (RSS and Atom), Jakarta Mail (IMAP), Apache POI (XLSX), Actuator; Checkstyle, JaCoCo (80% line gate for the core modules) and ArchUnit in every build; Testcontainers for integration tests
- Modules (backend): `vacancy`, `collection`, `matching`, `export`, `notification`, `web`, `common`; their allowed dependencies are checked by ArchUnit
- Frontend: Vite, TypeScript (strict), Preact, no UI library; Vitest for the logic; built into static assets served by the backend
- End-to-end: a Playwright smoke test in `e2e/`, run against the built jar in a local Chrome
- Decisions: 38 recorded architecture decisions in [docs/decisions.md](docs/decisions.md)
- Deployment: Docker Compose (`app`, `postgres`, `backup`, and `caddy` on a VPS); secrets only in `.env`

## Tests

```sh
cd backend && mvn verify                   # unit and integration tests (needs Docker), style, coverage gate, architecture
cd frontend && npm ci && npm test          # logic tests; `npm run typecheck` for types
cd e2e && npm ci && npm run test:local     # browser smoke test; needs the built jar, Docker and Chrome
```

## Running it

See [docs/deployment.md](docs/deployment.md).

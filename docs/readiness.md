# Readiness analysis and improvement tracker (2026-11-04)

Purpose: one place to track the improvements proposed by the readiness analysis. Update the **Status** column as work lands (`todo` → `doing` → `done YYYY-MM-DD`). Finished items should also get a normal entry in PLAN.md / CHANGELOG.md when they become real tasks.

Method: four independent read-only reviews ran in parallel (backend, frontend, security/ops, product/docs). Findings already recorded in [code-review.md](code-review.md) are not repeated. Items marked *unverified* were derived from documents or greps and must be checked before work starts.

## Wave 1 result (2026-11-04)

Done in 6 parallel worktrees and merged into local `master` (6 merge commits, no conflicts; not pushed). Verified on the merged tree: backend `mvn verify` 844 tests, 0 failures; frontend `tsc` clean, 341 tests pass; `scripts/check-plan-sync.sh` passes; the CI job `plan-sync` was added to `ci.yml`.

Verified only statically (not run): GitHub workflows (YAML parse), Docker healthchecks, `cap_drop`, Caddy `NET_BIND_SERVICE` on 80/443 (the image builds, `compose config` and `caddy validate` pass). Check the browser console under the new CSP after the first deploy.

Still open from wave 1: OPS-08 SHA pinning of actions; the run watchdog in `RunCoordinator` and a deadline in `UrlFetcher` (rest of T-092 / F7); FE-12 inline notices (only logging was added); BE-10 (status validation was already in place, convention documented). Lane B edited `backend/config/checkstyle.xml` (IllegalCatch suppression). Lane C decides "local profile" by hostname (localhost, 127.0.0.1, ::1) when the auth check fails.

**Owner's `.env.example` edits are gone.** At the start the file had uncommitted changes (folder `Job Alerts`, max per run 100, max age 31); when the merges finished it matched the committed version (`Job alerts`, 50, 14) and the changes are in no stash or commit. No lane was asked to edit it, and the file was last modified at 22:27, so the cause is unknown. Re-apply them if they are still wanted.

## Scores

| Area | Score | Summary |
|---|---|---|
| Product and docs | 86/100 | 99 of 101 tasks done; only T-036 (Jooble, blocked on a key) and T-092 (review backlog) are open. Docs lag the newest tasks. First-run, restore and portability UX is thinner than the feature set. |
| Security and ops | 72/100 | Sound core design (hashed token, cookie flags, Host/Origin filters, log masking, non-root image). Weak supply-chain and operational hygiene: no dependency updates or scans, no image build/release in CI, no limits or healthchecks, backups only on the same host. |
| Backend | 78/100 | Solid single-owner design. Inconsistent error model, an SSRF check-then-use gap on URL import, a missing `lower(company)` index, minimal observability, thin tests on the error and scheduler layers. |
| Frontend | 82/100 | Clean typecheck, 327 passing tests, small bundle, 0 vulnerabilities, careful accessibility. No lint; large screens untested; a sticky Dashboard error and an auth-status fallback to the open UI. |

## Suggested execution order (parallel waves)

Tasks in a wave touch different files and can run in parallel agents/worktrees.

- **Wave 1 (quick wins, one agent per lane):**
  - Backend errors: BE-01, BE-02, BE-03, BE-15 (the handler tests), BE-09, BE-10
  - Backend data/jobs: BE-05, BE-06, BE-07, BE-08, PR-01 (F7)
  - Frontend resilience: FE-01, FE-02, FE-03, FE-06, FE-12
  - CI and repo hygiene: OPS-01, OPS-08, OPS-13, FE-15
  - Docker and Caddy: OPS-03, OPS-04, OPS-05, OPS-10, OPS-14
  - Docs: PR-06
- **Wave 2:** OPS-02, OPS-09, BE-04, PR-02, PR-04, PR-07, FE-05, FE-10.
- **Wave 3:** OPS-06, OPS-07, OPS-11, PR-03, PR-05, PR-09/FE-04 (component tests), BE-11, FE-09, FE-11.
- **Later / optional:** OPS-12, OPS-15, BE-12..BE-14, FE-07, FE-08, FE-13, FE-14, PR-08, PR-10..PR-15.

Conflict notes (items in one lane touch the same files; lanes do not overlap):
- `ApiExceptionHandler.java` and its test: BE-01, BE-02, BE-03, BE-15, BE-09 go together.
- Dockerfile, compose and Caddyfile: OPS-03/04/05/10/14 go together.
- `.github/workflows`: OPS-01, OPS-08, OPS-09 go together.
- Frontend onboarding and empty states: PR-02, PR-04, PR-07 go together.
- FE-04 and PR-09 are the same work (U14); FE-14 follows FE-04; FE-10 and FE-11 touch Pipeline/Board and should not run beside FE-14.
- PR-11/PR-12 and OPS-11 change auth/settings screens, so schedule them apart from FE-02.

## Security and ops (OPS)

| ID | Sev | Status | Where | Problem | Fix | Effort |
|---|---|---|---|---|---|---|
| OPS-01 | High | done 2026-11-04; merged into master | `.github/` | No dependabot, CodeQL, Trivy, `npm audit` or secret scan | Add dependabot (maven, npm, docker, actions, e2e), CodeQL (java, js), Trivy on image, `npm audit --omit=dev` | S |
| OPS-02 | High | todo | `backend/pom.xml:10` | Spring Boot 3.5.6 is over a year old; other libraries unchecked | Bump to latest 3.5.x; run `versions:display-dependency-updates` and dependency-check (poi, pdfbox, commons-text, jsoup, anthropic-java) | S |
| OPS-06 | High | todo | `ops/backup/backup.sh:37`, `docs/operations.md` | Backups only on the same host, unencrypted; `down -v` loses data and backups together | Optional off-site step (rclone/restic/s3) with age/gpg encryption; warn about `down -v` | M |
| OPS-03 | Med | done 2026-11-04; merged into master | `backend/Dockerfile:4` | UI built on EOL `node:18`; floating base image tags | Node 20/22 matching CI; pin images by minor tag or digest | S |
| OPS-04 | Med | done 2026-11-04; merged into master | `backend/Dockerfile:15-18` | No BuildKit cache, no `HEALTHCHECK`, no JVM container flags | Cache mount for `.m2`, healthcheck via wget, `-XX:MaxRAMPercentage=75` | S |
| OPS-05 | Med | done 2026-11-04; merged into master | `docker-compose*.yml` | No healthchecks, memory limits, log rotation or hardening | Healthcheck + `depends_on: service_healthy`, `mem_limit`, json-file rotation, `cap_drop`, `no-new-privileges` | S |
| OPS-07 | Med | todo | `ops/backup/` | No restore drill on the real stack, no backup-age alert, backup runs as root | `last-success` stamp + failing healthcheck, optional Telegram alert, `user: postgres`, scratch-DB restore drill in docs | M |
| OPS-08 | Med | partly: SHA pinning open; merged into master | `ci.yml` | No `timeout-minutes`/`concurrency`; actions not pinned to SHAs; reports not uploaded | Add them; dependabot keeps SHAs fresh | S |
| OPS-09 | Med | todo | `.github/workflows/` | Production image and compose never built in CI; no release workflow or GHCR image | `docker compose config` + `docker build` + Trivy job; tag-triggered release pushing a versioned image | M |
| OPS-10 | Med | done 2026-11-04; merged into master | `ops/caddy/Caddyfile:6-11` | Missing CSP, X-Frame-Options, Permissions-Policy, body limit; HSTS without includeSubDomains | Add headers (test CSP against the built UI), `request_body max_size 25MB` | S |
| OPS-11 | Med | todo | `AuthController.java:77,89`, `AuthFilter.java:56-60` | Cookie holds the raw access token for 30 days; logout cannot revoke it | Random server-side session id with expiry; shorter lifetime | M |
| OPS-12 | Low | todo | `docker-compose.yml:20-23` | `env_file` passes every secret to the app | Explicit `environment:` or `*_FILE` secrets; document that `local` has no auth | M |
| OPS-13 | Low | done 2026-11-04; merged into master | `.gitignore` | `.idea/`, `.claude/worktrees/`, `*.log`, `.env.*`, e2e reports not ignored | Add them; review `docs/PROMPT.md` before committing | S |
| OPS-14 | Low | done 2026-11-04; merged into master | `.dockerignore` | Whole repo is sent as build context | Add `e2e/ docs/ design/ .github .idea .claude` or whitelist | S |
| OPS-15 | Low | todo | `docs/deployment.md` | No hardening checklist (ufw vs Docker, unattended upgrades, SSH, uptime check, rollback) | Add checklist section | S |

Not verified by the review: the latest Spring Boot patch and CVE status (no network).

## Product and docs (PR)

Requirement coverage against `docs/PROMPT.md`: all core requirements are met (collection, dedup, pipeline, profiles, views, search, export/import, backups, dashboard, auth, tests, Phase 0 docs). Gaps: Jooble (T-036, blocked), onboarding covers only the search profile, the wizard-skipped flag lives in `localStorage`, seniority/employment-type detection (G12) is open although the PROMPT lists those fields. Unverified: Tier 3 HTML adapter, mobile layout, whether the JSON backup includes resumes, presets, documents and mail.

| ID | Pri | Status | Proposal | Value | Effort |
|---|---|---|---|---|---|
| PR-01 | High | partly: F7 body deadline done; merged into master; rest of T-092 todo | Split T-092 (about 10 items). Start with F7 (response body read deadline), the only item that can hang the run lock. Others: F8 board removal expiry, posted_at, F10 Retry-After dates, F11 token characters, F12 company lists during a run, F13 start-up sweep, U8 palette stale hit, U9 unsaved rules, U14 component tests | Removes the stuck-run risk | S (F7) |
| PR-02 | High | todo | "Getting started" checklist (profile, sources, resume, preset, AI opt-in, alerts/Telegram) on the Dashboard, state kept on the server | First-run clarity for M6/M7 features | M |
| PR-03 | High | todo | Verify the JSON backup covers profiles, views, company lists, resumes, presets, documents, packages and mail links. Add a round-trip test (export, wipe, import) and a "Restore from backup" button with a dry-run report | Real portability and recovery | M |
| PR-04 | Med | todo | Empty states with a next-step button for Sources, Mail, Presets, Resume, Profiles, Pipeline. Remove or hide `Placeholder.tsx` (wired in `App.tsx:195`) | Fewer dead ends | S |
| PR-05 | Med | todo | Settings > Integrations: status of Adzuna, IMAP, Telegram keys with "Test connection" (secrets stay in `.env`) | Fewer silent misconfigurations | M |
| PR-06 | Med | done 2026-11-04; merged into master, CI job `plan-sync` added | Sync docs: README status (T-100, T-101), `.env.example` defaults (folder `Job Alerts`, max per run 100, max age 31) vs `docs/deployment.md` and `docs/operations.md`, mark T-036 `blocked`, normalise the "Requests" milestone; CI check that PLAN.md and tasks.csv statuses match | Stops drift | S |
| PR-07 | Med | todo | Store the wizard-skipped flag on the server; "Run setup wizard again" in Settings | Consistent across browsers | S |
| PR-08 | Med | todo | Seniority and employment-type detection and badges (G12) | Meets the PROMPT normalize step | M |
| PR-09 | Med | todo | Component tests for Inbox, drawer, Kanban, palette (U14); mobile e2e (cards, bottom sheet) | Protects main flows | M |
| PR-10 | Low | todo | Unsaved-rules warning (U9) and stale palette hit (U8) | UX polish | S |
| PR-11 | Low | todo | Retention settings: purge Expired/Dismissed older than N days, DB size, backup retention in the UI | Long-term hygiene | M |
| PR-12 | Low | todo | "Data and privacy" page listing everything that leaves the machine, with on/off state | Trust | S |
| PR-13 | Low | todo | AI-suggested filters from the resume (G10), behind the AI opt-in | Differentiator | L |
| PR-14 | Low | todo | Jooble adapter against a WireMock fixture and a feature flag, ready for when a key exists (T-036) | Closes the last source gap | M |
| PR-15 | Low | todo | Help page and "?" shortcut cheat sheet from `design/README.md` | Discoverability | S |

## Backend (BE)

Ready for single-owner use (local, or VPS behind a token); not for multi-user or untrusted use. Strengths: parameterised SQL, `FOR UPDATE` on read-modify-write, bounded bodies and pages, Host/Origin filters, log masking, ArchUnit, JaCoCo 80% gate. Not compiled or run in this review (no `mvnw` in `backend/`; integration tests need Docker). BE-01..BE-06 are the ones worth fixing first.

| ID | Sev | Status | Where | Problem | Fix | Effort |
|---|---|---|---|---|---|---|
| BE-01 | Med | done 2026-11-04; merged into master | `web/ApiExceptionHandler.java:43-44` | Every `DuplicateKeyException` says "same canonical URL already exists", also for document version, preset name, company list, saved view | Branch on the constraint name; generic "already exists" otherwise | S |
| BE-02 | Med | done 2026-11-04; merged into master | `web/ApiExceptionHandler.java` | Inconsistent error shape: `ResponseStatusException`, bad path UUID, missing param, unsupported media type and unexpected exceptions fall to Boot's default body, not RFC 9457 | `spring.mvc.problemdetails.enabled=true` or extend `ResponseEntityExceptionHandler`; add a logged 500 handler | S |
| BE-03 | Med | done 2026-11-04; merged into master | `ApiExceptionHandler.java:92-95` | Blanket `IllegalArgumentException` → 422 with raw message; domain exceptions (`FactsNotConfirmed`, `ResumeTooLong`, `UnsupportedResume`, `CriterionException`) have no distinct fields; real bugs become 422 | Dedicated handlers with properties; IAE handler last and logged | M |
| BE-04 | Med | todo | `collection/UrlFetcher.java:49,159-164` | SSRF check-then-use gap: host resolved twice, DNS rebinding can reach internal addresses (cloud metadata on a VPS) | Resolve once and connect to the vetted IP; block the metadata range | M |
| BE-05 | Med | done 2026-11-04; merged into master | `matching/RecalculationService.java:95-102` | Per-vacancy catch is too narrow; an NPE aborts the pass and the job still ends `COMPLETED` with no error | Catch `RuntimeException`, log, count as failed; add FAILED state | S |
| BE-06 | Med | done 2026-11-04; merged into master | `vacancy/VacancyRepository.java:165-168`, `Deduplicator.java:55` | Fuzzy dedup filters `lower(company)` with no index: sequential scan per posting | Migration: index on `lower(company)` | S |
| BE-07 | Low | done 2026-11-04; merged into master | `CorrespondenceService.java:142-146`, `VacancyTrackerApplication.java:8` | Single-thread scheduler; the 15-minute IMAP sync can delay the 06:00/18:00 collection and rate job | `spring.task.scheduling.pool.size=3` | S |
| BE-08 | Low | done 2026-11-04; merged into master | `CorrespondenceService.java:119-131,150-160` | `syncing` flag handled inconsistently between manual and scheduled paths | Move the compare-and-set into `sync()` | S |
| BE-09 | Low | done 2026-11-04; merged into master | `web/ImportController.java:41-42` | Client `charset=` to `Charset.forName` → odd 422; whole body held in memory | `MediaType.getCharset()` with UTF-8 fallback, clear 415/400 | S |
| BE-10 | Low | partly stale: unknown status already 422; convention documented; merged into master | `VacancyController.java:59-69`, `SearchController.java:22-24`, `MailController:36` | Hand-written validation; unknown `status` silently returns an empty page; `limit` clamped in one endpoint, rejected in another | Validate `status` (422); one convention, documented | S |
| BE-11 | Low | todo | `application.yml:12-21` | Observability is health only: no metrics, no run correlation id, `show-details: never` | Metrics/prometheus behind the token, Micrometer counters, `runId` in MDC | M |
| BE-12 | Low | todo | `ai/ClaudeCliClient.java:75-80` | CLI stream readers use the shared ForkJoin common pool and can starve it | Dedicated executor or virtual threads | S |
| BE-13 | Low | todo | `PresetController:114`, `ResumeController:55`, `MailController:63`, `ImportController:38` | Upload limits duplicated as magic numbers; no multipart/Tomcat limits | Central config + Tomcat limits | S |
| BE-14 | Low | todo | `collection/pipeline/ExpiryService.java:31` | Hard-coded `WINDOWED_SOURCES`; a new windowed adapter would be expired wrongly | `windowed()` flag on `SourceAdapter` + test | M |
| BE-15 | Low | done 2026-11-04; merged into master | `src/test` | No direct tests for `ApiExceptionHandler`, `StaleRunSweeper`, `CollectionScheduler`, `RateScheduler`, `AiService`, `DocxWriter`, `ExportController`, `BackupWriter`, `SearchController` (some covered indirectly) | `@WebMvcTest` for the handler; small sweeper/scheduler tests | M |

## Frontend (FE)

Checks run: `tsc --noEmit` clean; 327 tests in 45 files pass; build OK (JS 171 kB, 52 kB gzip, CSS 10 kB gzip); `npm audit --omit=dev` 0 vulnerabilities (only runtime dependency is preact). No ESLint configured. All dev dependencies are 1-2 majors behind (not urgent). Accessibility is handled deliberately (skip link, focus traps, live toast, forced-colors, reduced motion, non-Latin key fallback).

| ID | Sev | Status | Where | Problem | Fix | Effort |
|---|---|---|---|---|---|---|
| FE-01 | Med | done 2026-11-04; merged into master | `screens/Dashboard.tsx:22,28-31,49-51` | `error` never reset: after one failed load the Dashboard shows "Could not load" for the whole session; no Retry | `setError(null)` on success; use `LoadFailed` with retry | S |
| FE-02 | Med | done 2026-11-04; merged into master | `App.tsx:75-79` | If `/api/auth/status` fails (network/5xx) the app falls back to `session="open"` and shows a broken UI on a token-protected VPS | Connection-error screen with Retry; fall back to open only for the local profile | S |
| FE-03 | Med | done 2026-11-04; merged into master | `router.ts:19-27`, `App.tsx:136` | No focus move or `document.title` on route change | Set title per screen, focus `#content`/`h1` (not on first load or query-only changes) | S |
| FE-04 | Med | todo | `screens/*`, `components/*`, `api/*` | Big screens untested (Inbox, VacancyDrawer, Profiles, Sources, Transfer, Settings, Resume, Presets, Palette, Board, FilterBar, ProfileEditor, Wizard, Login, AddVacancy); no coverage gate. Same gap as U14 | Component tests for Inbox triage, drawer save/error, Login; coverage floor in `vitest.config.ts` | L |
| FE-05 | Low | todo | `package.json`, CI | No ESLint (hooks, jsx-a11y) or formatter; effects with deliberate incomplete deps | Add eslint + typescript-eslint + hooks + jsx-a11y, `lint` script in CI; optional axe in e2e | M |
| FE-06 | Low | done 2026-11-04; merged into master | `components/Toast.tsx:25-31`, `Pipeline.tsx:345-346` | Undo timer restarts on every parent render (fresh `action` object); no pause on hover/focus | Depend on `action?.label`, keep `onClick` in a ref, pause on hover/focus | S |
| FE-07 | Low | todo | `Dashboard.tsx:68`, `domain/dashboard.ts:43`, `domain/preview.ts:3` | Locale forced to `en-US` in places, browser locale elsewhere | One shared formatter module | S |
| FE-08 | Low | todo | all screens | All UI text hard-coded English, no i18n layer | Record "English only" as an ADR, or move strings to `messages.ts` | L |
| FE-09 | Low | todo | `api/client.ts:49-62`, `api/auth.ts:12,30-33` | Raw `Failed to fetch` in toasts; `watchUnauthorized` monkey-patches `fetch` | Wrap fetch in `client.ts`, map network errors, detect 401 centrally | M |
| FE-10 | Low | todo | `Pipeline.tsx:280,289`, `Board.tsx:88` | `aria-selected` on `<tr>` without grid role; Board cards focusable but unlabelled | Drop `aria-selected`; card `role="group"` + `aria-label` | S |
| FE-11 | Low | todo | `ViewBar.tsx:92`, `Profiles.tsx:136`, `MailScreen.tsx:92,100`, `PresetsScreen.tsx:195,318`, `ResumeScreen.tsx:216`, `ApplyPanel.tsx:92` | 8 blocking `window.confirm` calls; profile editor drops unsaved rules without a prompt (U9) | One `ConfirmDialog` (focus trap, Esc, default Cancel) + unsaved-changes guard | M |
| FE-12 | Low | partly: logging only, no inline notice; merged into master | `Inbox.tsx:77-83`, `Profiles.tsx:63,74`, `Pipeline.tsx:125`, `App.tsx:~91` | At least 5 `.catch(() => undefined)` hide failures | Inline "could not load X" notice or logging | S |
| FE-13 | Low | todo | `styles/app.css:180,516,2786` (2786 was `graphite.css:1041`, folded into `app.css`) | `outline: none` on focus targets; line 1041 may hit an interactive input (*unverified*) | Check for a `:focus-visible` replacement; add if missing | S |
| FE-14 | Low | todo | `Pipeline.tsx` 463, `DocumentsPanel.tsx` 462, `VacancyDrawer.tsx` 413, `PresetsScreen.tsx` 407 lines | Largest components mix loading, state, keys, markup | Extract hooks and table body, after FE-04 | M |
| FE-15 | Low | done 2026-11-04; merged into master | `package.json` | No automated dependency updates | Dependabot/Renovate (overlaps OPS-01); plan vite 8 + vitest 5 together | S |

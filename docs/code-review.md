# Code review (2026-11-02)

Scope: the whole repository at commit `0b256f8` (master): README, PLAN.md, docs/, the backend (`backend/src/main`, migrations, configuration), the frontend (`frontend/src`) and the end-to-end test (`e2e/`). The backend and the frontend were each reviewed by an independent reader who traced every finding through the code; build problems were found by running the documented commands on a clean Windows 11 machine (JDK 21, Maven 3.9.16, Node 26, Docker Engine 29). Fixes are recorded in [decisions.md](decisions.md) ADR-039 and in [CHANGELOG.md](../CHANGELOG.md) (T-076).

Severity: **critical** blocks the build or exposes the user's data; **high** loses or changes data, or breaks the keyboard-first promise, in ordinary use; **medium** needs an unusual but realistic situation; **low** is an edge case or a missing test.

## Overall assessment

The code base is small, readable and defensive: parameterised SQL everywhere (no injection found in `CriterionCompiler`, `SqlFragment` or `VacancySort`), XXE closed in feeds and pages, `robots.txt` honoured, private addresses refused on import by URL, secrets masked in logs, no `dangerouslySetInnerHTML` in the UI, and the status table in the UI matches ADR-023 and `VacancyStatus` exactly. Module boundaries are enforced by ArchUnit and hold.

The weak spots are (1) the build did not run on current tools, (2) the local API could be reached by any web page through DNS rebinding, (3) background jobs wrote whole rows without a lock while the user edited them, and (4) several keyboard paths changed data the user did not mean to change. Tests are broad and run against a real PostgreSQL, but they cover features one at a time; races and feature interactions had no tests.

## Findings and their state

### Build and environment

| # | Severity | Finding | Where | State |
|---|---|---|---|---|
| B1 | critical | `mvn verify` fails on Docker Engine 29+: Testcontainers 1.21.3 (the Spring Boot default) speaks an API version Docker 29 refuses, so 52 integration tests error with "Could not find a valid Docker environment". CI on a current runner fails the same way. | `backend/pom.xml` | Fixed: `testcontainers.version` 1.21.4 |
| B2 | critical | The backend-only build fails without a UI build: `AuthApiTest.theSinglePageAppItselfIsServedWithoutAToken` expects `/index.html` to exist, which only `npm run build` creates. The CI `backend` job does not build the UI. | `AuthApiTest.java:186` | Fixed: the test checks that the auth filter does not refuse the page (not 401/403/429), which is what it is about |
| B3 | high | `npm test` fails on Node 22+ (5 theme tests): Node's own experimental `localStorage` global shadows jsdom's and is undefined without a backing file. | `frontend/vitest.config.ts` | Fixed: `--no-experimental-webstorage` for test workers on Node 22+ |

### Backend

| # | Severity | Finding | Where | State |
|---|---|---|---|---|
| F1 | critical | **DNS rebinding opens the local API.** `OriginFilter` only compares `Origin` with `Host`; after a rebind both are the attacker's name, and the local profile has no token. A page on `evil.example` that re-resolves to 127.0.0.1 can read `/api/export/backup.json` (all vacancies, notes, contacts) and start runs or imports (Firefox, Safari; Chrome's local-network prompt blocks it). | `web/OriginFilter.java:35,44-55` | Fixed: `HostFilter` refuses every request (whatever its path) whose Host is not loopback or in `APP_ALLOWED_HOSTS` (the VPS domain, set by `docker-compose.vps.yml`); `HostFilterTest` |
| F2 | high | **Background jobs overwrite a concurrent user change.** Ingest, the inbox rule and expiry load a row and write every column back, without a lock. A status set by the user between the read and the write is reverted (the vacancy is back in the Inbox while the history says Saved); notes can be lost the same way. | `IngestService.java:51,68`, `MatchService.java:103,148`, `ExpiryService.java:69-74`, `VacancyRepository.java:43-53` | Fixed: every read-modify-write of a vacancy (user edit, status change, bulk change, ingest, inbox rule, expiry) reads the row with `SELECT … FOR UPDATE`, so they serialise |
| F3 | high | A vacancy the user added by hand is hidden automatically once a source also lists it: "has no source row" was the only sign of a manual vacancy. | `MatchService.java:133-137`, `VacancyService.java:26-44` | Fixed: creating or importing by URL writes a first history entry "added by hand", which is not automatic, so the inbox rule never touches the vacancy; `InboxRuleTest` |
| F4 | medium | A salary corrected through CSV import is overwritten by the next run (only `description` was marked as edited). | `VacancyService.java:168-173` | Fixed: a changed salary column marks `salary` as edited; `ReviewFixesTest` |
| F5 | medium | Fuzzy de-duplication merges two openings of one board with the same title and place, although the source gives them different ids; one never appears and the other never expires. | `Deduplicator.java:47-66` | Fixed: a candidate already listed by the same adapter under another id is skipped; `DeduplicatorTest` |
| F6 | medium | Posting links are stored without checking the scheme and are used in `href` and `window.open`: a `javascript:` or `data:` link from a source, a manual entry or an import can reach the browser. | `Normalizer.java:47`, `VacancyController.java:126`, `VacancyImporter.java:143`; UI `VacancyDrawer.tsx:196`, `Inbox.tsx:106` | Fixed on both sides: only http(s) links are stored (`CanonicalUrl.webLink`; manual create answers 422, an import row is rejected) and the UI opens only http(s) links (`safeLink`) |
| F7 | medium | Reading a response body has no deadline: a source that sends headers and stalls keeps the run lock until a restart. | `HttpFetcher.java:110,197`, `RunCoordinator.java:55`, `UrlFetcher.java:57,93` | Open (backlog T-092): needs a deadline on the whole exchange and a run watchdog |
| F8 | low | Removing an ATS board (or emptying the list) expires its New and Saved vacancies after three runs. | `GreenhouseAdapter.java:58,70`, `ExpiryService.java:30-40` | Open (T-092) |
| F9 | low | `posted_at` is never stored, so `postedAt` filters never match and `maxAgeDays` has no effect. | `IngestService.java`, `VacancyRepository.java:37-42` | Fixed (2026-11-04): the date a source gives is stored (the earliest seen wins, a missing date never erases it), `postedAge` is a fact for rules, `maxAgeDays` filters by it, and the Inbox, the drawer and the Pipeline show it |
| F10 | low | `Retry-After` is capped at 60 s and a date value is ignored. | `RetryPolicy.java:32`, `HttpFetcher.java:139,241-247` | Open (T-092) |
| F11 | low | An access token with a space, comma, semicolon, quote or backslash starts the app, but browser sign-in always fails (the cookie refuses those characters). | `AccessToken.java:15`, `AuthController.java:77,89` | Open (T-092); documented: use letters and digits |
| F12 | low | A running collection does not pick up company-list changes. | `MatchService.java:79-82`, `CompanyListService.java` | Open (T-092) |
| F13 | low | The start-up sweep can mark a run started in the first minute as interrupted. | `StaleRunSweeper.java:32-34` | Open (T-092) |
| F14 | medium | A PATCH with only the next-action note clears its date (and the other way round), so the vacancy leaves the dashboard's next actions. | `VacancyService.java:108-110` | Fixed: each part is set on its own; `ReviewFixesTest` |
| F15 | low | No tests for the risky interactions above. | tests | Partly fixed: tests added for F1, F3, F4, F5, F6, F14 |

### Frontend and end-to-end test

| # | Severity | Finding | Where | State |
|---|---|---|---|---|
| U1 | high | **Status selects commit on every arrow key.** On Windows, ↑/↓ and type-ahead on a focused closed `<select>` change its value at once; each one recorded a status change (often one with no way back, such as Saved → Applied). Same for the priority select. | `VacancyDrawer.tsx:159-168,223-233`, `Board.tsx:96-102` | Fixed: choose, then press **Move** / **Set priority** |
| U2 | high | **A finished Refresh rebuilt the whole screen**, losing unsaved notes in the open drawer, the bulk Undo toast and the Inbox selection. | `App.tsx:136-142` | Fixed: the screens reload their data on `dataVersion` instead of being re-mounted |
| U3 | medium | **Fast keyboard triage acted on the wrong vacancy**: the selection was a position captured at key press and the key handler read stale state. | `Inbox.tsx:58-117` | Fixed: the selection is a vacancy id held in a ref; the handler is registered once |
| U4 | medium | Keyboard focus and the highlighted Inbox row drifted apart (Tab to row 4, X dismissed row 1). | `Inbox.tsx:85-103,159-186` | Fixed: focusing a row (or its buttons) selects it |
| U5 | medium | Shortcuts did nothing with a Ukrainian or Russian keyboard layout (they compared `e.key`). | `triage.ts:14-19`, `palette.ts:41-43` | Fixed: the physical key (`e.code`) is used when the typed letter is not Latin; tests added |
| U6 | medium | The e2e check that the drawer opened always passed (it also matched the sidebar). | `e2e/tests/smoke.spec.ts:119` | Fixed: the dialog is found by its name |
| U7 | low | The drawer said "Save failed" when the save succeeded and only the history reload failed. | `VacancyDrawer.tsx:97-111` | Fixed |
| U8 | low | The command palette could open a hit of the previous query and dropped the Pipeline's URL state. | `CommandPalette.tsx` | Open (T-092) |
| U9 | low | The profile editor discards unsaved rules without asking. | `ProfileEditor.tsx:144` | Open (T-092) |
| U10 | low | The selected Inbox row and palette option are invisible in Windows High Contrast mode. | `app.css:804-807,1299-1301` | Fixed in the redesign (T-090): outline and a `forced-colors` rule |
| U11 | low | A CI retry of the serial e2e suite always failed (the database was no longer empty). | `e2e/playwright.config.ts:12` | Fixed: no retries |
| U12 | low | `run-local.sh` could wait forever for PostgreSQL and could test an outdated jar. | `e2e/run-local.sh:17-23` | Fixed: bounded wait, checked `docker run`, jar older than the UI sources refused |
| U13 | low | The Export screen is a placeholder and "Add one by URL" has no UI behind it. | `App.tsx:34,145`, `Pipeline.tsx:161` | Fixed in the gap work (T-079) |
| U14 | low | No component tests for Inbox triage, the drawer, the board or the palette. | tests | Open (T-092) |

### Second opinion before the pull request (Codex, base master)

All four findings were fixed on 2026-11-02, each with a regression test:

| Priority | Finding | Fix | Test |
|---|---|---|---|
| P1 | `remoteok`, `workingnomads` and `telegram-forward` list only a window (or only what the owner forwards), but expiry treated them as full listings and could expire every vacancy they no longer show | Added to `ExpiryService.WINDOWED_SOURCES` | `ExpiryAndRunLockTest.theNewWindowedAndIncrementalSourcesNeverExpireAVacancy` |
| P2 | A package without a tailored resume read the *current* resume file at download time, so replacing the resume changed what an old application seemed to have been sent with | The package keeps its own copy of the resume as it was when prepared (V18) | `PackageApiTest.replacingTheResumeLaterDoesNotChangeAPreparedPackage` |
| P2 | With a pasted resume (no file) the package ZIP could not be downloaded | The copy of a pasted resume is its text: the ZIP holds `resume.docx` and `resume.txt` | `PackageApiTest.aPastedResumeGoesIntoThePackageAsTextAndWord` |
| P2 | Editing a vacancy's title or description left its fit computed from the old text | Each fit stores a hash of the text it scored (V18); a changed text makes it stale for the background pass and for reads | `ResumeApiTest.editingTheVacancyTextMakesItsFitStale`, `readingAFitRescoresAPostingWhoseTextChanged` |

### Keyboard shortcuts that must be kept

Inventory used as the regression list for the redesign (T-090): Ctrl/Cmd+K palette (also in text fields); Esc leaves a field first, then closes the drawer; Tab is trapped in the drawer and the palette; palette ↑/↓/Enter/Esc; Inbox J/K/S/X/O/Enter and double-click; board Alt+←/Alt+→, drag and drop and the Move list; rule builder Alt+↑/Alt+↓; Enter submits forms. The design README says plain ←/→ for the board, the code needs Alt: the code is kept and the README corrected.

### Second opinion on M7 (Codex, branch feature/m7-ai-presets against master, 2026-11-03)

All six findings (P2) were confirmed and fixed, each with a test:

| Finding | Fix | Test |
|---|---|---|
| Saving one part of the Presets screen reset the whole form and lost unsaved edits elsewhere | The form is rebuilt only when another preset is chosen; after a save only the saved fields come from the server | e2e `m7-presets.spec.ts` (fills, saves the preset, then the base resume) |
| A package from a pasted resume prepared before V22 has no stored PDF and failed to download | The PDF is made from the kept text at download time (when it fits two pages) | `PackageApiTest.aPastedResumeGoesIntoThePackageAsTextAndWord` |
| A fact confirmation survived an edit made before the debounced recheck, so new facts could be saved unconfirmed | Every edit clears the confirmation; saving waits until the check matches the text in the editor | e2e `m7-presets.spec.ts` (edit after confirming) |
| A quote over a line break inside Skills counted as proof | The quote check marks every character with its section, so a quote is proof only if it reaches outside Skills | `QuoteCheckTest.aMentionOnlyInSkillsIsNotProof` |
| A long link after ordinary text was not broken and ran off the page | Any word wider than the column is broken, wherever it stands | `ResumePdfTest.aLongLinkAfterOrdinaryTextIsBrokenInsideTheColumn` |
| The analysis cache ignored company and location, which the prompts send | The hash covers title, company, location and description | `AnalysisApiTest.aChangedLocationOrCompanyMakesTheGoalMatchStaleToo` |

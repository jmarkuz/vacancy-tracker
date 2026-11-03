# Design

Status: Beacon 1.0, light first (T-101, ADR-052); before that the dark-first redesign after DESIGN.md (T-090). Files: `tokens.css` (design tokens, the same file as `frontend/src/styles/tokens.css`), `dashboard.html` (static clickable mockup with fake data from Phase 0; it now picks up the new tokens but keeps its old layout). Open `dashboard.html` directly in a browser; it needs no build step. It uses the system font and loads nothing from the internet.

## 1. Rationale

**Style: Beacon ("Маяк") 1.0, light first (T-101, ADR-052).** The brand guide and implementation package of 2026-11-03 are in [vacancy-tracker-beacon-handoff-v1/beacon-handoff](vacancy-tracker-beacon-handoff-v1/beacon-handoff) (start with its `index.html`; values in `styles/tokens.json`; acceptance checklist in `docs/acceptance.html`). The goal is that context, the next action and the state of the data are clear at first sight, also for people with low vision. It replaces the Superhuman-inspired dark-first look of T-090 ([DESIGN.md](../DESIGN.md) stays as the record of that step).

- Neutral, calm surfaces: canvas `#F2F5FA`, surface `#FFFFFF`, text `#14243B`, secondary text `#3E4E64`, control borders `#71839A`; dark theme on navy `#101B2C` / `#17263A`. Text is at least 7:1 and borders 3:1 against what they sit on (checked by `frontend/src/styles/contrast.test.ts`).
- Four accent roles, each with one meaning everywhere: **blue** `#1243B2` for overview, inbox, search and mail; **green** `#13563C` for saving, the pipeline and applying ("Save" is always green, even in a blue section); **purple** `#5C2B98` for the resume, presets, AI and the skill match; **amber** `#704400` for sources, profiles, import and settings. Red `#9E2432` is only for errors and deleting. The logo is always blue.
- The system font Segoe UI (Arial as fallback); no font files are shipped or loaded from the internet. 18 px body (20 or 22 px in Settings), 16 px secondary text, headings 30 / 24 / 20 px, metrics 34 px bold with tabular figures.
- Controls at least 44 px high; buttons 8 px radius, panels 12 px; 1 px borders and a 4 px bar for the current item; a 3 px focus ring with a 3 px offset.
- Layout: header 64 px with the logo, search, refresh and theme; sidebar 200 px with a colored marker per section ("My profile" groups resume, presets, sources, profiles, import and settings); the Inbox details beside the list (340 px) with no overlay.

**Typography.** Segoe UI for everything; numbers use tabular figures (`font-variant-numeric: tabular-nums`) so columns align. Unknown values are written as "Not specified", never 0 or a dash. The skill match is "22 of 49 skills", never turned into a percentage.

**Color is never the only signal.** Every status is a badge with a colored dot and a text label (`Screening`, `Offer`). Source health is `ok`, `warn` or `error` in text. Scores show the number and the profile name. This also keeps the UI readable for color-blind users and in grayscale print.

**Themes and size.** Light by default (the `<html>` starts with `data-theme="light"`). The header button and Settings choose light, dark or system; "system" follows `prefers-color-scheme`. Settings also sets the base text size, 18, 20 or 22 px, on the root (`data-text-size`); every size is in rem, so the whole page follows, and browser zoom up to 200% works as well. Both choices are kept in this browser. Components reference tokens only, never raw hex values.

**Motion.** 120 to 180 ms ease-out transitions for hover, drawer open and progress bars. All transitions are removed under `prefers-reduced-motion: reduce` (handled in `tokens.css` and in the mockup CSS).

**Accessibility.** "Skip to content" is the first stop of Tab. Visible focus ring (3 px, 3 px offset, neutral) on every interactive element. Tabs follow the WAI-ARIA pattern (arrows, Home, End). Disabled buttons keep readable text on a neutral surface and their reason is written next to them. Score tooltips open on focus as well as on hover. Drag-and-drop in Kanban has a keyboard alternative. Tables use real `<table>` markup with headers.

## 2. Screens in the mockup

| Screen | What it shows | Key interactions |
|---|---|---|
| Dashboard | KPI cards (New today, Inbox, Active, Interviews this week, Overdue), Top matches, funnel Saved to Offer, applications per week (SVG), Next actions, Source health | KPI cards open a filtered Inbox or Pipeline |
| Inbox | Ranked New vacancies with score badge, profile, source; filter chips (All, Score 70+, Remote) | Keyboard triage J / K / S / X / O; click a row opens the drawer |
| Pipeline | Table (default) or Kanban; filter chips (Overdue, Has next action); bulk actions | Row opens the drawer; checkboxes enable bulk actions; Kanban drag-and-drop and arrow keys |
| Vacancy drawer | Status, score with reasons, facts, notes, next action, sources, status history | Status change recorded in history; Save notes; Open original |
| Sources & Runs | Per-adapter enable state, health, last success, failure count, recent runs | Refresh this source |
| Search profiles | Rule builder rows (label, field, value, operator, mode, weight, missing-value policy), enabled switch, live preview | Add, duplicate, reorder with arrows, recalculate, save as new version |

Global: header with search (Ctrl/Cmd+K command palette), "Refreshed ... · next ..." line, Refresh button with per-step progress and a summary toast with a link to the Inbox.

## 3. Responsive behavior

- **Working sizes 1440 × 900 and 1280 × 800:** header, sidebar, the Inbox list with the selected vacancy beside it; the full details open as the drawer on the right.
- **Below 1000 px:** the sections wrap above the content; the Inbox details come above the list; every action stays in the flow of the page; 16 px side gutter. No horizontal page scroll (only a table scrolls inside its own area), also with 22 px text (checked by `e2e/tests/beacon.spec.ts` at 640 px).

## 4. Empty, loading and error states

| State | Where | Design |
|---|---|---|
| Empty inbox | Inbox | Dashed bordered panel: "Inbox is empty", one line of guidance ("Refresh the sources or relax the filter") |
| Empty pipeline after filtering | Pipeline | Same pattern, with a "Clear filters" button |
| First run (no profile) | Whole app | Onboarding wizard (T-058): a single centered panel with three steps: name the profile, choose sources, set the first criteria. The example file is offered as a copy-only reference, never loaded automatically |
| Refresh in progress | Header | Button shows "Refreshing..."; the meta line shows the current source and step (for example "Refreshing: Greenhouse (1/5)"); button disabled while a run is active |
| Refresh finished | Toast | "+12 new, 3 updated, 1 source failed" with an "Open Inbox" link; auto-dismisses after 4.5 s |
| Source error | Sources & Runs, Dashboard | Badge with text "error" and a short reason ("Rate limited (HTTP 429), backing off"); other sources are unaffected |
| Source warning | Sources & Runs, Dashboard | Badge "warn" with the quota or the cause |
| Loading list | Inbox, Pipeline | Skeleton rows with the same height as real rows (not yet mocked; implementation uses the same row markup with `aria-busy="true"`) |
| Recalculating scores | Search profiles | Toast "Recalculating scores in the background. Statuses are not changed." Scores update when the job finishes |
| Save error | Search profiles | Inline field-level errors next to the row (for example "regex is not allowed for salary"); the save button stays enabled |
| Network or source failure during collection | Toast and Runs | Summary toast mentions the failed source; the run detail lists the error message |

## 5. Keyboard shortcuts

Global:

| Keys | Action |
|---|---|
| Ctrl+K / Cmd+K | Open command palette (search vacancies, jump to screens) |
| Esc | Close drawer, palette or dialog |

Inbox:

| Keys | Action |
|---|---|
| J / K | Next / previous vacancy |
| S | Save the selected vacancy (status Saved) |
| X | Dismiss (status Dismissed) |
| O | Open the original posting in a new tab |
| Enter | Open the vacancy drawer |

Kanban:

| Keys | Action |
|---|---|
| Alt+Left / Alt+Right on a focused card | Move the card to the previous / next column (the code has always needed Alt; this table said plain arrows) |
| Drag and drop | Move a card between columns |
| "Move to" list + Move button | Move by choosing a column, then pressing Move (choosing alone changes nothing) |

Rule builder:

| Keys | Action |
|---|---|
| Tab / Shift+Tab | Move between fields in a row |
| Alt+Up / Alt+Down from any control in a rule | Move the rule; focus stays on the same control |
| Up / down buttons (focusable) | Reorder a rule |

Vacancy drawer: the status and priority lists only choose; **Move** and **Set priority** apply. Esc leaves a text field first, then closes the drawer.

Shortcuts are not active while focus is in a text field, so typing never triggers an action. Letter shortcuts also work with a Ukrainian or Russian layout: the physical key counts when the typed letter is not Latin (J is J even when it types "о").

## 6. Tokens reference

`tokens.css` defines:
- Colors: surface and text scale, accent, semantic (success, warning, danger, info), status palette (13 statuses), score tiers. Each has light and dark values.
- Typography: Segoe UI, sizes in rem from an 18 px base (16 to 34 px), line heights, weights 400 / 600 / 700.
- Roles: `--role-blue`, `--role-green`, `--role-purple`, `--role-amber`, each with `-hover` and `-soft`, and `--color-on-role` for text on a filled role; `data-role` on an element chooses its role.
- Spacing: `--space-0` to `--space-12` on a 4 px grid.
- Radii: `--radius-sm` (8 px), `--radius-md` (12 px), `--radius-pill`.
- Layout: sidebar, header, drawer and content widths; mobile gutter.
- Motion: durations and easing; zero durations under reduced motion.

Dark theme is applied under `:root[data-theme="dark"]`, and under `@media (prefers-color-scheme: dark)` for `:root[data-theme="system"]`.

## 7. Mockup limits (intentional)

- Data is fake; companies are fictional; salaries are indicative.
- The live preview uses a simplified evaluator (text contains, in, comparison) to show the interaction. The real rule engine is specified in `docs/search-profiles.md`.
- Import and export buttons are disabled; saving, recalculation and refresh only show feedback.
- Onboarding wizard and bulk undo are shown as toasts; their full flows are implementation tasks (T-058, T-063).
- The Export screen is not built in the mockup (disabled navigation item).

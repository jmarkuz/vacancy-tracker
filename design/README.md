# Design

Status: M6 (dark-first redesign after design file, T-090). Files: `tokens.css` (design tokens, the same file as `frontend/src/styles/tokens.css`), `dashboard.html` (static clickable mockup with fake data from Phase 0; it now picks up the new tokens but keeps its old layout). Open `dashboard.html` directly in a browser; it needs no build step. The only external dependency is Google Fonts (Inter, JetBrains Mono).

## 1. Rationale

**Style: dark first (T-090, ADR-046).** The visual language comes from [design file](../design file) (an MIT-licensed analysis of third-party's site, copied unchanged with its source). third-party is a keyboard-first mail client; this tool is used the same way, for hours of triage, so its calm editorial look fits. design file describes marketing pages; the app takes its palette, type and shapes, not its page layouts (no portraits, no closing teal band):

- Deep indigo-navy surfaces (`#0e0c1f` to `#1f1c40`), warm off-grey text, hairline borders (`#3f3a52` on dark, `#e8e4dd` on light).
- One accent: pale violet `#c9b4fa` on dark (focus, primary actions, the current screen, pending decisions); indigo navy `#1b1938` on light. Accent is never used for status. The deep teal of design file is the success colour (Interview, Offer, AI switched on).
- Inter (variable) at the in-between weights design file asks for: 460 for text, 540 for headings and buttons, with slightly negative tracking on headings.
- Shapes: 4px tags, 6px inputs, 8px rounded-rectangle buttons (never pills), 12px cards, 16px dialogs and the drawer. Flat surfaces; only floating panels (drawer, palette, toast) lift.
- 4px spacing grid. Generous whitespace in the dashboard, tighter density in tables where users scan many rows.
- Generous whitespace in the dashboard, tighter density in tables (Pipeline, Sources) where users scan many rows.

**Typography.** Inter for text. Numbers (scores, counts, dates, salaries, run IDs) use JetBrains Mono or tabular numerals (`font-variant-numeric: tabular-nums`) so that columns align and values can be compared at a glance.

**Color is never the only signal.** Every status is a badge with a colored dot and a text label (`Screening`, `Offer`). Source health is `ok`, `warn` or `error` in text. Scores show the number and the profile name. This also keeps the UI readable for color-blind users and in grayscale print.

**Themes.** Dark by default (the `<html>` starts with `data-theme="dark"`, so there is no light flash). The toggle cycles dark, light and system; "system" follows `prefers-color-scheme`. Both themes are defined in `tokens.css` as token overrides; components reference tokens only, never raw hex values. Contrast targets WCAG AA for text and interactive elements in both themes.

**Motion.** 120 to 180 ms ease-out transitions for hover, drawer open and progress bars. All transitions are removed under `prefers-reduced-motion: reduce` (handled in `tokens.css` and in the mockup CSS).

**Accessibility.** Visible focus ring (2px accent, offset) on every interactive element. Score tooltips open on focus as well as on hover. Drag-and-drop in Kanban has a keyboard alternative. Tables use real `<table>` markup with headers.

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

- **Desktop (above 1100 px):** sidebar navigation, two-column dashboard panels, drawer on the right (480 px).
- **Tablet (760 to 1100 px):** dashboard panels stack; the rule builder rows shrink to two columns; the live preview is no longer sticky.
- **Phone (360 to 760 px):** sidebar hidden; bottom navigation bar; 16 px side gutter; KPI cards in two columns; the Pipeline table is replaced by cards; the drawer becomes a bottom sheet (max 88% of the viewport height); rule rows stack vertically. No horizontal page scroll (only the table container scrolls, and on phones the table is hidden).

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
- Typography: font families, sizes (12 to 28 px), line heights, weights.
- Spacing: `--space-0` to `--space-12` on a 4 px grid.
- Radii: `--radius-sm` (8 px), `--radius-md` (12 px), `--radius-pill`.
- Layout: sidebar, header, drawer and content widths; mobile gutter.
- Motion: durations and easing; zero durations under reduced motion.

Dark theme is applied in two places: under `@media (prefers-color-scheme: dark)` guarded by `:root:not([data-theme="light"])`, and under `:root[data-theme="dark"]`. The mockup theme toggle sets `data-theme`.

## 7. Mockup limits (intentional)

- Data is fake; companies are fictional; salaries are indicative.
- The live preview uses a simplified evaluator (text contains, in, comparison) to show the interaction. The real rule engine is specified in `docs/search-profiles.md`.
- Import and export buttons are disabled; saving, recalculation and refresh only show feedback.
- Onboarding wizard and bulk undo are shown as toasts; their full flows are implementation tasks (T-058, T-063).
- The Export screen is not built in the mockup (disabled navigation item).

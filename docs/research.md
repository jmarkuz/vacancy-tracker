# Research: comparable solutions

Status: Phase 0 (M0). Scope: what we borrow from existing tools and what we deliberately leave out.

## Comparable solutions

| Solution | Type | What it does well | What we borrow | What we do NOT borrow |
|---|---|---|---|---|
| OSApplyTrack | Open-source job application tracker | Background collection stages discovered jobs as leads | Inbox with status `New`: discovered jobs wait for triage before entering the pipeline | Its scraping approach (see JobSpy below) |
| JobSync | Self-hosted job search dashboard | Analytics and job-to-resume matching | A 0-100 match score with explained reasons. We start with a rule-based score; an LLM score is an optional later feature | LLM-first matching as a requirement |
| ShortList | Email-driven application tracking | Updates statuses from confirmation and rejection emails | Email status updates as a separate epic (M4) | Email as a core dependency for the MVP |
| Huntr / Teal | Commercial job trackers | Kanban pipeline; quick save from a job page | Kanban board in Pipeline; "add current page" bookmarklet | Account-based cloud lock-in; we are self-hosted |
| JobSpy | Python library for scraping job boards | Pulls LinkedIn / Glassdoor / Indeed listings | Nothing | Direct scraping of LinkedIn and Glassdoor: fragile and against site terms. Forbidden in this project |

## Gaps in existing tools that this project targets

1. **Universal, data-defined search profiles.** Most trackers hard-code a role or keyword set. Here every profile is data (criteria with `mode`, `weight`, `missingValuePolicy`) edited in the UI and exported as YAML/JSON.
2. **Explainable scoring.** Every score comes with `matchReasons[]` that reference a criterion ID, so the user can see why a vacancy ranked high or low.
3. **Scheduled, unattended collection with a single-run lock.** Collection runs without the user; the Refresh button shows live per-source progress over SSE.
4. **Source-respecting ingestion.** Only official APIs, public feeds, and email alerts the user subscribed to. No account logins, no CAPTCHA bypass, no proxy rotation.
5. **Full data ownership.** Self-hosted, single user, PostgreSQL, export to CSV / XLSX / JSON including profiles.
6. **Filters as the same model as profiles.** A saved view can become a search profile in one click and back.

## Design references

- Visual style: Linear, Vercel, Notion (neutral grays, single accent, thin borders, generous whitespace).
- Keyboard-first triage: inspired by mail clients (J/K navigation, single-key actions) and Linear's command palette (Ctrl/Cmd+K).

## Conclusions

- No existing tool combines universal rule-based profiles, explainable scores, and a source-compliant collection pipeline in a single self-hosted app. The gap justifies the project.
- Scraping-based collection is the main risk that existing tools accept and this project rejects. See [sources.md](sources.md) for the compliant alternatives.

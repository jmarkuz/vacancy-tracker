# Gap analysis: Vacancy Tracker and the compared platform (2026-11-02)

Compared product: [the compared platform](https://the compared platform/), an "AI-powered career platform that aggregates job listings, provides resume matching, and generates customized resumes" (its Terms of Service, section 2). Goal of the comparison: find what the tracker lacks for the full cycle *find → rate against the resume → adapt the resume and cover letter → prepare and submit → follow the status and the answers*, and close the gaps that fit the tracker's rules (personal, self-hosted, compliant sources only).

## How the site was read, and what was not read

- `robots.txt` (read 2026-11-02) allows `/`, `/about`, `/terms`, `/privacy-policy` and `/visa-sponsorship`, and disallows for every agent `/search`, `/jobs/`, `/job-detail/`, `/login`, `/register`, `/resume`, `/settings`, `/profile`, `/messages`, `/notifications`, `/pricing` and `/_framework/`.
- The Terms of Service (section 4, "Acceptable Use") forbid "scraping data" and "creating automated accounts".
- **The page the comparison was asked for, `/search`, is closed to automated access.** One request to it was made before `robots.txt` had been read; the answer was the empty shell of a Blazor application ("Loading… 0%", then "An unhandled error has occurred."), with no content. It was not rendered in a browser, not retried and not reached in any other way. No account was created and nobody signed in.
- What follows is therefore based on the pages that `robots.txt` allows (home, About, Terms, Visa Sponsorship, the sitemap) and on the structured data those pages publish (a schema.org `WebApplication` with a `featureList`). Disallowed paths are listed only as hints of features, never visited.

## What the compared platform offers (from the allowed pages)

| Feature | Evidence |
|---|---|
| Upload the resume once; matches with international jobs | Home: "Upload your resume once"; feature "AI Resume Matching" |
| Resume analysis and scoring | JSON-LD featureList: "Resume Analysis & Scoring" |
| A custom, ATS-optimised resume per job | Home: "Job-Specific Resume Generation"; featureList "Automated Resume Tailoring" |
| Cover letters | Terms section 5: the fair-use limit counts "customized resumes and cover letters combined" (150 per week) |
| Visa sponsorship filter and pages per role | Feature "Visa Sponsorship Opportunities"; featureList "Visa Sponsorship Filtering"; job cards with a "Visa Sponsor" badge |
| Smart search with AI filters tailored to the profile | Feature "Smart Search" |
| Application tracking (applied, saved, shortlisted) | Feature "Application Tracking" |
| Smart career assistant | featureList "Smart Career Assistant" |
| Messages and notifications | Only the disallowed paths `/messages` and `/notifications` (not visited) |
| Job cards: title, company, place, work arrangement, employment type, seniority, posted date | `/visa-sponsorship` page |
| Scale: "1M+ Active Jobs", "100K+ Companies", "20+ Countries" | Home |
| Accounts, free start, paid plans, fair-use limit, 7-day refund | Home "Start Free"; Terms sections 3 and 5 |

## Gaps, by priority

Priority: **P1** blocks the full cycle the owner asked for; **P2** makes the cycle noticeably better; **P3** nice to have; **n/a** does not apply to a personal, compliant tool.

| # | Gap in Vacancy Tracker (before M6) | Priority | Closed by | State |
|---|---|---|---|---|
| G1 | No resume: nothing to rate vacancies against | P1 | T-080 resume upload (PDF, DOCX, TXT/MD, pasted text) with editable keywords | Done |
| G2 | No fit of a vacancy to the resume, with reasons | P1 | T-081 deterministic fit 0..100 with matched and missing skills and years, for every vacancy; shown in the Inbox, the Pipeline and the drawer | Done |
| G3 | No resume tailored to a vacancy | P1 | T-082, T-083: Claude through the official CLI or an API key, behind an explicit opt-in; draft, edit, save as a version | Done |
| G4 | No cover letter | P1 | T-083, same flow as G3 | Done |
| G5 | No application package; applying is not linked to the documents used | P1 | T-084, T-085: package of chosen versions, ZIP download, apply page opened, "I submitted" moves to Applied and keeps the link | Done |
| G6 | Answers of employers are not tracked | P1 | T-086, T-087: job mail from the user's IMAP folders, classified, linked to the application, with a suggested status | Done |
| G7 | Visa sponsorship is not known or filterable (the column existed, nothing filled it) | P2 | T-078: read from the posting text, filterable in views and profiles | Done |
| G8 | Export and import exist in the API but the screen was a placeholder; "add by URL" had no UI | P2 | T-079 | Done |
| G9 | Few sources for Ukraine and Europe | P2 | T-088, T-089: the owner's list checked source by source; compliant ones connected (Djinni RSS, Remote OK, Working Nomads, Telegram posts forwarded to the owner's own bot) | Done |
| G10 | Smart search tailored to the profile | P3 | Partly: profiles are rule-based and explained (ADR-008); the fit score ranks by the resume. AI-suggested filters are not built | Open (later) |
| G11 | Smart career assistant (chat) | P3 | Not built: the cost and the data sent to a third party are not justified for a single user; the AI adapter of T-082 could serve it later behind the same opt-in | Open (later) |
| G12 | Seniority and employment-type badges | P3 | Not built: seniority stays a user-defined title rule (ADR-036) | Open |
| G13 | One million jobs from an aggregated index | n/a | The tracker collects only from compliant sources the owner chooses (ADR-007); no index of its own | Not applicable |
| G14 | Accounts, plans, refunds, SEO pages per role | n/a | Single-user, self-hosted (ADR-017) | Not applicable |

## Where the tracker already does more

- Every score is explained and reproducible (rule engine, ADR-008); AI is optional, off by default and never needed for scoring.
- Sources are checked against their terms and `robots.txt` and recorded (docs/sources.md); nothing is scraped from sites that forbid it.
- The full status history, Kanban, bulk changes with Undo, saved views, full-text search, CSV/XLSX/JSON export and daily backups.
- Data stays on the user's machine or VPS; nothing leaves except requests to the sources, the optional Telegram digest and, only after the explicit opt-in, the texts the user asks Claude to adapt.

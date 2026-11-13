# Gap analysis: Vacancy Tracker and tools for the full job-search cycle (2026-11-02)

Compared product: one commercial career platform that gathers job listings, matches a resume to them and produces resumes adapted to a vacancy. Its name is left out on purpose: the comparison concerns a kind of tool, not a company. Goal of the comparison: find what the tracker lacks for the full cycle *find → rate against the resume → adapt the resume and cover letter → prepare and submit → follow the status and the answers*, and close the gaps that fit the tracker's rules (personal, self-hosted, compliant sources only).

## Independence and third-party names

Vacancy Tracker is an independent personal project. It is not affiliated with, endorsed by or derived from any product or site compared here, or their operators. The comparison is written in the author's own words. No text, code, design, image, logo or data of the compared site was copied or reused: the tracker's features come from the owner's requirements, and a feature idea (for example matching a resume to vacancies) is a general function, not any one site's expression. Names and trademarks mentioned anywhere in the project belong to their owners.

## Review of the compared site's terms (2026-11-13)

The owner supplied the text of the Terms of Service (last updated January 2025); this section records what they say, in our own words. Section 4 forbids users of the platform from scraping its data, creating accounts by automated means and using it unlawfully; this project used the platform in none of those ways: no account was created, no data was collected, and automated access was limited to the pages that `robots.txt` allows plus the single request described in the next section, which returned no content. Section 6 states that the content, features and functionality are owned by the operator and protected by copyright, trademark and similar laws. The terms contain no clause against comparison, benchmarking, reverse engineering or building a similar product, and they do not name the operator, its country or the governing law. Copyright protects the expression of a work, not a function or idea, and nothing of the site's expression was copied. This is a record of a reading, not legal advice; the terms can change (section 8), so they should be read again before the project is published or used commercially.

## How the site was read, and what was not read

- The compared site's `robots.txt` (read 2026-11-02) allows its home, about, terms, privacy and visa-sponsorship pages, and disallows for every agent its search, job-listing and job-detail pages, the sign-in, registration, resume, settings, profile, messages, notifications and pricing pages and its application framework path.
- The Terms of Service (section 4, acceptable use) prohibit scraping the site's data and creating accounts by automated means.
- **The page the comparison was asked for, the site's search page, is closed to automated access.** One request to it was made before `robots.txt` had been read; the answer was an empty page shell of a web application that had not loaded, with no content. It was not rendered in a browser, not retried and not reached in any other way. No account was created and nobody signed in.
- What follows is therefore based on the pages that `robots.txt` allows (home, about, terms, visa sponsorship, the sitemap) and on the structured data those pages publish (a schema.org `WebApplication` with a feature list). Disallowed paths are listed only as hints of features, never visited.

## What this kind of tool offers (from the allowed pages)

| Feature | Evidence |
|---|---|
| The resume is uploaded once and matched against jobs in several countries | Home page: the resume-matching feature |
| Resume analysis and scoring | Published structured data (schema.org feature list) |
| A resume adapted to each job and prepared for applicant tracking systems | Home page; the same feature list |
| Cover letters | Terms section 5: the weekly fair-use limit counts adapted resumes and cover letters together |
| Visa sponsorship filter and pages per role | A listed feature and a feature-list entry; job cards carry a sponsorship badge |
| Search with filters tailored to the profile by AI | Home page |
| Application tracking (applied, saved, shortlisted) | Home page |
| A career assistant | Published structured data (feature list) |
| Messages and notifications | Only the names of two disallowed paths (not visited) |
| Job cards: title, company, place, work arrangement, employment type, seniority, posted date | The visa-sponsorship page |
| The scale the site claims (jobs, companies and countries in the millions, hundreds of thousands and tens) | Home page |
| Accounts, a free start, paid plans, a fair-use limit and a refund period | Home page; Terms sections 3 and 5 |

## Gaps, by priority

Priority: **P1** blocks the full cycle the owner asked for; **P2** makes the cycle noticeably better; **P3** nice to have; **n/a** does not apply to a personal, compliant tool.

| # | Gap in Vacancy Tracker (before M6) | Priority | Closed by | State |
|---|---|---|---|---|
| G1 | No resume: nothing to rate vacancies against | P1 | T-080 resume upload (PDF, DOCX, TXT/MD, pasted text) with editable keywords | Done |
| G2 | No fit of a vacancy to the resume, with reasons | P1 | T-081 deterministic fit 0..100 with matched and missing skills and years, for every vacancy; shown in the Inbox, the Pipeline and the drawer | Done |
| G3 | No resume tailored to a vacancy | P1 | T-082, T-083: Claude through the official CLI or an API key, behind an explicit opt-in; draft, edit, save as a version | Done |
| G4 | No cover letter | P1 | T-083, same flow as G3 | Done |
| G5 | No application package; applying is not linked to the documents used | P1 | T-084, T-085: package of chosen versions, ZIP download, apply page opened, marking it submitted moves to Applied and keeps the link | Done |
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

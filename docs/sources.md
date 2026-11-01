# Sources: verified terms, limits and adapter capabilities

Status legend:
- `ok`: terms and limits confirmed against the source's own documentation.
- `check`: the adapter is planned, but at least one term or limit is only confirmed by secondary sources. Re-verify against the official page before the adapter is built (task T-027, then the matching adapter task).
- `blocked`: must not be built in the current scope.

Verification note (Phase 0): searches returned third-party summaries for most sources. The official pages were not fetched directly in this phase, so every row below is `check` unless stated otherwise. The first task of each adapter is to re-read the official terms and record the result here.

## Tier 1: official public APIs and feeds

| Source | Adapter | URL (official) | Auth | Limits (as reported) | Attribution / terms | Risks | Status |
|---|---|---|---|---|---|---|---|
| Greenhouse Job Board | `ats-greenhouse` | `https://boards-api.greenhouse.io/v1/boards/{token}/jobs` (public, no key) | None | No published hard limit; the API is cached; poll every few hours | Public data; link to the original posting | Endpoint is undocumented in search results; board token must be configured per company | check |
| Lever Postings | `ats-lever` | `https://api.lever.co/v0/postings/{site}?mode=json` (EU: `api.eu.lever.co`) | None | Unclear; the authenticated v1 API has separate limits; do not use v1 | Public postings; link to the original | No list endpoint for slugs; location is free text; no salary data | check |
| Ashby Job Board | `ats-ashby` | `https://api.ashbyhq.com/posting-api/job-board/{company}?includeCompensation=true` | None | Not published; poll every few hours | Public postings; link to the original | Company display name is not in the feed; no server-side search | check |
| Arbeitnow | `arbeitnow` | Arbeitnow Free Job Search API (documented on arbeitnow.com/blog/job-board-api) | None | Not found; use conservative polling (once per few hours) | Terms not found; confirm before enabling | Visa sponsorship filter exists; UK endpoint also exists | check |
| Adzuna | `adzuna` | `https://api.adzuna.com/v1/api/jobs/{country}/search/{page}` (docs: developer.adzuna.com) | `app_id` + `app_key` (query params), free registration | Conflicting reports: 25 calls/min and 250/day vs 1,000/month; verify in the portal | Terms not found; confirm the required attribution | Supported country list differs by source (12 to 19 codes); countries must come from profiles and be validated against the live list | check |
| Jooble | `jooble` | `POST https://jooble.org/api/{apiKey}` (docs: jooble.org/api/about) | API key emailed after registration | Usage limits are set per key; no public paid tiers | Terms not found; confirm | Key in URL path: never log full URLs; request is POST, not GET | check |
| Remotive | `remotive` | `https://remotive.com/api/remote-jobs` (docs: remotive.com/api-documentation) | None | At most 4 requests per day (recommended); blocks above 2 requests per minute (reported) | Attribution and a link back are required. Must not forward jobs to LinkedIn, Google Jobs or Jooble. Must not use listings to collect emails | Listings are delayed 24 h; violating the attribution rule breaks their terms | check |
| Himalayas | `himalayas` | `https://himalayas.app/jobs/api` and `/jobs/api/search` | None | `limit` capped at 20 per request (since 2025-03-24); 429 responses reported | Terms not found; confirm attribution | Pagination required for large sets; 429 handling is mandatory | check |
| Jobicy | `jobicy` | `https://jobicy.com/api/v2/remote-jobs` (docs: jobi.cy/apidocs) | None reported | `count` up to 100 per example; no official rate limit found | Terms not found; confirm free-use terms | Third-party paid wrappers exist; use only the official endpoint | check |
| We Work Remotely RSS | `rss` (configured feed) | `https://weworkremotely.com/remote-jobs.rss` | None | None published for the feed | Feed page: free use with attribution. API terms: scraping and storing data outside the API is prohibited, and it is unclear if this covers the RSS feed | Conflicting terms. Owner decision (2026-11-01): use only for personal use, with attribution and link back; no storage outside personal use | ok (personal use only) |
| Generic RSS / Atom | `rss` | User-configured URLs | None | Per-domain rate limit | Per feed: check each feed's terms | Feeds may contain HTML; sanitize | ok (framework); each feed `check` |

## Tier 2: email alerts via IMAP

| Source | Adapter | Mechanism | Auth | Risks | Status |
|---|---|---|---|---|---|
| LinkedIn job alerts | `email-linkedin` | User-created alerts; the tool reads the alert emails from a dedicated IMAP label | User's own mailbox (IMAP app password) | Parse only the email content; never log in to LinkedIn; never open LinkedIn pages | check |
| Jobs.cz, StartupJobs.cz, Indeed and others | `email-<sender>` | One parser per sender, tested on saved fixtures | User's own mailbox | Email templates change without notice; fixture tests catch this | check |

Notes for Tier 2:
- The user creates the alerts on each site. The tool does not log in to any job site.
- LinkedIn's User Agreement prohibits automated data collection (see below). Email alerts that the user receives and the tool parses from the user's own inbox are the compliant path.

## Tier 3: generic HTML (Jsoup + CSS selectors)

Only where `robots.txt` and the site's terms allow it. Each site needs a written terms check recorded here before its config is added. No sites are enabled by default.

## Forbidden

| Target | Reason | Status |
|---|---|---|
| LinkedIn direct scraping (jobs search pages) | User Agreement section on scraping and automated data collection; enforcement precedent (hiQ, Mantheos) | blocked |
| Glassdoor, Indeed direct scraping | Site terms prohibit automated access; fragile | blocked |
| Any CAPTCHA bypass or anti-bot evasion | Forbidden by project policy | blocked |
| Logging in to job sites with the user's account | Forbidden by project policy | blocked |
| Proxy rotation | Forbidden by project policy | blocked |

## LinkedIn: findings

- The User Agreement prohibits automated data collection, regardless of whether data is public (secondary source; exact clause to be confirmed on linkedin.com/legal before publication of the project).
- Enforcement history: hiQ Labs case (consent judgment, data destruction) and the Mantheos settlement.
- Decision: no LinkedIn scraping. See [decisions.md](decisions.md) ADR-007.

## Adapter capabilities matrix

Each adapter declares which criteria fields it can translate into request parameters on the server side. Everything else is applied after fetching in the pipeline.

| Adapter | Server-side: keywords | Server-side: country / location | Server-side: remote / workMode | Server-side: posted age | Pagination | Notes |
|---|---|---|---|---|---|---|
| `ats-greenhouse` | no | no | no | no | not needed (whole board) | Filter everything in pipeline |
| `ats-lever` | no | no | no | no | `limit` / `skip` | |
| `ats-ashby` | no | no | no | no | not needed | |
| `arbeitnow` | yes (`search`) | yes (`location`) | yes (`remote`) | no | `page` | Visa filter available |
| `adzuna` | yes (`what`) | yes (country path segment, validated) | no | yes (`max_days_old`) | `page` | Country mandatory per profile |
| `jooble` | yes (`keywords`) | yes (`location`) | no | no | `page` | POST body |
| `remotive` | yes (`search`) | no | remote only | no | none (single list) | Max 4 calls/day: cache |
| `himalayas` | yes (`q`) | yes (`country`) | yes (remote only) | no | `offset` / `limit` (max 20) | |
| `jobicy` | yes (`tag`) | yes (`geo`) | remote only | no | `count` | |
| `rss` | no | no | no | no | per feed | Filter everything in pipeline |
| `email-*` | no | no | no | email date | n/a | Parsed from mail |

Exact parameter names above are taken from search summaries and must be confirmed in each adapter's task.

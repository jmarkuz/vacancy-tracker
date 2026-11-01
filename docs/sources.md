# Sources: verified terms, limits and adapter capabilities

Status legend:
- `ok`: terms and limits confirmed against the source's official page (verified date below).
- `ok (constraints)`: usable, with attribution or other conditions the adapter must implement.
- `check`: the adapter is planned, but a term or limit is still unconfirmed. Resolve before the adapter task (T-028 onwards).
- `blocked`: must not be built in the current scope.

Verification (T-027, 2026-11-01): official pages were read through the web-fetch agent. Where a page could not be read, the row says so. Quotes are from the official pages unless marked as secondary.

## Tier 1: official public APIs and feeds

| Source | Adapter | Official page | Auth | Limits | Attribution and terms | Risks | Status |
|---|---|---|---|---|---|---|---|
| Greenhouse Job Board | `ats-greenhouse` | docs.greenhouse.io/job-board.html (old developers.greenhouse.io redirects here) | None: "Job Board data is publicly available, so authentication is not required for any GET endpoints." | None documented | No usage-terms section found on the page | List endpoint: only `content=true` documented. Board token must be configured per company | ok |
| Lever Postings | `ats-lever` | github.com/lever/postings-api; hire.lever.co/developer/postings needs sign-in | GET needs no key (implied by examples; POST needs an API key). EU host: api.eu.lever.co | Only POST application limit: 2 per second (429). GET limit not stated | No attribution rule in README. "These jobs may be scraped by third parties" (published postings only) | Official developer page not readable without sign-in; GET terms rest on the README | ok (constraints: published postings only) |
| Ashby Job Board | `ats-ashby` | developers.ashbyhq.com/docs/public-job-posting-api (updated 2026-10-23) | None shown in the official example | None stated | No terms stated. "If you host your own careers page, you can use this data to populate it." | Only `includeCompensation` documented; respect `isListed` (false = direct link only) | ok |
| Arbeitnow | `arbeitnow` | arbeitnow.com/blog/job-board-api; arbeitnow.com/terms (section 11) | None ("requires no API key") | None stated; Postman doc holds the parameter reference | Must link back to arbeitnow.com. API may be revoked at any time. Use License covers "non-commercial" use; API data reuse not defined | Commercial use unclear; `remote` is a response field, not a filter | ok (personal use, link back) |
| Adzuna | `adzuna` | developer.adzuna.com; terms at developer.adzuna.com/docs/terms_of_service | `app_id` + `app_key` query params, free key at signup | Not published: "plans ... and their limits, are shown in your account" | Permitted: "Personal job search". Any other use needs written agreement. Commercial or revenue use needs a licence. Caching and storage rules not explicit | Country list not on any readable page; check the account or interactive docs | ok (personal use only) |
| Jooble | `jooble` | jooble.org/api/about (request form); terms jooble.org/info/terms | API key after the request form; free status not stated | Not stated | Terms contain no API clauses. Site content "may not be copied for republication". Job content provided by third parties | Request method and body not on the public page. Access by request only. Compliance contact: compliance@jooble.com | check (blocked until docs and key obtained) |
| Remotive | `remotive` | remotive.com/api-documentation redirects to github.com/remotive-com/remote-jobs-api (README) | None; private paid API exists | Advised max 4 requests per day; more than 2 per minute is blocked. 429 not documented | Attribution and link back to the URL on Remotive plus naming Remotive. Listings delayed 24 hours. Must not submit jobs to Jooble, Neuvoo, Google Jobs, LinkedIn Jobs. Email collection through listings breaches terms | Access terminated if attribution is missing | ok (constraints) |
| Himalayas | `himalayas` | himalayas.app/api; terms himalayas.app/terms#api (not read) | Optional. Unkeyed requests share one public allowance; key gives 60/min and 10,000/day | `limit` max 20 on /jobs/api; use `cursor` (offset deprecated). Search endpoint `/jobs/api/search` with `page` | Show a visible link to the job on Himalayas or himalayas.app. Use applicationLink for Apply. Show jobs only on sites you operate. Do not pass to aggregators or LinkedIn/Google Jobs. Remove expired jobs. No browser calls (no CORS) | Use a key to avoid the shared allowance; 429 handling mandatory | ok (constraints) |
| Jobicy | `jobicy` | jobicy.com/jobs-rss-feed (jobi.cy/apidocs redirects here) | None required for the Jobs API (optional Bearer key only changes `url`, paid per direct-link job) | Feed and status: no more than once per hour. Last 7 days only; cursor valid 24 h; `count` 1 to 100 | Credit Jobicy and link to the source. Keep Apply links to Jobicy pages. Storage and redistribution allowed; keep listings synchronized and mark closed jobs | Jobs leaving the 7-day window are not necessarily closed | ok (constraints) |
| We Work Remotely RSS | `rss` (configured feed) | weworkremotely.com RSS and API terms pages both return HTTP 403 to the fetcher; not readable | None | Not readable | Unverified. Earlier secondary sources: feed free with attribution; API terms prohibit storing or scraping data outside the API. Owner decision (Phase 0): personal use only | Cannot confirm before enabling. Ask WWR or verify from a browser | check (personal use only) |
| Generic RSS / Atom | `rss` | User-configured URLs | None | Per-domain rate limit | Per feed: check each feed's terms before adding | Feeds may contain HTML; sanitize | ok (framework); each feed `check` |

## Tier 2: email alerts via IMAP

| Source | Adapter | Mechanism | Auth | Risks | Status |
|---|---|---|---|---|---|
| LinkedIn job alerts | `email-linkedin` | User-created alerts; the tool reads the alert emails from a dedicated IMAP label | User's own mailbox (IMAP app password) | Parse only the email content; never log in to LinkedIn; never open LinkedIn pages | Built (T-065) on a synthetic fixture; needs a check against a real alert email |
| Jobs.cz, StartupJobs.cz, Indeed and others | `email-<sender>` | One parser per sender, tested on saved fixtures | User's own mailbox | Email templates change without notice; fixture tests catch this | Built (T-066): `email-jobscz`, `email-startupjobs`, `email-indeed` on synthetic fixtures written from public link formats; each needs a check against a real alert email |

Notes for Tier 2:
- The user creates the alerts on each site. The tool does not log in to any job site.
- LinkedIn's User Agreement prohibits automated data collection (see below). Email alerts that the user receives and the tool parses from the user's own inbox are the compliant path.

## Tier 3: generic HTML (Jsoup + CSS selectors)

Only where `robots.txt` and the site's terms allow it. Each site needs a written terms check recorded here before its config is added. No sites are enabled by default, and the repository contains none.

The adapter is `html` (T-073, ADR-035). It reads vacancy cards from a list page with CSS selectors and opens no vacancy page. It never requests a site unless **all** of this holds:

- the site is configured and `enabled` is true;
- the configuration records the terms check: `terms-checked-on` (the date) and `terms-url` (the page whose terms were read), matching a row in the table below;
- `robots.txt` of the site allows the list page for the `VacancyTracker` agent (RFC 9309: the most specific group, the longest rule wins, `Crawl-delay` is waited between pages). No `robots.txt` (a 4xx answer) means allowed; one that cannot be read (5xx, no answer) means the site is not fetched and counts as failed.

Settings are ordinary properties; in a deployment they are environment variables (`APP_SOURCES_HTML_SITES_0_ID`, and so on):

```yaml
app:
  sources:
    html:
      sites:
        - id: example                    # short name
          enabled: true
          list-url: https://careers.example.test/jobs?page={page}   # {page} is optional
          max-pages: 3                   # at most 5, and only with {page}
          terms-checked-on: 2026-11-01   # required
          terms-url: https://careers.example.test/terms             # required
          item: li.job                   # CSS selector of one vacancy card
          title: a.title                 # inside the card; its text is the title
          link: ""                       # inside the card; default: the title element, else the first a[href]
          company: .company              # inside the card; or use company-name for one company's own page
          company-name: ""
          location: .location
```

Limits: at most 200 cards per page, 5 pages per site, one request per second per host. A site that fails does not stop the others; the source counts as failed only when no site could be read. Vacancies carry the source `html`; the site is the first part of the vacancy's external id. The terms and `robots.txt` can change: the check is a record of what was read on that date, not a permanent permission.

### Approved sites

| Site | Terms page read | Date | What the terms say about automated reading | `robots.txt` on that date | Config id |
|---|---|---|---|---|---|
| (none) | | | | | |

## Reference data: exchange rates

| Source | Used by | Official page | Auth | Limits | Terms | Status |
|---|---|---|---|---|---|---|
| ECB euro reference rates | Salary comparison (T-051, ADR-011) | ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml; rates page and disclaimer read 2026-11-01 | None | No request limit stated. Published about 16:00 CET on working days; the feed sends Last-Modified, so unchanged days answer 304 | "Published for information purposes only. Using the rates for transaction purposes is strongly discouraged." Free reuse with the ECB cited as the source, reproduced accurately (disclaimer page; the conditions were read from a summary, not verbatim) | ok (constraints: cite the ECB wherever converted amounts are shown) |

## Forbidden

| Target | Reason | Status |
|---|---|---|
| LinkedIn direct scraping (jobs search pages) | User Agreement section on scraping and automated data collection; enforcement precedent (hiQ, Mantheos) | blocked |
| Glassdoor, Indeed direct scraping | Site terms prohibit automated access; fragile | blocked |
| Any CAPTCHA bypass or anti-bot evasion | Forbidden by project policy | blocked |
| Logging in to job sites with the user's account | Forbidden by project policy | blocked |
| Proxy rotation | Forbidden by project policy | blocked |

## LinkedIn: findings

- Official User Agreement (linkedin.com/legal/user-agreement, "Effective on November 3, 2025"), section 8.2 "Don'ts": no bots or automated methods to access the Services; no scraping or copying the Services, including profiles and other data; no bypassing access controls or use limits; no copying or distributing information obtained from the Services, including through data aggregators.
- Enforcement history (secondary): hiQ Labs consent judgment, Mantheos settlement.
- Decision: no LinkedIn scraping. See [decisions.md](decisions.md) ADR-007. LinkedIn job alerts received by email are a separate Tier 2 source.

## Adapter capabilities matrix

Each adapter declares which criteria fields it can translate into request parameters on the server side. Everything else is applied after fetching in the pipeline.

| Adapter | Server-side: keywords | Server-side: country / location | Server-side: remote / workMode | Server-side: posted age | Pagination | Notes |
|---|---|---|---|---|---|---|
| `ats-greenhouse` | no | no | no | no | not needed (whole board) | `content=true` for descriptions; filter everything in pipeline |
| `ats-lever` | no | yes (`location`, `commitment`, `team`, `department`) | no | no | `skip` / `limit` | Multiple values OR, case-sensitive |
| `ats-ashby` | no | no | no | no | not needed | `includeCompensation=true`; skip `isListed=false` |
| `arbeitnow` | no (`visa_sponsorship` filter only) | no | no | no | `page` (per Postman doc, not verified) | `remote` is a response field: filter in pipeline |
| `adzuna` | yes (`what`) | yes (country path segment, validated) | no | yes (`max_days_old`) | `page` | Personal use only; country mandatory per profile |
| `jooble` | yes (keywords in POST body, per request form docs) | yes (`location`) | no | no | `page` | Blocked until request method and key obtained |
| `remotive` | yes (`search`, `category`) | no | remote only (no parameter) | no | `limit` only | Max 4 calls per day: cache; never more than 2 per minute |
| `himalayas` | yes (`q`, `country`) on /jobs/api/search | yes (`country`, `worldwide`) | remote only | no | `cursor` (preferred), `page` on search; `limit` max 20 | |
| `jobicy` | yes (`tag`) | yes (`geo`) | remote only | no | `count` (1 to 100), `cursor`; 7-day window | Poll at most once per hour |
| `rss` | no | no | no | no | per feed | Filter everything in pipeline |
| `email-*` | no | no | no | email date | n/a | Parsed from mail |

Parameter names come from the official pages read in T-027. Arbeitnow `page` and Jooble body fields are not confirmed and must be checked in each adapter task.

# Sources: verified terms, limits and adapter capabilities

Status legend:
- `ok`: terms and limits confirmed against the source's official page (verified date below).
- `ok (constraints)`: usable, with attribution or other conditions the adapter must implement.
- `check`: the adapter is planned, but a term or limit is still unconfirmed. Resolve before the adapter task (T-028 onwards).
- `blocked`: must not be built in the current scope.

Verification (T-027, 2026-11-01, closed 2026-11-02): official pages were read through the web-fetch agent. Where a page could not be read, the row says so and the source is `blocked` until the owner reads it in a browser; protection against automated access is never bypassed. Quotes are from the official pages unless marked as secondary.

## Tier 1: official public APIs and feeds

| Source | Adapter | Official page | Auth | Limits | Attribution and terms | Risks | Status |
|---|---|---|---|---|---|---|---|
| Greenhouse Job Board | `ats-greenhouse` | docs.greenhouse.io/job-board.html (old developers.greenhouse.io redirects here) | None: "Job Board data is publicly available, so authentication is not required for any GET endpoints." | None documented | No usage-terms section found on the page | List endpoint: only `content=true` documented. Board token must be configured per company | ok |
| Lever Postings | `ats-lever` | github.com/lever/postings-api; hire.lever.co/developer/postings needs sign-in | GET needs no key (implied by examples; POST needs an API key). EU host: api.eu.lever.co | Only POST application limit: 2 per second (429). GET limit not stated | No attribution rule in README. "These jobs may be scraped by third parties" (published postings only) | Official developer page not readable without sign-in; GET terms rest on the README | ok (constraints: published postings only) |
| Ashby Job Board | `ats-ashby` | developers.ashbyhq.com/docs/public-job-posting-api (updated 2026-10-23) | None shown in the official example | None stated | No terms stated. "If you host your own careers page, you can use this data to populate it." | Only `includeCompensation` documented; respect `isListed` (false = direct link only) | ok |
| Arbeitnow | `arbeitnow` | arbeitnow.com/blog/job-board-api; arbeitnow.com/terms (section 11) | None ("requires no API key") | None stated; Postman doc holds the parameter reference | Must link back to arbeitnow.com. API may be revoked at any time. Use License covers "non-commercial" use; API data reuse not defined | Commercial use unclear; `remote` is a response field, not a filter | ok (personal use, link back) |
| Adzuna | `adzuna` | developer.adzuna.com; terms at developer.adzuna.com/docs/terms_of_service | `app_id` + `app_key` query params, free key at signup | Not published: "plans ... and their limits, are shown in your account" | Permitted: "Personal job search". Any other use needs written agreement. Commercial or revenue use needs a licence. Caching and storage rules not explicit | Country list not on any readable page; check the account or interactive docs | ok (personal use only) |
| Jooble | `jooble` | jooble.org/api/about (request form); terms jooble.org/info/terms | API key after the request form; free status not stated | Not stated | Terms contain no API clauses. Site content "may not be copied for republication". Job content provided by third parties | Request method and body not on the public page. Access by request only. Compliance contact: compliance@jooble.com | blocked (2026-11-02): no public request format, key only by request form. Unblock when the owner has a key and the API documentation, and has read the terms in a browser; then add the adapter (T-036) |
| Remotive | `remotive` | remotive.com/api-documentation redirects to github.com/remotive-com/remote-jobs-api (README) | None; private paid API exists | Advised max 4 requests per day; more than 2 per minute is blocked. 429 not documented | Attribution and link back to the URL on Remotive plus naming Remotive. Listings delayed 24 hours. Must not submit jobs to Jooble, Neuvoo, Google Jobs, LinkedIn Jobs. Email collection through listings breaches terms | Access terminated if attribution is missing | ok (constraints) |
| Himalayas | `himalayas` | himalayas.app/api; terms himalayas.app/terms#api (not read) | Optional. Unkeyed requests share one public allowance; key gives 60/min and 10,000/day | `limit` max 20 on /jobs/api; use `cursor` (offset deprecated). Search endpoint `/jobs/api/search` with `page` | Show a visible link to the job on Himalayas or himalayas.app. Use applicationLink for Apply. Show jobs only on sites you operate. Do not pass to aggregators or LinkedIn/Google Jobs. Remove expired jobs. No browser calls (no CORS) | Use a key to avoid the shared allowance; 429 handling mandatory | ok (constraints) |
| Jobicy | `jobicy` | jobicy.com/jobs-rss-feed (jobi.cy/apidocs redirects here) | None required for the Jobs API (optional Bearer key only changes `url`, paid per direct-link job) | Feed and status: no more than once per hour. Last 7 days only; cursor valid 24 h; `count` 1 to 100 | Credit Jobicy and link to the source. Keep Apply links to Jobicy pages. Storage and redistribution allowed; keep listings synchronized and mark closed jobs | Jobs leaving the 7-day window are not necessarily closed | ok (constraints) |
| Remote OK | `remoteok` | remoteok.com/api (also linked as "JSON feed"); terms remoteok.com/legal, read 2026-11-02 | None | No limit stated; robots.txt `Crawl-delay: 1`; one request per run | "Link back to the URL on Remote OK and mention Remote OK as a source" (the notice is element 0 of the answer); no `nofollow`; no logo. The vacancy keeps the Remote OK page as its link and the source shows as Remote OK | Access is suspended without the credit; a window of recent jobs (never auto-expired) | ok (constraints: link back and credit) |
| Working Nomads | `workingnomads` | workingnomads.com/api/exposed_jobs/ (the "API" link in the site menu); terms workingnomads.com/terms-and-conditions, read 2026-11-02 | None | No key, no filters, no pages; one request per run | No clause on automated reading; 3.1(d) forbids using the service to build a competing product | A window of current jobs (never auto-expired); links go through a /job/go/ redirect | ok |
| We Work Remotely RSS | `rss` (configured feed) | weworkremotely.com RSS and API terms pages both return HTTP 403 to the fetcher; not readable | None | Not readable | Unverified. Earlier secondary sources: feed free with attribution; API terms prohibit storing or scraping data outside the API. Owner decision (Phase 0): personal use only | Cannot confirm before enabling. Ask WWR or verify from a browser | blocked as a ready-made source (2026-11-02): the terms could not be read. The generic `rss` adapter can read the feed once the owner has read the terms in a browser and recorded the check in the Feeds table below |
| Generic RSS / Atom | `rss` | User-configured URLs | None | Per-domain rate limit | Per feed: check each feed's terms before adding | Feeds may contain HTML; sanitize | ok (framework); each feed `check` |

## Tier 2: email alerts via IMAP

| Source | Adapter | Mechanism | Auth | Risks | Status |
|---|---|---|---|---|---|
| LinkedIn job alerts | `email-linkedin` | User-created alerts; the tool reads the alert emails from a dedicated IMAP label | User's own mailbox (IMAP app password) | Parse only the email content; never log in to LinkedIn; never open LinkedIn pages | Built (T-065) on a synthetic fixture; needs a check against a real alert email |
| Jobs.cz, StartupJobs.cz, Indeed and others | `email-<sender>` | One parser per sender, tested on saved fixtures | User's own mailbox | Email templates change without notice; fixture tests catch this | Built (T-066): `email-jobscz`, `email-startupjobs`, `email-indeed` on synthetic fixtures written from public link formats; each needs a check against a real alert email |
| Telegram posts the owner forwards | `telegram-forward` | The owner forwards a channel or group post (or pastes a link or text) to their own bot in the official Telegram app; each run reads the bot's messages with the Bot API `getUpdates`. Only the owner's chat counts. Off unless `app.sources.telegram-forward.enabled=true` with a bot token and the owner's chat id (by default those of the Telegram digest) | The owner's bot token from `.env` (never the owner's Telegram account) | Telegram keeps updates for 24 hours: run at least daily. A chat with protected content cannot be forwarded: paste the link or text | Built (T-089, ADR-045) |

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

### Feeds (RSS and Atom)

Source `rss` (T-040, ADR-036) reads feeds listed under `app.sources.rss.feeds`: `id`, `enabled`, `url`, `terms-checked-on`, `terms-url`, and an optional `company-name`. Like the HTML adapter it requests nothing for a feed that is disabled or has no recorded terms check. One feed is shipped, from the owner's list (Djinni, below); any other is added in configuration. Write the check in the table below before adding the feed. One feed that fails does not stop the others. Items without a title or an http(s) link are dropped; the item text is reduced to plain text.

| Feed | Terms page read | Date | What the terms say about automated reading | Config id |
|---|---|---|---|---|
| Djinni, https://djinni.co/jobs/rss/ (linked on the job list as "Jobs feed in RSS"; accepts the list filters, for example `?primary_keyword=Java&employment=remote`) | djinni.co/terms-of-use | 2026-11-02 | No clause against reading the site or the feed; the only parsing ban is on harvesting candidates' contacts and personal data. `robots.txt` allows `/jobs/rss/` | `djinni` (shipped, on by default; `APP_SOURCES_DJINNI_ENABLED`, `APP_SOURCES_DJINNI_URL`). The feed does not name the company: its vacancies show "Djinni" |

## Owner's list (T-088, checked 2026-11-02)

The owner's spreadsheet "Пошук роботи: канали комунікації" lists 22 Telegram entries, 11 Facebook groups and pages, 2 LinkedIn groups and 30 job sites. Each was checked on 2026-11-02: the terms page (quoted in a few words or by section), `robots.txt` for the path that would be read, and any official API, feed or email alert. Pages that answered 403, a challenge or a time-out were recorded as unreadable and **not** retried in any other way. Verdicts:

- **connected**: an adapter or feed reads it (Tier 1 or 2 above);
- **email**: the site sends job alerts by email; once the owner subscribes, the mails can be parsed from the mailbox (a parser is added after a real alert is available as a sample);
- **manual**: no automated route; the owner adds a vacancy with "Add vacancy" (a single page read, as a browser would, only where `robots.txt` allows it) or types it;
- **platform**: a talent marketplace where one applies to the platform itself;
- **blocked**: the terms or `robots.txt` forbid automated reading, or the terms could not be read; nothing is connected.

### Job sites

| Site | Verdict | Route | Terms and robots.txt (2026-11-02) |
|---|---|---|---|
| djinni.co | **connected** (+ email) | RSS feed `djinni` | djinni.co/terms-of-use: no clause on reading; robots allows `/jobs/rss/`; vacancy pages carry JobPosting data |
| remoteok.com | **connected** | API `remoteok` | remoteok.com/legal: link back and credit Remote OK; robots `Allow: /`, `Crawl-delay: 1` |
| workingnomads.com | **connected** (+ email) | API `workingnomads` | Terms: no clause on reading, no competing product (3.1(d)); robots allows all; email alerts at /subscribe |
| jobs.dou.ua | **blocked** until DOU consents | RSS exists (`/vacancies/feeds/?category=…`) | dou.ua/legal 2.5 forbids access "через автоматизовані програмні засоби" without the Administration's consent. Ask DOU; the feed can then be added as an `rss` feed with the consent recorded |
| jobgether.com | **blocked** until confirmed | Key-less API (`/astroapi/ai/jobs.json`) offered on /developers | Terms 4.5: refrain from "extracting its content by automated means", which contradicts the developer page; `robots.txt` allows only that address, `Crawl-delay: 2`. Confirm with support@jobgether.com first |
| jobico.io | **blocked** until confirmed | XML feed `/api/feeds/jobs.xml` and an MCP endpoint offered on /developers | Terms (17 Oct 2026): «Забороняється використовувати автоматичне парсування, ботів», which contradicts the developer page. Confirm with support@jobico.io first |
| golangprojects.com | **blocked** (+ email) | RSS `/rss.xml` | No terms page; robots allows; the server answers 403 to non-browser clients, which is protection the tool does not work around. Weekly newsletter by email |
| ua.indeed.com | **blocked** + email | Existing `email-indeed` parser | indeed.com/legal D 21: "may not crawl, scrape, extract data"; robots disallows `/rss`. Alerts come from @indeed.com, @indeedemail.com, @indeedmail.com |
| wellfound.com | **blocked** + email | Saved-search alerts | wellfound.com/terms III.4 and III.6: no scraping, no spiders or robots |
| happymonday.ua (now happymonday.jobs) | **email** | "Set alert" by email, weekly newsletter | Terms restrict "caching, framing or similar means"; reading the list page is not recommended |
| jobsforhumanity.com | **email** / manual | Alert form on /jobs/ | No terms page; its WordPress feed is empty; no JobPosting data |
| jobs.dev.ua | **manual** (+ email) | Add by link (JobPosting data) | dev.ua/pages/agreement 2.2.6 and 2.2.7 are vague on automation and require consent for use "for commercial and other purposes" |
| relocate.me | **manual** | Add by link (JobPosting data) | No clause on automated reading; robots allows lists. A list-page reader would qualify; not configured (no verified selectors) |
| bazait.com | **manual** | Add by link | No terms found; bazait.com robots disallows `/jobs` |
| lezo.io | **manual** | Add by link or type it | No terms found; JavaScript-only site; alerts through its own Telegram bot |
| jobitt.com | **blocked** | — | jobitt.com/uk/agreement, Prohibited Activities: no "script bots", "crawlers", "spiders"; no email alerts |
| nofluffjobs.com | **blocked** | — | Terms unreadable (the fetch was refused) |
| theprotocol.it | **blocked** | — | HTTP 403; terms not found |
| bulldogjob.pl | **blocked** | — | HTTP 403; robots disallows `/feeds` |
| justjoin.it | **blocked** | — | robots disallows `/api/` and the terms page; the listed address now 404s |
| arc.dev (and /remote-jobs) | **blocked** | — | arc.dev/terms 7.7(k): no "software, devices, scripts, robots" to "access, scrape, crawl" |
| ycombinator.com/jobs | **blocked** | — | ycombinator.com/legal: no "data mining, robots, scraping". The official Hacker News API (`jobstories`) is a separate, permitted route not on the owner's list |
| workatastartup.com | **blocked** | — | YC terms; applying needs a YC account |
| remote.co | **blocked** | — | Unreadable (time-outs) |
| uatalents.com | **blocked** | — | Unreachable (HTTP 522) |
| meetfrank.com | **blocked** | — | HTTP 403, even for robots.txt |
| turing.com | **platform** | Apply to Turing | Terms 2(e): no scraping without written authorization |
| lemon.io | **platform** | Apply to Lemon.io | Terms 20(K): no scraping or crawling, AI agents named |
| remoteintech.company | reference only | A list of companies, not vacancies (github.com/remoteintech/remote-jobs) | No terms; the data licence is unclear. Use its `careers_url` values as a watch list by hand |

### Telegram

Telegram's terms forbid reading channels and groups automatically (ToS: "Telegram additionally prohibits data scraping"; Bot Developer Terms 4.3: no "scraping public group or channel contents"); `t.me/robots.txt` is absent, but the terms decide. A bot cannot read a channel it does not administer. The compliant route is **forwarding**: the owner forwards a post to their own bot in the official app, and `telegram-forward` collects it ("data submitted directly and voluntarily", 4.3). It works for every channel and group the owner can read, including private ones, except chats that forbid forwarding (then paste the link or text).

| Entry | What it is (2026-11-02) | Verdict |
|---|---|---|
| t.me/zaichenkoteam | Channel, vacancies, active today | forward to the bot |
| t.me/itexpert_vacancies | Channel, MilTech and IT vacancies, active | forward to the bot |
| t.me/offer_ua | Channel, vacancies, last post 2026-05-14 | forward to the bot |
| t.me/dzen_robota_digital | Channel, digital and IT vacancies, last post 2026-08-04 | forward to the bot |
| t.me/lobbyxIT | Channel, some vacancies, mostly events | forward to the bot |
| t.me/happymonday | Channel, career media, occasional vacancy digests | forward to the bot |
| t.me/BazaITJobs | Channel, mostly not vacancies | forward to the bot (low value) |
| t.me/dou_remote | Channel, events and courses, not vacancies | forward to the bot (low value) |
| t.me/itboxer | Channel, last post 2025-12-29 | forward to the bot (inactive) |
| t.me/itrecruit_ua, t.me/recruitingUA, t.me/jobsitlviv, t.me/jobsitua, t.me/hr_recruiter_ukraine, t.me/it_army_layboard, t.me/frontenduajobs | Public groups (no web preview) | forward to the bot (as a member) |
| t.me/it_recruiting_ua | Not a channel or group (a user name) | nothing to read |
| t.me/+TAnlc22ehOBlOGYy, t.me/+lMER8aQM8ro2YjJi, t.me/+S-r6Y26R9ElkOGEy | Private invite links | blocked for automation; forward as a member |
| @jobit_io_bot | JOBITT's bot for publishing CVs | not a source |

### Facebook and LinkedIn

| Entry | Verdict | Reason |
|---|---|---|
| facebook.com/groups/LvivWorkIT, /groups/1037268706420923, /groups/1652617378359383, /groups/226720891321083, /groups/1882449415335647, /groups/hrrecruiterua, /groups/do.IT.Poland, /groups/find.it.job, /groups/uajobitt, /groups/DIGITALJOBSUKR, and the page facebook.com/nofluffjobsua | **blocked** | Terms 3.2 forbid collecting data "using automated means" without permission; `robots.txt` disallows everything; the Groups API was removed on 2024-04-22. Group notifications by email, which the owner can switch on in their own account, are the only partial route |
| linkedin.com/groups/9156482, linkedin.com/groups/4195701 | **blocked** | User Agreement 8.2 (no bots, no scraping); `robots.txt` disallows everything. LinkedIn job-alert emails (daily or weekly, up to 20 searches) are parsed by `email-linkedin`; LinkedIn mail about applications and messages is tracked by the correspondence reader (T-086) |

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
| Reading Telegram channels or groups (t.me/s/ previews, user-account clients) | Telegram ToS: "Telegram additionally prohibits data scraping"; Bot Developer Terms 4.3 name "scraping public group or channel contents"; signing in as the user is forbidden by project policy. The compliant route is `telegram-forward` | blocked |
| Facebook groups and pages | Terms 3.2: no access or collection "using automated means" without permission; `robots.txt` disallows everything; the Groups API was removed on 2024-04-22 | blocked |
| LinkedIn groups | User Agreement 8.2 (no bots, no scraping); `robots.txt` disallows everything | blocked |

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
| `remoteok` | no | no | remote only | no | not needed (one list) | Credit Remote OK; keep its link |
| `workingnomads` | no | no | remote only | no | not needed (one list) | Filter everything in pipeline |
| `telegram-forward` | no | no | no | message date | `offset` of `getUpdates` | Only what the owner forwards |
| `email-*` | no | no | no | email date | n/a | Parsed from mail |

Parameter names come from the official pages read in T-027. Arbeitnow `page` and Jooble body fields are not confirmed and must be checked in each adapter task.

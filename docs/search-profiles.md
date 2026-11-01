# Search profiles

Status: Phase 0 (M0). The code contains no roles, technologies, countries, salaries or domains. Everything below is user data. The neutral example is in [examples/search-profile.example.yml](examples/search-profile.example.yml) and is never loaded automatically.

## 1. SearchProfile

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | UUID | yes | Assigned by the system |
| `name` | text | yes | Display name, unique among profiles |
| `enabled` | boolean | yes | Disabled profiles are neither collected nor scored |
| `sources` | list of adapter IDs or `all` | yes | Which adapters this profile uses |
| `schedule` | cron string | no | Optional override of the global schedule |
| `criteria` | list of Criterion | yes | Ordered rules (order is for display and reordering) |
| `thresholds` | object | yes | See section 4 |
| `version` | integer | yes | Incremented on every change |
| `updatedAt` | timestamp | yes | Last change |

Rules:
- Several profiles can exist; each can be enabled or disabled independently.
- Profiles are edited in the UI, imported and exported as YAML or JSON (format section 6).
- Every change creates a new version and keeps the previous one (ADR-010).
- A vacancy can match several profiles. Scores and reasons are stored per profile; lists show the best one.
- First run: an onboarding wizard creates the first profile. No profile is created from the example file automatically.

## 2. Criterion

```
Criterion {
  id:                   string   (stable, unique within the profile; used by matchReasons)
  field:                enum     (see 2.1)
  operator:             enum     (see 2.2)
  value:                any      (type depends on field and operator)
  mode:                 enum     require | prefer | avoid | exclude
  weight:               int      1..10
  missingValuePolicy:   enum     ignore | penalize | hide   (default: ignore)
  label:                string   (plain-language name shown in the UI and in matchReasons)
}
```

### 2.1 Fields

| Field | Value type | Notes |
|---|---|---|
| `title` | text | |
| `description` | text | |
| `keywords` | text | Searches title and description together |
| `company` | text | |
| `companyType` | enum (user-defined list) | |
| `industry` | text | |
| `location` | text | Free text from the source |
| `country` | ISO 3166-1 alpha-2 | Normalized during ingestion |
| `city` | text | |
| `workMode` | enum: `onsite`, `hybrid`, `remote` | Detected during normalization |
| `employmentType` | enum (user-defined list) | |
| `seniority` | enum (user-defined list) | Detected from title and description |
| `salary` | object `{ amount, currency, period, basis }` | `period` = month / year; `basis` = gross / net |
| `workLanguage` | text (language code) | |
| `visaSponsorship` | boolean | |
| `relocationSupport` | boolean | |
| `postedAge` | integer days | Computed from `postedAt` |
| `source` | adapter ID | |
| `tags` | list of text | |

### 2.2 Operators

| Operator | Applies to | Meaning |
|---|---|---|
| `contains_any` | text, list | At least one of the values appears |
| `contains_all` | text, list | All values appear |
| `not_contains` | text, list | None of the values appear |
| `equals` | any scalar | Exact match (case-insensitive for text) |
| `in` | scalar | Value is one of the list |
| `not_in` | scalar | Value is not one of the list |
| `regex` | text | Java regex, case-insensitive by default; invalid patterns are rejected at save time |
| `gte` | number, salary, date | Greater than or equal |
| `lte` | number, salary, date | Less than or equal |
| `between` | number, salary, date | Inclusive range `[min, max]` |
| `exists` | any | Field has a non-empty value |
| `not_exists` | any | Field is empty or absent |

Type checks happen at save time: an operator that does not apply to a field is rejected by the API with a field-level error.

### 2.3 Modes

| Mode | If satisfied | If not satisfied |
|---|---|---|
| `require` | No effect | Vacancy is filtered out (`Hidden`) |
| `prefer` | Adds `+weight` to the score | No effect |
| `avoid` | Subtracts `weight` from the score | No effect |
| `exclude` | Vacancy is filtered out (`Hidden`) | No effect |

"Satisfied" means the operator returns true for the vacancy's field. For `require`, the criterion is evaluated as "must be satisfied"; for `exclude`, as "must not be satisfied" when the match triggers.

### 2.4 missingValuePolicy

Applies when the field is absent from the vacancy (for example, no salary is stated).

| Policy | Effect when the field is missing |
|---|---|
| `ignore` (default) | The criterion is skipped: no filter, no score change |
| `penalize` | The criterion's weight is subtracted from the score, in every mode. The vacancy is not filtered. "Unknown" counts against it |
| `hide` | The vacancy is filtered out (`Hidden`), regardless of mode |

Default `ignore` keeps useful vacancies visible.

`exists` and `not_exists` test presence itself, so they are never "missing": they are simply satisfied or not. The missing-value policy applies to every other operator.

### 2.5 Salary and currency

- A salary criterion compares normalized values, not raw ones.
- Normalization: period to yearly (monthly x 12), then currency to the profile's currency using the cached exchange rate (ADR-011), then compare the yearly gross amount.
- Net salaries are not converted to gross. A salary criterion with `basis` set to `gross` does not match net postings; those are handled by `missingValuePolicy`.
- A currency without a known rate is treated as missing data.

### 2.6 Company lists

- `allowlist`: the company is always shown and receives a score bonus (`+10`, configurable constant in the scoring settings).
- `blocklist`: the company is always filtered out (`Hidden`).
- Each list entry is global (`profileId = null`) or per profile.

## 3. Score formula

Input: the list of criteria that were evaluated for one vacancy and one profile.

```
positive = sum of weight for each prefer criterion that is satisfied
negative = sum of weight for each avoid criterion that is satisfied
            + sum of weight for each penalize-policy criterion whose field is missing (see 2.4)
allowlistBonus = 10 if the company is allowlisted, else 0

maxPositive = sum of weight for all prefer criteria that are not ignored by missing data
raw = positive - negative + allowlistBonus
score = clamp( round( 100 * (raw + maxNegativeAbsorb) / (maxPositive + maxNegativeAbsorb + allowlistBonus) ), 0, 100 )
```

Where:
- `maxNegativeAbsorb = sum of weight for all avoid criteria` keeps the scale symmetric: a vacancy that satisfies every avoid rule and no prefer rule scores 0, one that satisfies every prefer rule and no avoid rule scores 100.
- If `maxPositive + maxNegativeAbsorb + allowlistBonus = 0` (no scoring criteria), the score is `0` and `matchReasons` is empty.

Clarifications (T-052, ADR-025):
- A criterion that `ignore` skips because its value is missing takes no part in the formula: it is left out of `maxPositive` and `maxNegativeAbsorb` as well, as if it did not exist. This keeps "ignore" meaning "no score change".
- A penalized missing value adds its weight to `negative` in every mode. Prefer criteria with a penalized missing value stay in `maxPositive`.
- Integer arithmetic is used: `score = min(100, (200 * max(0, raw + absorb) + denominator) / (2 * denominator))`.
- A filtered vacancy has no score (stored as null with `hidden = true`); its reasons list the filters.

Properties:
- Deterministic: same vacancy, profile version and rules always give the same score.
- Monotonic: adding a satisfied prefer criterion never lowers the score.
- Range: 0 to 100 inclusive.

Filtering happens before scoring. A vacancy filtered by `require`, `exclude`, blocklist or `hide` gets no score for that profile and is marked `Hidden` for that profile.

### 3.1 matchReasons

Each evaluated criterion that changed the score or the filter result produces one reason:

```
MatchReason {
  criterionId: string   (references Criterion.id)
  delta:       int      (+weight, -weight, 0 for filters)
  kind:        prefer | avoid | require | exclude | allowlist | blocklist | missing
  text:        string   (plain language, for example "+8: keyword in description")
}
```

Plain-language rendering uses the criterion `label` when set, otherwise a generated sentence from field, operator and value.

## 4. Thresholds

| Threshold | Meaning |
|---|---|
| `inboxMinScore` | Below it, the vacancy is `Hidden` (still visible via the "hidden by score" filter). Default 40 |
| `notifyMinScore` | Minimum score for a vacancy to appear in the Telegram digest. Default 70 |
| `maxAgeDays` | Vacancies older than this are ignored at ingestion for this profile. Default 60 |

Thresholds are per profile.

## 5. How adapters translate criteria into requests

Each adapter declares its capabilities in code (not in profile data). Capabilities list which fields and operators the source supports server-side.

Steps for one run:
1. For each enabled profile and each of its enabled sources, the adapter receives the subset of criteria it can express server-side (`keywords`, `country`, `location`, `workMode`, `postedAge`).
2. The adapter builds request parameters. Examples: a `keywords contains_any [a, b]` criterion becomes a search term; a `country in [..]` criterion for Adzuna becomes the country path segment, one request per country.
3. Anything the adapter cannot express server-side is applied after fetching, in the pipeline matching stage.
4. The adapter never drops a criterion silently: unsupported criteria are listed in the run log at debug level.
5. Server-side filters are an optimization. The pipeline re-evaluates every criterion locally, so results are the same whether or not a server-side filter was applied.

Capability matrix per adapter: see [sources.md](sources.md), section "Adapter capabilities matrix".

Request limits are respected first: a profile that would require more requests than the source allows is split into cached runs (for example, Remotive is called at most 4 times per day and results are reused across profiles).

## 6. Import and export

- Format: YAML (primary, human-editable) or JSON.
- Export contains the profile, all criteria, thresholds, schedule and the current version number.
- Import validates each criterion against the field and operator rules (section 2). Errors are reported per criterion with a path, for example `criteria[3].operator: 'regex' is not allowed for field 'salary'`.
- Import creates a new version of an existing profile (matching by `name`) or a new profile. It never runs criteria; it only stores them after validation.
- `schemaVersion` is included to allow future format changes.

## 7. Views and profiles

- Inbox and Pipeline filters use the same Criterion model (ADR-009).
- A saved view can be turned into a search profile in one click: the criteria are copied, and the user chooses the mode for each row.
- A search profile can be turned into a view: criteria are copied; modes are kept as data for display.

## 8. Recalculation

- Saving a profile version triggers a background job that recalculates `VacancyMatch` rows for that profile.
- Recalculation never changes statuses set by the user (ADR-012); it only writes scores and reasons.
- The "Recalculate scores" button triggers the same job manually.

## 9. Live preview

Shown in the profile editor before saving:

> Of N existing vacancies, M would match, K of them would land in the Inbox.

The preview runs the rule engine against the current database without writing `VacancyMatch` rows. It also shows up to 5 sample vacancies with their score and matchReasons.

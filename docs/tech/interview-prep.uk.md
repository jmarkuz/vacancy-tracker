# Підготовка до інтерв'ю: Vacancy Tracker

Українська версія. Англійський оригінал: [interview-prep.md](interview-prep.md) (мова репозиторію за [ADR-020](../decisions.md#adr-020-language-policy) англійська, а інтерв'ю, найімовірніше, теж будуть англійською, тож тримайте оригінал під рукою для термінології).

Див. також: [project-deep-dive.uk.md](project-deep-dive.uk.md) (каталог усього функціоналу по блоках) і [star-stories.md](star-stories.md) (сім STAR-історій з англійськими скриптами).

Робочий документ для презентації та захисту проєкту: що він робить, які архітектурні й технічні рішення ухвалені, чого кожне коштувало і куди показувати в коді на запитання «покажіть». Назви класів, файлів, ADR і терміни лишені англійською навмисно: саме так їх називатимуть на співбесіді.

Кожне посилання на код веде у файл і діапазон рядків станом на 2026-11-16. Якщо файл перемістять, посилання треба виправити тут, у `docs/tech/`.

## Як користуватися

| Скільки часу | Що читати |
|---|---|
| 2 хвилини | [1. Пітч](#1-пітч) |
| 10 хвилин | 1, [2. Цифри](#2-проєкт-у-цифрах), [3. Архітектура](#3-архітектура-на-одну-сторінку), [8. Чесні обмеження](#8-чесні-обмеження-і-що-я-зробив-би-далі) |
| 30 хвилин | Додайте кейси, про які очікуєте питання (розділ 5), і [банк питань](#9-банк-питань) |
| Раунд «покажіть код» | [10. Де що лежить](#10-де-що-лежить) і рядок **Code** кожного кейсу |

Кожен кейс (C1–C20) має однакову форму: **Problem** (проблема), **Decision** (рішення), **Trade-off** (ціна), **Code** (код), **Tests** (тести) і **If they push** (уточнююче питання та чесна відповідь). Кожен кейс читається окремо.

Зміст:
1. [Пітч](#1-пітч)
2. [Проєкт у цифрах](#2-проєкт-у-цифрах)
3. [Архітектура на одну сторінку](#3-архітектура-на-одну-сторінку)
4. [Індекс рішень (ADR → код)](#4-індекс-рішень-adr--код)
5. [Кейси](#5-кейси)
6. [Наскрізні теми](#6-наскрізні-теми)
7. [Демо-сценарій (5 хвилин)](#7-демо-сценарій-5-хвилин)
8. [Чесні обмеження і що я зробив би далі](#8-чесні-обмеження-і-що-я-зробив-би-далі)
9. [Банк питань](#9-банк-питань)
10. [Де що лежить](#10-де-що-лежить)

---

## 1. Пітч

**30 секунд.** Vacancy Tracker це self-hosted інструмент пошуку роботи для однієї людини. Він за розкладом збирає вакансії з офіційних API, стрічок і з листів-сповіщень на пошті користувача, прибирає дублікати, оцінює кожну вакансію за пошуковими профілями користувача з поясненням причин оцінки та веде кожну заявку через пайплайн із повною історією статусів. У коді немає ні ролей, ні технологій, ні країн: усе це дані в профілях користувача.

**2 хвилини (додайте це).** Це Java 21 / Spring Boot 3.5 модульний моноліт на PostgreSQL 16 із фронтендом на Preact + TypeScript, який збирається в той самий jar. Цікава інженерія тут не CRUD, а:

1. **Пайплайн збору, що переживає погані джерела**: ізоляція кожного джерела, retry з backoff і jitter, circuit breaker, що зберігається в БД, пейсинг за хостами, квотний бюджет викликів у БД для API з лімітами, і правило закінчення терміну, яке ніколи не вважає збійне джерело ознакою того, що вакансії більше немає (C5, C6, C7).
2. **Автоматизація, що ніколи не перезаписує користувача**: фонові задачі, імпорти й користувач пишуть в ті самі рядки вакансій, тому кожен read-modify-write бере блокування рядка, а автоматизація обмежена коротким списком полів (C3).
3. **Пояснюваний rule engine**: оцінки детерміновані, і кожен бал має названу причину; та сама модель `Criterion` використовується для оцінювання, для фільтрів списків (компілюється в параметризований SQL) і для збережених виглядів (C9).
4. **Політика джерел за принципом compliance-first**: ніякого скрейпінгу сайтів, що це забороняють, жодного входу на job-сайти під акаунтом користувача, жодного обходу CAPTCHA; покриття LinkedIn іде лише з власних листів-сповіщень користувача (C13).
5. **Опційний AI з жорсткими межами**: вимкнений за замовчуванням, явна згода на провайдера, AI лише пропонує, а застосунок перевіряє й рахує (цитати перевіряються, оцінки обчислюються в коді, факти звіряються з базовими документами) (C14).
6. **Безпека для local-first застосунку**: захист від DNS rebinding, відмова на запити зміни даних з інших сайтів, порівняння токена за постійний час із блокуванням після спроб, SSRF-захист імпорту за URL, маскування логів (C12).

**Філософія дизайну одним реченням**: *детерміновано й пояснювано там, де це важливо, захисно на кожній межі, і дані користувача ніколи не змінює машина в нього за спиною.*

---

## 2. Проєкт у цифрах

| Що | Значення | Джерело |
|---|---|---|
| Бекенд | 238 основних Java-файлів, близько 20 500 рядків; 141 тестовий файл, близько 15 800 рядків | `find backend/src -name '*.java'` станом на 2026-11-16 |
| Фронтенд | 99 вихідних файлів TypeScript/TSX, 82 тестові файли, без UI-бібліотеки | `find frontend/src` |
| Схема | 27 SQL-міграцій плюс одна Java-міграція ([`V12`](../../backend/src/main/java/db/migration/V12__Detect_visa_sponsorship.java)) | [`db/migration`](../../backend/src/main/resources/db/migration) |
| Архітектурні рішення | 54 ADR | [decisions.md](../decisions.md) |
| Адаптери джерел | 13 пакетів адаптерів (Greenhouse, Lever, Ashby, Arbeitnow, Remotive, Himalayas, Jobicy, Remote OK, Working Nomads, Adzuna, RSS/Atom, generic HTML, Telegram forward) плюс 4 парсери листів-сповіщень | [`collection/adapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter), [`collection/mail`](../../backend/src/main/java/com/vacancytracker/collection/mail) |
| Якісні бар'єри в кожному `mvn verify` | Checkstyle (збірка падає при порушенні), JaCoCo 80% покриття рядків для `vacancy`, `collection`, `matching`, `application`, правила модулів ArchUnit, інтеграційні тести з Testcontainers на PostgreSQL 16 | [`pom.xml`](../../backend/pom.xml), [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java) |
| CI | 4 джоби: узгодженість plan/CSV, backend verify, frontend typecheck + тести + збірка + `npm audit`, Playwright smoke-тест на запакованому jar | [`ci.yml`](../../.github/workflows/ci.yml) |
| Розгортання | Docker Compose: `app`, `postgres`, `backup` (щоденний `pg_dump`) і `caddy` (HTTPS) на VPS; секрети лише в `.env` | [`docker-compose.yml`](../../docker-compose.yml), [`docker-compose.vps.yml`](../../docker-compose.vps.yml) |

Нюанс покриття, який варто знати: бар'єр міряє лише **рядки** (покриття гілок звітується, близько 77% на момент [ADR-022](../decisions.md#adr-022-testing-strategy-and-coverage-gate), але не вимагається), а `web`, `export` і `notification` вимірюються, але не гейтяться.

---

## 3. Архітектура на одну сторінку

Повні діаграми (C4 context і container, flowchart пайплайна, ERD) лежать в [architecture.md](../architecture.md). Цей розділ це версія, яку можна сказати вголос.

### 3.1 Форма

```
                     ┌──────────────────────────── один Spring Boot jar ───────────────────────────┐
 Браузер ── REST/SSE ─►  web  ──────────────────────────────────────────────────────────────────┐   │
 (Preact SPA,            (контролери, фільтри, SSE)                                             │   │
  роздається з jar)        │ використовує всі модулі; web не використовує ніхто                │   │
                           ▼                                                                    │   │
   collection ──► matching ──► vacancy ◄── export                  application ─────────────────┘   │
   (адаптери,     (профілі,    (агрегат,    (CSV/XLSX/JSON)        (резюме, fit, AI-документи,      │
    пайплайн,      rule engine, таблиця                             пакети, відстеження пошти)       │
    HTTP, пошта)   оцінювання)  статусів,   notification                                             │
                                пошук)      (Telegram, opt-in)        common (id, URL, логування)    │
                     └──────────────────────────── PostgreSQL 16 (Flyway) ─────────────────────────┘
```

### 3.2 Правило модулів одним рядком

`web` може використовувати все; **ніщо** не може використовувати `web`; `vacancy` і `common` не залежать від того, що вище; `matching` → `vacancy`; `collection`, `export`, `notification`, `application` → `vacancy` + `matching` (+ `collection` для двох останніх); циклів немає. Це не домовленість, це тест, що падає ([C1](#c1-модульний-моноліт-із-примусовими-межами)).

### 3.3 Життя вакансії

1. **Тригер**: cron (`0 0 6,18 * * *` за замовчуванням, [`CollectionScheduler`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionScheduler.java#L16-L17)) або кнопка Refresh → [`RunCoordinator.start`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java#L54-L68) (один запуск за раз).
2. **Fetch** по адаптерах з ізоляцією, [`CollectionPipeline.runAdapter`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L178-L215).
3. **Нормалізація** ([`Normalizer`](../../backend/src/main/java/com/vacancytracker/collection/normalize/Normalizer.java), [`SalaryParser`](../../backend/src/main/java/com/vacancytracker/collection/normalize/SalaryParser.java), [`CountryDetector`](../../backend/src/main/java/com/vacancytracker/collection/normalize/CountryDetector.java)) і **канонізація URL** ([`CanonicalUrl`](../../backend/src/main/java/com/vacancytracker/common/CanonicalUrl.java#L27-L62)).
4. **Дедуплікація** і **ingest** в одній транзакції: [`IngestService.ingest`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/IngestService.java#L36-L73).
5. **Оцінювання** за кожним увімкненим профілем: [`MatchService.matchWith`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L102-L126) → [`RuleEngine`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L54-L63) → [`Scorer`](../../backend/src/main/java/com/vacancytracker/matching/Scorer.java#L38-L89); потім inbox rule переводить *New* у *Hidden* лише якщо жоден профіль не приймає вакансію.
6. **Закінчення терміну** для вакансій, не побачених у 3 чистих запусках: [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L34-L46).
7. **Публікація** прогресу через SSE: [`RunEventBus`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunEventBus.java#L43-L62).
8. Користувач сортує (Inbox), подається (пакет), а **поштовий рідер** зв'язує відповіді із заявкою й *пропонує* зміни статусу ([C13](#c13-відстеження-пошти-лише-читання-ідемпотентність-правила)).

---

## 4. Індекс рішень (ADR → код)

Карта в один рядок. Повний текст кожного запису в [decisions.md](../decisions.md); кейси нижче глибше розбирають ті, що позначені C-номером.

| ADR | Рішення | Ціна | Де в коді | Кейс |
|---|---|---|---|---|
| [ADR-001](../decisions.md#adr-001-modular-monolith) | Модульний моноліт, межі контролює ArchUnit | Немає незалежного масштабування чи деплою модулів | [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java#L23-L66) | C1 |
| [ADR-002](../decisions.md#adr-002-java-21-and-spring-boot-3x) | Java 21, Spring Boot 3.x, Maven | Збірка і CI потребують JDK 21 | [`pom.xml`](../../backend/pom.xml) | |
| [ADR-003](../decisions.md#adr-003-spring-data-jdbc-over-jpa) | Явний SQL через `JdbcClient`, не JPA | Більше SQL руками; без магії lazy loading | [`VacancyRepository`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L35-L82) | C2 |
| [ADR-004](../decisions.md#adr-004-postgresql-16-with-flyway-migrations) | PostgreSQL 16, лише Flyway, без `ddl-auto` | Кожна зміна схеми це файл на рев'ю | [`db/migration`](../../backend/src/main/resources/db/migration) | |
| [ADR-005](../decisions.md#adr-005-frontend-vite--typescript--preact-served-by-spring-boot) | Vite + TypeScript + Preact, без UI-кіта, роздається з jar | Власні малі компоненти (палітра, drawer, board) | [`frontend/src`](../../frontend/src/) | C18 |
| [ADR-006](../decisions.md#adr-006-scheduling-single-run-lock-and-sse-progress) | Запуски за розкладом і на вимогу, замок єдиного запуску, прогрес через SSE | Лише один інстанс (див. примітку в C5) | [`RunCoordinator`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java), [`RunEventBus`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunEventBus.java) | C5 |
| [ADR-007](../decisions.md#adr-007-source-policy-no-direct-scraping-of-linkedin-or-other-forbidden-sources) | Політика джерел: офіційні API і стрічки, власні листи користувача; без скрейпінгу заборонених сайтів | Менше покриття (немає скрейпінгу LinkedIn) | [sources.md](../sources.md) | C13 |
| [ADR-008](../decisions.md#adr-008-rule-based-scoring-in-the-mvp-llm-scoring-optional-later) | Детерміноване оцінювання за правилами, ніколи LLM-оцінка | Якість залежить від правил користувача | [`Scorer`](../../backend/src/main/java/com/vacancytracker/matching/Scorer.java) | C9 |
| [ADR-009](../decisions.md#adr-009-one-criterion-model-for-profiles-and-view-filters) | Одна модель `Criterion` для профілів, фільтрів і збережених виглядів | Невелике відображення для UI-фільтрів | [`CriterionCompiler`](../../backend/src/main/java/com/vacancytracker/matching/CriterionCompiler.java#L53-L83) | C9 |
| [ADR-010](../decisions.md#adr-010-profile-versioning-keep-every-previous-version) | Кожне збереження профілю це нова незмінна версія | Сховище росте (повільно) | [`SearchProfileService`](../../backend/src/main/java/com/vacancytracker/matching/SearchProfileService.java) | C10 |
| [ADR-011](../decisions.md#adr-011-salary-normalization-and-exchange-rate-source) | Зарплата нормалізується до річної gross і конвертується курсами ECB | Валюти поза ECB це «відсутні дані» | [`RateSalaryConverter`](../../backend/src/main/java/com/vacancytracker/matching/rates/RateSalaryConverter.java) | C9 |
| [ADR-012](../decisions.md#adr-012-upsert-rules-automation-never-overwrites-user-data) | Автоматизація ніколи не перезаписує дані користувача | Облік по полях (`userEditedFields`) | [`Vacancy.applyAutomated`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L115-L136) | C3 |
| [ADR-013](../decisions.md#adr-013-deduplication-pipeline) | Триправильна дедуплікація, переглянута після рев'ю | Консервативне fuzzy-правило може пропустити дублікат | [`Deduplicator`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L34-L103) | C8 |
| [ADR-014](../decisions.md#adr-014-expiry-rule) | Закінчення терміну лише після N чистих запусків; збійне джерело нічого не завершує | Віконні джерела ніколи не завершуються автоматично | [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L31-L70) | C7 |
| [ADR-015](../decisions.md#adr-015-search-postgresql-full-text-search) | Повнотекстовий пошук PostgreSQL, конфіг `simple`, GIN-індекс | Без стемінгу | [`VacancySearchService`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancySearchService.java#L28-L56) | C11 |
| [ADR-016](../decisions.md#adr-016-export-formats-and-encoding) | CSV (UTF-8 BOM), стрімінговий XLSX, JSON-бекап із версією схеми | Зручність для Excel ціною BOM | [`XlsxExporter`](../../backend/src/main/java/com/vacancytracker/export/XlsxExporter.java), [`BackupWriter`](../../backend/src/main/java/com/vacancytracker/export/BackupWriter.java#L37-L55) | C17 |
| [ADR-017](../decisions.md#adr-017-authentication-none-locally-single-token-on-a-vps), [ADR-033](../decisions.md#adr-033-access-token-how-adr-017-is-implemented) | Локально без автентифікації; на VPS один токен із порівнянням за постійний час і блокуванням | Модель одного користувача; cookie це і є токен | [`AuthFilter`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthFilter.java#L33-L60), [`AccessToken`](../../backend/src/main/java/com/vacancytracker/web/auth/AccessToken.java) | C12 |
| [ADR-018](../decisions.md#adr-018-adapter-resilience-retries-rate-limits-circuit-breaker) | Стійкість HTTP: backoff, пейсинг, умовні запити, circuit breaker | Власна реалізація замість бібліотеки | [`HttpFetcher`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L132-L181), [`SourceHealth`](../../backend/src/main/java/com/vacancytracker/collection/health/SourceHealth.java) | C6 |
| [ADR-022](../decisions.md#adr-022-testing-strategy-and-coverage-gate) | Стратегія тестів і бар'єр 80% рядків | Лише рядки; частина модулів без бар'єра | [`pom.xml`](../../backend/pom.xml), [`AbstractIntegrationTest`](../../backend/src/test/java/com/vacancytracker/support/AbstractIntegrationTest.java) | C19 |
| [ADR-023](../decisions.md#adr-023-status-transition-table) | Явна таблиця переходів статусів у коді | Відновлення термінальної вакансії йде через *New* | [`VacancyStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyStatus.java#L27-L42) | C4 |
| [ADR-025](../decisions.md#adr-025-missingvaluepolicy-penalize-means-a-weight-penalty), [ADR-026](../decisions.md#adr-026-automatic-inbox-hiding-and-its-limits) | `penalize` це штраф ваги; автоматичне приховування зі строгими межами | Більше правил для пояснення | [`RuleEngine.effect`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L85-L100), [`MatchService.applyInboxRule`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L128-L152) | C9, C10 |
| [ADR-028](../decisions.md#adr-028-company-allowlist-and-blocklist) | Allow/block-списки компаній; block перемагає allow, allow ніколи не перекриває правила користувача | Лише точні назви | [`CompanyLists`](../../backend/src/main/java/com/vacancytracker/matching/CompanyLists.java) | C9 |
| [ADR-029](../decisions.md#adr-029-imap-reader-read-only-processed-state-in-the-database), [ADR-030](../decisions.md#adr-030-email-sources-one-parser-per-sender-no-expiry) | IMAP-рідер лише читає; стан «оброблено» в БД; один парсер на відправника | Рідер опитується, а не push (немає IMAP IDLE) | [`ImapMailReader`](../../backend/src/main/java/com/vacancytracker/collection/mail/ImapMailReader.java#L132-L230) | C13 |
| [ADR-034](../decisions.md#adr-034-log-masking-and-structured-logs) | Маскування логів у два шари (форми + налаштовані значення секретів) | Best effort для секретів у вільному тексті | [`LogMasking`](../../backend/src/main/java/com/vacancytracker/common/LogMasking.java#L15-L60) | C12 |
| [ADR-035](../decisions.md#adr-035-generic-html-adapter-approved-sites-only), [ADR-036](../decisions.md#adr-036-feed-adapter-country-detection-and-what-stays-undetected) | Generic HTML і feed-адаптери лише для сайтів із записаною перевіркою умов; `robots.txt` за RFC 9309 | Селектори ламаються, коли змінюється сторінка | [`HtmlSiteAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/html/HtmlSiteAdapter.java#L100-L135), [`RobotsRules`](../../backend/src/main/java/com/vacancytracker/collection/adapter/html/RobotsRules.java) | C13 |
| [ADR-037](../decisions.md#adr-037-review-follow-ups-what-changed-in-collection-and-import), [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior) | Виправлення з рев'ю: без валідаторів для multi-request джерел, редиректи лише на той самий хост, часткове читання не є чистим, запуск завжди завершується | Більше байтів за запуск | [`CollectionPipeline`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L74-L130) | C5, C6 |
| [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits) | Перевірка Host, блокування рядків, ручні вакансії, лише веб-посилання | Фонова задача може почекати рядок, який користувач зберігає | [`HostFilter`](../../backend/src/main/java/com/vacancytracker/web/HostFilter.java#L25-L70) | C2, C12 |
| [ADR-040](../decisions.md#adr-040-resume-and-the-fit-score) | Детермінована оцінка fit резюме | Терміни за формою ловлять деякі не-навички | [`ResumeFitScorer`](../../backend/src/main/java/com/vacancytracker/application/fit/ResumeFitScorer.java#L31-L93) | C15 |
| [ADR-041](../decisions.md#adr-041-claude-adapter-and-the-ai-opt-in) | Один інтерфейс `AiClient` (`cli`, `api`, `fake`); AI вимкнений за замовчуванням; згода на провайдера | `cli` працює лише там, де встановлено Claude Code | [`AiService`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L22-L90) | C14 |
| [ADR-042](../decisions.md#adr-042-tailored-documents-versions-and-application-packages) | Чернетки ніколи не зберігаються; збережені версії незмінні й пронумеровані; пакети фіксують те, що надіслано; застосунок ніколи сам не подає форму | Більше таблиць | [`DocumentService`](../../backend/src/main/java/com/vacancytracker/application/documents/DocumentService.java#L137-L215), [`PackageService`](../../backend/src/main/java/com/vacancytracker/application/packaging/PackageService.java#L132-L160) | C14, C16 |
| [ADR-043](../decisions.md#adr-043-correspondence-job-mail-links-to-applications-status-suggestions) | Класифікація пошти на правилах, пошта ніколи не йде в AI; пропозиції потребують підтвердження користувача | Списки фраз треба підтримувати | [`MailClassifier`](../../backend/src/main/java/com/vacancytracker/application/mail/MailClassifier.java#L60-L80), [`MailLinker`](../../backend/src/main/java/com/vacancytracker/application/mail/MailLinker.java#L45-L88) | C13 |
| [ADR-044](../decisions.md#adr-044-visa-sponsorship-from-the-posting-text) | Visa sponsorship визначається з тексту вакансії; відмова перемагає пропозицію | Незвичне формулювання лишається невідомим | [`VisaSponsorship`](../../backend/src/main/java/com/vacancytracker/vacancy/VisaSponsorship.java) | C20 |
| [ADR-047](../decisions.md#adr-047-ai-presets-and-base-documents)–[ADR-049](../decisions.md#adr-049-employer-match-and-goal-match) | Пресети, снепшоти, Employer match і Goal match з оцінками, які рахує код | AI-виклики при першому відкритті вакансії | [`AnalysisService`](../../backend/src/main/java/com/vacancytracker/application/analysis/AnalysisService.java#L244-L258) | C14 |
| [ADR-050](../decisions.md#adr-050-resume-pdf-of-at-most-two-pages), [ADR-051](../decisions.md#adr-051-deterministic-fact-check-before-saving) | PDF резюме до двох сторінок; детермінована перевірка фактів перед збереженням | Перевірка фактів навмисно буквальна | [`ResumePdf`](../../backend/src/main/java/com/vacancytracker/application/documents/ResumePdf.java), [`FactCheck`](../../backend/src/main/java/com/vacancytracker/application/documents/FactCheck.java) | C14, C16 |
| [ADR-052](../decisions.md#adr-052-beacon-design-light-first)–[ADR-054](../decisions.md#adr-054-liquid-glass-over-graphite) | Дизайн Beacon, Graphite і Liquid Glass з виміряними цілями контрасту | Ручні перевірки зі скрінрідером ще не зроблені | [`contrast.test.ts`](../../frontend/src/styles/contrast.test.ts) | C18 |

---

## 5. Кейси

### C1. Модульний моноліт із примусовими межами

**Problem.** Один розробник, один користувач, один артефакт розгортання. Мікросервіси додали б операційну вартість без користі, але моноліт без меж стає кашею.

**Decision.** Один Spring Boot застосунок, розділений за bounded context на `vacancy`, `collection`, `matching`, `export`, `notification`, `application`, `web`, `common` ([ADR-001](../decisions.md#adr-001-modular-monolith)). Правила залежностей виконувані: ArchUnit працює в кожній збірці й падає на заборонену залежність, цикл, контролер поза `web` або новий пакет верхнього рівня без правила.

**Trade-off.** Немає незалежного масштабування і деплою. Прийнято: навантаження це одна людина, а межі дозволяють винести модуль пізніше без переписування. Між модулями працюють вузькі шви замість доступу навскіс: пресети потрапляють у JSON-бекап через крихітний інтерфейс [`BackupSection`](../../backend/src/main/java/com/vacancytracker/common/BackupSection.java#L12-L18) у `common`, тож `export` не залежить від `application`.

**Code.**
- Правила: [`ArchitectureTest.java`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java#L23-L66) (хелпер `onlyDependsOn`, по правилу на модуль, `nothingDependsOnWeb`, `modulesHaveNoCycles`, `webIsTheOnlyPlaceWithControllers`).
- Приклад шва: [`BackupSection`](../../backend/src/main/java/com/vacancytracker/common/BackupSection.java#L12-L18), реалізований у [`PresetBackup`](../../backend/src/main/java/com/vacancytracker/application/preset/PresetBackup.java#L26) і спожитий у [`BackupWriter`](../../backend/src/main/java/com/vacancytracker/export/BackupWriter.java#L37-L55) через `ObjectProvider<BackupSection>`.
- Кожен модуль має `package-info.java`, наприклад [`collection/package-info.java`](../../backend/src/main/java/com/vacancytracker/collection/package-info.java).

**Tests.** [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java).

**If they push: «Чому не мікросервіси?»** Бо в системи один письменник, одна БД і один деплой, а складні проблеми тут (ідемпотентний ingest, узгоджена історія статусів) *простіші* всередині однієї транзакційної межі. Правила модулів дозволяють сказати, де були б шви: `collection` очевидний перший кандидат на винесення, бо він I/O-bound і схильний до збоїв.

---

### C2. Явний SQL замість JPA і блокування рядків замість «оптимістичної магії»

**Problem.** Домен це кілька агрегатів із простими зв'язками, але операціям потрібен точний SQL: upsert через `ON CONFLICT`, JSONB, пошук `tsvector`, стабільна пагінація і читання, що блокують рядок на час read-modify-write.

**Decision.** `JdbcClient` з явним SQL, що вручну мапиться в record `VacancyState`; доменний об'єкт `Vacancy` відновлюється через `Vacancy.restore(state)` і записується назад через `toState()`. Жодного lazy loading, кешу сесії чи dirty checking, про які треба думати ([ADR-003](../decisions.md#adr-003-spring-data-jdbc-over-jpa)). Заголовок ADR каже «Spring Data JDBC», але його ж примітка про реалізацію фіксує, що репозиторій опинився на простому `JdbcClient` без інтерфейсів Spring Data (див. [розділ 8](#8-чесні-обмеження-і-що-я-зробив-би-далі)).

**Блокування рядків.** Фонова задача (ingest, inbox rule, expiry) і користувач (редагування, зміна статусу) можуть торкнутися однієї вакансії в той самий момент. Раніше задача, що прочитала рядок, могла записати назад застарілий статус або втратити нотатку. Тепер кожен read-modify-write читає рядок через `SELECT ... FOR UPDATE` усередині транзакції ([ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits)): вони серіалізуються по рядку.

**Trade-off.** Фонова задача може кілька мілісекунд чекати рядок, який зберігає користувач. Альтернатива, оновлення по колонках, уникає очікування, але відмовляється від моделі агрегата; блокування зберігає єдиний шлях запису.

**Code.**
- Читання з блокуванням: [`VacancyRepository.findByIdForUpdate`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L96-L102).
- Upsert: [`VacancyRepository.save`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L35-L82).
- Read-modify-write із блокуванням у сервісі: [`VacancyService.lockForChange`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L127-L130), використовується в [`update`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L132-L157), [`changeStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L159-L166), [`moveTo`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L173-L188).
- Той самий патерн у фонових шляхах: [`IngestService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/IngestService.java#L51-L52), [`MatchService.matchWith`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L103-L104), [`ExpiryService.expire`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L72-L80).
- Шов restore/convert: [`Vacancy.restore` / `toState`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L86-L113).

**Tests.** [`ReviewFixesTest`](../../backend/src/test/java/com/vacancytracker/web/ReviewFixesTest.java), [`InboxRuleTest`](../../backend/src/test/java/com/vacancytracker/matching/InboxRuleTest.java), [`VacancyTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VacancyTest.java); інтеграційні тести ганяють справжні міграції на Testcontainers.

**If they push: «Чому не оптимістичне блокування `@Version`?»** Оптимістичне блокування змушує переможеного повторювати або падати. Тут переможений це фонова задача, яка має просто почекати, а користувач ніколи не повинен бачити хибну помилку конфлікту. Коротке песимістичне блокування одного рядка простіше й передбачуваніше. Конкуренція мізерна (один користувач), тож блокування майже ніколи ні для кого не утримується.

---

### C3. Автоматизація ніколи не перезаписує написане користувачем

**Problem.** Одну й ту саму вакансію переписує кожен запуск збору (новий опис, зарплата, дата last seen), а користувач редагує нотатки, теги, пріоритет, статус, next action і контакт. Наївний upsert знищує роботу користувача.

**Decision.** Автоматизація може писати лише короткий білий список: `lastSeenAt`, джерела, похідну оцінку, прапорець «не бачив» і *нормалізовані* поля `description` та зарплату, причому лише якщо користувач їх не редагував. Кожне редагування такого поля користувачем додає його ім'я в `userEditedFields`; автоматичне оновлення перевіряє цю множину. Вакансія без зарплати ніколи не стирає зарплату, яку дало раніше побачення ([ADR-012](../decisions.md#adr-012-upsert-rules-automation-never-overwrites-user-data)).

**Trade-off.** Облік по кожному полю, а відредаговане поле більше не слідує за джерелом, доки користувач не скине його. У цьому й суть.

**Code.**
- Білий список і перевірка: [`Vacancy.applyAutomated`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L115-L136) та value object [`AutomatedFields`](../../backend/src/main/java/com/vacancytracker/vacancy/AutomatedFields.java).
- Позначення редагування: [`Vacancy.editDescription`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L176-L180) і `editSalary` одразу нижче.
- Єдине місце, де автоматизація може змінити *статус*: expiry (лише з *New*/*Saved*, примітка `auto-expired`, [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L72-L80)) і inbox rule (C10).
- CSV-імпорт, що змінює зарплату, теж позначає `salary` як відредаговану ([`VacancyService.importCsvState`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L205-L236)), щоб наступний запуск не скасував виправлення.

**Tests.** [`VacancyTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VacancyTest.java), [`ReviewFixesTest`](../../backend/src/test/java/com/vacancytracker/web/ReviewFixesTest.java), [`CollectionPipelineTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/CollectionPipelineTest.java).

**If they push: «Звідки ви знаєте, що список правил повний?»** Правило структурне: `applyAutomated` приймає record `AutomatedFields`, який *не може виразити* статус, нотатку, тег чи контакт. Рев'юер бачить в одному типі, чого автоматизації можна торкатися.

---

### C4. Модель статусів: таблиця, пошук шляху й історія лише на додавання

**Problem.** Заявка проходить через тринадцять статусів. Без таблиці будь-який статус можна було б виставити з будь-якого іншого; деякі події (запрошення на інтерв'ю, поки ще *Applied*, «я подався» зі *Saved*) передбачають кілька кроків.

**Decision.**
- Дозволені переходи це один `EnumMap` у самому enum, а `Vacancy.changeStatus` кидає `InvalidStatusTransitionException` для всього іншого. Термінальні стани (*Rejected*, *Withdrawn*, *Expired*, *Dismissed*, *Hidden*) можна лише відновити в *New*; з *Accepted* виходу немає ([ADR-023](../decisions.md#adr-023-status-transition-table)).
- Кожна зміна додає `StatusEvent`; історія це журнал аудиту і джерело істини для воронки та тижневих лічильників дашборду (колонку `applied_at` ніколи не заповнювали, див. [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior)).
- [`StatusPath.between`](../../backend/src/main/java/com/vacancytracker/vacancy/StatusPath.java#L23-L48) це пошук у ширину по тій самій таблиці: повертає *найкоротший ланцюжок легальних переходів*, тож складна зміна записується як звичайні кроки, а історія лишається лінійною. [`VacancyService.moveTo`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L173-L188) записує кожен крок із відступом у одну мікросекунду (роздільність часу в БД), щоб зберегти їхній порядок.
- Undo існує, але як явна окрема операція ([`Vacancy.undoStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L153-L161)), що перевіряє, що вакансія досі в очікуваному статусі.

**Trade-off.** Відновлення термінальної вакансії проходить через *New*, і скорочення «куди повернути» втрачається; натомість історія ніколи не містить неможливого переходу.

**Code.** [`VacancyStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyStatus.java#L27-L65), [`Vacancy.changeStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L139-L147), [`StatusPath`](../../backend/src/main/java/com/vacancytracker/vacancy/StatusPath.java), [`BulkStatusService`](../../backend/src/main/java/com/vacancytracker/vacancy/BulkStatusService.java#L47-L110) (масова зміна з Undo й причинами пропуску по id). Фронтенд дзеркалить таблицю в [`statuses.ts`](../../frontend/src/domain/statuses.ts#L5), а юніт-тест тримає дзеркало чесним.

**Tests.** [`VacancyStatusTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VacancyStatusTest.java), [`StatusPathTest`](../../backend/src/test/java/com/vacancytracker/vacancy/StatusPathTest.java), [`BulkStatusServiceTest`](../../backend/src/test/java/com/vacancytracker/vacancy/BulkStatusServiceTest.java), фронтенд [`statuses.test.ts`](../../frontend/src/domain/statuses.test.ts).

**If they push: «Навіщо BFS для такого малого графа?»** Бо таблиця це дані й вона змінюватиметься. Ланцюжок, написаний руками, тихо зіпсується, щойно додадуть перехід; BFS виводить шлях із таблиці, яка вже є джерелом істини.

---

### C5. Запуск збору: ізоляція, замок єдиного запуску й живий прогрес

**Problem.** П'ятнадцять із чимось джерел із різною надійністю; два одночасні запуски змагалися б за upsert і перевищили б ліміти джерел; кнопці Refresh потрібен живий зворотний зв'язок по кожному джерелу.

**Decision.**
- **Контракт адаптера**: [`SourceAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/SourceAdapter.java#L10-L19) має три методи (`id`, `serverSideFields`, `fetch`). `fetch` повертає [`FetchOutcome`](../../backend/src/main/java/com/vacancytracker/collection/adapter/FetchOutcome.java#L9-L38), record, що може нести ще й *роботу, яку виконати, коли все збережено* (`afterStored`), і *попередження про часткове читання* (`withWarning`).
- **Ізоляція**: [`runAdapter`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L178-L215) ловить `UpstreamException`, `DataAccessException` і помилки конфігурації (`IllegalArgumentException` / `IllegalStateException`) *по кожному адаптеру* і записує збій; решта адаптерів продовжують. Вакансія, яку не вдалося зберегти, рахується помилкою й не зупиняє запуск ([`store`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L224-L244)).
- **Запуск завжди завершується**: [`execute`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L79-L97) закриває запуск як `interrupted` у `finally` і публікує кінець SSE-потоку, тож баг не залишить запуск «running» назавжди; [`StaleRunSweeper`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/StaleRunSweeper.java#L32) виправляє запуски, залишені аварією, при старті.
- **Замок єдиного запуску**: [`RunCoordinator.start`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java#L54-L68) є `synchronized`, пам'ятає id активного запуску й віддає роботу в **однопотоковий executor**. Другий тригер отримує id активного запуску з `started = false` (API перетворює це на 200 проти 202 в [`RunController`](../../backend/src/main/java/com/vacancytracker/web/RunController.java#L83)).
- **Graceful shutdown**: [`RunCoordinator.shutdown`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java#L80-L94) просить пайплайн зупинитися між джерелами, чекає 30 с, потім позначає запуск `interrupted`.
- **Прогрес**: [`RunEventBus`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunEventBus.java#L43-L84) зберігає події кожного запуску й відтворює їх запізнілому підписнику перед підключенням, тож UI бачить повну історію, навіть якщо під'єднався посеред запуску; зберігає останні 20 завершених запусків.
- **Спостережуваність**: один структурований лог-рядок на джерело й один на запуск, із key-value полями в профілі `vps` ([`logSource`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L133-L155)).

**Trade-off (скажіть про це відверто).** [ADR-006](../decisions.md#adr-006-scheduling-single-run-lock-and-sse-progress) пропонував advisory lock PostgreSQL плюс внутрішньопроцесний guard. Реалізовано внутрішньопроцесний guard плюс рядок `run` у статусі `running`; примітка ADR сама каже, що advisory lock «має значення лише з більше ніж одним інстансом». Тож замок коректний для **одного інстансу**, а саме так задуманий деплой, і його довелося б змінити, перш ніж стане можливим другий інстанс.

**Tests.** [`CollectionPipelineTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/CollectionPipelineTest.java), [`ExpiryAndRunLockTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/ExpiryAndRunLockTest.java), [`RunEventBusTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/RunEventBusTest.java), [`RunControllerTest`](../../backend/src/test/java/com/vacancytracker/web/RunControllerTest.java).

**If they push: «Що, якщо процес помре посеред запуску?»** Ingest іде по одній вакансії й ідемпотентний (дедуплікація поглинає повтор), листи-сповіщення позначаються обробленими лише *після* збереження (C13), а sweeper при старті позначає осиротілі рядки `running` як interrupted. Тому запуск безпечно повторити.

---

### C6. Стійкість HTTP: зроблена малою й протестована без мережі

**Problem.** Джерела падають, тротлять і зависають. Надійному клієнту потрібні retry, ввічливість, дисципліна квот і захист від повільних або завеликих відповідей, без фреймворку, який довелося б вивчати й йому довіряти.

**Decision.** Один спільний клієнт, [`HttpFetcher`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L132-L181), з:
- **Retry з експоненційним backoff і jitter** на мережеві помилки, 429 і 5xx, до 4 спроб; `Retry-After` має пріоритет, якщо є ([`RetryPolicy.delayMillis`](../../backend/src/main/java/com/vacancytracker/collection/http/RetryPolicy.java#L30-L35); обмежений 60 с).
- **Пейсинг по хостах**: [`DomainRateLimiter.acquire`](../../backend/src/main/java/com/vacancytracker/collection/http/DomainRateLimiter.java#L21-L28) роздає часові слоти по хосту (мінімальний інтервал, за замовчуванням один запит на секунду), а не буквальний token bucket: простіше й достатньо.
- **Circuit breaker у БД**: [`SourceHealth`](../../backend/src/main/java/com/vacancytracker/collection/health/SourceHealth.java#L18-L72) відкривається після 5 послідовних збоїв на 15 хвилин; стан це рядок у `source_state`, тож переживає перезапуск і видимий у UI. `allowRequest` перевіряється перед кожним запуском адаптера.
- **Бюджет викликів у БД** для API з квотами: [`CallBudget.tryAcquire`](../../backend/src/main/java/com/vacancytracker/collection/http/CallBudget.java#L20-L36) це один атомарний `INSERT ... ON CONFLICT ... DO UPDATE ... WHERE calls < max RETURNING`, тож два потоки не можуть обидва взяти останній виклик. [`tryAcquireSpaced`](../../backend/src/main/java/com/vacancytracker/collection/http/CallBudget.java#L40-L58) серіалізує викликачів одного адаптера через `pg_advisory_xact_lock` і відлічує інтервал від реального останнього виклику, а не від кошика годинної мітки.
- **Обмежені тіла з дедлайном**: відповіді понад ліміт (за замовчуванням 25 МБ, `APP_COLLECTION_HTTP_MAX_BODY_MB`) відхиляються й не обрізаються мовчки, бо обрізаний JSON упав би пізніше як «invalid». Раніше ліміт був зашитий на 5 МБ і міг валити джерела, що віддають увесь список одразу (наприклад велика дошка Greenhouse); урок: захисний ліміт має бути налаштовуваним, а повідомлення про помилку має називати ліміт і налаштування, а **watchdog закриває потік**, коли минає дедлайн тіла, бо таймаут запиту покриває лише заголовки ([`readBounded`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L234-L268)).
- **Редиректи**: клієнт сам редиректів не слідує; fetcher слідує до трьох, **лише на той самий хост**, щоб заголовок із ключем і пейсинг лишалися з хостом, для якого призначені ([`fetch`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L132-L181)).
- **Умовні запити** (ETag / Last-Modified) лише для джерел з одним запитом ([`ConditionalCache`](../../backend/src/main/java/com/vacancytracker/collection/http/ConditionalCache.java#L20-L30)). Для multi-request джерел їх *прибрали* після того, як рев'ю знайшло реальний баг: одна дошка відповіла «not modified», поки інша змінилася, і вакансії першої дошки не оновилися, а після трьох запусків завершилися; валідатори також зберігалися до того, як пайплайн щось записав ([ADR-037](../decisions.md#adr-037-review-follow-ups-what-changed-in-collection-and-import)).
- **Квотні джерела викликаються один раз**: `getOnce` без retry, бо повтор це ще один виклик, якого збережений бюджет не враховує ([ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior)).

**Trade-off.** Власна реалізація замість Resilience4j. ADR допускав обидва варіанти; власні деталі малі (політика retry, лімітер, бюджет викликів, трекер здоров'я й кеш мають по близько 100 рядків або менше), повністю тестуються юніт-тестами з фейковим `Sleeper` і зберігаються в тій самій БД. Ціна в тому, що баги мої, і рев'ю їх знайшло й виправило.

**Tests.** [`RetryAndRateLimitTest`](../../backend/src/test/java/com/vacancytracker/collection/http/RetryAndRateLimitTest.java), [`CallBudgetTest`](../../backend/src/test/java/com/vacancytracker/collection/http/CallBudgetTest.java), [`BodyDeadlineTest`](../../backend/src/test/java/com/vacancytracker/collection/http/BodyDeadlineTest.java), [`HttpFetcherTest`](../../backend/src/test/java/com/vacancytracker/collection/http/HttpFetcherTest.java), [`SourceHealthTest`](../../backend/src/test/java/com/vacancytracker/collection/health/SourceHealthTest.java). Вони ганяються проти локального JDK `HttpServer` із фікстурами; **жоден тест не торкається справжньої мережі** ([ADR-022](../decisions.md#adr-022-testing-strategy-and-coverage-gate)).

**If they push: «Чому рев'ю прибрало ETag для деяких джерел?»** Це найкраща історія проєкту про кешування і коректність. Умовний запит відповідає «нічого не змінилося» для *одного URL*, а правило expiry міркує про те, *які вакансії бачили в запуску*. Коли за одним адаптером кілька дощок, «not modified» на одній неможливо відрізнити від «зникло» для інших. Коректність перемогла економію трафіку.

---

### C7. Expiry, якого не обдурить збій джерела

**Problem.** «Не бачили N запусків означає, що вакансії немає» хибне щоразу, коли проблема в джерелі, а не в вакансії.

**Decision.** Вакансія в *New* або *Saved* завершується лише після **3 чистих запусків**, у яких джерело, що її перелічувало, завершилося без помилки й не повернуло її ([запит `CANDIDATES`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L34-L46); N це `app.collection.expiry-runs`). Наслідки, закодовані в SQL і коді:
- Збійне джерело не рахується як «не бачили» (`s.error_message IS NULL`), тож збій ніколи нічого не завершує ([ADR-014](../decisions.md#adr-014-expiry-rule)).
- **Часткове читання** не є чистим: [`FetchOutcome.withWarning`](../../backend/src/main/java/com/vacancytracker/collection/adapter/FetchOutcome.java#L35-L38) зберігає прочитані вакансії, але записує проблему як повідомлення про помилку.
- **Віконні джерела** (Remotive, Jobicy, Arbeitnow, Himalayas, Adzuna, RSS, HTML, Remote OK, Working Nomads, Telegram forward) показують лише частину відкритого, а поштові джерела перелічують лист один раз, тож їхні вакансії виключені з expiry й закриваються вручну ([`WINDOWED_SOURCES`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L31-L32), `adapter NOT LIKE 'email-%'`). Завершуються лише ATS-джерела з повною дошкою (Greenhouse, Lever, Ashby).
- `304 Not Modified` зараховує всі раніше бачені вакансії цього джерела як побачені ([`touchAdapter`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L189-L191)).
- Кожне завершення це окрема транзакція з блокуванням рядка й повторною перевіркою статусу перед записом ([`expire`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L72-L80)).

**Trade-off.** Вакансія з віконного джерела лишається *New*, доки користувач її не закриє. Застаріла картка дратує; хибно завершена жива вакансія це втрачена можливість.

**Tests.** [`ExpiryAndRunLockTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/ExpiryAndRunLockTest.java), [`CollectionPipelineTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/CollectionPipelineTest.java).

---

### C8. Дедуплікація і як рев'ю зробило її менш «розумною»

**Problem.** Та сама вакансія з'являється на кількох дошках із різними URL.

**Decision.** Три впорядковані правила, перше співпадіння виграє ([`Deduplicator.match`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L34-L48)): канонічний URL, потім `(adapter, externalId)`, потім fuzzy-правило в межах тієї самої компанії. Дублікат додає рядок `vacancy_source` до наявної вакансії замість нової, тож вакансія пам'ятає всі свої джерела ([`IngestService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/IngestService.java#L51-L72)). Канонізація URL прибирає параметри трекінгу, приводить хост до нижнього регістру й відкидає фрагменти та кінцеві слеші ([`CanonicalUrl`](../../backend/src/main/java/com/vacancytracker/common/CanonicalUrl.java#L41-L80)); унікальний індекс `vacancy_canonical_url_uk` (у [`V1__baseline.sql`](../../backend/src/main/resources/db/migration/V1__baseline.sql)) забезпечує «щонайбільше одна вакансія на канонічний URL» у БД, а не лише в коді.

**Що змінило рев'ю.** Перша версія використовувала Jaro-Winkler на всьому ключі `title | location` із порогом 0.9. Вона оцінила «Software Engineer | Berlin» проти «... | London» у 0.94, а «Engineer I» проти «Engineer II» у 0.97, тож різні вакансії зливалися, і друга ніколи не з'являлася. Тепер fuzzy-правило вимагає **сумісних місць** ([`sameLocation`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L72-L75)) та заголовків, які **рівні після нормалізації або рівні за винятком одного слова з друкарською помилкою** (6+ літер, без цифр, не більше двох правок; [`titleScore`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L82-L103)). Воно також пропускає кандидата, якого *той самий адаптер* уже перелічує під іншим id: на одній дошці різні id це різні вакансії ([рядок 60](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L59-L62)).

**Trade-off.** Консервативне правило пропустить деякі справжні дублікати (переформульований заголовок). Для трекера вакансій показати дублікат дешевше, ніж сховати іншу роботу.

**Tests.** [`DeduplicatorTest`](../../backend/src/test/java/com/vacancytracker/collection/dedup/DeduplicatorTest.java), [`CanonicalUrlTest`](../../backend/src/test/java/com/vacancytracker/common/CanonicalUrlTest.java), [`NormalizerTest`](../../backend/src/test/java/com/vacancytracker/collection/normalize/NormalizerTest.java).

**If they push: «Як би ви робили це в масштабі?»** Блокувати за ключем (компанія), порівнювати всередині блоку (що код і робить із лімітом 200 кандидатів на пошук), а до MinHash/LSH або триграмного індексу переходити, якщо блоки виростуть. На тисячах рядків проста версія правильна.

---

### C9. Пояснюваний rule engine, одна модель для оцінювання і фільтрації

**Problem.** Інструмент не повинен містити доменних даних (ролей, технологій, країн, порогів), але має ранжувати вакансії так, щоб користувач міг довіряти й налагоджувати.

**Decision.** Користувач пише **критерії**: поле, оператор, значення, *режим* (`require`, `prefer`, `avoid`, `exclude`), вагу і `missingValuePolicy` (`ignore`, `penalize`, `hide`).
- [`RuleEngine`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L54-L63) оцінює кожен критерій на мапі **фактів** ([`VacancyFacts.of`](../../backend/src/main/java/com/vacancytracker/matching/VacancyFacts.java#L25)) у тризначний вердикт `SATISFIED`, `NOT_SATISFIED` або `MISSING` ([`verdict`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L102-L121)), а потім виводить ефект ([`effect`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L85-L100)). Тризначна логіка це головне: «у вакансії не вказана зарплата» *не* те саме, що «зарплата замала», і політика вирішує, що з цим робити ([ADR-025](../decisions.md#adr-025-missingvaluepolicy-penalize-means-a-weight-penalty)).
- [`Scorer`](../../backend/src/main/java/com/vacancytracker/matching/Scorer.java#L38-L89) перетворює ефекти на `score = clamp(round(100 × (positive − negative + bonus + absorb) / (maxPositive + absorb + bonus)), 0, 100)`. Він використовує **цілочисельну арифметику** (`(200 × n + d) / (2 × d)`, рядок 87), тож округлення точне. Відфільтрована вакансія має *відсутню оцінку*, а кожен бал і кожен фільтр має зрозумілу людині [`MatchReason`](../../backend/src/main/java/com/vacancytracker/matching/MatchReason.java). Властивість, яку варто назвати: додавання виконаного критерію `prefer` ніколи не знижує оцінку.
- **Списки компаній**: компанія з blocklist відфільтровується й перемагає будь-який allow; компанія з allowlist отримує бонус і не ховається за низьку оцінку, але **не** перекриває власні правила `require`/`exclude` користувача ([ADR-028](../decisions.md#adr-028-company-allowlist-and-blocklist), [`CompanyLists`](../../backend/src/main/java/com/vacancytracker/matching/CompanyLists.java)).
- **Зарплата** порівнюється як річна gross у валюті *критерію* за кешованими курсами ECB; невідома валюта чи період це «missing», ніколи не здогадка ([`RateSalaryConverter`](../../backend/src/main/java/com/vacancytracker/matching/rates/RateSalaryConverter.java), [`ExchangeRateService`](../../backend/src/main/java/com/vacancytracker/matching/rates/ExchangeRateService.java), [ADR-011](../decisions.md#adr-011-salary-normalization-and-exchange-rate-source)).
- **Одна модель, два обчислювачі.** *Той самий* `Criterion` компілюється також у параметризований SQL `WHERE` для фільтрів Inbox/Pipeline і збережених виглядів ([`CriterionCompiler.compile`](../../backend/src/main/java/com/vacancytracker/matching/CriterionCompiler.java#L53-L83)). Значення завжди біндяться параметрами, ніколи не конкатенуються, а `NULL` перетворюється на `FALSE` через coalesce, тож невідомі дані мовчки не задовольняють фільтр ([ADR-009](../decisions.md#adr-009-one-criterion-model-for-profiles-and-view-filters)). Повернутий [`SqlFragment`](../../backend/src/main/java/com/vacancytracker/common/SqlFragment.java#L9) несе SQL і параметри разом.
- **Валідація перед обчисленням**: [`ProfileValidator`](../../backend/src/main/java/com/vacancytracker/matching/ProfileValidator.java#L117) перевіряє поля, оператори (за видом поля) і форми значень; імпорт профілю *строгий* (невідомий ключ будь-де відхиляється з його шляхом), а YAML читає парсер, що не інстанціює класи з тегів ([ADR-027](../decisions.md#adr-027-strict-profile-import-and-company-lists), [`ProfileTransfer`](../../backend/src/main/java/com/vacancytracker/web/ProfileTransfer.java)).

**Trade-off.** Якість матчингу цілком залежить від правил користувача (ціна «без доменних даних у коді»). Пом'якшення це зворотний зв'язок: live-прев'ю показує, що зробив би чернетковий профіль ([`ProfilePreviewService`](../../backend/src/main/java/com/vacancytracker/matching/ProfilePreviewService.java)), а wizard будує перший профіль.

**Tests.** [`RuleEngineTest`](../../backend/src/test/java/com/vacancytracker/matching/RuleEngineTest.java) (кожен оператор і режим, політики missing-value), [`ScorerTest`](../../backend/src/test/java/com/vacancytracker/matching/ScorerTest.java), [`CriterionCompilerTest`](../../backend/src/test/java/com/vacancytracker/matching/CriterionCompilerTest.java), [`CompanyListsTest`](../../backend/src/test/java/com/vacancytracker/matching/CompanyListsTest.java), [`RateSalaryConverterTest`](../../backend/src/test/java/com/vacancytracker/matching/rates/RateSalaryConverterTest.java), [`ProfileValidatorTest`](../../backend/src/test/java/com/vacancytracker/matching/ProfileValidatorTest.java).

**If they push: «Чому не LLM для оцінювання?»** Недетермінізм, вартість, затримка й відправка даних вакансій третій стороні; проєкт обіцяє, що з машини нічого не йде, окрім запитів до джерел ([ADR-008](../decisions.md#adr-008-rule-based-scoring-in-the-mvp-llm-scoring-optional-later)). Де LLM *таки* використовується (C14), він лише пропонує, а застосунок рахує й перевіряє.

---

### C10. Версії профілів, фоновий перерахунок і межі автоматичного приховування

**Problem.** Зміна профілю має перерахувати все, не змагатися із запущеним збором і ніколи не перекривати рішення користувача.

**Decision.**
- **Версії**: кожне збереження, включно з відновленням, вставляє незмінний снепшот; кожен `VacancyMatch` зберігає `profileVersion`, що його породила, тож оцінку можна простежити до правил ([ADR-010](../decisions.md#adr-010-profile-versioning-keep-every-previous-version)). Відхилене збереження нічого не змінює: валідація йде першою, а все збереження це одна транзакція ([`SearchProfileService`](../../backend/src/main/java/com/vacancytracker/matching/SearchProfileService.java#L14-L60)).
- **Лічильник generation**: після кожної *закоміченої* зміни профілю `AtomicLong` збільшується в колбеку `afterCommit`; довгий [`MatchSession`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L67-L87) порівнює його на кожній вакансії й перезавантажує профілі, тож запуск не перепише оцінки зі свіжого перерахунку оцінками зі старої версії профілю.
- **Перерахунок** це одна фонова задача з прогресом, який UI опитує ([`RecalculationService`](../../backend/src/main/java/com/vacancytracker/matching/RecalculationService.java#L60-L118)).
- **Inbox rule** ([`MatchService.applyInboxRule`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L128-L164)) може перемістити вакансію між *New* і *Hidden*, але лише якщо виконується **все**: статус *New* або *Hidden*; у вакансії є щонайменше одне джерело (додану вручну ніколи не чіпають); уся її історія складається з автоматичних подій (примітка починається з `auto-`); і жоден застосовний профіль її не приймає. Одна дія користувача в історії назавжди виводить вакансію з-під впливу автоматизації ([ADR-026](../decisions.md#adr-026-automatic-inbox-hiding-and-its-limits), [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior), [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits)).

**Tests.** [`InboxRuleTest`](../../backend/src/test/java/com/vacancytracker/matching/InboxRuleTest.java), [`RecalculationServiceTest`](../../backend/src/test/java/com/vacancytracker/matching/RecalculationServiceTest.java), [`RecalculationIntegrationTest`](../../backend/src/test/java/com/vacancytracker/matching/RecalculationIntegrationTest.java), [`SearchProfileServiceTest`](../../backend/src/test/java/com/vacancytracker/matching/SearchProfileServiceTest.java).

---

### C11. Повнотекстовий пошук без пошукового сервісу

**Problem.** Пошук за назвою, компанією й описом плюс палітра Ctrl/Cmd+K для кількох тисяч рядків.

**Decision.** **Згенерована колонка `tsvector` із GIN-індексом** у БД ([`V1__baseline.sql`](../../backend/src/main/resources/db/migration/V1__baseline.sql)), конфігурація `simple` (нейтральна до мови, без стемінгу), ранжування `ts_rank` плюс бусти за збіг у назві (+1.0) чи компанії (+0.5), сніпети через `ts_headline` ([`VacancySearchService.search`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancySearchService.java#L28-L56)). Ввід користувача перетворюється в [`SearchQuery.toTsQuery`](../../backend/src/main/java/com/vacancytracker/vacancy/SearchQuery.java#L27) так, що **виживають лише літери й цифри**: жоден ввід не може впровадити синтаксис tsquery. Кожне слово це префіксний збіг, усі мають збігтися, до 8 термінів по 40 символів; термін із крапкою на кшталт `node.js` також збігається як цілий токен.

**Trade-off і чесна примітка.** Немає стемінгу («developer» не знаходить «developers»), а пошук `c#` збігається за префіксом `c` (визнано «still not changed» в [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior)). [ADR-015](../decisions.md#adr-015-search-postgresql-full-text-search) каже, що запит використовує `websearch_to_tsquery`; код натомість будує власний санітизований префіксний запит для `to_tsquery`, тож текст ADR і код тут розходяться. Скажіть про це, якщо спитають; правда в коді.

**Tests.** [`SearchQueryTest`](../../backend/src/test/java/com/vacancytracker/vacancy/SearchQueryTest.java), [`VacancySearchServiceTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VacancySearchServiceTest.java).

---

### C12. Безпека для застосунку, що працює на `localhost`

У local-first застосунку незвична модель загроз: у профілі за замовчуванням *немає логіну*, тож зловмисник це **веб-сторінка, відкрита в браузері самого користувача**.

| Загроза | Захист | Код |
|---|---|---|
| **DNS rebinding**: ворожа сторінка перерезолвить своє ім'я на `127.0.0.1` і стає same-origin з локальним API. Перевірка `Origin` не допоможе, бо `Origin` і `Host` обидва несуть ім'я зловмисника | `HostFilter` відповідає на кожен запит, на будь-якому шляху, лише для loopback-імен та імен із `APP_ALLOWED_HOSTS` (домен VPS); перевірка навмисно не обмежена `/api/`, щоб жоден запис шляху її не обійшов | [`HostFilter`](../../backend/src/main/java/com/vacancytracker/web/HostFilter.java#L25-L70), [`HostFilterTest`](../../backend/src/test/java/com/vacancytracker/web/HostFilterTest.java) |
| **Cross-site записи**: сторінка шле POST на локальний API | `OriginFilter` відмовляє на запит зміни стану, чий хост `Origin` відрізняється від `Host` | [`OriginFilter`](../../backend/src/main/java/com/vacancytracker/web/OriginFilter.java#L22-L50), [`OriginFilterTest`](../../backend/src/test/java/com/vacancytracker/web/OriginFilterTest.java) |
| **Підбір токена** (профіль VPS) | Токен порівнюється як SHA-256 дайджести через `MessageDigest.isEqual` (постійний час); застосунок не стартує з токеном коротшим за 16 символів; після 5 хибних токенів з однієї адреси за 10 хвилин адреса отримує 429 з `Retry-After`, і *навіть правильний токен відхиляється, поки адресу заблоковано*, тож підбір не вкладеться під ліміт; запит узагалі без токена не рахується як спроба | [`AccessToken`](../../backend/src/main/java/com/vacancytracker/web/auth/AccessToken.java#L8-L25), [`AuthFilter`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthFilter.java#L33-L60), [`FailureLimiter`](../../backend/src/main/java/com/vacancytracker/web/auth/FailureLimiter.java#L18-L60) |
| **Обхід автентифікації трюками зі шляхом** | Фільтр вирішує за шляхом, за яким *маршрутизує* Spring (`UrlPathHelper`: декодований, із розкритими dot-сегментами, без `;params`), а не за сирим URI, бо `/api;x=1/vacancies` маршрутизується на `/api/vacancies`, але не починається з `/api/` | [`AuthFilter.shouldNotFilter`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthFilter.java#L33-L40) |
| **Крадіжка cookie / CSRF** | `HttpOnly`, `SameSite=Strict`, `Secure` при HTTPS (напряму або через `X-Forwarded-Proto`); токен у query string ніколи не приймається | [`AuthController.cookie`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthController.java#L86-L90) |
| **SSRF в «імпорті за URL»** | Лише http(s); loopback, link-local, site-local, multicast, carrier-grade NAT і unique-local адреси відхиляються для адреси *і кожного редиректу*; для власника є перемикач `allow-private-hosts` | [`UrlFetcher.requireAllowed` / `isInternal`](../../backend/src/main/java/com/vacancytracker/collection/UrlFetcher.java#L97-L125), [`ImportUrlTest`](../../backend/src/test/java/com/vacancytracker/web/ImportUrlTest.java) |
| **Посилання `javascript:` / `data:` з джерела** | Зберігаються лише http(s)-посилання з хостом ([`CanonicalUrl.webLink`](../../backend/src/main/java/com/vacancytracker/common/CanonicalUrl.java#L27-L40)); UI також відкриває лише http(s) ([`safeLink`](../../frontend/src/domain/links.ts#L5)) для рядків, збережених до правила | [`CanonicalUrlTest`](../../backend/src/test/java/com/vacancytracker/common/CanonicalUrlTest.java), [`links.test.ts`](../../frontend/src/domain/links.test.ts) |
| **XSS і clickjacking** | Строгий CSP (`default-src 'self'`, `object-src 'none'`, `frame-ancestors 'none'`), `X-Frame-Options`, `nosniff`, `Referrer-Policy: no-referrer`; у UI немає `dangerouslySetInnerHTML`; виправлено алерт code scanning про DOM-текст | [`SecurityHeadersFilter`](../../backend/src/main/java/com/vacancytracker/web/SecurityHeadersFilter.java#L22-L48), [`SecurityHeadersFilterTest`](../../backend/src/test/java/com/vacancytracker/web/SecurityHeadersFilterTest.java) |
| **XXE у стрічках і сторінках** | Декларації DOCTYPE відхиляються в feed-адаптері | [`RssAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/rss/RssAdapter.java#L29) |
| **Секрети в логах** | Два шари: відомі форми (ключі в query string, значення bearer/basic, URL Telegram-бота, session cookie) *і* кожне значення секрету з конфігурації як точний текст; властивості з іменами `*password`, `*secret`, `*token`, `*key` реєструються самі при старті, тож нове налаштування покрите без коду; повідомлення, що зберігаються чи показуються (не лише логуються), маскуються біля джерела | [`LogMasking`](../../backend/src/main/java/com/vacancytracker/common/LogMasking.java#L15-L60), [`SecretRegistration`](../../backend/src/main/java/com/vacancytracker/common/logging/SecretRegistration.java), [`LogMaskingTest`](../../backend/src/test/java/com/vacancytracker/common/LogMaskingTest.java), [`LogOutputTest`](../../backend/src/test/java/com/vacancytracker/common/logging/LogOutputTest.java) |
| **Загартування контейнерів** | `cap_drop: [ALL]`, `no-new-privileges`, ліміти пам'яті, застосунок прив'язаний до `127.0.0.1`; на VPS назовні лише Caddy | [`docker-compose.yml`](../../docker-compose.yml), [`docker-compose.vps.yml`](../../docker-compose.vps.yml) |

**Відомі межі (описані в [ADR-033](../decisions.md#adr-033-access-token-how-adr-017-is-implemented) і [ADR-034](../decisions.md#adr-034-log-masking-and-structured-logs)).** Cookie *і є* токеном, тож хто може прочитати профіль браузера чи лог проксі, що записує cookie, має токен; таблиця збоїв у пам'яті (перезапуск її очищає) і по адресі, тож зловмисник із багатьма адресами сповільнений, а не зупинений (звідси мінімум 16 символів); секрет, що не має форми відомих шаблонів і не лежить у властивості в стилі `*key`, не маскується; ім'я, що змінює DNS-відповідь між SSRF-перевіркою й запитом, не покрите.

**If they push: «Як ви знайшли дірку з DNS rebinding?»** Незалежне рев'ю ([code-review.md](../code-review.md), знахідка F1, critical): вважали, що перевірки Origin достатньо. Урок, зафіксований у [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits): перевірка same-origin настільки сильна, наскільки гарантовано, що *origin ваш*, а це має доводити й заголовок Host.

---

### C13. Відстеження пошти: лише читання, ідемпотентність, правила

**Problem.** Дві задачі з поштової скриньки користувача: (а) листи-*сповіщення* від LinkedIn, Jobs.cz, StartupJobs і Indeed як джерело, що не скрейпить ці сайти, і (б) *відповіді* на заявки (підтвердження, відмови, запрошення на інтерв'ю, офери), прив'язані до вакансії.

**Рішення.**
- **Рідер не може зашкодити скриньці.** За замовчуванням відкриває папки лише для читання, бере листи через `peek` (навіть не ставить прапорець «прочитано»), ніколи не видаляє, не переміщує й не надсилає, ніколи не читає вкладення, просить сервер фільтрувати за датою, тож старі листи навіть не перелічуються, обмежує тіла й кількість листів за читання та ніколи не логує пароль ([`ImapMailReader`](../../backend/src/main/java/com/vacancytracker/collection/mail/ImapMailReader.java#L132-L230), [ADR-029](../decisions.md#adr-029-imap-reader-read-only-processed-state-in-the-database)).
- **«Оброблено» живе в БД** (таблиця `processed_mail`, ключ Message-ID або папка + UID validity + UID), а не в прапорцях скриньки, тож працює на серверах, що не вміють зберігати прапорці, і коли лист скопійовано. Обробник відповідає `PROCESSED` або `RETRY` ([`MailHandler`](../../backend/src/main/java/com/vacancytracker/collection/mail/MailHandler.java#L9-L13)); запам'ятовується лише `PROCESSED`.
- **At-least-once доставка, зроблена ідемпотентною.** Лист позначається обробленим лише *після* того, як його вакансії збережено: адаптер повертає колбек `FetchOutcome.afterStored(...)`, який пайплайн виконує, коли кожну вакансію записано без помилки сховища ([`CollectionPipeline.runAdapter`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L197-L204)). Якщо збереження не вдалося, той самий лист пропонується знову, а дедуплікація поглинає повтор. Той самий патерн для Telegram update offset ([`TelegramForwardAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/telegram/TelegramForwardAdapter.java#L81-L94)).
- **Один парсер на відправника**, що читає лише вміст листа: ніколи не відкриває посилання, не логіниться й переписує посилання на вакансії в чистий вигляд. Парсер, що прийняв лист, але не знайшов вакансій, логує попередження (змінений макет інакше повторювався б вічно) ([`LinkedInAlertParser`](../../backend/src/main/java/com/vacancytracker/collection/mail/LinkedInAlertParser.java), [`EmailAlertAdapter`](../../backend/src/main/java/com/vacancytracker/collection/mail/EmailAlertAdapter.java#L20), [ADR-030](../decisions.md#adr-030-email-sources-one-parser-per-sender-no-expiry)).
- **Класифікація на правилах, п'ятьма мовами, у навмисному порядку**: спершу тема-сповіщення (сповіщення часто містять слова на кшталт «interview»), потім відмова перед усім іншим («thank you for applying ... unfortunately» це відмова) ([`MailClassifier.classify`](../../backend/src/main/java/com/vacancytracker/application/mail/MailClassifier.java#L60-L80)). **Пошта ніколи не йде в AI**, а зберігається лише *робоча* пошта (відправник, тема, дата й перші 600 символів), решта пропускається ([ADR-043](../decisions.md#adr-043-correspondence-job-mail-links-to-applications-status-suggestions)).
- **Зв'язування це прозорий бал, а не здогад.** [`MailLinker.link`](../../backend/src/main/java/com/vacancytracker/application/mail/MailLinker.java#L45-L88): назва компанії в темі чи відправнику +4 (або в тілі +3), домен відправника збігається з компанією чи її вакансією +3 (домен job-борду не рахується, бо один відправник пише про багато вакансій), назва вакансії в листі +2, +1 якщо користувач подавався; потрібен поріг 3, а рядок-доказ зберігається разом зі зв'язком. Інакше лист лишається незв'язаним для користувача.
- **Пропонувати, а не діяти.** Відмова пропонує *Rejected*, інтерв'ю пропонує *Interview* і так далі; нічого не змінюється, поки користувач не прийме, а прийняття проходить легальним шляхом через [`StatusPath`](../../backend/src/main/java/com/vacancytracker/vacancy/StatusPath.java#L23-L48).
- **Compliance за дизайном** ([ADR-007](../decisions.md#adr-007-source-policy-no-direct-scraping-of-linkedin-or-other-forbidden-sources), [ADR-045](../decisions.md#adr-045-new-sources-from-the-owners-list)): без скрейпінгу заборонених сайтів; Telegram читається лише через *власного бота* власника з пересланими постами (що дозволяють умови для ботів Telegram), ніколи читанням каналів; групи Facebook і LinkedIn заблоковані; generic HTML-адаптер вимагає *записаної* перевірки умов і дотримується `robots.txt` за RFC 9309 (5xx рахується збоєм, заборонена сторінка ніколи не запитується) ([`HtmlSiteAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/html/HtmlSiteAdapter.java#L100-L135), [`RobotsRules`](../../backend/src/main/java/com/vacancytracker/collection/adapter/html/RobotsRules.java)). [sources.md](../sources.md) записує перевірку умов для кожного джерела.

**Tests.** [`ImapMailReaderTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/ImapMailReaderTest.java), [`ProcessedMailRepositoryTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/ProcessedMailRepositoryTest.java), [`SenderParsersTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/SenderParsersTest.java) і [`LinkedInAlertParserTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/LinkedInAlertParserTest.java) із фікстурами, схожими на справжні, у [`test/resources/mail`](../../backend/src/test/resources/mail), [`MailClassifierTest`](../../backend/src/test/java/com/vacancytracker/application/mail/MailClassifierTest.java), [`MailLinkerTest`](../../backend/src/test/java/com/vacancytracker/application/mail/MailLinkerTest.java), [`CorrespondenceSyncTest`](../../backend/src/test/java/com/vacancytracker/application/mail/CorrespondenceSyncTest.java), [`HtmlSiteAdapterTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/html/HtmlSiteAdapterTest.java), [`RobotsRulesTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/html/RobotsRulesTest.java).

**If they push: «Чому листи-сповіщення, а не скрейпінг LinkedIn?»** Умови це забороняють, це застосовували в суді, і воно крихке. Сповіщення, які користувач і так отримує, дають покриття без юридичного ризику. Це ще й тримає дизайн чесним: інструмент *ніколи не логіниться* на job-сайт.

---

### C14. AI на короткому повідку

**Problem.** Власник хоче, щоб резюме й супровідні листи адаптував Claude, але без прихованого потоку даних, без вигаданих фактів і без оцінки, яку модель може завищити.

**Рішення.**
1. **Вимкнено за замовчуванням, і два бар'єри.** [`AiService`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L22-L90) це *єдині двері*. Виклик відхиляється (HTTP 409) **до того, як щось залишить машину**, якщо провайдер не налаштований *і* власник не ввімкнув AI у Settings після прочитання точного повідомлення про те, що надсилається. Згода зберігається на провайдера (`ai_consent`), тож перехід з `cli` на `api` питає знову, а її можна відкликати будь-коли ([`complete`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L80-L88), [`consent`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L60-L71)). Текст повідомлення це константа поруч із кодом ([`NOTICE`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L28-L33)), повторена в README.
2. **CLI-провайдер у пісочниці.** Реалізація `cli` запускає офіційний `claude` у print-режимі **без інструментів, без MCP-серверів, без slash-команд, без збереженої сесії, налаштування лише з порожньої тимчасової папки**, промпт у stdin, а **`ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` прибрано з середовища дочірнього процесу**, тож платить підписка, а застосунок ніколи не бачить і не передає токен ([`commandLine`](../../backend/src/main/java/com/vacancytracker/application/ai/ClaudeCliClient.java#L58-L66), [`complete`](../../backend/src/main/java/com/vacancytracker/application/ai/ClaudeCliClient.java#L69-L112)). Таймаут вбиває процес; тимчасова папка видаляється у `finally`. Провайдер `api` використовує офіційний SDK; відмова повідомляється, ніколи не показується як документ ([`AnthropicApiClient`](../../backend/src/main/java/com/vacancytracker/application/ai/AnthropicApiClient.java#L28-L95)). Провайдер `fake` відповідає з самого промпта для тестів і end-to-end набору ([`FakeAiClient`](../../backend/src/main/java/com/vacancytracker/application/ai/FakeAiClient.java#L25)).
3. **Гігієна проти prompt injection.** Правила живуть у системному промпті; дані в блоках із тегами; текст вакансії приходить з інтернету й позначений як дані. Усе в будь-яких даних, що *виглядає як один із наших тегів*, переписується на `[tag]`, щоб вакансія не могла закрити власний блок і додати завдання ([`Prompts.neutralise` / `block`](../../backend/src/main/java/com/vacancytracker/application/documents/Prompts.java#L94-L100), збирається в [`Prompts.adapt`](../../backend/src/main/java/com/vacancytracker/application/documents/Prompts.java#L37-L57)); [`PresetGenerationApiTest`](../../backend/src/test/java/com/vacancytracker/web/PresetGenerationApiTest.java#L93-L116) надсилає вакансію з `</vacancy>` і «Ignore all previous instructions» й перевіряє, що блок закривається рівно один раз, а текст приходить як `[vacancy]`.
4. **Модель пропонує, рішення приймає код.**
   - **Employer match**: модель пропонує статус і цитату на кожну вимогу; сервер потім **перевіряє, що кожна цитата буквально є в документі** (з точністю до пробілів); цитата, якої немає, робить вимогу *missing*; цитата, знайдена лише в секції Skills, обмежує її *partial* ([`QuoteCheck.place`](../../backend/src/main/java/com/vacancytracker/application/analysis/QuoteCheck.java#L30-L78), [`AnalysisService.check`](../../backend/src/main/java/com/vacancytracker/application/analysis/AnalysisService.java#L244-L258)). Сама оцінка рахується в коді: `round(100 × Σ(weight × status) / Σ(weight))` з вагами 3/2/1 і статусами 1/0.5/0 ([`EmployerMatch.score`](../../backend/src/main/java/com/vacancytracker/application/analysis/EmployerMatch.java#L20-L34)). «Модель, що цитує недбало, втрачає бали, а не здобуває».
   - **Кешування за хешем вмісту**: результати чекліста, match і goal кешуються за MD5 тексту, який вони читали (відбиток вмісту, не криптографічне використання), тож незмінний текст завжди має ту саму оцінку, а адаптований текст оцінюється за *тим самим* чеклістом, що й базовий ([`AnalysisService.hash`](../../backend/src/main/java/com/vacancytracker/application/analysis/AnalysisService.java#L283-L296)).
   - **Fact check перед збереженням**: [`FactCheck`](../../backend/src/main/java/com/vacancytracker/application/documents/FactCheck.java#L19-L50) порівнює згенерований текст з *базовими документами* й повідомляє числа, дати, роботодавців, посади й технології, яких у базі немає. Збереження чернетки з результатами відхиляється (422), якщо користувач не підтвердить *саме ці знахідки*; нова знахідка потребує нового підтвердження ([ADR-051](../decisions.md#adr-051-deterministic-fact-check-before-saving)). Перевірка навмисно буквальна, тобто зсув у бік правди.
5. **Незмінна провенанс-історія.** Згенерований текст це *чернетка*, яку ніколи не зберігають. Кожна чернетка зберігає `generation_snapshot` (пресет, guardrail, ціль, ваги, промпт, базова чернетка); збережена версія вказує на нього й ніколи не змінюється, тож завжди відомо, з чого зроблено надісланий документ, навіть якщо пресет потім змінився ([`GenerationSnapshot`](../../backend/src/main/java/com/vacancytracker/application/documents/GenerationSnapshot.java#L14), [`DocumentService.save`](../../backend/src/main/java/com/vacancytracker/application/documents/DocumentService.java#L185-L214), [ADR-048](../decisions.md#adr-048-generation-from-the-base-draft-with-a-snapshot)). Guardrail не є полем пресета: він читається з ресурсів і не редагується ([`PresetDefaults`](../../backend/src/main/java/com/vacancytracker/application/preset/PresetDefaults.java#L13), [`presets/default/guardrail.md`](../../backend/src/main/resources/presets/default/guardrail.md)).
6. **Подає людина.** Пакет заявки фіксує, які версії йдуть із заявкою; ZIP містить документи й чекліст; застосунок відкриває сторінку в новій вкладці й **ніколи не заповнює й не подає форму**. «I submitted» переводить вакансію в *Applied* через `StatusPath` з пакетом і версіями в примітці історії ([`PackageService.markSubmitted`](../../backend/src/main/java/com/vacancytracker/application/packaging/PackageService.java#L132-L160), [ADR-042](../decisions.md#adr-042-tailored-documents-versions-and-application-packages)).

**Trade-off.** Більше рухомих частин, ніж «викликати API і показати текст», а перше відкриття вакансії з увімкненим AI коштує до чотирьох викликів (чекліст, goal, базове резюме, базовий супровідний лист), після чого жодного, поки тексти незмінні ([ADR-049](../decisions.md#adr-049-employer-match-and-goal-match)).

**Tests.** [`ClaudeCliClientTest`](../../backend/src/test/java/com/vacancytracker/application/ai/ClaudeCliClientTest.java) (з фейковим головним класом `claude`, [`FakeClaudeMain`](../../backend/src/test/java/com/vacancytracker/application/ai/FakeClaudeMain.java)), [`AnthropicApiClientTest`](../../backend/src/test/java/com/vacancytracker/application/ai/AnthropicApiClientTest.java), [`QuoteCheckTest`](../../backend/src/test/java/com/vacancytracker/application/analysis/QuoteCheckTest.java), [`FactCheckTest`](../../backend/src/test/java/com/vacancytracker/application/documents/FactCheckTest.java), [`AiDocumentsApiTest`](../../backend/src/test/java/com/vacancytracker/web/AiDocumentsApiTest.java), [`FactCheckApiTest`](../../backend/src/test/java/com/vacancytracker/web/FactCheckApiTest.java) і браузерний тест [`full-cycle.spec.ts`](../../e2e/tests/full-cycle.spec.ts) (лише fake AI).

**If they push: «Як ви не даєте моделі вигадати навичку?»** Я не намагаюся це *запобігти*, я роблю це *видимим і дорогим*: guardrail велить використовувати лише базові документи; потім детермінована перевірка перелічує кожне число, дату, роботодавця, посаду чи технологію, яких немає в тих документах, і збереження чекає на користувача. Оцінку не можна завищити, бо вона рахується з цитат, які сервер перевірив.

---

### C15. Детермінована оцінка fit резюме

**Problem.** Користувач хоче бачити, як вакансія співвідноситься з його резюме, швидко, для кожного рядка списку, без AI-виклику.

**Decision.** [`ResumeFitScorer.score`](../../backend/src/main/java/com/vacancytracker/application/fit/ResumeFitScorer.java#L31-L93) це чиста функція: вакансія «просить» свої skill-подібні терміни плюс кожне ключове слово резюме, яке в ній згадано; `coverage = matched / asked`; `score = 85 × coverage`, `+15` якщо ключове слово резюме є в назві, `−15` якщо вакансія просить більше років, ніж показує резюме, обмежено 0..100, із причинами для збігів, відсутніх навичок, назви, років і тонкого тексту. Ключові слова *пропонуються* з резюме (заголовок «Skills», потім форми термінів на кшталт `C#`, `Node.js`, `CI/CD`), а **користувач редагує цей список**, тож вбудованого словника навичок немає ([ADR-040](../decisions.md#adr-040-resume-and-the-fit-score), [`Terms`](../../backend/src/main/java/com/vacancytracker/application/resume/Terms.java#L24)). Формат файлу визначається за байтами (`%PDF`, `PK`), а не за заявою браузера ([`ResumeTextExtractor`](../../backend/src/main/java/com/vacancytracker/application/resume/ResumeTextExtractor.java#L76-L78)).

**Застарілість без фреймворку задач.** Fit застарілий, коли резюме (або його ключові слова) змінилося, або коли текст вакансії змінився після оцінювання. Кожен fit зберігає MD5 тексту, який читав, порівнюваний у SQL з `md5` PostgreSQL того самого тексту, тож читання fit спершу перераховує його за потреби; зміна резюме й кінець кожного запуску (через done-listener `RunEventBus`) запускають фонове проходження по застарілих fit ([`ResumeFitService`](../../backend/src/main/java/com/vacancytracker/application/fit/ResumeFitService.java#L60-L107)).

**Trade-off.** Терміни за формою ловлять деякі не-навички (місто в списку), а синоніми не зливаються (`k8s` і `Kubernetes` це два терміни). Список «missing» це порада, а не вирок, і UI каже «N of M skills», а не відсоток, що звучав би як ймовірність ([ADR-052](../decisions.md#adr-052-beacon-design-light-first)).

**Tests.** [`ResumeFitScorerTest`](../../backend/src/test/java/com/vacancytracker/application/fit/ResumeFitScorerTest.java), [`TermsTest`](../../backend/src/test/java/com/vacancytracker/application/resume/TermsTest.java), [`ResumeTextExtractorTest`](../../backend/src/test/java/com/vacancytracker/application/resume/ResumeTextExtractorTest.java), [`ResumeApiTest`](../../backend/src/test/java/com/vacancytracker/web/ResumeApiTest.java).

---

### C16. PDF резюме: справжній текст, не більше двох сторінок

**Decision.** [`ResumePdf.render`](../../backend/src/main/java/com/vacancytracker/application/documents/ResumePdf.java#L47-L84) пише PDF через PDFBox (вже залежність): A4, одна колонка, справжній виділюваний текст (те, що читають applicant tracking системи), без таблиць, іконок чи текстових полів, **Liberation Sans вбудований як підмножина** (SIL OFL), тож кирилиця й латиниця виглядають однаково всюди; символ без гліфа стає `?`, а не падінням. Кількість сторінок *міряється після кожного рендеру* ([`pages`](../../backend/src/main/java/com/vacancytracker/application/documents/ResumePdf.java#L85)); збережена версія резюме зберігає свій PDF, а текст, PDF якого має 3+ сторінки, відхиляється (422), і нічого не зберігається. Після задовгої AI-чернетки AI просять скоротити *щонайбільше двічі*, потім редагує користувач ([`DocumentService.shorten`](../../backend/src/main/java/com/vacancytracker/application/documents/DocumentService.java#L107-L127)); завантажений PDF використовується без змін, якщо має 1-2 сторінки ([ADR-050](../decisions.md#adr-050-resume-pdf-of-at-most-two-pages)).

**Trade-off.** Макет навмисно простий; дизайнерський макет потребував би іншого рендерера й власного ADR.

**Tests.** [`ResumePdfTest`](../../backend/src/test/java/com/vacancytracker/application/documents/ResumePdfTest.java), [`ResumePdfApiTest`](../../backend/src/test/java/com/vacancytracker/web/ResumePdfApiTest.java).

---

### C17. Експорти й бекапи, які можна відновити

**Decision.**
- **CSV**: UTF-8 із BOM і лапкуванням RFC 4180, щоб Excel правильно відкривав не-ASCII текст ([`VacancyExporter`](../../backend/src/main/java/com/vacancytracker/export/VacancyExporter.java#L36)); **власноруч написаний мінімальний CSV-парсер** обробляє поля в лапках, подвоєні лапки, CRLF/LF і необов'язковий BOM ([`CsvParser`](../../backend/src/main/java/com/vacancytracker/export/CsvParser.java#L6)). CSV-імпорт *зливається* з наявними вакансіями й зберігає поля, відредаговані вручну, зі звітом про помилки по рядках ([`VacancyImporter`](../../backend/src/main/java/com/vacancytracker/export/VacancyImporter.java)).
- **XLSX**: **стрімінговий** `SXSSFWorkbook` Apache POI, щоб великі набори не жили в пам'яті; клітинки обрізаються на ліміті Excel у 32 767 символів ([`XlsxExporter`](../../backend/src/main/java/com/vacancytracker/export/XlsxExporter.java#L26)).
- **JSON-бекап** із `schemaVersion`, вакансіями й історією статусів плюс **секціями від інших модулів** через [`BackupSection`](../../backend/src/main/java/com/vacancytracker/common/BackupSection.java#L12-L18) (пресети, із завантаженим файлом базового резюме в Base64). Старі бекапи без секції імпортуються як раніше, тож версія схеми лишається 1 ([`BackupWriter`](../../backend/src/main/java/com/vacancytracker/export/BackupWriter.java#L37-L55), [ADR-047](../decisions.md#adr-047-ai-presets-and-base-documents)).
- **Стабільне читання**: експорти читають у стабільному порядку (перше побачення), тож запуск, що додає вакансії під час бекапу, не може зробити так, щоб рядок «проскочив» між сторінками ([ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior)).
- **Бекап БД**: контейнер `backup` запускає `pg_dump` у custom-форматі, приймає дамп лише після того, як його повністю записано *і прочитано назад* через `pg_restore --list`, зберігає файли за віком і ніколи не видаляє файли, яких не створював; відновлення задокументоване й протестоване на справжньому compose-стеку ([`backup.sh`](../../ops/backup/backup.sh), [`test-backup.sh`](../../ops/backup/test-backup.sh), [operations.md](../operations.md), [ADR-032](../decisions.md#adr-032-backup-service)).

**Tests.** [`BackupRoundTripTest`](../../backend/src/test/java/com/vacancytracker/export/BackupRoundTripTest.java), [`VacancyCsvExportTest`](../../backend/src/test/java/com/vacancytracker/export/VacancyCsvExportTest.java), [`VacancyXlsxExportTest`](../../backend/src/test/java/com/vacancytracker/export/VacancyXlsxExportTest.java), [`CsvParserTest`](../../backend/src/test/java/com/vacancytracker/export/CsvParserTest.java).

**Чесна межа.** Том бекапів на тому самому хості, що й база, тож він не захищає від втрати машини; JSON-експорт це другий, незалежний шлях ([ADR-032](../decisions.md#adr-032-backup-service)).

---

### C18. Фронтенд для клавіатури й для слабкого зору

**Рішення.**
- **Preact + TypeScript (strict), без UI-бібліотеки, графіки як SVG**; збірка Vite копіюється в jar, тож розгортання це один артефакт ([ADR-005](../decisions.md#adr-005-frontend-vite--typescript--preact-served-by-spring-boot)).
- **Логіка винесена з компонентів.** Правила живуть у чистих модулях у [`domain/`](../../frontend/src/domain) з юніт-тестами, наприклад клавіатурне сортування ([`triage.ts`](../../frontend/src/domain/triage.ts#L1-L35)), дзеркало таблиці статусів ([`statuses.ts`](../../frontend/src/domain/statuses.ts#L5)), порядкова різниця між базовим і адаптованим документом ([`diff.ts`](../../frontend/src/domain/diff.ts#L13)) і стан фільтрів в URL ([`router.ts`](../../frontend/src/router.ts#L12)).
- **Inbox, керований клавіатурою** (`J`/`K` рух, `S` зберегти, `X` відхилити, `O` відкрити, `Enter` деталі). Деталі, що з'явилися з рев'ю: скорочення повертається до **фізичної клавіші** (`e.code`), коли набрана літера не латинська, тож працює з українською чи російською розкладкою, але *не* для розкладок на кшталт Dvorak, що мають латинську літеру ([`actionForKey`](../../frontend/src/domain/triage.ts#L19-L35)); скорочення ігноруються у полях форм і діалогах і ніколи не крадуть Enter/Space у сфокусованої кнопки ([`isTypingTarget`, `activatesOnKey`](../../frontend/src/domain/triage.ts#L56-L71)); виділення це **id вакансії** у ref, а не індекс рядка, тож швидкий набір не вплине на не той рядок ([стан і refs в `Inbox.tsx`](../../frontend/src/screens/Inbox.tsx#L40-L58), [обробник клавіш](../../frontend/src/screens/Inbox.tsx#L162)).
- **Жодних випадкових змін даних**: `<select>`, що змінює статус, комітить при кожній стрілці на Windows, тож селекти лише *обирають*, а застосовує кнопка **Move** ([`VacancyDrawer`](../../frontend/src/screens/VacancyDrawer.tsx#L176)); завершений Refresh перезавантажує дані екранів через лічильник `dataVersion`, а не перемонтовує, тож незбережена нотатка, тост Undo масової зміни й виділення виживають ([`App.tsx`](../../frontend/src/App.tsx#L65)).
- **Доступність як тести, а не наміри**: юніт-тест читає `tokens.css` і перевіряє 58 пар текст/межа/фокус за цілями 7:1 (текст) і 3:1 (межі), а інший перевіряє, що скляні заливки досі їх дотримуються ([`contrast.test.ts`](../../frontend/src/styles/contrast.test.ts), [`liquidGlass.test.ts`](../../frontend/src/styles/liquidGlass.test.ts)); смуги збігів це фокусовані елементи `meter` із озвученим значенням ([`MatchBar`](../../frontend/src/components/MatchBar.tsx#L4)); скло стає суцільним при Reduced transparency, Increased contrast і forced colors.
- **Офлайн за дизайном**: системні стеки шрифтів, жодних запитів за шрифтами чи аналітикою, тож застосунок нічого не вантажить з інтернету ([ADR-052](../decisions.md#adr-052-beacon-design-light-first), [ADR-053](../decisions.md#adr-053-graphite-apple-uber-and-bugatti-references-over-beacon)).

**Tests.** 82 файли Vitest плюс специфікації Playwright в [`e2e/tests`](../../e2e/tests) (smoke, повний цикл заявки з fake AI, сценарій пресетів, фільтр Inbox, дизайн Beacon).

**Чесна межа.** Ручні перевірки, названі в [ADR-052](../decisions.md#adr-052-beacon-design-light-first) і [ADR-054](../decisions.md#adr-054-liquid-glass-over-graphite) (скрінрідер, реальне масштабування 200%, користувачі зі слабким зором), *не* автоматизовані й *не* виконані; тестами покрито лише контраст, вкладки клавіатури, розмір тексту й перекомпонування на 640 px.

---

### C19. Стратегія тестів із зубами

| Рівень | Підхід | Де |
|---|---|---|
| Unit | Чиста логіка без Spring: дедуплікація, парсинг зарплати, rule engine, scorer, таблиця статусів, ghost-евристики, fact check, класифікатор пошти | [`backend/src/test`](../../backend/src/test/java/com/vacancytracker/) |
| Integration | **Один Testcontainer PostgreSQL 16 на тестову JVM**, спільний для класів; Spring кешує контексти застосунку між класами, тож контейнер на клас залишив би кешовані контексти, що вказують на зупинену БД; міграції Flyway виконуються при старті контексту; кожен тест спершу очищає таблиці | [`AbstractIntegrationTest`](../../backend/src/test/java/com/vacancytracker/support/AbstractIntegrationTest.java#L10-L34) |
| Контракти адаптерів | Кожен адаптер тестується із **записаними фікстурами** проти локального JDK `HttpServer`; ніколи справжньої мережі | напр. [`GreenhouseContractTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/greenhouse/GreenhouseContractTest.java), [`LeverContractTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/lever/LeverContractTest.java), [`AdapterContractTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/AdapterContractTest.java), фікстури в [`test/resources/fixtures`](../../backend/src/test/resources/fixtures) |
| Архітектура | Правила ArchUnit у кожній збірці | [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java) |
| Схема | Тест базової схеми й тест Java-міграції даних | [`BaselineSchemaTest`](../../backend/src/test/java/com/vacancytracker/BaselineSchemaTest.java), [`CardLinkRepairMigrationTest`](../../backend/src/test/java/com/vacancytracker/vacancy/CardLinkRepairMigrationTest.java) |
| Час | Кероване годинникове джерело замість `Instant.now()` | [`MutableClock`](../../backend/src/test/java/com/vacancytracker/support/MutableClock.java) |
| Взаємодії | Тести, додані після рев'ю 2026-11-02, для гонок і взаємодій функцій, яких не бачили тести окремих функцій | [`ReviewFixesTest`](../../backend/src/test/java/com/vacancytracker/web/ReviewFixesTest.java), [`FullCycleIntegrationTest`](../../backend/src/test/java/com/vacancytracker/web/FullCycleIntegrationTest.java) |
| Фронтенд | Vitest + jsdom для логіки й компонентів; `npm audit --omit=dev --audit-level=high` у CI | [`frontend`](../../frontend) |
| End to end | Playwright проти *запакованого jar* на порожньому PostgreSQL; падає на будь-якій помилці скрипта в браузері; лише fake AI і тестовий лист | [`e2e/tests`](../../e2e/tests) |
| Бар'єри | Checkstyle валить збірку; бар'єр JaCoCo 80% рядків на основний модуль | [`pom.xml`](../../backend/pom.xml) |

**Історія, що показує зрілість.** Перше незалежне рев'ю прогнало задокументовану збірку на чистій машині й виявило, що *сама збірка не працювала на актуальних інструментах*: Testcontainers 1.21.3 не міг говорити з Docker Engine 29, а один бекенд-тест потребував UI-збірки, якої CI-джоба backend ніколи не робила ([code-review.md](../code-review.md), B1 і B2). Обидва виправлено (Testcontainers 1.21.4; тест тепер перевіряє, що auth-фільтр не відмовляє сторінці). Зелений CI на машині одного розробника це не те саме, що відтворювана збірка.

---

### C20. Доменні евристики, чесно

Дві малі функції показують, як додати доменне знання *без* жорсткого вшивання робіт, країн чи технологій.

- **Сигнали ghost-вакансії** ([`GhostJob.assess`](../../backend/src/main/java/com/vacancytracker/vacancy/GhostJob.java#L28-L51)): три ознаки, обчислені з *власних даних* користувача без зовнішнього сервісу, кожна з названою причиною: опублікована 30+ днів тому, висить 30+ днів із першого побачення, без зарплати. Дві з трьох роблять вакансію «імовірною». Це порада, а не вирок; вакансія без дати публікації оцінюється лише за двома іншими ознаками; а профіль може використати її як правило (`ghostSignals gte 2`) через [`VacancyFacts`](../../backend/src/main/java/com/vacancytracker/matching/VacancyFacts.java#L37).
- **Visa sponsorship** ([`VisaSponsorship.detect`](../../backend/src/main/java/com/vacancytracker/vacancy/VisaSponsorship.java#L40)): читає власні слова вакансії, повертає `false` для відмови, `true` для пропозиції, `null` в іншому випадку, англійською, німецькою, польською, українською й російською. **Відмова перемагає пропозицію в тому самому тексті** («sponsorship is not available, but visa support for family members»). Невідоме *ніколи* не є true чи false, тож профіль вирішує, як ставитися до невідомого. Значення виводиться при відкритті, при оновленні опису й при редагуванні користувачем ([`Vacancy.applyAutomated`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L124-L127)), а Java-міграція (V12) заповнила наявні рядки ([`V12__Detect_visa_sponsorship.java`](../../backend/src/main/java/db/migration/V12__Detect_visa_sponsorship.java)). Списки фраз це мовні дані, а не список робіт, тож правило «без доменних даних у коді» зберігається ([ADR-044](../decisions.md#adr-044-visa-sponsorship-from-the-posting-text)).
- **Визначення країни** бере країну лише тоді, коли ціла частина локації між комами це назва країни (десять мов з локальних даних JDK), виключає «Georgia», ігнорує дволітерні коди (вони конфліктують зі штатами США) і відкидає все неоднозначне; логіка така: **хибна країна сховала б вакансії через правило `require`, тож відсутня це безпечніша помилка** ([`CountryDetector`](../../backend/src/main/java/com/vacancytracker/collection/normalize/CountryDetector.java#L17), [ADR-036](../decisions.md#adr-036-feed-adapter-country-detection-and-what-stays-undetected)).

**Tests.** [`GhostJobTest`](../../backend/src/test/java/com/vacancytracker/vacancy/GhostJobTest.java), [`GhostJobApiTest`](../../backend/src/test/java/com/vacancytracker/web/GhostJobApiTest.java), [`VisaSponsorshipTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VisaSponsorshipTest.java), [`CountryDetectorTest`](../../backend/src/test/java/com/vacancytracker/collection/normalize/CountryDetectorTest.java), [`SalaryParserTest`](../../backend/src/test/java/com/vacancytracker/collection/normalize/SalaryParserTest.java).

---

## 6. Наскрізні теми

Де в коді живе кожна тема, про яку питають інтерв'юери.

| Тема | Що казати | Дивись |
|---|---|---|
| **Concurrency** | Однопотоковий executor і `synchronized` координатор для запусків; фонова задача перерахунку; SSE-емітери з відтворенням; лічильник `generation`, що дозволяє довгій задачі помітити паралельну зміну профілю; блокування рядків для read-modify-write; `pg_advisory_xact_lock` для серіалізації рознесених викликів API | C2, C5, C10, [`CallBudget`](../../backend/src/main/java/com/vacancytracker/collection/http/CallBudget.java#L40-L58) |
| **Транзакції** | Методи сервісів `@Transactional`; ingest однієї вакансії це одна транзакція; expiry і matching працюють **по одній транзакції на вакансію** (через `TransactionTemplate`), тож один поганий рядок не скасовує решту; імпорти це транзакції по рядках | [`IngestService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/IngestService.java#L36), [`MatchService.session`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L84), [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L64-L70), [`VacancyService.importState`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L190-L199) |
| **Ідемпотентність** | Дедуплікація поглинає повтори; офсети пошти й Telegram комітяться *після* збереження; бюджет викликів це атомарний upsert; «I submitted» вдруге нічого не робить; додавання наявного запису в список компаній нічого не змінює | C5, C8, C13 |
| **Стійкість** | Ізоляція по адаптерах, retry/backoff/jitter, circuit breaker у БД, обмежені й часово обмежені тіла, редиректи на той самий хост, запуск, що завжди завершується, sweeper при старті | C5, C6 |
| **Цілісність даних** | Унікальний індекс канонічного URL, `CHECK` на статус, upsert через `ON CONFLICT`, історія лише на додавання, незмінні версії й снепшоти | [`V1__baseline.sql`](../../backend/src/main/resources/db/migration/V1__baseline.sql), C3, C4, C14 |
| **Дизайн API** | Тіла помилок problem-detail (у стилі RFC 9457) з помилками по полях; 202 проти 200 для «запущено» проти «вже працює»; SSE для прогресу; строга валідація імпорту зі звітами по рядках | [`ApiExceptionHandler`](../../backend/src/main/java/com/vacancytracker/web/ApiExceptionHandler.java), [`RunController`](../../backend/src/main/java/com/vacancytracker/web/RunController.java#L83) |
| **Безпека** | Модель загроз для local-first застосунку (зловмисник це браузер), порівняння за постійний час, блокування, SSRF-захист, CSP, маскування логів | C12 |
| **Observability** | Структуровані лог-рядки на джерело й на запуск із key-value полями (JSON у профілі `vps`), секрети маскуються на виході, лише health-ендпоінт; **немає ендпоінта метрик** (названа прогалина) | [`CollectionPipeline`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L133-L155), [`logback-spring.xml`](../../backend/src/main/resources/logback-spring.xml) |
| **Тестування** | Контрактні тести на фікстурах, Testcontainers, ArchUnit, бар'єр покриття, Playwright | C19 |
| **Збірка, CI/CD, ops** | Maven `verify` як єдиний бар'єр; чотири CI-джоби; Compose із загартованими контейнерами; щоденні перевірені бекапи; задокументоване відновлення | C17, C19, [`ci.yml`](../../.github/workflows/ci.yml) |
| **Доменне моделювання** | Агрегат із явною таблицею станів; value objects (`AutomatedFields`, `FetchOutcome`, `SqlFragment`); records для незмінних даних | C3, C4 |
| **Процес** | 54 ADR, план із ID задач, який CI-джоба тримає синхронним із CSV, changelog на кожну задачу, незалежні рев'ю коду, знахідки яких відстежуються | [PLAN.md](../../PLAN.md), [code-review.md](../code-review.md) |

---

## 7. Демо-сценарій (5 хвилин)

Запуск: `docker compose up -d --build` (спершу задайте `POSTGRES_PASSWORD` у `.env`), відкрити <http://127.0.0.1:8080>. Скріншоти з [README](../../README.md#screenshots) це запасний варіант.

1. **Wizard і профіль (45 с).** Перший запуск: wizard будує пошуковий профіль. Покажіть live-прев'ю: чернетковий профіль оцінює справжні вакансії *до* збереження, з причинами. Вкажіть суть дизайну: у коді немає ні ролей, ні країн, усе це дані профілю.
2. **Refresh (45 с).** Натисніть Refresh: живий прогрес по джерелах через SSE. Скажіть, що збій одного джерела не зупиняє інші, і покажіть статус по джерелах на екрані Sources. *(Код: C5, C6.)*
3. **Сортування Inbox (60 с).** Лише клавіатура: `J`/`K`, `S`, `X`, `O`. Відкрийте вакансію: бейдж оцінки з причинами, прапорець «Likely ghost» з причинами, visa sponsorship як факт, список джерел, що показує злитий дублікат. *(Код: C8, C9, C20.)*
4. **Pipeline та історія (45 с).** Таблиця й Kanban, зміна статусу, історія, масова зміна з Undo. UI пропонує лише легальні наступні статуси (дзеркалить таблицю), а сервер відмовляє на все інше. *(Код: C4.)*
5. **Dashboard (30 с).** Воронка й список «Time to follow up» (заявки без активності 10 днів). Зауважте, що числа беруться з історії статусів.
6. **Пошта (45 с).** Екран Mail: відповідь, прив'язана до заявки *із причиною*, і *запропонована* зміна статусу, що чекає на Accept. Наголосіть на IMAP лише для читання. *(Код: C13.)*
7. **AI, якщо спитають (60 с).** Settings показує AI **вимкненим**; повідомлення про згоду перелічує точно, що надсилається. З fake-провайдером: Generate, порядкова різниця з базою, знахідки fact check, що блокують збереження до підтвердження, Employer match «Base 62% → Tailored 78%», PDF на дві сторінки, ZIP-пакет. Застосунок відкриває сторінку заявки; **подає користувач**. *(Код: C14, C16.)*
8. **Завершіть цифрами**: 54 ADR, межі під контролем ArchUnit, жоден тест не торкається справжньої мережі, і список чесних обмежень нижче.

---

## 8. Чесні обмеження і що я зробив би далі

Казати це першим це сила: кожен пункт нижче задокументований у репозиторії, тож жоден не буде сюрпризом для рев'юера.

| Обмеження | Докази | Що я зробив би |
|---|---|---|
| **Лише один інстанс.** Замок запуску внутрішньопроцесний; advisory lock PostgreSQL, запропонований у [ADR-006](../decisions.md#adr-006-scheduling-single-run-lock-and-sse-progress), не реалізований | [`RunCoordinator`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java#L54-L68), «Implementation note (T-044)» у [ADR-006](../decisions.md#adr-006-scheduling-single-run-lock-and-sse-progress) | Брати `pg_try_advisory_lock` на виділеному з'єднанні на весь запуск і винести лімітер збоїв та `RunEventBus` із пам'яті процесу |
| **Текст ADR і код розходяться у трьох місцях**: [ADR-003](../decisions.md#adr-003-spring-data-jdbc-over-jpa) каже Spring Data JDBC, а код це простий `JdbcClient`; [ADR-015](../decisions.md#adr-015-search-postgresql-full-text-search) каже `websearch_to_tsquery`, а код будує санітизований `to_tsquery`; [ADR-018](../decisions.md#adr-018-adapter-resilience-retries-rate-limits-circuit-breaker) каже «token bucket», а лімітер це розстановка слотів | [`VacancyRepository`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L21-L35), [`VacancySearchService`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancySearchService.java#L41-L45), [`DomainRateLimiter`](../../backend/src/main/java/com/vacancytracker/collection/http/DomainRateLimiter.java#L21-L28) | Додати абзац «Revision» до кожного ADR (як це вже робить [ADR-013](../decisions.md#adr-013-deduplication-pipeline)), щоб запис відповідав коду |
| **Пошук `c#` збігається за префіксом `c`**; немає стемінгу | [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior) «Still not changed», [ADR-015](../decisions.md#adr-015-search-postgresql-full-text-search) | Конфігурація повнотекстового пошуку на профіль і трактування `c#`/`c++` як цілих токенів |
| **Відкритий бекенд рев'ю (T-092)**: видалення ATS-дошки завершує її вакансії після трьох запусків; `Retry-After` як HTTP-дата ігнорується; токени з ворожими до cookie символами не можуть увійти; зміни списків компаній не доходять до запуску, що йде; sweep при старті може позначити запуск, розпочатий у першу хвилину, як interrupted; крайні випадки палітри й незбережених правил; немає компонентних тестів для Inbox, drawer, board і палітри. *Дедлайн читання тіла (F7) уже в коді: [`HttpFetcher.BODY_DEADLINE`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L38-L39) з [`BodyDeadlineTest`](../../backend/src/test/java/com/vacancytracker/collection/http/BodyDeadlineTest.java), але прапорець T-092 у [PLAN.md](../../PLAN.md) досі відкритий, а [code-review.md](../code-review.md) досі веде F7 як відкриту, тож документи відстають від коду.* | [code-review.md](../code-review.md), рядок T-092 у [PLAN.md](../../PLAN.md) | Закривати T-092 по пунктах і оновити обидва документи |
| **Адаптер Jooble заблокований** (ще немає ключа й документації API) | T-036 у [PLAN.md](../../PLAN.md) | Чекати на провайдера; контракт адаптера й маскування ключа в шляху вже є |
| **Валюти поза списком ECB це «відсутні дані»**; курси оновлюються раз на робочий день | [ADR-011](../decisions.md#adr-011-salary-normalization-and-exchange-rate-source) | Друге джерело курсів за тим самим інтерфейсом конвертера |
| **Ручні перевірки доступності не зроблені** (скрінрідер, реальне масштабування 200%, користувачі зі слабким зором) | [ADR-052](../decisions.md#adr-052-beacon-design-light-first), [ADR-054](../decisions.md#adr-054-liquid-glass-over-graphite) | Пройти ручний чекліст із hand-off дизайну, перш ніж вважати дизайн прийнятим |
| **Провайдер AI `cli` потребує встановленого й залогіненого Claude Code**, тож не працює в Docker-образі; провайдер `api` коштує за запит | [ADR-041](../decisions.md#adr-041-claude-adapter-and-the-ai-opt-in) | Використовувати `api` у контейнерах; `cli` лишити для запуску jar на десктопі |
| **Немає ендпоінта метрик**: метрики запусків це лог-рядки | [ADR-034](../decisions.md#adr-034-log-masking-and-structured-logs) | Лічильники Micrometer і ендпоінт Prometheus за токеном |
| **Бар'єр покриття лише по рядках; `web`, `export`, `notification` без бар'єра** | [ADR-022](../decisions.md#adr-022-testing-strategy-and-coverage-gate), [`pom.xml`](../../backend/pom.xml) | Додати покриття гілок для rule engine і гейтити `export` |
| **Поштова скринька опитується** (кожні 15 хвилин), а не push | [ADR-029](../decisions.md#adr-029-imap-reader-read-only-processed-state-in-the-database), [ADR-043](../decisions.md#adr-043-correspondence-job-mail-links-to-applications-status-suggestions) | IMAP IDLE з відкатом на опитування |
| **Бекапи ділять хост із базою** | [ADR-032](../decisions.md#adr-032-backup-service) | Позахостова копія (об'єктне сховище) дампа |

**Чого тут навмисно немає і як це сказати.** Немає Kafka, немає Kubernetes, немає хмарного провайдера й немає мікросервісів. Це *рішення*, а не прогалина: навантаження це пошук роботи однієї людини, тож один контейнер із PostgreSQL це правильний розмір ([ADR-001](../decisions.md#adr-001-modular-monolith)). Якщо спитають, як воно еволюціонувало б: пайплайн збору став би першим винесеним сервісом за чергою (його стадії вже чисті функції навколо `fetch` і `upsert`, див. [architecture.md](../architecture.md)), запуски брали б розподілений замок, а `RunEventBus` переїхав би на брокер.

---

## 9. Банк питань

Короткі відповіді; посилання це куди вказати.

**Архітектура**
1. *Чому модульний моноліт?* Один користувач, один артефакт, одна БД; межі забезпечені тестами, тож винесення лишається можливим. [C1]
2. *Як не дати модулям заплутатися?* ArchUnit у кожній збірці падає на заборонену залежність, цикл чи невідомий пакет. [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java#L23-L66)
3. *Що б ви винесли першим?* `collection`: I/O-bound, схильний до збоїв, а його стадії це чисті функції навколо fetch і upsert. [C5]
4. *Як би масштабували вшир?* Розподілений замок запуску, спільний стан лімітера, брокер для подій прогресу (розділ 8).

**Дані та персистентність**
5. *Чому не JPA?* Явні upsert, JSONB, `tsvector` і блокування рядків легше обґрунтувати в SQL, без lazy loading чи кешу сесії. [C2]
6. *Як уникаєте втрачених оновлень?* `SELECT ... FOR UPDATE` на кожному read-modify-write й автоматизація, обмежена білим списком полів. [C2, C3]
7. *Як керуєте зміною схеми?* Лише Flyway, без `ddl-auto`; міграції ганяються в тестах на Testcontainers; одна Java-міграція заповнює похідний факт. [C19, C20]
8. *Чому повнотекстовий пошук PostgreSQL, а не Elasticsearch?* Тисячі рядків, без додаткового сервісу; GIN-індекс на згенерованій колонці. [C11]

**Надійність**
9. *Що буде, коли джерело лежить?* Воно ізольоване, повторюється з backoff і jitter, спрацьовує circuit breaker у БД після 5 збоїв і ніколи не призводить до expiry. [C5, C6, C7]
10. *Що, якщо застосунок впаде посеред запуску?* Ingest ідемпотентний, офсети пошти й Telegram комітяться після збереження, а sweeper при старті закриває осиротілі запуски. [C5, C13]
11. *Як обробляєте квоти API?* Атомарний upsert-бюджет у БД, виклики без retry для квотних джерел і інтервал від реального останнього виклику. [C6]
12. *Чому прибрали ETag для деяких джерел?* «Not modified» для одного URL не відрізнити від «зникло» для інших за тим самим адаптером; коректність важливіша за трафік. [C6]

**Алгоритми та логіка**
13. *Як працює оцінка?* Зважені критерії prefer/avoid з нормалізованою цілочисельною формулою, явні політики missing-value і причина для кожного бала. [C9]
14. *Як зроблена дедуплікація і що пішло не так спершу?* Три впорядковані правила; перше fuzzy-правило зливало різні вакансії, тож тепер воно вимагає сумісних місць і майже однакових заголовків. [C8]
15. *Чому BFS по таблиці статусів?* Таблиця це дані; шляхи виводяться з неї, тож не можуть застаріти. [C4]

**Безпека**
16. *Яка ваша модель загроз для localhost-застосунку?* Браузер: DNS rebinding і cross-site записи; тому перевірки Host і Origin на кожному запиті. [C12]
17. *Як захищаєте токен?* Порівняння дайджестів за постійний час, блокування після 5 збоїв (і для правильного токена), мінімум 16 символів, cookie `HttpOnly` `SameSite=Strict`. [C12]
18. *SSRF в імпорті за URL?* Перевірка схеми плюс блокування внутрішніх діапазонів адрес для адреси й кожного редиректу; вікно DNS-гонки задокументоване. [C12]
19. *Що не пускає секрети в логи?* Маскування на виході за формою і за точним налаштованим значенням, а повідомлення, що зберігаються чи показуються, маскуються біля джерела. [C12]

**AI**
20. *Як контролюєте, що йде в модель?* Вимкнено за замовчуванням, згода на провайдера з точним повідомленням, єдині двері, що відмовляють до того, як щось піде, блоки даних із тегами й нейтралізація ін'єкцій. [C14]
21. *Як запобігаєте вигаданим фактам?* Guardrail, потім детермінована різниця чисел, дат, роботодавців, посад і технологій з базовими документами; збереження чекає на користувача. [C14]
22. *Чому оцінка рахується в коді?* Щоб модель не могла її завищити: цитати перевіряються буквально, а формула детермінована. [C14]

**Якість і процес**
23. *Як тестуєте адаптери без мережі?* Фікстури, які роздає локальний JDK `HttpServer`. [C19]
24. *Що вимагає ваш CI?* Синхрон plan/CSV, Maven verify (тести, Checkstyle, бар'єр покриття, правила архітектури), frontend typecheck/тести/збірка/audit і Playwright smoke-тест проти запакованого jar. [C19]
25. *Що знайшло незалежне рев'ю?* DNS rebinding, втрачені оновлення між фоновими задачами й користувачем, автоприхована ручна вакансія, клавіатурні шляхи, що змінювали дані випадково, і збірку, яка падала на актуальних інструментах; усе відстежено в [code-review.md](../code-review.md). [C2, C12, C18, C19]

**Поведінкові**
26. *Що б ви зробили інакше?* Тримати ADR і код синхронними з самого початку (три точки розбіжності), реалізувати міжінстансний замок раніше, якби multi-instance колись був ціллю, і виконати ручні перевірки доступності, перш ніж оголошувати дизайн готовим.
27. *Чим пишаєтеся найбільше?* Інваріантом «автоматизація не перезаписує користувача», що забезпечений типами й блокуваннями рядків, і compliance-first політикою джерел, що сформувала архітектуру, а не була додана наприкінці.

---

## 10. Де що лежить

| Що шукаєте | Куди |
|---|---|
| Таблиця статусів | [`VacancyStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyStatus.java#L27-L42) |
| Що може писати автоматизація | [`Vacancy.applyAutomated`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L115-L136), [`AutomatedFields`](../../backend/src/main/java/com/vacancytracker/vacancy/AutomatedFields.java) |
| SQL upsert | [`VacancyRepository.save`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L35-L82) |
| Читання з блокуванням рядка | [`VacancyRepository.findByIdForUpdate`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L96-L102) |
| Інтерфейс адаптера | [`SourceAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/SourceAdapter.java#L10-L19) |
| Один адаптер від початку до кінця | [`GreenhouseAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/greenhouse/GreenhouseAdapter.java#L25) з [`GreenhouseContractTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/greenhouse/GreenhouseContractTest.java) і [`greenhouse-acme.json`](../../backend/src/test/resources/fixtures/greenhouse-acme.json) |
| Пайплайн | [`CollectionPipeline`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L74-L130) |
| Дедуплікація | [`Deduplicator`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L34-L103) |
| Expiry | [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L31-L70) |
| Retry/backoff | [`RetryPolicy`](../../backend/src/main/java/com/vacancytracker/collection/http/RetryPolicy.java#L30-L35), [`HttpFetcher`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L132-L181) |
| Circuit breaker | [`SourceHealth`](../../backend/src/main/java/com/vacancytracker/collection/health/SourceHealth.java#L18-L72) |
| Бюджет викликів | [`CallBudget`](../../backend/src/main/java/com/vacancytracker/collection/http/CallBudget.java#L20-L58) |
| SSE-прогрес | [`RunEventBus`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunEventBus.java#L43-L84), [`RunController.events`](../../backend/src/main/java/com/vacancytracker/web/RunController.java#L67-L68) |
| Rule engine і оцінювання | [`RuleEngine`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L54-L121), [`Scorer`](../../backend/src/main/java/com/vacancytracker/matching/Scorer.java#L38-L89) |
| Criterion → SQL | [`CriterionCompiler`](../../backend/src/main/java/com/vacancytracker/matching/CriterionCompiler.java#L53-L83) |
| Автоприховування в Inbox | [`MatchService.applyInboxRule`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L128-L164) |
| Повнотекстовий пошук | [`VacancySearchService`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancySearchService.java#L28-L56), [`SearchQuery`](../../backend/src/main/java/com/vacancytracker/vacancy/SearchQuery.java#L27) |
| Безпека Host / Origin / токен | [`HostFilter`](../../backend/src/main/java/com/vacancytracker/web/HostFilter.java#L25-L70), [`OriginFilter`](../../backend/src/main/java/com/vacancytracker/web/OriginFilter.java#L22-L50), [`AuthFilter`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthFilter.java#L33-L60) |
| SSRF-захист | [`UrlFetcher`](../../backend/src/main/java/com/vacancytracker/collection/UrlFetcher.java#L97-L125) |
| Маскування логів | [`LogMasking`](../../backend/src/main/java/com/vacancytracker/common/LogMasking.java#L15-L60) |
| IMAP-рідер | [`ImapMailReader`](../../backend/src/main/java/com/vacancytracker/collection/mail/ImapMailReader.java#L132-L230) |
| Класифікація й зв'язування пошти | [`MailClassifier`](../../backend/src/main/java/com/vacancytracker/application/mail/MailClassifier.java#L60-L80), [`MailLinker`](../../backend/src/main/java/com/vacancytracker/application/mail/MailLinker.java#L45-L88) |
| Згода на AI і шлюз викликів | [`AiService`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L22-L90) |
| Claude CLI у пісочниці | [`ClaudeCliClient`](../../backend/src/main/java/com/vacancytracker/application/ai/ClaudeCliClient.java#L58-L112) |
| Гігієна проти prompt injection | [`Prompts`](../../backend/src/main/java/com/vacancytracker/application/documents/Prompts.java#L94-L100) |
| Перевірка цитат і оцінка match | [`QuoteCheck`](../../backend/src/main/java/com/vacancytracker/application/analysis/QuoteCheck.java#L30-L78), [`EmployerMatch`](../../backend/src/main/java/com/vacancytracker/application/analysis/EmployerMatch.java#L20-L34) |
| Fact check | [`FactCheck`](../../backend/src/main/java/com/vacancytracker/application/documents/FactCheck.java#L19-L50) |
| PDF резюме | [`ResumePdf`](../../backend/src/main/java/com/vacancytracker/application/documents/ResumePdf.java#L47-L84) |
| Оцінка fit резюме | [`ResumeFitScorer`](../../backend/src/main/java/com/vacancytracker/application/fit/ResumeFitScorer.java#L31-L93) |
| Бекапи й експорти | [`BackupWriter`](../../backend/src/main/java/com/vacancytracker/export/BackupWriter.java#L37-L55), [`XlsxExporter`](../../backend/src/main/java/com/vacancytracker/export/XlsxExporter.java), [`backup.sh`](../../ops/backup/backup.sh) |
| Клавіатурне сортування | [`triage.ts`](../../frontend/src/domain/triage.ts#L1-L71) |
| Тести контрасту | [`contrast.test.ts`](../../frontend/src/styles/contrast.test.ts) |
| Правила архітектури | [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java#L23-L66) |
| База інтеграційних тестів | [`AbstractIntegrationTest`](../../backend/src/test/java/com/vacancytracker/support/AbstractIntegrationTest.java#L10-L34) |
| Схема | [`db/migration`](../../backend/src/main/resources/db/migration), ERD в [architecture.md](../architecture.md) |
| Рішення, план, рев'ю | [decisions.md](../decisions.md), [PLAN.md](../../PLAN.md), [code-review.md](../code-review.md), [sources.md](../sources.md) |

Пов'язані документи: [architecture.md](../architecture.md) (діаграми), [search-profiles.md](../search-profiles.md) (схема профілю й критерію та формула оцінки), [sources.md](../sources.md) (умови, ліміти й статус кожного джерела), [deployment.md](../deployment.md), [operations.md](../operations.md), [gap-analysis.md](../gap-analysis.md), [specs/M7-ai-presets.md](../specs/M7-ai-presets.md).

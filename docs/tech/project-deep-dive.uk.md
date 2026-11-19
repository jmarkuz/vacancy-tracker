# Vacancy Tracker: глибокий аналіз проєкту по функціоналу

Документ для підготовки й захисту проєкту на технічному інтерв'ю. Це другий, повторний прохід: він доповнює [interview-prep.uk.md](interview-prep.uk.md) (кейси рішень, банк питань) повним каталогом того, **що проєкт вміє**, розбитим по функціональних блоках, із технічними рішеннями кожного блоку. STAR-історії лежать окремо: [star-stories.md](star-stories.md).

Станом на 2026-11-17. Усе тут перевірено по коду, тестах і документах репозиторію; посилання на код ведуть у файл і діапазон рядків і автоматично перевірені на існування.

## Зміст

1. [Проєкт одним абзацом](#1-проєкт-одним-абзацом)
2. [Що перевірено під час цього аналізу](#2-що-перевірено-під-час-цього-аналізу)
3. [Матриця підтримуваного функціоналу](#3-матриця-підтримуваного-функціоналу)
4. [Функціональні блоки F1–F17](#4-функціональні-блоки)
5. [Модель даних за міграціями](#5-модель-даних-за-міграціями)
6. [Каталог API](#6-каталог-api)
7. [Конфігурація](#7-конфігурація)
8. [Стек технологій](#8-стек-технологій)
9. [Історія розробки і процес](#9-історія-розробки-і-процес)
10. [Ризики, обмеження і чого немає](#10-ризики-обмеження-і-чого-немає)
11. [Як подати проєкт: структура розповіді](#11-як-подати-проєкт-структура-розповіді)

---

## 1. Проєкт одним абзацом

**Vacancy Tracker** це персональний self-hosted застосунок для пошуку роботи **для однієї людини**. За розкладом (за замовчуванням о 06:00 і 18:00) та за кнопкою Refresh він збирає вакансії з офіційних API, RSS/Atom-стрічок, листів-сповіщень на пошті користувача й пересланих Telegram-постів; нормалізує їх, зливає дублікати, оцінює кожну вакансію за **правилами, які написав користувач** (з поясненням кожного бала), і веде кожну заявку через пайплайн із повною історією статусів. Окремий шар (резюме, fit-оцінка, опційний AI, пакети заявок, відстеження відповідей на пошті) закриває повний цикл «знайшов → оцінив → подався → дізнався відповідь».

Три принципи, що пояснюють більшість рішень:
1. **Немає доменних даних у коді.** Ролі, технології, країни, пороги це дані профілів користувача ([README](../../README.md)).
2. **Детермінованість і пояснюваність.** Оцінки обчислюються правилами; там, де є AI, він лише пропонує, а код перевіряє й рахує ([ADR-008](../decisions.md), C14 у [interview-prep](interview-prep.uk.md)).
3. **Автоматизація ніколи не змінює те, що написав або вирішив користувач**, а дані нікуди не йдуть без явної згоди.

Чого проєкт **не** є: це не багатокористувацька система, не агрегатор з власним індексом, не скрейпер заборонених сайтів і не інструмент автоматичної подачі заявок (подає завжди людина).

---

## 2. Що перевірено під час цього аналізу

Не лише прочитано, а й запущено (у цьому контейнері):

| Перевірка | Результат |
|---|---|
| Фронтенд: `npm ci`, `npm test` (Vitest 5.1) | **82 файли, 726 тестів, усі пройшли** |
| Фронтенд: `npm run typecheck` (`tsc --noEmit`, strict) | без помилок |
| Бекенд: Maven `test` для 23 класів чистої логіки (оцінювання, rule engine, статуси, fact check, перевірка цитат, mail linker/classifier, ghost/visa, лімітер збоїв, retry, дедлайн тіла, ArchUnit та ін.) | **260 тестів, 0 збоїв**; 3 класи (`DeduplicatorTest`, `HostFilterTest`, `OriginFilterTest`) не стартували лише тому, що наслідують `AbstractIntegrationTest`, якому потрібен Docker-демон, а його в цьому контейнері немає |
| Повний `mvn verify` з інтеграційними тестами на Testcontainers | **не запускався** (немає Docker-демона). Вважайте інтеграційні тести непідтвердженими цим прогоном |
| Статичний підрахунок | 774 тестові методи на бекенді (`@Test`/`@ParameterizedTest`), 19 сценаріїв Playwright; 238 основних і 141 тестовий Java-файл, ~20 500 / ~15 800 рядків |

Саме тому в розповіді безпечно казати: «фронтенд-набір і чиста бекенд-логіка зелені, повний інтеграційний набір ганяється в CI на Testcontainers», і не казати, що ви особисто щойно прогнали все.

---

## 3. Матриця підтримуваного функціоналу

Легенда: **є** працює за замовчуванням; **opt-in** вимкнено, вмикається явно; **config** потребує налаштування (ключі, дошки, скринька); **нема** свідомо не підтримується.

| Область | Можливість | Стан | Блок |
|---|---|---|---|
| Джерела | Greenhouse, Lever, Ashby (дошки з конфігурації), Arbeitnow, Remotive, Himalayas, Jobicy, Remote OK, Working Nomads | є / config | F1 |
| | Adzuna (безкоштовний ключ), RSS/Atom (Djinni у комплекті), generic HTML (лише схвалені сайти) | config | F1 |
| | Листи-сповіщення LinkedIn, Jobs.cz, StartupJobs, Indeed (IMAP) | config | F1, F11 |
| | Telegram: пости, які користувач пересилає власному боту | config | F1 |
| | Jooble | **нема** (заблоковано до отримання ключа, T-036) | F1 |
| | Скрейпінг LinkedIn/Indeed/Glassdoor, вхід під акаунтом, обхід CAPTCHA, ротація проксі | **нема** (заборонено політикою) | F1 |
| Обробка | Нормалізація (HTML→текст, зарплата з вільного тексту, режим роботи, країна), канонічні URL | є | F2 |
| | Дедуплікація в 3 кроки, злиття джерел в одну вакансію | є | F3 |
| | Visa sponsorship із тексту, «ghost»-сигнали, вік вакансії | є | F2, F5 |
| | Додавання вручну й імпорт за URL (schema.org JobPosting) | є | F2 |
| Профілі | Правила (19 полів, 12 операторів, 4 режими, 3 політики відсутніх даних), версії, wizard, live-прев'ю, імпорт/експорт YAML/JSON | є | F4 |
| | Allow/block-списки компаній (глобальні й на профіль) | є | F4 |
| | Збережені види й фільтри на тій самій моделі `Criterion` | є | F4 |
| Оцінювання | Оцінка 0–100 за профілем із причинами, найкраща оцінка на вакансію, фонова перерахунок | є | F5 |
| | Автоматичне приховування в Inbox за порогом (з жорсткими межами) | є | F5 |
| | Оцінка LLM | **нема** (свідомо, ADR-008) | F5 |
| Робота з заявками | 13 статусів із таблицею переходів, історія, Kanban, масові зміни з Undo, нотатки/теги/пріоритет/наступна дія | є | F6 |
| | Dashboard: лічильники, топ збігів, воронка, заявки по тижнях, «Time to follow up», статистика відповідей | є | F7 |
| | Повнотекстовий пошук і палітра Ctrl/Cmd+K | є | F8 |
| Резюме | Завантаження PDF/DOCX/TXT/MD або вставка, редаговані ключові слова, fit-оцінка 0–100 із причинами | є | F9 |
| AI | Пресети, адаптація резюме й листа, Employer match, Goal match, fact check, PDF ≤ 2 сторінки, пакети заявок | **opt-in** (вимкнено за замовчуванням) | F10 |
| | Автоматична подача форм | **нема** (подає людина) | F10 |
| Пошта | Відповіді роботодавців: класифікація 5 мовами, зв'язування із заявкою, запропонований статус, дата інтерв'ю з тексту | config | F11 |
| Сповіщення | Telegram-дайджест нових збігів; повідомлення про запропоновану зміну статусу | **opt-in** | F12 |
| Дані | CSV (UTF-8 BOM), XLSX (стрімінг), JSON-бекап із профілями й пресетами; імпорт зі звітом по рядках; щоденний `pg_dump` | є | F13 |
| Безпека | Local: без логіну, `127.0.0.1`; VPS: токен + HTTPS через Caddy; Host/Origin-перевірки, SSRF-захист, CSP, маскування логів | є | F14 |
| UI | 11 екранів, клавіатурне сортування, 3 теми, текст 18–22 px, «Liquid Glass» із вимикачем | є | F15 |
| Операції | Docker Compose, hardened-контейнери, health-check, graceful shutdown, структуровані логи | є | F16 |
| | Метрики (Prometheus), кілька інстансів, акаунти й ролі | **нема** | F16 |

---

## 4. Функціональні блоки

Кожен блок має однакову форму: **Що вміє**, **Як працює**, **Рішення**, **Межі**, **Код**, **Тести**. Глибші розбори рішень (проблема, ціна, «якщо тиснуть») лежать у кейсах C1–C20 [interview-prep.uk.md](interview-prep.uk.md#5-кейси); тут їх лише згадано.

### F1. Збір вакансій із джерел

**Що вміє.** Збирає з 13 типів адаптерів за розкладом (cron `0 0 6,18 * * *`, змінюється через `APP_COLLECTION_CRON`) і на вимогу: кнопка Refresh запускає або приєднується до активного запуску й показує живий прогрес по джерелах; кнопка «Refresh this source» запускає одне джерело. Екран **Sources & Runs** показує по кожному джерелу здоров'я (OK / Warning / Error / Not run yet) текстом і крапкою, пояснення (збої підряд або час повторної спроби), останній успіх, лічильники останнього запуску й останню помилку. Профіль пошуку визначає, які джерела йому потрібні (`sources`).

**Як працює.** Кожен адаптер реалізує три методи ([`SourceAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/SourceAdapter.java#L11-L29)) і повертає [`FetchOutcome`](../../backend/src/main/java/com/vacancytracker/collection/adapter/FetchOutcome.java#L9-L56). Пайплайн ([`CollectionPipeline`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L84-L140)) проходить адаптери по черзі: перевіряє circuit breaker, викликає `fetch`, для кожної вакансії робить нормалізацію → дедуплікацію → збереження → оцінювання, пише статистику по джерелу, а після всіх адаптерів запускає expiry. Один запуск за раз ([`RunCoordinator`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunCoordinator.java#L84-L99)), прогрес через SSE ([`RunEventBus`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/RunEventBus.java#L43-L84)). Курси валют ECB оновлюються при старті й о 16:30 за Берліном у робочі дні ([`RateScheduler`](../../backend/src/main/java/com/vacancytracker/collection/rates/RateScheduler.java#L25-L34)).

**Рішення.** Ізоляція адаптерів і запуск, що завжди завершується (C5); стійкість HTTP із persisted circuit breaker і quota-бюджетом (C6); правило expiry, яке не плутає збій джерела зі зникненням вакансії (C7); політика джерел compliance-first: кожне джерело з 65 у списку власника перевірено за умовами та `robots.txt` і записано у [sources.md](../sources.md) із вердиктом, Telegram читається лише через власного бота, LinkedIn лише через листи (C13). Адаптери, що віддають лише вікно відкритих вакансій (Remotive, Jobicy, Arbeitnow, Himalayas, Adzuna, RSS, HTML, Remote OK, Working Nomads, Telegram), не завершують вакансії автоматично.

**Межі.** Селектори generic HTML ламаються зі зміною сторінки; Jooble заблоковано; на момент запису T-038/T-039 у CHANGELOG назви полів Himalayas і Jobicy ще не були звірені з живою відповіддю ([CHANGELOG](../../CHANGELOG.md)).

**Код.** [`adapter/`](../../backend/src/main/java/com/vacancytracker/collection/adapter) (по пакету на джерело), [`CollectionScheduler`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionScheduler.java#L16-L17), [`RunController`](../../backend/src/main/java/com/vacancytracker/web/RunController.java#L29-L92), [`SourceController`](../../backend/src/main/java/com/vacancytracker/web/SourceController.java#L44-L58). **Тести.** По контрактному тесту на адаптер із фікстурами ([`AdapterContractTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/AdapterContractTest.java), [`GreenhouseContractTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/greenhouse/GreenhouseContractTest.java)), [`CollectionPipelineTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/CollectionPipelineTest.java), [`ExpiryAndRunLockTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/ExpiryAndRunLockTest.java).

---

### F2. Нормалізація й додавання вручну

**Що вміє.** Приводить сирі вакансії до єдиної форми: HTML→звичайний текст із зведеними пробілами; режим роботи з поля джерела або зі слів у локації й описі (`remote`/`hybrid`/`onsite`); зарплата з вільного тексту; країна з локації; канонічний URL. Вакансію можна додати вручну або **імпортувати за URL** зі сторінки зі schema.org `JobPosting` у JSON-LD.

**Як працює.** [`Normalizer.normalize`](../../backend/src/main/java/com/vacancytracker/collection/normalize/Normalizer.java#L23-L65) повертає порожнє, якщо немає назви чи справжнього посилання. [`SalaryParser`](../../backend/src/main/java/com/vacancytracker/collection/normalize/SalaryParser.java#L16-L40) читає `€70–85k / yr`, `PLN 18–22k / mo`: символи й ISO-коди валют, суфікс `k`, роки/місяці/години, діапазони (перевернуті міняються місцями); gross/net не інтерпретується. [`CountryDetector`](../../backend/src/main/java/com/vacancytracker/collection/normalize/CountryDetector.java#L18) бере країну лише коли ціла частина локації між комами це назва країни (десять мов, «Georgia» виключено, двобуквені коди ігноруються) і воліє «невідомо» над «помилково» (бо хибна країна сховала б вакансію правилом `require`). [`JobPostingExtractor`](../../backend/src/main/java/com/vacancytracker/collection/JobPostingExtractor.java#L25-L60) читає JSON-LD, зокрема `@graph`, і розекранує розмітку, екрановану двічі. Імпорт за URL обмежений http(s), таймаутами й розміром, відхиляє приватні адреси ([`UrlFetcher`](../../backend/src/main/java/com/vacancytracker/collection/UrlFetcher.java#L113-L141)).

**Рішення.** Seniority навмисно **не** визначається: рівні це власний список користувача, а будь-який вбудований словник був би доменними даними в коді ([ADR-036](../decisions.md#adr-036-feed-adapter-country-detection-and-what-stays-undetected)). Автоматичні виведені факти (visa sponsorship, вік, ghost-сигнали) обчислюються з власних даних вакансії без зовнішніх сервісів (C20).

**Межі.** «Praha» чи «Berlin» без країни країни не дають (можна фільтрувати за `location`); сторінка без JobPosting-даних повертає 422, і вакансію додають вручну.

**Код.** [`Normalizer`](../../backend/src/main/java/com/vacancytracker/collection/normalize/Normalizer.java), [`CanonicalUrl`](../../backend/src/main/java/com/vacancytracker/common/CanonicalUrl.java#L30-L83), [`VacancyService.create`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L28-L55), [`VacancyController.importUrl`](../../backend/src/main/java/com/vacancytracker/web/VacancyController.java#L100-L109). **Тести.** [`NormalizerTest`](../../backend/src/test/java/com/vacancytracker/collection/normalize/NormalizerTest.java), [`SalaryParserTest`](../../backend/src/test/java/com/vacancytracker/collection/normalize/SalaryParserTest.java), [`CountryDetectorTest`](../../backend/src/test/java/com/vacancytracker/collection/normalize/CountryDetectorTest.java), [`JobPostingExtractorTest`](../../backend/src/test/java/com/vacancytracker/collection/JobPostingExtractorTest.java), [`ImportUrlTest`](../../backend/src/test/java/com/vacancytracker/web/ImportUrlTest.java).

---

### F3. Дедуплікація й ingest

**Що вміє.** Та сама вакансія з кількох джерел стає одним записом, що пам'ятає всі свої джерела (`vacancy_source`). Для кожного збереження результат це NEW, UPDATED або DUPLICATE, і ці лічильники видно в підсумку запуску.

**Як працює.** Три впорядковані правила: канонічний URL → `(adapter, externalId)` → консервативне fuzzy (сумісні місця + однакові заголовки з допуском на одну друкарську помилку) ([`Deduplicator`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L34-L103)). Ingest виконується в одній транзакції; для наявної вакансії читає рядок з `FOR UPDATE` і застосовує лише білий список автоматичних полів ([`IngestService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/IngestService.java#L36-L77)).

**Рішення.** Перша версія на Jaro-Winkler зливала різні вакансії («Berlin»/«London» 0.94; «Engineer I»/«II» 0.97); заміна на структурне правило і захист «різні id однієї дошки це різні вакансії» (C8, STAR S4). Унікальний індекс `vacancy_canonical_url_uk` гарантує інваріант на рівні БД. Автоматика не перезаписує поля, які редагував користувач (C3).

**Межі.** Консервативне правило пропустить переформульований заголовок; кандидати обмежені 200 на пошук.

**Тести.** [`DeduplicatorTest`](../../backend/src/test/java/com/vacancytracker/collection/dedup/DeduplicatorTest.java), [`CanonicalUrlTest`](../../backend/src/test/java/com/vacancytracker/common/CanonicalUrlTest.java), [`CollectionPipelineTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/CollectionPipelineTest.java).

---

### F4. Профілі пошуку, rule engine, списки компаній, види

**Що вміє.** Користувач описує, що він шукає, правилами, а не кодом. **Критерій** = поле + оператор + значення + режим (`require`/`prefer`/`avoid`/`exclude`) + вага 1..10 + політика відсутніх даних (`ignore`/`penalize`/`hide`) + підпис. **19 полів** (назва, опис, ключові слова, компанія, локація, країна, режим роботи, зарплата, мова, `visaSponsorship`, `relocationSupport`, `postedAge`, `ghostSignals`, джерело, теги та ін.), **12 операторів** (`contains_any/all`, `not_contains`, `equals`, `in`, `not_in`, `regex`, `gte`, `lte`, `between`, `exists`, `not_exists`). Профіль має пороги (`inboxMinScore` 40, `notifyMinScore` 70, `maxAgeDays` 60 за замовчуванням), список джерел і необов'язковий розклад ([`Thresholds`](../../backend/src/main/java/com/vacancytracker/matching/Thresholds.java#L1-L9), [search-profiles.md](../search-profiles.md)).

Навколо профілів: **редактор правил** із live-прев'ю (справжній rule engine і scorer по всіх збережених вакансіях пакетами по 500, лічильники й до 6 зразків із причинами, [`ProfilePreviewService`](../../backend/src/main/java/com/vacancytracker/matching/ProfilePreviewService.java#L23-L61)); **wizard** першого профілю; **версії** (кожне збереження, включно з відновленням, нова незмінна версія); **імпорт/експорт** YAML/JSON зі строгою валідацією; **allow/block-списки** (глобальні чи профільні, allow дає +10, block завжди перемагає); **збережені види й фільтри** на тій самій моделі `Criterion` (фільтр компілюється в параметризований SQL).

**Як працює.** [`ProfileValidator`](../../backend/src/main/java/com/vacancytracker/matching/ProfileValidator.java#L117) перевіряє поля, оператори за видом поля й форми значень до запису. [`RuleEngine`](../../backend/src/main/java/com/vacancytracker/matching/RuleEngine.java#L54-L121) обчислює вердикт (`SATISFIED`/`NOT_SATISFIED`/`MISSING`) і ефект; [`CriterionCompiler`](../../backend/src/main/java/com/vacancytracker/matching/CriterionCompiler.java#L53-L83) ту саму модель перетворює на `WHERE` із параметрами; [`SavedViewService`](../../backend/src/main/java/com/vacancytracker/matching/SavedViewService.java#L14-L60) зберігає критерії, сортування й колонки; [`CompanyListService`](../../backend/src/main/java/com/vacancytracker/matching/CompanyListService.java#L12-L77) змінює списки й запускає перерахунок.

**Рішення.** Одна модель для оцінювання й фільтрів (ADR-009); тризначна логіка й політики відсутніх даних (ADR-025); версії профілів і `profileVersion` у кожному збігу (ADR-010, C10); строгий імпорт, YAML-парсер без інстанціювання класів з тегів (ADR-027); block > allow, allow не перекриває `require`/`exclude` (ADR-028).

**Межі.** Регулярні вирази у видах виконує PostgreSQL (`~*`), у профілях Java, тож lookbehind чи `\b` поводяться по-різному; списки компаній порівнюють лише повні назви; прев'ю використовує глобальні списки (або власні списки редагованого профілю, якщо передано id).

**Код.** [`SearchProfileService`](../../backend/src/main/java/com/vacancytracker/matching/SearchProfileService.java#L14-L60), [`ProfileController`](../../backend/src/main/java/com/vacancytracker/web/ProfileController.java#L30-L157), [`ProfileTransfer`](../../backend/src/main/java/com/vacancytracker/web/ProfileTransfer.java), [`CompanyLists`](../../backend/src/main/java/com/vacancytracker/matching/CompanyLists.java), [`SavedViewController`](../../backend/src/main/java/com/vacancytracker/web/SavedViewController.java#L28-L69). **Тести.** [`RuleEngineTest`](../../backend/src/test/java/com/vacancytracker/matching/RuleEngineTest.java), [`CriterionCompilerTest`](../../backend/src/test/java/com/vacancytracker/matching/CriterionCompilerTest.java), [`ProfileValidatorTest`](../../backend/src/test/java/com/vacancytracker/matching/ProfileValidatorTest.java), [`SearchProfileServiceTest`](../../backend/src/test/java/com/vacancytracker/matching/SearchProfileServiceTest.java), [`ProfilePreviewServiceTest`](../../backend/src/test/java/com/vacancytracker/matching/ProfilePreviewServiceTest.java), [`CompanyListIntegrationTest`](../../backend/src/test/java/com/vacancytracker/matching/CompanyListIntegrationTest.java), [`ProfileTransferTest`](../../backend/src/test/java/com/vacancytracker/web/ProfileTransferTest.java).

---

### F5. Оцінювання, Inbox і автоматичне приховування

**Що вміє.** Кожна вакансія оцінюється кожним увімкненим профілем, до якого вона застосовна: оцінка 0–100 (або «відфільтровано») і список причин людською мовою («+8: Keyword in description», «-5: … (not stated)», «Filtered out: …»). У списках показується найкраща оцінка. Профіль може відкидати за віком (`maxAgeDays`) і за «ghost»-сигналами. **Inbox** показує нові вакансії, які принаймні один профіль приймає.

**Як працює.** [`MatchService.matchWith`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L102-L127) читає вакансію з `FOR UPDATE`, будує факти ([`VacancyFacts.of`](../../backend/src/main/java/com/vacancytracker/matching/VacancyFacts.java#L25)), для кожного профілю рахує [`Scorer`](../../backend/src/main/java/com/vacancytracker/matching/Scorer.java#L50-L106), upsert-ить `VacancyMatch` і прибирає матчі профілів, що вже не застосовні. Потім [`applyInboxRule`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L129-L171) може перевести *New* ↔ *Hidden*, але лише для вакансій з джерелом, чия історія повністю автоматична. Зміна профілю чи списку компаній запускає [`RecalculationService`](../../backend/src/main/java/com/vacancytracker/matching/RecalculationService.java#L60-L118): одна фонова задача, запит під час задачі ставить у чергу рівно один додатковий прохід, помилки рахуються й задачу не зупиняють.

**Рішення.** Детермінована формула з цілочисельним округленням і поясненням кожного бала (C9); лічильник `generation` не дає довгому запуску перетерти свіжий перерахунок оцінками зі старого профілю (C10); автоматика ніколи не торкається того, що чіпав користувач (ADR-026, ADR-038).

**Межі.** Оцінка залежить від якості правил користувача; немає історії оцінок (рядок `vacancy_match` замінюється, зберігається лише версія профілю).

**Тести.** [`ScorerTest`](../../backend/src/test/java/com/vacancytracker/matching/ScorerTest.java), [`MatchServiceTest`](../../backend/src/test/java/com/vacancytracker/matching/MatchServiceTest.java), [`InboxRuleTest`](../../backend/src/test/java/com/vacancytracker/matching/InboxRuleTest.java), [`RecalculationServiceTest`](../../backend/src/test/java/com/vacancytracker/matching/RecalculationServiceTest.java), [`RecalculationIntegrationTest`](../../backend/src/test/java/com/vacancytracker/matching/RecalculationIntegrationTest.java).

---

### F6. Пайплайн заявок

**Що вміє.** Збережені вакансії проходять **13 статусів** (New, Saved, Applied, Screening, Test task, Interview, Offer, Accepted, Rejected, Withdrawn, Expired, Dismissed, Hidden) за явною таблицею переходів; кожна зміна лишає запис в історії. Екран **Pipeline**: таблиця (сортування, вибір колонок, липка шапка, сторінки по 100, картки на телефоні) і **Kanban** (перемикач Table/Board; перетягування мишею плюс клавіатурна альтернатива); **фільтри-чипи** та збережені види зі станом в URL; **масові зміни статусу з Undo** (рядок показує, скільки з вибраних можуть перейти в цільовий статус, «Saved (4 of 6)»); **drawer вакансії** (`?open=<id>`): факти, лише дозволені переходи, редагований опис і нотатки, пріоритет, теги, контакт, дата і нотатка наступної дії, історія, посилання на оригінал. Ручне додавання вакансії й імпорт за URL.

**Як працює.** Агрегат [`Vacancy`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L139-L174) і таблиця [`VacancyStatus`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyStatus.java#L27-L42); складні зміни йдуть найкоротшим шляхом [`StatusPath`](../../backend/src/main/java/com/vacancytracker/vacancy/StatusPath.java#L23-L48); масові зміни й Undo в [`BulkStatusService`](../../backend/src/main/java/com/vacancytracker/vacancy/BulkStatusService.java#L47-L110) (до 500 за раз, причини пропуску по id); усі записи беруть блокування рядка ([`VacancyService`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L139-L200)).

**Рішення.** Статуси як таблиця в коді, дзеркало в UI з тестом (C4); історія лише на додавання як джерело істини для воронки; Undo як окрема операція, що перевіряє очікуваний статус; вибір статусу в `<select>` не комітить, а застосовує кнопка **Move** (C18).

**Межі.** Відновлення термінальної вакансії проходить через *New*; один `PATCH` не атомарний між полями різних груп (кожна група окремо).

**Код.** [`VacancyController`](../../backend/src/main/java/com/vacancytracker/web/VacancyController.java#L39-L122), [`BulkController`](../../backend/src/main/java/com/vacancytracker/web/BulkController.java#L18-L43), фронтенд [`Pipeline.tsx`](../../frontend/src/screens/Pipeline.tsx), [`Board.tsx`](../../frontend/src/components/Board.tsx), [`VacancyDrawer.tsx`](../../frontend/src/screens/VacancyDrawer.tsx), [`kanban.ts`](../../frontend/src/domain/kanban.ts), [`bulk.ts`](../../frontend/src/domain/bulk.ts). **Тести.** [`StatusPathTest`](../../backend/src/test/java/com/vacancytracker/vacancy/StatusPathTest.java), [`VacancyStatusTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VacancyStatusTest.java), [`BulkStatusServiceTest`](../../backend/src/test/java/com/vacancytracker/vacancy/BulkStatusServiceTest.java), [`VacancyControllerTest`](../../backend/src/test/java/com/vacancytracker/web/VacancyControllerTest.java), [`VacancyFilterApiTest`](../../backend/src/test/java/com/vacancytracker/web/VacancyFilterApiTest.java), [`Board.test.tsx`](../../frontend/src/components/Board.test.tsx).

---

### F7. Dashboard і follow-up

**Що вміє.** П'ять KPI (New today, Inbox, Active, In interviews, Overdue), п'ять найкращих New за оцінкою, п'ять найближчих «наступних дій» (із позначкою «прострочено»), **воронка** Saved → Applied → Screening → Interview → Offer, **заявки по тижнях** за останні 8 тижнів, **«Time to follow up»** (заявки в Applied/Screening/Interview без зміни статусу й без листа 10 днів, `APP_FOLLOWUP_QUIET_DAYS`, якщо не заплановано пізнішу дію) і статистика відповідей (скільки подалось, скільки відповіло, медіана днів, розподіл результатів).

**Як працює.** Усе рахується SQL-ом з **історії статусів**, а не з колонки `applied_at` (її ніколи не заповнювали, ADR-038). Щоб скасована масовою дією зміна не рахувалась як досягнутий етап, запит `EFFECTIVE_EVENTS` віконною функцією `lead()` відкидає подію, після якої йде запис «undo» ([`DashboardService`](../../backend/src/main/java/com/vacancytracker/vacancy/DashboardService.java#L32-L36), [`funnel`](../../backend/src/main/java/com/vacancytracker/vacancy/DashboardService.java#L149-L175), [`weeks`](../../backend/src/main/java/com/vacancytracker/vacancy/DashboardService.java#L177-L198), [`followUps`](../../backend/src/main/java/com/vacancytracker/vacancy/DashboardService.java#L122)); для однакових часових міток undo впорядковується після скасованої зміни. Статистика відповідей в [`RepliesService`](../../backend/src/main/java/com/vacancytracker/vacancy/RepliesService.java#L19-L42).

**Тести.** [`DashboardServiceTest`](../../backend/src/test/java/com/vacancytracker/vacancy/DashboardServiceTest.java), [`RepliesServiceTest`](../../backend/src/test/java/com/vacancytracker/vacancy/RepliesServiceTest.java), [`DashboardApiTest`](../../backend/src/test/java/com/vacancytracker/web/DashboardApiTest.java), [`DashboardRepliesApiTest`](../../backend/src/test/java/com/vacancytracker/web/DashboardRepliesApiTest.java).

---

### F8. Пошук і командна палітра

**Що вміє.** `GET /api/search`: повнотекстовий пошук за назвою, компанією й описом із ранжуванням (збіг у назві +1.0, у компанії +0.5) і сніпетами; палітра **Ctrl/Cmd+K** шукає вакансії й веде до екранів. Окремо Inbox має фільтр за словами в назві чи описі («всі» або «будь-яке», «фраза в лапках», виключення; `/` фокусує поле).

**Як працює.** Згенерована колонка `tsvector` (`simple`) з GIN-індексом; ввід очищується до літер і цифр, кожне слово префікс, максимум 8 термінів ([`VacancySearchService`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancySearchService.java#L28-L56), [`SearchQuery`](../../backend/src/main/java/com/vacancytracker/vacancy/SearchQuery.java#L27)). Фільтр Inbox використовує ті самі keyword-критерії, що й види Pipeline. Деталі й чесна примітка про розбіжність з ADR-015: C11.

**Тести.** [`SearchQueryTest`](../../backend/src/test/java/com/vacancytracker/vacancy/SearchQueryTest.java), [`VacancySearchServiceTest`](../../backend/src/test/java/com/vacancytracker/vacancy/VacancySearchServiceTest.java), [`CommandPalette.test.tsx`](../../frontend/src/components/CommandPalette.test.tsx), [`inbox-filter.spec.ts`](../../e2e/tests/inbox-filter.spec.ts).

---

### F9. Резюме та fit-оцінка

**Що вміє.** Завантаження резюме (PDF, DOCX, TXT, Markdown) або вставка тексту, до 5 МБ; формат визначається за байтами, а не за заявою браузера; скан-PDF без тексту відхиляється з поясненням (OCR немає). Ключові слова **пропонуються** з секції «Skills» і за формою термінів (`C#`, `Node.js`, `CI/CD`), а користувач їх редагує. Для кожної вакансії показується **fit 0–100** («N of M skills») із причинами: збіги, відсутні навички, назва, роки досвіду.

**Як працює.** [`ResumeFitScorer`](../../backend/src/main/java/com/vacancytracker/application/fit/ResumeFitScorer.java#L31-L93): 85 × покриття, +15 якщо ключове слово резюме є в назві, −15 якщо вакансія просить більше років, ніж показує резюме; роки з обʼєднаних діапазонів дат або «N years». Застарілість fit визначається порівнянням MD5 тексту прямо в SQL; зміна резюме й кінець запуску запускають фонове перерахування ([`ResumeFitService`](../../backend/src/main/java/com/vacancytracker/application/fit/ResumeFitService.java#L60-L107)). Екстрактор: [`ResumeTextExtractor`](../../backend/src/main/java/com/vacancytracker/application/resume/ResumeTextExtractor.java#L76-L78).

**Межі.** Синоніми не зливаються (`k8s` ≠ `Kubernetes`); терміни за формою ловлять деякі не-навички, тож «missing» це порада, не вирок.

**Тести.** [`ResumeFitScorerTest`](../../backend/src/test/java/com/vacancytracker/application/fit/ResumeFitScorerTest.java), [`TermsTest`](../../backend/src/test/java/com/vacancytracker/application/resume/TermsTest.java), [`ResumeTextExtractorTest`](../../backend/src/test/java/com/vacancytracker/application/resume/ResumeTextExtractorTest.java), [`ResumeApiTest`](../../backend/src/test/java/com/vacancytracker/web/ResumeApiTest.java).

---

### F10. Пресети, AI-документи, Employer/Goal match, пакети заявок

**Що вміє (усе опційно, AI вимкнено за замовчуванням).**
- **Пресети**: мета пошуку (Main goal), зважені параметри цілі (ваги 0..1000, показуються частками), промпти резюме й супровідного листа, базове резюме й базовий лист. Завжди є хоча б один пресет і рівно один дефолтний; тексти за замовчуванням живуть у `resources/presets/default`, а не в коді ([`PresetService`](../../backend/src/main/java/com/vacancytracker/application/preset/PresetService.java#L96-L203), [`PresetDefaults`](../../backend/src/main/java/com/vacancytracker/application/preset/PresetDefaults.java#L13)).
- **Генерація документів для вакансії**: «Use as is» (базова чернетка без AI) або «Generate with AI» (адаптація базового документа), порядкова різниця з базою, ручне редагування, збереження як пронумерованої незмінної версії зі снепшотом того, з чого її зроблено ([`DocumentService`](../../backend/src/main/java/com/vacancytracker/application/documents/DocumentService.java#L70-L215)).
- **Employer match** (наскільки документ покриває чекліст вимог роботодавця) і **Goal match** (наскільки вакансія підходить меті користувача), «Base 62% → Tailored 78% (+16)» ([`AnalysisService`](../../backend/src/main/java/com/vacancytracker/application/analysis/AnalysisService.java#L88-L260)).
- **Fact check** перед збереженням: нові числа, дати, роботодавці, посади й технології, яких немає в базових документах ([`FactCheck`](../../backend/src/main/java/com/vacancytracker/application/documents/FactCheck.java#L19-L50)).
- **PDF резюме** ≤ 2 сторінки, A4, одна колонка, справжній текст, вбудований Liberation Sans із кирилицею ([`ResumePdf`](../../backend/src/main/java/com/vacancytracker/application/documents/ResumePdf.java#L47-L85)).
- **Пакет заявки**: вибрані версії, ZIP (документи, PDF, вакансія, чекліст), відкриття сторінки подачі в новій вкладці; «I submitted» переводить вакансію в *Applied* ([`PackageService`](../../backend/src/main/java/com/vacancytracker/application/packaging/PackageService.java#L59-L160)). Застосунок **ніколи сам не заповнює і не подає форму**.

**Як працює і чому так.** Єдині двері до моделі [`AiService`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L22-L90) відмовляють до відправки, якщо провайдера не налаштовано або немає згоди (по провайдеру, відкликувана); три реалізації `AiClient` (`cli` у пісочниці, `api`, `fake`); дані у промптах в теговних блоках з нейтралізацією ін'єкцій; оцінки Employer match рахує код після буквальної перевірки цитат; результати кешуються за MD5 тексту. Повний розбір: C14, C15, C16 і історія S5.

**Межі.** `cli` працює лише там, де встановлено й залогінено Claude Code (не в Docker-образі); перше відкриття вакансії з AI коштує до чотирьох викликів; перевірка фактів навмисно буквальна.

**Тести.** [`ClaudeCliClientTest`](../../backend/src/test/java/com/vacancytracker/application/ai/ClaudeCliClientTest.java), [`QuoteCheckTest`](../../backend/src/test/java/com/vacancytracker/application/analysis/QuoteCheckTest.java), [`FactCheckTest`](../../backend/src/test/java/com/vacancytracker/application/documents/FactCheckTest.java), [`ResumePdfTest`](../../backend/src/test/java/com/vacancytracker/application/documents/ResumePdfTest.java), [`PresetApiTest`](../../backend/src/test/java/com/vacancytracker/web/PresetApiTest.java), [`PresetGenerationApiTest`](../../backend/src/test/java/com/vacancytracker/web/PresetGenerationApiTest.java), [`PackageApiTest`](../../backend/src/test/java/com/vacancytracker/web/PackageApiTest.java), [`full-cycle.spec.ts`](../../e2e/tests/full-cycle.spec.ts), [`m7-presets.spec.ts`](../../e2e/tests/m7-presets.spec.ts).

---

### F11. Пошта: джерело-сповіщення і відстеження відповідей

**Що вміє.** Два незалежні режими читання одного IMAP-з'єднання, лише для читання:
1. **Листи-сповіщення** (LinkedIn, Jobs.cz, StartupJobs, Indeed) як джерела вакансій у виділеній папці (за замовчуванням «Job alerts»).
2. **Кореспонденція**: відповіді роботодавців і повідомлення LinkedIn про заявки в папках з `APP_MAIL_REPLIES_FOLDERS` (за замовчуванням INBOX). Лист класифікується (відмова, оффер, тестове завдання, інтерв'ю, заявку переглянули, заявку отримано, лист рекрутера, інше про роботу), зв'язується із заявкою з поясненням причини, а зміна статусу **пропонується** (користувач приймає або відхиляє).

Ще: екран **Mail** із фільтрами, лічильниками по категоріях і вибором частоти читання (5/10/15/30/45/60 хв, зберігається в БД, [`MailSettings`](../../backend/src/main/java/com/vacancytracker/application/mail/MailSettings.java#L15-L30)); «Check mail now» і «Re-read mail»; імпорт `.eml`; ручне зв'язування листа із заявкою; **дайджести** (лист-сповіщення зі списком вакансій зберігає до 30 з них, кожну можна додати в пайплайн, [`digestJobs`](../../backend/src/main/java/com/vacancytracker/application/mail/CorrespondenceService.java#L361-L376)); **дата й час інтерв'ю чи тестового** з тексту листа ([`MailEventExtractor`](../../backend/src/main/java/com/vacancytracker/application/mail/MailEventExtractor.java#L132)); видалення збереженої копії (скринька не змінюється).

**Як працює.** Читання, ідемпотентність і зв'язування описані в C13. [`CorrespondenceService.handle`](../../backend/src/main/java/com/vacancytracker/application/mail/CorrespondenceService.java#L301-L341): класифікація → відсікання `NOT_JOB` і `JOB_ALERT` → пошук наявного → зв'язування ([`MailLinker`](../../backend/src/main/java/com/vacancytracker/application/mail/MailLinker.java#L45-L88)) → пропозиція статусу лише тоді, коли лист рухає заявку вперед або завершує її дозволеним шляхом ([`suggestion`](../../backend/src/main/java/com/vacancytracker/application/mail/CorrespondenceService.java#L379-L394)) → одна атомарна вставка. Планувальник тікає щохвилини й читає, коли пройшов вибраний інтервал ([`@Scheduled`](../../backend/src/main/java/com/vacancytracker/application/mail/CorrespondenceService.java#L200)).

**Рішення.** Лист ніколи не йде в AI; зберігається лише робоча пошта (відправник, тема, дата, перші 600 символів); `@Transactional` свідомо не стоїть на `handle`, бо `sync()` і `importEml()` викликають його на тому самому біні й проксі Spring цей виклик не бачив би (див. коментар у [рядках 298-300](../../backend/src/main/java/com/vacancytracker/application/mail/CorrespondenceService.java#L298-L300)): атомарності досягає одна `INSERT ... ON CONFLICT DO NOTHING`.

**Межі.** Формулювання поза списками фраз дають «робочий лист» без пропозиції; зв'язування може не впіймати лист агенції; опитування, не IMAP IDLE.

**Тести.** [`CorrespondenceSyncTest`](../../backend/src/test/java/com/vacancytracker/application/mail/CorrespondenceSyncTest.java), [`MailClassifierTest`](../../backend/src/test/java/com/vacancytracker/application/mail/MailClassifierTest.java), [`MailLinkerTest`](../../backend/src/test/java/com/vacancytracker/application/mail/MailLinkerTest.java), [`MailEventExtractorTest`](../../backend/src/test/java/com/vacancytracker/application/mail/MailEventExtractorTest.java), [`CorrespondenceDigestTest`](../../backend/src/test/java/com/vacancytracker/application/mail/CorrespondenceDigestTest.java), [`MailSchedulingTest`](../../backend/src/test/java/com/vacancytracker/application/mail/MailSchedulingTest.java), [`ImapMailReaderTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/ImapMailReaderTest.java), [`SenderParsersTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/SenderParsersTest.java).

---

### F12. Сповіщення в Telegram

**Що вміє (вимкнено за замовчуванням).** Після кожного запуску збору **одне** повідомлення з новими збігами (статус New), чия найкраща оцінка досягла `notifyMinScore` свого профілю, від найкращих; і окреме повідомлення про листи, що пропонують зміну статусу (категорія, вакансія, компанія, запропонований статус; без тексту листа).

**Як працює.** SQL-вибірка кандидатів: лише `New`, увімкнені профілі, не приховані, оцінка ≥ порогу, побачені за останні 3 дні, ще не оголошені (`notified_vacancy`) ([`TelegramDigestService`](../../backend/src/main/java/com/vacancytracker/notification/TelegramDigestService.java#L49-L60)). Повідомлення простим текстом (без розмітки, щоб назви не могли впровадити форматування), не більше 10 позицій і не більше 4096 символів ([`sendDigest`](../../backend/src/main/java/com/vacancytracker/notification/TelegramDigestService.java#L115-L142), [`message`](../../backend/src/main/java/com/vacancytracker/notification/TelegramDigestService.java#L144)). Вакансія запам'ятовується як оголошена **лише після** того, як Telegram прийняв повідомлення, тож невдала відправка повториться. Дайджест виконується в окремому потоці після завершення запуску й ніколи не затримує й не валить запуск ([`addDoneListener`](../../backend/src/main/java/com/vacancytracker/notification/TelegramDigestService.java#L102-L105)). Токен бота не потрапляє в логи (він у URL запиту).

**Межі.** Назви вакансій бачить усякий, хто читає чат; кнопки «надіслати тест» немає.

**Тести.** [`TelegramDigestServiceTest`](../../backend/src/test/java/com/vacancytracker/notification/TelegramDigestServiceTest.java), [`MailSuggestionNotifierTest`](../../backend/src/test/java/com/vacancytracker/notification/MailSuggestionNotifierTest.java), [`TelegramForwardAdapterTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/telegram/TelegramForwardAdapterTest.java).

---

### F13. Експорт, імпорт, бекап

**Що вміє.** Експорт вакансій у **CSV** (UTF-8 з BOM, CRLF, RFC 4180, потоково пачками, з вибором колонок і фільтром), **XLSX** (стрімінговий `SXSSF`, числа зарплат лишаються числами, клітинки обрізаються на 32 767 символах) і повний **JSON-бекап** (`schemaVersion`, вакансії з історією статусів, профілі з версіями, види, списки компаній, пресети з базовими файлами в Base64). Імпорт JSON і CSV із **помилками по рядках**; CSV-імпорт зливається з наявними вакансіями й зберігає відредаговані користувачем поля, порожня клітинка чи відсутня колонка не стирають збережене. Щоденний `pg_dump` у контейнері `backup`.

**Рішення, що варто назвати.** **Захист від формульної ін'єкції в CSV**: клітинка, що починається з `=`, `+`, `-`, `@`, табуляції чи CR, отримує апостроф, бо текст вакансій походить від третіх сторін, а Excel виконає його як формулу; імпортер знімає рівно один апостроф, тож клітинка, що вже починалась з апострофа й формульного символа, повертається як була ([`VacancyExporter`](../../backend/src/main/java/com/vacancytracker/export/VacancyExporter.java#L66-L75), [`VacancyImporter.GUARDED`](../../backend/src/main/java/com/vacancytracker/export/VacancyImporter.java#L250-L256)). Експорт читає в стабільному порядку (перше побачення), щоб запуск під час бекапу не змістив рядки між сторінками. Тіла імпорту понад 20 МБ відхиляються.

**Тести.** [`VacancyCsvExportTest`](../../backend/src/test/java/com/vacancytracker/export/VacancyCsvExportTest.java), [`VacancyXlsxExportTest`](../../backend/src/test/java/com/vacancytracker/export/VacancyXlsxExportTest.java), [`CsvParserTest`](../../backend/src/test/java/com/vacancytracker/export/CsvParserTest.java), [`BackupRoundTripTest`](../../backend/src/test/java/com/vacancytracker/export/BackupRoundTripTest.java), скрипт [`test-backup.sh`](../../ops/backup/test-backup.sh).

---

### F14. Безпека й автентифікація

**Що вміє.** Профіль `local`: без логіну, лише `127.0.0.1`. Профіль `vps`: єдиний токен (≥ 16 символів, інакше застосунок не стартує) через `Authorization: Bearer` або `HttpOnly SameSite=Strict` cookie, блокування адреси після 5 хибних токенів за 10 хвилин; HTTPS через Caddy; тільки Caddy відкритий назовні. На кожен запит: перевірка `Host` проти DNS rebinding, перевірка `Origin` проти cross-site записів, суворий CSP та інші заголовки; імпорт за URL не сягає внутрішньої мережі; у БД і UI зберігаються лише http(s)-посилання; секрети маскуються в логах; AI вимкнено за замовчуванням і потребує згоди.

**Як працює.** Таблиця «загроза → захист → код» у [C12](interview-prep.uk.md#c12-безпека-для-застосунку-що-працює-на-localhost); історії S2 і S6 у [star-stories.md](star-stories.md). Фільтри: [`SecurityHeadersFilter`](../../backend/src/main/java/com/vacancytracker/web/SecurityHeadersFilter.java#L22-L48) → [`HostFilter`](../../backend/src/main/java/com/vacancytracker/web/HostFilter.java#L25-L70) → [`OriginFilter`](../../backend/src/main/java/com/vacancytracker/web/OriginFilter.java#L22-L50) → (vps) [`AuthFilter`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthFilter.java#L33-L60).

**Межі.** Cookie це і є токен; таблиця збоїв у пам'яті; секрет без відомої форми не маскується (повний список у C12).

---

### F15. Фронтенд, UX і доступність

**Що вміє.** Одно-сторінковий застосунок (Preact + TypeScript strict, без UI-бібліотеки; збирається в статику, яку роздає jar). **Екрани:** Dashboard, Inbox, Pipeline, Mail, Search profiles, Presets, Resume, Sources & Runs, Export/Import (Transfer), Settings, drawer вакансії та wizard першого запуску. Стан фільтрів, сортування й відкритої вакансії живе в URL. **Клавіатурне сортування Inbox** (`J`/`K`, `S`, `X`, `O`, `Enter`), працює і з українською/російською розкладкою ([`triage.ts`](../../frontend/src/domain/triage.ts#L19-L35)). **Теми:** light/dark/system; текст 18/20/22 px; Beacon → Graphite → Liquid Glass із вимикачем скла; без жодних запитів до інтернету (системні шрифти). **Доступність як тести:** 58 пар контрасту (7:1 текст, 3:1 межі) перевіряє юніт-тест; смуги збігів це `meter` з озвученим значенням; дані завантажуються через `dataVersion`, щоб Refresh не губив незбережене.

**Код.** [`App.tsx`](../../frontend/src/App.tsx#L68), [`router.ts`](../../frontend/src/router.ts#L12), [`api/`](../../frontend/src/api), [`domain/`](../../frontend/src/domain) (чиста логіка з тестами), [`screens/`](../../frontend/src/screens), [`components/`](../../frontend/src/components). **Тести.** 82 файли, 726 тестів (запущено в цьому аналізі), 19 сценаріїв Playwright у [`e2e/tests`](../../e2e/tests). Деталі й ADR: C18, [ADR-052](../decisions.md#adr-052-beacon-design-light-first), [ADR-053](../decisions.md#adr-053-graphite-a-calm-light-look-ink-pills-and-a-black-dark-theme-over-beacon), [ADR-054](../decisions.md#adr-054-glass-surfaces-over-graphite).

**Межі.** Ручні перевірки зі скрінрідером і справжнім масштабуванням 200% не виконані.

---

### F16. Розгортання, операції, спостережуваність

**Що вміє.** `docker compose up -d --build` піднімає `postgres` (16, том, healthcheck), `app` (multi-stage образ, non-root, `cap_drop: ALL`, `no-new-privileges`, ліміти пам'яті, порт лише на `127.0.0.1`) і `backup` (`pg_dump` у custom-форматі, дамп приймається після `pg_restore --list`, зберігання за віком). На VPS додається `caddy` з автоматичним HTTPS ([`docker-compose.vps.yml`](../../docker-compose.vps.yml), [`Caddyfile`](../../ops/caddy/Caddyfile)). Міграції Flyway виконуються при старті. Graceful shutdown: запуск зупиняється між джерелами, за 30 с позначається `interrupted`; при старті осиротілі `running`-запуски закриваються ([`StaleRunSweeper`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/StaleRunSweeper.java#L38)). Логи: читабельний текст локально й JSON по рядку в `vps` ([`logback-spring.xml`](../../backend/src/main/resources/logback-spring.xml)), з маскуванням секретів і по одному рядку з ключовими полями на джерело та запуск; `/actuator/health` без деталей.

**Межі.** Немає ендпоінта метрик; бекап на тому самому хості, що й база (друга незалежна копія це JSON-експорт); один інстанс.

**Документи.** [deployment.md](../deployment.md), [operations.md](../operations.md).

---

### F17. Якість: тести, CI, процес

**Що вміє.** Один шлях контролю якості: `mvn verify` = тести (юніт + інтеграційні на **одному спільному Testcontainer PostgreSQL 16** на JVM) + Checkstyle (падає при порушенні) + JaCoCo 80% рядків для `vacancy`, `collection`, `matching`, `application` + ArchUnit. CI з чотирьох джоб: узгодженість `PLAN.md` і `tasks.csv`; backend verify; frontend typecheck, тести з покриттям, збірка, `npm audit --omit=dev --audit-level=high`; Playwright smoke проти запакованого jar на порожній БД ([`ci.yml`](../../.github/workflows/ci.yml)). Адаптери тестуються **лише на фікстурах** проти локального `HttpServer`, без справжньої мережі.

**Процес.** План із ID задач і критеріями прийняття ([PLAN.md](../../PLAN.md), дубль у `tasks.csv`), запис у CHANGELOG на кожну задачу, 54 ADR, незалежні рев'ю коду, знахідки яких відстежуються ([code-review.md](../code-review.md)), аналіз прогалин проти конкурента ([gap-analysis.md](../gap-analysis.md)). Деталі: C19.

---

## 5. Модель даних за міграціями

Схема змінюється лише Flyway-міграціями ([`db/migration`](../../backend/src/main/resources/db/migration)); на старті виконуються всі. Ключові групи (UUID-ключі, `timestamptz` у UTC):

| Міграція | Що додає |
|---|---|
| V1 | `vacancy` (з згенерованим `search_vector` і GIN-індексом, `CHECK` на статус, унікальний частковий індекс канонічного URL), `vacancy_source`, `status_event`, `saved_view` |
| V2, V3, V4, V5 | `source_state` (здоров'я й circuit breaker), `http_cache` (ETag/Last-Modified), `run` і `run_source_stat`, область запуску, `source_quota` (бюджет викликів) |
| V6, V8, V9 | Профілі пошуку з версіями й критеріями; `vacancy_match` (оцінка, причини, версія профілю на пару вакансія×профіль); `company_list` |
| V7 | `exchange_rate` (курси ECB) |
| V10, V11, V13 | `processed_mail`, `notified_vacancy`, офсет Telegram `getUpdates` |
| V12 (Java) | Одноразове заповнення `visa_sponsorship` для раніше збережених вакансій |
| V14, V18 | Резюме й fit-оцінки; виправлення після рев'ю (копія резюме в пакеті, хеш тексту fit) |
| V15, V16 | Згода на AI, версії документів; пакети заявок |
| V17, V25, V27, V28 | Кореспонденція; список вакансій дайджесту; дата й час події з листа; виправлення компанії/локації для card-link листів |
| V19, V20, V21, V22, V23 | Пресети AI; снепшоти генерації; чекліст/Employer match/Goal match; PDF резюме; результати fact check |
| V24, V26 | Індекс за нижнім регістром компанії; `app_setting` (напр. інтервал читання пошти) |

Повна ERD (Mermaid) у [architecture.md](../architecture.md).

---

## 6. Каталог API

Усі контролери лежать у `web`; помилки у форматі problem-detail (RFC 9457), 422 з помилками по полях, 409 для неприпустимого переходу ([`ApiExceptionHandler`](../../backend/src/main/java/com/vacancytracker/web/ApiExceptionHandler.java)).

| Область | Ендпоінти |
|---|---|
| Вакансії | `GET/POST /api/vacancies`, `GET/PATCH /api/vacancies/{id}`, `POST …/{id}/status`, `GET …/{id}/matches`, `GET …/{id}/history`, `POST /api/vacancies/import-url`; список підтримує `status`, `filter` (JSON критеріїв), `sort`, `page`, `size` |
| Масові зміни | `POST /api/bulk/vacancies/status`, `POST …/undo` |
| Запуски й джерела | `POST /api/runs`, `GET /api/runs/latest`, `GET /api/runs/{id}`, `GET /api/runs/{id}/events` (SSE); `GET /api/sources`, `POST /api/sources/{id}/refresh` |
| Профілі | `GET/POST/PUT/DELETE /api/profiles…`, `/meta`, `/validate`, `/preview`, `/import`, `/{id}/export`, `/{id}/versions…`, `/{id}/versions/{v}/restore` |
| Види, списки, пошук | `/api/views…` (CRUD і `/{id}/vacancies`), `/api/company-lists…`, `GET /api/search` |
| Перерахунок | `POST/GET /api/recalculation` |
| Dashboard | `GET /api/dashboard`, `GET /api/dashboard/replies` |
| Резюме й fit | `/api/resume` (файл, текст, ключові слова, оригінал), `GET /api/resume/fits`, `GET /api/vacancies/{id}/fit`, `POST /api/resume/recalculate` |
| Пресети | `/api/presets…` (CRUD, duplicate, default, базове резюме й лист файлом/текстом) |
| AI і документи | `/api/ai/status`, `POST/DELETE /api/ai/consent`; `…/documents/draft`, `/shorten`, `/as-is`, `/facts`, збереження, `GET /api/documents/{id}/snapshot` і `/download` |
| Аналіз | `GET/POST /api/vacancies/{id}/analysis`, `POST …/{id}/match` |
| Пакети | `POST/GET /api/vacancies/{id}/packages`, `GET /api/packages/{id}/download`, `POST …/submitted` |
| Пошта | `/api/mail/status`, `/settings`, список, `/counts`, `/applications`, `/sync/start`, `/reread`, `/sync`, `/import`, `/{id}/accept`, `/{id}/dismiss`, `/{id}/vacancy`, `/{id}/jobs/{i}/add`, `/delete`, `/delete-old` |
| Дані | `GET /api/export/backup.json`, `/vacancies.xlsx`, `/vacancies.csv`; `POST /api/import/vacancies` (JSON чи CSV) |
| Автентифікація | `GET /api/auth/status`, `POST /api/auth/login`, `POST /api/auth/logout` |

---

## 7. Конфігурація

Усе через `.env` (приклад: [`.env.example`](../../.env.example)); секрети лише там і маскуються в логах.

| Група | Змінні (суть) |
|---|---|
| База й профіль | `POSTGRES_*`, `SPRING_PROFILES_ACTIVE` (`local`/`vps`) |
| Доступ (vps) | `APP_ACCESS_TOKEN` (≥ 16), `APP_AUTH_MAX_FAILURES` (5), `APP_AUTH_FAILURE_WINDOW_MINUTES` (10), `DOMAIN`, `APP_ALLOWED_HOSTS` (з `DOMAIN`) |
| Джерела | `APP_SOURCES_GREENHOUSE_BOARDS`, `…LEVER_SITES`, `…ASHBY_BOARDS`, Adzuna (id, ключ, країни, валюти), Himalayas (ключ), параметри Jobicy/Remotive/Arbeitnow |
| Пошта | `APP_MAIL_IMAP_*` (host, port, ssl, user, пароль, папка, `MAX_AGE_DAYS` 14, `MAX_PER_RUN` 50, `MARK_PROCESSED`), `APP_MAIL_REPLIES_FOLDERS` |
| Telegram | `APP_NOTIFICATIONS_TELEGRAM_*` (enabled, токен, chat id, `MAX_ITEMS` 10, `MAX_AGE_DAYS` 3, `MAIL_SUGGESTIONS`) |
| AI | `APP_AI_PROVIDER` (`off`/`cli`/`api`/`fake`), `ANTHROPIC_API_KEY`, `APP_AI_CLI_COMMAND` |
| Розклади й пороги | `APP_COLLECTION_CRON`, `APP_COLLECTION_EXPIRY_RUNS` (3), `APP_RATES_*`, `APP_MATCHING_ALLOWLIST_BONUS` (10), `APP_FOLLOWUP_QUIET_DAYS` (10) |
| Бекап | `BACKUP_INTERVAL_SECONDS` (86400), `BACKUP_KEEP_DAYS` (14) |
| Імпорт | `APP_IMPORT_ALLOW_PRIVATE_HOSTS` (false) |

---

## 8. Стек технологій

- **Бекенд:** Java 21, Spring Boot 3.5.6, Maven; PostgreSQL 16, Flyway, `JdbcClient` з явним SQL; Jsoup (HTML), Rome (RSS/Atom), Jakarta Mail (IMAP), Apache POI (XLSX, DOCX), Apache PDFBox (читання резюме й запис PDF), Anthropic Java SDK (опційний AI), Actuator, commons-text (схожість рядків).
- **Фронтенд:** Vite, TypeScript (strict), Preact, без UI-бібліотеки, графіки SVG; Vitest + jsdom.
- **Якість:** Checkstyle, JaCoCo, ArchUnit, Testcontainers, Playwright; GitHub Actions; Dependabot.
- **Розгортання:** Docker Compose, Caddy, pg_dump; секрети в `.env`.

---

## 9. Історія розробки і процес

- Історія git охоплює приблизно два тижні (2026-11-03 – 2026-11-16, 87 комітів, 26 merge-комітів); план і CHANGELOG датуються з 2026-11-01. Автори комітів: власник (під двома іменами) і Claude, плюс Dependabot; у CHANGELOG є записи про виправлення після рев'ю іншим AI-інструментом («Codex»).
- Віхи: **M0** план і дизайн; **M1** ядро трекера; **M2** збір; **M3** оцінювання; **M4** пошта й сповіщення; **M5** загартування (бекап, токен, логи, ArchUnit, smoke-тест, VPS); **M6** повний цикл заявки (резюме, fit, AI, пакети, кореспонденція, нові джерела); **M7** пресети AI, match, PDF, fact check; потім запити власника: фільтр Inbox і дизайн Beacon з подальшим Graphite і Liquid Glass.
- Незалежні рев'ю (дата 2026-11-02 і наступні раунди) знайшли критичні й високі проблеми: DNS rebinding, втрачені оновлення, збірку, що не працювала на актуальних інструментах, клавіатурні шляхи, що змінювали дані випадково. Кожну знахідку записано з серйозністю, місцем у коді й станом ([code-review.md](../code-review.md)).

Це важливий контекст для чесної розповіді: проєкт розроблявся з AI-асистентами під керівництвом власника, а процес (ADR, плани з критеріями прийняття, незалежні рев'ю, тести як бар'єри) і є тим, що робить такий підхід надійним. Як це подати: [star-stories.md](star-stories.md#перед-тим-як-використовувати-ці-історії).

---

## 10. Ризики, обмеження і чого немає

Повна таблиця з доказами й планом дій: [interview-prep.uk.md, розділ 8](interview-prep.uk.md#8-чесні-обмеження-і-що-я-зробив-би-далі). Коротко для швидкої відповіді:

- **Один інстанс**: замок запуску внутрішньопроцесний, advisory lock з ADR-006 не реалізовано.
- **ADR і код розходяться** в трьох місцях (Spring Data JDBC / `JdbcClient`, `websearch_to_tsquery` / власний `to_tsquery`, token bucket / слоти).
- **Незакритий беклог T-092** (термін дії при видаленні ATS-дошки, `Retry-After` як дата та ін.); дедлайн читання тіла вже в коді, а документи відстають.
- **Інтеграційні тести** потребують Docker; в цьому аналізі їх не запущено.
- **Для блоку row lock немає окремого конкурентного тесту** (перевірено пошуком по тестах); це чесна прогалина, яку варто назвати разом із планом: тест на два потоки, що одночасно змінюють одну вакансію.
- **Немає:** Kafka, Kubernetes, хмарного провайдера, мікросервісів, метрик Prometheus, багатокористувацького режиму. Це рішення розміру, а не недогляд ([ADR-001](../decisions.md#adr-001-modular-monolith)).

---

## 11. Як подати проєкт: структура розповіді

**Хвилина 0–2: пітч** (розділ 1 цього документа або [interview-prep.uk.md §1](interview-prep.uk.md#1-пітч)).

**Хвилина 2–8: одна вертикаль.** Не перелічуйте все; візьміть один наскрізний шлях і пройдіть його в глибину: «вакансія з джерела → нормалізація → дедуплікація → оцінка з поясненням → Inbox → заявка → відповідь на пошті → запропонований статус». На кожному кроці називайте **рішення і його ціну** (F1–F7, F11).

**Хвилина 8–15: два-три глибокі кейси** на вибір інтерв'юера: concurrency і цілісність (S1), безпека (S2), коректність при частковій відмові (S3), якість даних (S4), AI під контролем (S5).

**Завершення: чесність.** Одним абзацом: що обмежено і що б ви зробили далі (розділ 10). Це підсилює довіру до решти.

| Якщо питають про… | Беріть |
|---|---|
| Concurrency, транзакції, цілісність даних | F3, F6, S1, C2, C3 |
| Надійність і відмовостійкість | F1, F11, S3, C5–C7 |
| Алгоритми, якість даних | F3, F4, S4, C8, C9 |
| Безпека | F14, S2, C12 |
| AI, приватність, ризики LLM | F10, S5, C14 |
| Архітектура й межі модулів | розділ 8, C1 |
| Процес, якість, тестування | F17, S6, C19 |
| Продуктове мислення, compliance | F1, S7, C13 |
| AI-assisted development | розділ 9, S6 |

# STAR-історії для технічного інтерв'ю: Vacancy Tracker

Сім історій у форматі **STAR** (Situation, Task, Action, Result), які показують сеньйорний рівень інженерії: робота з конкурентністю, безпекою, коректністю при відмовах, якістю даних, ризиками AI, відтворюваністю збірки й етикою джерел даних. Кожна історія має:

- версію **українською** (щоб зрозуміти й запам'ятати);
- версію **English (to say)** (її можна проговорювати майже дослівно, ~1.5 хвилини);
- **докази в репозиторії** (посилання на код, тести й документи, щоб відповісти на «покажіть»);
- **питання, що підуть далі**, з чесними відповідями.

Контекст проєкту: [project-deep-dive.uk.md](project-deep-dive.uk.md) (функціонал) і [interview-prep.uk.md](interview-prep.uk.md) (рішення й банк питань). Англійський оригінал рішень: [interview-prep.md](interview-prep.md).

## Перед тим, як використовувати ці історії

Прочитайте це, бо від цього залежить довіра до розповіді.

1. **Факти беруться з репозиторію, а роль ваша.** Усе, що сталося в проєкті (знайдено, вирішено, виправлено), підтверджено кодом, ADR, [code-review.md](../code-review.md) і CHANGELOG. Але *хто саме що зробив* репозиторій показує лише частково: в історії git є коміти власника (37 + 14 під двома іменами), Claude (22) і Dependabot (14), а в CHANGELOG є виправлення «після рев'ю Codex». Тож проєкт розроблявся з AI-асистентами під вашим керівництвом. Розповідайте так, як було насправді: ви власник і архітектор, який ставив вимоги, ухвалював рішення, організовував незалежні рев'ю й перевіряв результат. Це сильна й чесна історія, що збігається з пунктом «AI-assisted development» у вашому CV. **Замініть «I» там, де це було не ваше особисте виконання, на «I directed / I reviewed / we»**; не кажіть, що писали руками те, чого не писали. Інтерв'юер може спитати про деталі реалізації; готуйтеся відповідати на них по коду (посилання нижче).
2. **Метрик бізнесу немає, і вигадувати їх не треба.** Це персональний інструмент без користувачів і виручки. Результати в історіях виміряні тим, що є: що перестало ламатися, які тести з'явилися, які інваріанти тепер гарантовано, які числа збігаються з кодом. Не додавайте відсотків, яких немає.
3. **Називайте обмеження самі.** У кожній історії є чесний блок «що лишилось відкритим». Це робить решту розповіді переконливішою.
4. **Дати.** Репозиторій має близько двох тижнів історії (2026-11-03 – 2026-11-16) і плани від 2026-11-01. Не розтягуйте історії на «місяці роботи».

## Карта історій

| # | Історія | Що показує (сеньйорний сигнал) | Підходить на питання |
|---|---|---|---|
| S1 | [Втрачені оновлення між фоновими задачами й користувачем](#s1-втрачені-оновлення-між-фоновими-задачами-й-користувачем) | Концептуальний підхід до класу гонок, а не латання одного бага; вибір песимістичного блокування з обґрунтуванням; чесність щодо прогалини в тестах | concurrency, транзакції, цілісність даних |
| S2 | [DNS rebinding: «безпечний» локальний застосунок без логіну](#s2-dns-rebinding-безпечний-локальний-застосунок-без-логіну) | Модель загроз; fail-closed; дефект у припущенні, а не в рядку коду | безпека, threat modeling |
| S3 | [Коректність при частковій відмові: ETag, expiry, пошта](#s3-коректність-при-частковій-відмові-etag-expiry-пошта) | Інваріанти системи важливіші за оптимізацію; at-least-once + ідемпотентність | надійність, кешування, розподілені збої |
| S4 | [Fuzzy-дедуплікація, що зливала різні вакансії](#s4-fuzzy-дедуплікація-що-зливала-різні-вакансії) | Міркування про асиметрію помилок; заміна «розумного» рішення «простим і строгим» | алгоритми, якість даних |
| S5 | [AI на короткому повідку](#s5-ai-на-короткому-повідку) | Приватність за дизайном, prompt injection, детермінована перевірка результату моделі | AI/LLM у продукті, ризики |
| S6 | [Збірка, що працювала лише в одному місці, і незалежне рев'ю](#s6-збірка-що-працювала-лише-в-одному-місці-і-незалежне-ревю) | Тести зелені не означає, що правильно; керування AI-згенерованим кодом через рев'ю, ADR і бар'єри | якість, процес, AI-assisted development |
| S7 | [Compliance-first: 65 джерел і відмова від скрейпінгу](#s7-compliance-first-65-джерел-і-відмова-від-скрейпінгу) | Вміння казати «ні» заради юридичної чистоти й знайти легальний шлях до мети | продуктове мислення, етика, ризики |

Швидкий вибір: питають про **«складну проблему, яку розв'язали»** → S1 або S3; **«помилку, яку виправили»** → S4 або S2; **«як ухвалюєте рішення під невизначеністю»** → S5 або S7; **«як гарантуєте якість»** → S6; **«конфлікт чи ризик»** → S7 (розмова з власником продукту про межі) або S5.

---

## S1. Втрачені оновлення між фоновими задачами й користувачем

**Коли використовувати:** «розкажіть про складну проблему конкурентності», «як ви гарантуєте цілісність даних», «як працюєте з транзакціями».

### Українською

**Situation.** У трекері одну й ту саму вакансію змінюють одночасно користувач (статус, нотатки, наступна дія) і три фонові процеси: ingest при кожному запуску збору, inbox rule, що ховає або повертає вакансії, і expiry. Усі три читали рядок, змінювали об'єкт у пам'яті й записували назад **усі колонки** без блокування. Незалежне рев'ю коду знайшло наслідок: статус, який користувач виставив між читанням і записом фонової задачі, **повертався назад** (вакансія знову в Inbox, хоча в історії стоїть Saved), а нотатки могли губитись так само.

**Task.** Гарантувати, що дія користувача ніколи не губиться через фонову задачу, не переписуючи модель даних і не вводячи для користувача помилок «конфлікт версій».

**Action.**
- Побачив, що це не один баг, а **клас**: кожен шлях «прочитав рядок, змінив, записав» (редагування, зміна статусу, масова зміна, ingest, inbox rule, expiry). Тому рішення мало бути одним правилом для всіх, а не латками.
- Розглянув два варіанти: оновлення лише змінених колонок і блокування рядка. Обрав **`SELECT … FOR UPDATE` на кожному read-modify-write** у транзакції: зберігається модель агрегата й один шлях запису; ціна це кілька мілісекунд очікування для фонової задачі. Оптимістичне блокування (`@Version`) відкинув: переможений тут це фонова задача, яка має просто почекати, а користувач не повинен бачити хибних конфліктів.
- Звузив поверхню ризику **типом**: автоматичне оновлення приймає record `AutomatedFields`, який *не вміє виразити* статус, нотатку, тег чи контакт, і змінює опис і зарплату лише якщо їх не редагував користувач (`userEditedFields`).
- Закрив суміжні дефекти, знайдені тим самим рев'ю: вакансію, додану вручну, inbox rule більше не ховає (запис «added by hand» у історії робить її «не автоматичною»); `PATCH` із однією нотаткою більше не стирає дату наступної дії.
- Для складних змін статусу записую кроки з відступом у одну мікросекунду, щоб історія зберігала порядок.

**Result.** Усі шість шляхів запису серіалізуються по рядку; відкат статусу користувача фоновою задачею більше неможливий за конструкцією. Рішення й ціна записані в ADR ([ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits), [ADR-012](../decisions.md#adr-012-upsert-rules-automation-never-overwrites-user-data)). **Чесно про прогалину:** окремого конкурентного тесту на блокування немає (я перевірив пошуком по тестах); покрито поведінку суміжних дефектів, а сам замок покладається на код-рев'ю. Наступний крок: тест із двома потоками, що одночасно змінюють одну вакансію.

### English (to say)

**Situation.** In my job-tracking app, the same vacancy row is written by the user, with status, notes and next action, and by three background processes: the ingest on every collection run, an inbox rule, and an expiry job. All three loaded the row, changed an object in memory, and wrote every column back with no lock. An independent code review found the consequence: a status the user set between the read and the write could be silently reverted, and notes could be lost the same way.

**Task.** Make sure a user's action is never lost to a background job, without redesigning the data model and without showing the user false "version conflict" errors.

**Action.** I saw this was a class of bug, not a single bug: every read-modify-write path had the same flaw, so the fix had to be one rule applied everywhere. I compared two options, column-scoped updates and row locking, and chose `SELECT … FOR UPDATE` on every read-modify-write inside a transaction. It keeps the aggregate model and a single write path, and the price is a few milliseconds of waiting for a background job. I rejected optimistic locking because the loser here is a background job that should simply wait, and the user should never see a conflict. I also narrowed the risk by type: automation can only write through an `AutomatedFields` record that cannot even express a status or a note, and it only touches description and salary when the user hasn't edited them. The same review exposed two neighbouring bugs, manual vacancies being auto-hidden and a partial update wiping the next-action date, and I fixed those too.

**Result.** All six write paths now serialise per row, so a background job can no longer revert what the user did. I documented the decision and its cost in an ADR. To be honest about the gap: the lock itself has no dedicated concurrency test; the neighbouring behaviours are tested. A two-thread test is the next thing I'd add.

### Докази в репозиторії

- Знахідка F2 (high) і її виправлення: [code-review.md](../code-review.md); рішення: [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits), [ADR-012](../decisions.md#adr-012-upsert-rules-automation-never-overwrites-user-data).
- Читання з блокуванням: [`VacancyRepository.findByIdForUpdate`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyRepository.java#L96-L102); сервіс: [`lockForChange`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L127-L130), [`update`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L132-L157), [`moveTo`](../../backend/src/main/java/com/vacancytracker/vacancy/VacancyService.java#L173-L188).
- Фонові шляхи: [`IngestService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/IngestService.java#L51-L52), [`MatchService.matchWith`](../../backend/src/main/java/com/vacancytracker/matching/MatchService.java#L103-L104), [`ExpiryService.expire`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L81-L89).
- Тип-обмеження автоматики: [`Vacancy.applyAutomated`](../../backend/src/main/java/com/vacancytracker/vacancy/Vacancy.java#L115-L136), [`AutomatedFields`](../../backend/src/main/java/com/vacancytracker/vacancy/AutomatedFields.java).
- Тести суміжних дефектів: [`ReviewFixesTest`](../../backend/src/test/java/com/vacancytracker/web/ReviewFixesTest.java), [`InboxRuleTest`](../../backend/src/test/java/com/vacancytracker/matching/InboxRuleTest.java).

### Питання, що підуть далі

- *Чому не `@Version`?* Бо переможений це фонова задача, яка має почекати, а не користувач, якому показують конфлікт; конкуренція мізерна.
- *Чи не буде deadlock?* Фонові задачі й редагування блокують **один** рядок за транзакцію, тож цикл неможливий. Масова зміна статусу ([`BulkStatusService.change`](../../backend/src/main/java/com/vacancytracker/vacancy/BulkStatusService.java#L47-L72)) натомість блокує до 500 рядків в **одній** транзакції в порядку, у якому надійшли id, без сортування; цикл можливий лише між двома накладеними масовими операціями з протилежним порядком, що для одного користувача нереалістично. Простий захист це сортувати id перед блокуванням; окремого тесту на це немає.
- *Чому не рівень ізоляції `SERIALIZABLE`?* Він покарав би весь застосунок повторами транзакцій заради одного класу гонок, який точково закривається блокуванням.

---

## S2. DNS rebinding: «безпечний» локальний застосунок без логіну

**Коли використовувати:** «розкажіть про проблему безпеки, яку знайшли», «як ви моделюєте загрози», «найнеочевидніша вразливість».

### Українською

**Situation.** Застосунок за замовчуванням працює на `127.0.0.1` без автентифікації: він для однієї людини на її комп'ютері. Я вважав, що перевірки `Origin` на запитах, що змінюють дані, достатньо, щоб жодна веб-сторінка у браузері не могла ним керувати. Незалежне рев'ю оцінило це як **critical**: через DNS rebinding ворожа сторінка перерезолвить своє ім'я на `127.0.0.1`, стане same-origin з локальним API і прочитає все: `GET /api/export/backup.json` віддає усі вакансії, нотатки й контакти, а ще можна запускати збори й імпорти. Перевірка `Origin` тут безсила, бо `Origin` і `Host` обидва несуть ім'я зловмисника.

**Task.** Закрити діру, не додаючи логіну для локального режиму, не ламаючи розгортання на VPS за доменним ім'ям і так, щоб жоден запис шляху її не обійшов.

**Action.**
- Зрозумів, що дефект у **припущенні**: same-origin надійний лише якщо origin *ваш*, а це має довести заголовок `Host`. Додав `HostFilter`, який відповідає **лише** на loopback-імена та імена з `APP_ALLOWED_HOSTS`.
- Свідомо **не обмежив** фільтр шляхом `/api/`: рутер Spring читає шляхи інакше, ніж сирий URI (`/API/`, `/api;x=1/`, кодовані літери), тому вузька перевірка обходиться. Перевіряються всі запити, включно зі статичними сторінками.
- Fail-closed: порожній чи відсутній `Host` відхиляється; клієнт без `Host` (HTTP/1.0, тести) оцінюється за іменем, яке визначив сервер.
- Зв'язав із розгортанням: у `docker-compose.vps.yml` `APP_ALLOWED_HOSTS` береться з `DOMAIN`, тож VPS працює без ручного налаштування. Залишив `OriginFilter` як другий шар для cross-site записів.
- Описав залишкові ризики в ADR замість того, щоб їх замовчувати.

**Result.** Критична знахідка закрита; `HostFilterTest` і `OriginFilterTest` тримають поведінку. Урок записано в [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits): перевірка same-origin настільки сильна, наскільки гарантовано, що origin ваш. Задокументовані й межі: ім'я, що змінює DNS-відповідь між SSRF-перевіркою імпорту й запитом, не покрите.

### English (to say)

**Situation.** By default my app binds to `127.0.0.1` with no authentication, because it's a single-user tool on the owner's machine. I believed checking the `Origin` header on state-changing requests was enough to keep any web page in the browser from driving it. An independent review rated that **critical**: with DNS rebinding, a hostile page re-resolves its own name to `127.0.0.1`, becomes same-origin with my API, and can read everything, including a full backup endpoint, and start runs and imports. The `Origin` check can't help, because `Origin` and `Host` both carry the attacker's name.

**Task.** Close the hole without adding a login to local mode, without breaking the VPS deployment behind a domain, and so that no path spelling could bypass it.

**Action.** I realised the flaw was in my assumption: same-origin is only trustworthy if the origin is yours, and the `Host` header has to prove that. I added a `HostFilter` that answers only for loopback names and an allow-list from `APP_ALLOWED_HOSTS`. I deliberately did not limit it to `/api/`, because Spring routes paths differently from the raw URI, for example `/api;x=1/vacancies`, so a narrow check can be bypassed; every request is checked, static pages included. It fails closed on a missing or blank host. I tied it to deployment: the VPS compose file derives the allow-list from the `DOMAIN` variable, so nobody has to configure it by hand. I kept the origin filter as a second layer, and I wrote the residual risks into the ADR rather than hiding them.

**Result.** The critical finding is closed and covered by tests for both filters. The lesson is recorded in the ADR: a same-origin check is only as strong as the guarantee that the origin is yours.

### Докази в репозиторії

- Знахідка F1 (critical): [code-review.md](../code-review.md); рішення: [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits).
- [`HostFilter`](../../backend/src/main/java/com/vacancytracker/web/HostFilter.java#L25-L70) (коментар про те, чому перевіряється кожен запит), [`OriginFilter`](../../backend/src/main/java/com/vacancytracker/web/OriginFilter.java#L22-L50), [`AuthFilter.shouldNotFilter`](../../backend/src/main/java/com/vacancytracker/web/auth/AuthFilter.java#L33-L40) (той самий урок про нормалізований шлях).
- Тести: [`HostFilterTest`](../../backend/src/test/java/com/vacancytracker/web/HostFilterTest.java), [`OriginFilterTest`](../../backend/src/test/java/com/vacancytracker/web/OriginFilterTest.java), [`AuthApiTest`](../../backend/src/test/java/com/vacancytracker/web/auth/AuthApiTest.java).
- Розгортання: [`docker-compose.vps.yml`](../../docker-compose.vps.yml).

### Питання, що підуть далі

- *Чому Chrome не вразливий?* Згідно з рев'ю, у Firefox і Safari атака працює, а Chrome блокує запит запитом дозволу на доступ до локальної мережі; на це не варто покладатися.
- *Що ще на межі довіри?* SSRF в імпорті за URL (блокування внутрішніх діапазонів для адреси й кожного редиректу), `javascript:`-посилання з джерел (зберігаються лише http(s)), формульна ін'єкція в CSV. Таблиця: C12 у [interview-prep.uk.md](interview-prep.uk.md#c12-безпека-для-застосунку-що-працює-на-localhost).
- *Чому не просто додати токен для локального режиму?* Це змінило б продукт (логін для однієї людини на її комп'ютері); перевірка `Host` закриває конкретну атаку, не змінюючи досвід.

---

## S3. Коректність при частковій відмові: ETag, expiry, пошта

**Коли використовувати:** «розкажіть про компроміс між продуктивністю й коректністю», «як ви проєктуєте для збоїв», «ідемпотентність», «кешування».

### Українською

**Situation.** Система збирає вакансії з ~15 джерел і має правило: вакансія, яку не бачили три чисті запуски, завершується (Expired). Для економії трафіку був ETag/If-Modified-Since. Рев'ю виявило небезпечну взаємодію: адаптер Greenhouse читає **кілька дощок**; одна дошка відповідала «304 Not Modified», інша змінювалась, і вакансії першої не оновлювались, а після трьох запусків **помилково завершувались**. До того ж валідатори зберігались *до* того, як пайплайн щось записав, тож збій пізніше в запуску втрачав дані до наступної зміни джерела. Аналогічна проблема була з листами: позначити лист «обробленим» до збереження вакансій означало втратити їх при збої.

**Task.** Зберегти інваріант «збій або зміна джерела ніколи не завершує живу вакансію й не губить дані», навіть якщо це коштує трафіку.

**Action.**
- Сформулював проблему як суперечність двох моделей: умовний запит каже «нічого не змінилось» для **одного URL**, а правило expiry міркує про **які вакансії бачили в запуску**. «Not modified» на одній дошці не відрізнити від «зникла» для вакансій інших.
- Для джерел із кількома запитами (Greenhouse, Lever, Ashby, Arbeitnow, Himalayas, Adzuna, RSS) **прибрав валідатори** (ціна: кілька сотень кілобайт за запуск); лишив їх для джерел з одним запитом (Remotive, Jobicy), де вони безпечні й справді економлять квоту.
- Ввів **`FetchOutcome`** з двома примітивами: `afterStored` (дія, яку пайплайн виконує лише коли всі вакансії записані без помилок сховища; так листи й офсет Telegram позначаються обробленими *після* збереження, а повтор поглинає дедуплікація) і `withWarning` (**часткове читання не є чистим**: прочитане зберігається, але запуск не рахується для expiry).
- Розвів джерела за природою: «віконні» (показують лише частину відкритого: Remotive, Jobicy, Arbeitnow, Himalayas, Adzuna, RSS, HTML, Remote OK, Working Nomads, Telegram) і листи не завершують вакансії автоматично; завершуються лише джерела з повною дошкою (Greenhouse, Lever, Ashby). Збійне джерело не рахується як «не бачили».
- Для квотних API (Remotive, Jobicy) виклики без retry, бо повтор це ще один виклик, якого збережений бюджет не враховує.

**Result.** Інваріант «збій джерела нічого не завершує» закодований у SQL запиту expiry, у типі `FetchOutcome` і в правилах адаптерів, і покритий тестами пайплайна й expiry. Ціна, названа явно: більше трафіку для multi-request джерел і те, що вакансії з віконних джерел лишаються *New*, поки користувач їх не закриє. Зайва картка дратує, а хибно завершена жива вакансія це втрачена можливість.

### English (to say)

**Situation.** My system collects jobs from about fifteen sources and has a rule: a vacancy not seen in three clean runs expires. To save bandwidth it used ETag and If-Modified-Since. A review found a dangerous interaction. The Greenhouse adapter reads several boards; one board answered "304 Not Modified" while another had changed, so the first board's vacancies were never refreshed and, after three runs, were wrongly expired. Worse, the validators were saved before the pipeline had stored anything, so a later failure lost data until the source changed. The same shape existed for e-mail: marking a message processed before its vacancies were stored meant losing them on failure.

**Task.** Keep the invariant that a source failure or a partial answer never expires a live vacancy or loses data, even if it costs bandwidth.

**Action.** I framed it as a conflict between two models: a conditional request says "nothing changed" for one URL, while expiry reasons about which vacancies were seen in a run. With several boards behind one adapter, "not modified" on one board is indistinguishable from "gone" for the others. So I removed validators for multi-request sources, at the cost of a few hundred kilobytes per run, and kept them for single-request sources where they're safe. I introduced a `FetchOutcome` with two primitives: `afterStored`, which the pipeline runs only after every posting was written, so mail and Telegram offsets are marked processed after storing and deduplication absorbs retries; and `withWarning`, so a partial read keeps what was read but doesn't count as a clean run for expiry. I separated sources by nature: windowed sources and e-mail never auto-expire, only whole-board sources do, and a failed source never counts as "not seen". Quota-limited APIs are called once, with no retries, because a retry is a call the stored budget doesn't count.

**Result.** The invariant "an outage never expires anything" is encoded in the expiry SQL, in the `FetchOutcome` type and in the adapter rules, and it's covered by pipeline and expiry tests. The cost is explicit: more bytes for multi-request sources, and vacancies from windowed sources stay "New" until the user closes them. A stale card is annoying; a wrongly expired live job is a missed opportunity.

### Докази в репозиторії

- Рішення й причини: [ADR-037](../decisions.md#adr-037-review-follow-ups-what-changed-in-collection-and-import), [ADR-038](../decisions.md#adr-038-full-review-2026-11-02-decisions-that-change-behavior), [ADR-014](../decisions.md#adr-014-expiry-rule), [ADR-029](../decisions.md#adr-029-imap-reader-read-only-processed-state-in-the-database), [ADR-030](../decisions.md#adr-030-email-sources-one-parser-per-sender-no-expiry).
- Запит expiry і список віконних джерел: [`ExpiryService`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/ExpiryService.java#L31-L79).
- Примітиви: [`FetchOutcome`](../../backend/src/main/java/com/vacancytracker/collection/adapter/FetchOutcome.java#L9-L56); їхнє використання: [`CollectionPipeline.runAdapter`](../../backend/src/main/java/com/vacancytracker/collection/pipeline/CollectionPipeline.java#L178-L216), [`TelegramForwardAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/telegram/TelegramForwardAdapter.java#L81-L94).
- Умовні запити лише для одно-запитних джерел: [`ConditionalCache`](../../backend/src/main/java/com/vacancytracker/collection/http/ConditionalCache.java#L20-L30), [`HttpFetcher`](../../backend/src/main/java/com/vacancytracker/collection/http/HttpFetcher.java#L132-L181).
- Пошта: [`ImapMailReader`](../../backend/src/main/java/com/vacancytracker/collection/mail/ImapMailReader.java#L132-L230), [`MailHandler`](../../backend/src/main/java/com/vacancytracker/collection/mail/MailHandler.java#L9-L13).
- Тести: [`ExpiryAndRunLockTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/ExpiryAndRunLockTest.java), [`CollectionPipelineTest`](../../backend/src/test/java/com/vacancytracker/collection/pipeline/CollectionPipelineTest.java), [`ProcessedMailRepositoryTest`](../../backend/src/test/java/com/vacancytracker/collection/mail/ProcessedMailRepositoryTest.java).

### Питання, що підуть далі

- *Це exactly-once?* Ні, це at-least-once плюс ідемпотентність: повтор безпечний, бо дедуплікація й `ON CONFLICT` поглинають дубль.
- *Чому не зберегти стан «бачили» окремо від вакансій?* Можна, але правило «бачили в запуску» вже виводиться з `last_seen_at` і результатів запусків, а додатковий стан додав би ще одну річ, що може розійтися з реальністю.
- *Що, якщо джерело змінило формат і віддає порожній список?* Для листів парсер, що прийняв лист і не знайшов вакансій, логує попередження; для API порожня відповідь без помилки зараховується як чистий запуск, тож це відома межа для джерел із повною дошкою.

---

## S4. Fuzzy-дедуплікація, що зливала різні вакансії

**Коли використовувати:** «розкажіть про помилку у вашому рішенні й як виправили», «алгоритми й якість даних», «як ухвалюєте компроміс між false positive і false negative».

### Українською

**Situation.** Одна вакансія з'являється на кількох дошках під різними URL. Третє правило дедуплікації було «м'яким»: подібність Джаро-Вінклера на ключі `title | location` з порогом 0.9 серед вакансій тієї самої компанії. Рев'ю перевірило його на реалістичних парах і показало: «Software Engineer | Berlin» проти «… | London» дає **0.94**, а «Engineer I» проти «Engineer II» дає **0.97**. Тобто різні вакансії зливались, друга **ніколи не з'являлась**, а першу користувач бачив як одну. Пізніше те саме знайшлось для заголовків C++, C#, C.

**Task.** Зробити так, щоб різні вакансії не зливались, не перетворюючи дедуплікацію на нічого не варту.

**Action.**
- Відтворив проблему на конкретних парах і зрозумів корінь: схожість рядків міряє «скільки символів збігається», а нам потрібно «чи це та сама роль». Різниця в одному символі (`I`/`II`) чи одному слові (`Berlin`/`London`) змінює сутність.
- Замінив правило на **структурне**: потрібні (а) **сумісні місця** (одне невідоме, рівні або одне містить інше: «Berlin» / «Berlin Germany») і (б) заголовки **рівні після нормалізації** (прибрано пунктуацію й «(m/f/d)») **або рівні за винятком одного слова з друкарською помилкою** (6+ літер, без цифр, не більше двох правок). Рівні й варіанти в інших словах це різні вакансії.
- Додав захист: кандидат, якого **той самий адаптер** уже перелічує під іншим id, пропускається, бо на одній дошці різні id це різні вакансії.
- Сформулював принцип асиметрії помилок: показати дублікат дешевше, ніж сховати іншу роботу. Тому правило консервативне. Той самий принцип застосував у визначенні країни: хибна країна сховала б вакансію через `require`, тож краще «невідомо».
- Записав ревізію в ADR замість тихого виправлення, щоб причина лишилась.

**Result.** Різні вакансії більше не зливаються; тести дедуплікації тримають приклади, що колись провалились. Ціна названа: консервативне правило пропустить дублікат із переформульованим заголовком. На масштабі я б перейшов на блокування за компанією плюс MinHash або триграмний індекс, але на тисячах рядків проста версія правильна.

### English (to say)

**Situation.** The same job shows up on several boards under different URLs. My third deduplication rule was a soft one: Jaro-Winkler similarity on a `title | location` key, threshold 0.9, among vacancies of the same company. A review tried it on realistic pairs and showed that "Software Engineer | Berlin" against "… | London" scored 0.94, and "Engineer I" against "Engineer II" scored 0.97. Distinct openings were being merged, and the second one never appeared.

**Task.** Stop merging different jobs without making deduplication useless.

**Action.** I reproduced it with concrete pairs and found the root cause: string similarity measures how many characters match, but what I need is whether it's the same role, and one character or one word can change that. I replaced the rule with a structural one. It needs compatible places, where one is unknown, they're equal, or one contains the other, and titles that are equal after normalisation, or equal except for one word with a typo, six letters or more, no digits, at most two edits. Levels and variants in other words count as different jobs. I also skip any candidate that the same adapter already lists under a different id, because on one board different ids are different openings. The guiding principle was the asymmetry of errors: showing a duplicate is cheaper than hiding a different job. I applied the same principle to country detection, where a wrong country would hide vacancies through a `require` rule, so "unknown" is the safer error. I recorded the revision in the ADR instead of fixing it silently.

**Result.** Different jobs no longer merge, and the tests keep the examples that used to fail. The cost is explicit: the conservative rule will miss a duplicate with a reworded title. At scale I'd block by company and use MinHash or a trigram index, but at thousands of rows the simple version is right.

### Докази в репозиторії

- Ревізія з числами 0.94 і 0.97: [ADR-013](../decisions.md#adr-013-deduplication-pipeline); пов'язано: [ADR-036](../decisions.md#adr-036-feed-adapter-country-detection-and-what-stays-undetected) (країна), [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits) (різні id однієї дошки).
- Код: [`Deduplicator.match`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L34-L48), [`sameLocation`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L72-L75), [`titleScore`](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L82-L103), [захист id](../../backend/src/main/java/com/vacancytracker/collection/dedup/Deduplicator.java#L59-L62).
- Індекс на рівні БД: [`V1__baseline.sql`](../../backend/src/main/resources/db/migration/V1__baseline.sql).
- Тести: [`DeduplicatorTest`](../../backend/src/test/java/com/vacancytracker/collection/dedup/DeduplicatorTest.java), [`CanonicalUrlTest`](../../backend/src/test/java/com/vacancytracker/common/CanonicalUrlTest.java), [`CountryDetectorTest`](../../backend/src/test/java/com/vacancytracker/collection/normalize/CountryDetectorTest.java).

### Питання, що підуть далі

- *Чому не навчили модель?* Для пошуку роботи для однієї людини ML додав би непояснюваність, а пояснюваність це принцип проєкту; правило детерміноване й тестується прикладами.
- *Чи можна розділити помилково злиті?* ADR-013 згадує ручну дію «split», заплановану на M2; я не перевіряв, чи вона реалізована, тож не стверджуйте цього, не перевіривши в PLAN.md.

---

## S5. AI на короткому повідку

**Коли використовувати:** «як ви інтегрували LLM у продукт», «ризики AI», «приватність», «prompt injection», «як довіряти виходу моделі».

### Українською

**Situation.** Власник хотів, щоб резюме й супровідні листи під вакансію адаптував Claude. Ризики були очевидні: ніхто не повинен отримувати дані без явної згоди; модель може **вигадати факти** у резюме; текст вакансії приходить з інтернету й може містити інструкції для моделі; а оцінку «наскільки резюме покриває вимоги» модель легко завищує.

**Task.** Дати користь від AI так, щоб застосунок лишався повністю корисним без нього, нічого не йшло назовні без згоди, а результат моделі можна було перевірити, а не прийняти на віру.

**Action.**
- **Вимкнено за замовчуванням, два бар'єри.** Єдині двері `AiService` відмовляють (409) **до** відправки, якщо провайдер не налаштований або власник не ввімкнув AI в Settings після прочитання точного повідомлення про те, що надсилається. Згода зберігається **по провайдеру** й відкликується.
- **Пісочниця для CLI-провайдера:** `claude` у print-режимі без інструментів, MCP, slash-команд і збереженої сесії, налаштування з порожньої тимчасової папки, промпт у stdin, а `ANTHROPIC_API_KEY`/`ANTHROPIC_AUTH_TOKEN` прибрано з середовища дочірнього процесу, тож застосунок ніколи не бачить і не передає токен підписки.
- **Проти prompt injection:** правила в системному промпті, дані в теговних блоках; усе, що виглядає як наш тег, переписується на `[tag]`, щоб вакансія не закрила власний блок і не додала завдання. Є тест із вакансією, що містить `</vacancy>` та «Ignore all previous instructions».
- **Модель пропонує, код вирішує:** для Employer match модель дає статус і цитату, а **сервер перевіряє, що цитата буквально є в документі**; не знайдено означає «missing»; знайдено лише в Skills означає не вище «partial». Оцінку рахує код за формулою з вагами 3/2/1. Результати кешуються за MD5 тексту, тож незмінний текст завжди дає ту саму оцінку.
- **Fact check перед збереженням:** детермінована різниця числа, дат, роботодавців, посад і технологій із базовими документами; збереження чекає підтвердження *саме цих* знахідок.
- **Незмінна провенанс-історія:** чернетки не зберігаються, версії незмінні, кожна має снепшот (пресет, guardrail, ціль, вага, промпт, база); guardrail не редагується з UI. Застосунок **ніколи не подає форму**: подає людина.
- **Тестованість без AI:** провайдер `fake` відповідає детерміновано, і навіть містить навмисне «перебільшення», щоб e2e-тест перевіряв fact check.

**Result.** AI-функції вимкнені, доки власник їх не ввімкне; без них працюють пошук, оцінювання, пайплайн і fit. З ними оцінку не можна завищити, нові «факти» видно до збереження, а кожен надісланий документ простежується до свого снепшота. Ціна: перше відкриття вакансії з AI коштує до чотирьох викликів, а перевірка фактів навмисно буквальна (повторно підтверджувати доводиться й чесні нові терміни).

### English (to say)

**Situation.** The owner wanted resumes and cover letters adapted to each vacancy by Claude. The risks were clear: nothing should leave the machine without explicit consent; a model can invent facts in a resume; vacancy text comes from the internet and can contain instructions aimed at the model; and a model will happily inflate a "how well does this cover the requirements" score.

**Task.** Get the value of AI while keeping the app fully useful without it, sending nothing out without consent, and making the model's output something we verify rather than trust.

**Action.** AI is off by default and has two gates: a single service door refuses before anything is sent unless a provider is configured and the owner has switched AI on after reading the exact notice; consent is stored per provider and can be withdrawn. For the CLI provider I run the official `claude` in print mode with no tools, no MCP servers, no saved session, and I strip the API-key variables from the child's environment, so the app never sees or passes a subscription token. Against prompt injection, rules live in the system prompt and data in tagged blocks, and anything that looks like one of our tags is rewritten, so a posting can't close its own block and add a task; a test sends a posting that tries exactly that. The model proposes and the code decides: for the employer match, the model gives a status and a quote per requirement, and the server checks that the quote is literally in the document, otherwise the requirement is missing, and a quote found only in a Skills section caps it at partial. The score is computed in code with fixed weights and cached by a hash of the text, so the same text always gets the same score. Before saving, a deterministic fact check lists every number, date, employer, title or technology not in the base documents, and saving waits for the user to confirm those exact findings. Generated drafts aren't stored; saved versions are immutable, each with a snapshot of what it was made from, and the app never submits an application form. For testing, a fake provider answers deterministically, and even includes one deliberate overreach so the end-to-end test proves the fact check works.

**Result.** Without AI, search, scoring, the pipeline and the resume fit all work. With it, the score can't be inflated, new "facts" are visible before saving, and every document that was sent traces back to its snapshot. The cost is up to four calls the first time a vacancy is opened with AI on, and a fact check that's deliberately literal, so even honest new terms need confirming.

### Докази в репозиторії

- Рішення: [ADR-041](../decisions.md#adr-041-claude-adapter-and-the-ai-opt-in), [ADR-042](../decisions.md#adr-042-tailored-documents-versions-and-application-packages), [ADR-047](../decisions.md#adr-047-ai-presets-and-base-documents), [ADR-048](../decisions.md#adr-048-generation-from-the-base-draft-with-a-snapshot), [ADR-049](../decisions.md#adr-049-employer-match-and-goal-match), [ADR-051](../decisions.md#adr-051-deterministic-fact-check-before-saving); повний розбір: C14 у [interview-prep.uk.md](interview-prep.uk.md#c14-ai-на-короткому-повідку).
- Двері й згода: [`AiService`](../../backend/src/main/java/com/vacancytracker/application/ai/AiService.java#L22-L90); пісочниця: [`ClaudeCliClient`](../../backend/src/main/java/com/vacancytracker/application/ai/ClaudeCliClient.java#L58-L112).
- Проти ін'єкцій: [`Prompts`](../../backend/src/main/java/com/vacancytracker/application/documents/Prompts.java#L94-L100), тест [`PresetGenerationApiTest`](../../backend/src/test/java/com/vacancytracker/web/PresetGenerationApiTest.java#L93-L116).
- Перевірка цитат і оцінка: [`QuoteCheck`](../../backend/src/main/java/com/vacancytracker/application/analysis/QuoteCheck.java#L30-L78), [`AnalysisService.check`](../../backend/src/main/java/com/vacancytracker/application/analysis/AnalysisService.java#L244-L258), [`EmployerMatch.score`](../../backend/src/main/java/com/vacancytracker/application/analysis/EmployerMatch.java#L20-L34).
- Fact check і провенанс: [`FactCheck`](../../backend/src/main/java/com/vacancytracker/application/documents/FactCheck.java#L19-L50), [`DocumentService.save`](../../backend/src/main/java/com/vacancytracker/application/documents/DocumentService.java#L185-L214), [`GenerationSnapshot`](../../backend/src/main/java/com/vacancytracker/application/documents/GenerationSnapshot.java#L14).
- Тести: [`ClaudeCliClientTest`](../../backend/src/test/java/com/vacancytracker/application/ai/ClaudeCliClientTest.java), [`QuoteCheckTest`](../../backend/src/test/java/com/vacancytracker/application/analysis/QuoteCheckTest.java), [`FactCheckTest`](../../backend/src/test/java/com/vacancytracker/application/documents/FactCheckTest.java), [`full-cycle.spec.ts`](../../e2e/tests/full-cycle.spec.ts), [`m7-presets.spec.ts`](../../e2e/tests/m7-presets.spec.ts).

### Питання, що підуть далі

- *Чи не надто параноїдально?* Ціна захисту мала (кілька класів і одна згода), а вартість помилки висока: вигадана посада в резюме, надісланому роботодавцю.
- *Чому MD5?* Це відбиток вмісту для кешу, не криптографічне використання; колізії тут неважливі й безпеки це не торкається.
- *Чи надійна нейтралізація тегів?* Вона закриває конкретний клас атаки (закриття власного блоку), але не робить модель імунною до інструкцій у тексті; тому додаткова лінія оборони це перевірка результату, а не віра в промпт.

---

## S6. Збірка, що працювала лише в одному місці, і незалежне рев'ю

**Коли використовувати:** «як ви гарантуєте якість», «розкажіть про досвід code review», «як працюєте з AI-згенерованим кодом», «що ви зробили, коли тести були зелені, а продукт мав проблеми».

### Українською

**Situation.** До першого незалежного рев'ю в проєкті було багато зелених тестів і CI. Рев'ю зробило те, чого я сам не робив: прогнало **задокументовані команди на чистій машині** (Windows 11, JDK 21, Maven 3.9.16, Node 26, Docker Engine 29). Результат: `mvn verify` **не запускався**. Testcontainers 1.21.3 (версія за замовчуванням у Spring Boot) звертався до Docker API, який Docker Engine 29 відхиляв, тож 52 інтеграційні тести не могли стартувати; один бекенд-тест очікував UI-збірки, якої CI-джоба backend ніколи не робила; п'ять тестів теми падали на Node 22+ через експериментальний `localStorage`. Окрім збірки, рев'ю знайшло критичні й високі дефекти в коді (S1, S2). Тобто «зелено» було властивістю однієї машини.

**Task.** Зробити якісні бар'єри достовірними: збірка відтворювана на сучасних інструментах, а знахідки не губляться, мають серйозність, власника й стан.

**Action.**
- Виправив причини, а не симптоми: Testcontainers піднято до 1.21.4; тест SPA перевіряє, що auth-фільтр не відмовляє сторінці, і не вимагає UI-збірки; воркери Vitest на Node 22+ запускаються з `--no-experimental-webstorage`; у CI Playwright 1.55.1 і runner `ubuntu-24.04` закріплені з коментарем, чому.
- Організував рев'ю як **процес**, а не разову подію: окремі читачі для бекенда й фронтенда, кожну знахідку простежено по коду; усе у таблиці з серйозністю (critical/high/medium/low), місцем у коді й станом (fixed/open); незакрите винесено в беклог T-092, а не загублено.
- Додав **тести на взаємодії функцій**, яких не було (одно-функціональні тести їх не бачили): для F1, F3, F4, F5, F6, F14.
- Закріпив рішення в **ADR** (54), а план із ID задач і критеріями прийняття синхронізував із CSV окремою CI-джобою; бар'єри в кожній збірці: Checkstyle, JaCoCo 80% рядків для чотирьох основних модулів, ArchUnit для меж модулів.
- Це й була моя відповідь на питання довіри до AI-згенерованого коду: не перечитувати кожен рядок, а будувати **незалежну перевірку, що не залежить від автора** (чиста машина, окремий рев'юер, архітектурні тести, e2e на запакованому jar).

**Result.** `mvn verify` стартує на сучасних інструментах (52 інтеграційні тести, які не могли запуститись, тепер запускаються). Усі critical/high знахідки рев'ю закриті, відкрите чесно перелічене. У цьому аналізі я самостійно повторив частину перевірки: 726 фронтенд-тестів і typecheck пройшли, 260 бекенд-тестів чистої логіки пройшли; інтеграційні в моєму середовищі без Docker не запускались, і я це кажу прямо.

### English (to say)

**Situation.** Before the first independent review, the project had a lot of green tests and CI. The review did what I hadn't: it ran the documented commands on a clean machine, Windows 11 with JDK 21, Maven, Node 26 and Docker Engine 29. `mvn verify` didn't run. The Testcontainers version that Spring Boot pins spoke a Docker API version that Docker 29 refuses, so 52 integration tests couldn't start; a backend test expected a UI build that the CI backend job never produced; and five theme tests failed on Node 22 because of its experimental `localStorage`. Beyond the build, the review found critical and high-severity defects in the code. So "green" had been a property of one machine.

**Task.** Make the quality gates trustworthy: a reproducible build on current tools, and findings that don't get lost, each with a severity, a location and a state.

**Action.** I fixed causes, not symptoms: I bumped Testcontainers to a version that works with Docker 29, rewrote the SPA test so it doesn't depend on a UI build, ran Vitest workers with the right Node flag, and pinned the Playwright version and the CI runner image with a comment explaining why. I ran the review as a process, not an event: separate readers for backend and frontend, every finding traced through the code and recorded with severity, location and state, and unfinished items moved to an explicit backlog. I added tests for feature interactions that single-feature tests had missed. I recorded decisions in ADRs, synced the plan with a CSV through a CI job, and kept gates in every build: Checkstyle, an 80% line-coverage gate on the core modules, and ArchUnit for module boundaries. That was also my answer to trusting AI-assisted code: I don't try to re-read every line, I build checks that don't depend on the author, such as a clean machine, an independent reviewer, architecture tests and end-to-end tests on the packaged jar.

**Result.** `mvn verify` now runs on current tools, including the 52 integration tests that couldn't start. All critical and high findings from that review are closed, and the open ones are listed openly. While preparing this interview I re-ran part of the checks myself: all 726 front-end tests and the type check pass, and 260 back-end tests of pure logic pass. The integration tests need Docker, which my environment didn't have, so I won't claim I just ran those.

### Докази в репозиторії

- Знахідки B1–B3, F1–F15, U1–U14 зі станом: [code-review.md](../code-review.md); рішення: [ADR-039](../decisions.md#adr-039-review-2026-11-02-host-check-row-locks-manual-vacancies-web-links-keyboard-commits), [ADR-022](../decisions.md#adr-022-testing-strategy-and-coverage-gate), [ADR-024](../decisions.md#adr-024-frontend-test-tooling-and-accepted-dev-only-advisories).
- Збірка й бар'єри: [`pom.xml`](../../backend/pom.xml), [`ci.yml`](../../.github/workflows/ci.yml) (закріплений runner із коментарем), [`ArchitectureTest`](../../backend/src/test/java/com/vacancytracker/ArchitectureTest.java#L23-L66), [`AbstractIntegrationTest`](../../backend/src/test/java/com/vacancytracker/support/AbstractIntegrationTest.java#L10-L34).
- План і синхронізація: [PLAN.md](../../PLAN.md), [`check-plan-sync.sh`](../../scripts/check-plan-sync.sh).
- Тести взаємодій: [`ReviewFixesTest`](../../backend/src/test/java/com/vacancytracker/web/ReviewFixesTest.java), [`FullCycleIntegrationTest`](../../backend/src/test/java/com/vacancytracker/web/FullCycleIntegrationTest.java), [`e2e/tests`](../../e2e/tests).

### Питання, що підуть далі

- *Чи писали код AI?* Скажіть як є: проєкт розроблявся з AI-асистентами (коміти Claude й виправлення за рев'ю іншого AI-інструмента видно в історії), а ваша роль це вимоги, рішення, рев'ю й перевірка; саме тому процес (ADR, незалежне рев'ю, бар'єри) такий важливий.
- *Які метрики якості?* 774 тестові методи на бекенді (статичний підрахунок), 726 фронтенд-тестів (запущено), 19 сценаріїв Playwright; покриття рядків 97.7 %, 91.4 %, 90.9 % для `vacancy`, `collection`, `matching` на момент запису ADR-022 (не перемірювалось у цьому аналізі).
- *Що лишилось слабким?* Інтеграційні тести залежать від Docker; ручні перевірки доступності не виконані; беклог T-092 не закрито повністю.

---

## S7. Compliance-first: 65 джерел і відмова від скрейпінгу

**Коли використовувати:** «розкажіть, коли ви сказали „ні“», «як ви працюєте з юридичними/етичними обмеженнями», «конфлікт між вимогами власника і ризиком».

### Українською

**Situation.** Власник дав список із **65 каналів і сайтів** (job-борди, Telegram-канали, групи Facebook і LinkedIn) і хотів зібрати вакансії звідусіль. Для багатьох найпростішим шляхом був би скрейпінг: LinkedIn, Indeed, групи Facebook, закриті Telegram-чати. Але умови використання цих сервісів його забороняють (для LinkedIn є судові прецеденти), `robots.txt` часто забороняє все, а сервери частини сайтів відповідають 403 «не-браузерам», тобто це свідомий захист.

**Task.** Дати максимальне покриття джерел **без порушення умов** і без обходу захисту, зберігши довіру до інструмента й залишивши аудитовий слід того, що чому дозволено.

**Action.**
- Перед будь-яким кодом **прочитав умови й `robots.txt` кожного** з 65 записів (дата перевірки 2026-11-02) і записав вердикт у документ: *connected* (офіційний API чи стрічка), *email* (лише листи користувача), *manual* (додати за посиланням), *platform*, *blocked*.
- Підключив лише те, що дозволено: **Djinni** (RSS, у комплекті), **Remote OK** і **Working Nomads** (публічні JSON API; Remote OK вимагає посилання назад, тож кожна вакансія веде на його сторінку й джерело названо).
- Для LinkedIn: **лише листи-сповіщення**, які користувач сам створює; парсер читає тільки вміст листа, ніколи не відкриває посилань і не логіниться.
- Для Telegram: замість читання каналів (заборонено умовами) власник **пересилає пости власному боту**, а адаптер читає повідомлення бота через `getUpdates` лише з чату власника; це дозволено умовами для ботів як дані, «подані безпосередньо й добровільно»; офсет зберігається після збереження вакансій.
- Для generic HTML-адаптера: сайт читається лише коли в конфігурації **записана перевірка умов** і `robots.txt` дозволяє (RFC 9309; відповідь 5xx це збій, заборонена сторінка не запитується). У репозиторії **жодного сайту не додано**.
- Не обходив захист: сервер, що відповідає 403 не-браузерам, лишився «blocked»; для DOU вказано, у кого просити письмової згоди. Так само при порівнянні з конкурентом читав лише сторінки, дозволені його `robots.txt`.

**Result.** Є легальне покриття (API, RSS, листи, пересилання), а кожне рішення простежується до перевіреного пункту умов у [sources.md](../sources.md). Ціна названа: покриття менше, ніж у скрейпера; частина джерел (DOU, Jobgether та ін.) заблокована до письмової згоди; Telegram потребує однієї дії на пост. Натомість інструмент ніколи не логіниться на job-сайти й не залежить від крихких селекторів чужих сторінок.

### English (to say)

**Situation.** The owner gave me a list of 65 channels and sites, job boards, Telegram channels, Facebook and LinkedIn groups, and wanted vacancies collected from all of them. For many, the easy path would have been scraping: LinkedIn, Indeed, Facebook groups, private Telegram chats. But their terms forbid it, with court precedents for LinkedIn, `robots.txt` often disallows everything, and some servers answer 403 to non-browsers, which is a deliberate protection.

**Task.** Maximise coverage without breaking any terms or bypassing any protection, keep trust in the tool, and leave an audit trail of what is allowed and why.

**Action.** Before writing any code I read the terms and `robots.txt` for each of the 65 entries and recorded a dated verdict: connected through an official API or feed, e-mail only, manual add-by-link, platform, or blocked. I connected only what is allowed: Djinni's RSS feed, and Remote OK and Working Nomads through their public JSON APIs, with the attribution link Remote OK requires. For LinkedIn I use only job-alert e-mails the user creates themselves; the parser reads only the e-mail content, never opens a link and never logs in. For Telegram, instead of reading channels, which the terms forbid, the owner forwards posts to their own bot and the adapter reads that bot's messages, which the bot terms allow as data submitted directly and voluntarily. The generic HTML adapter reads a site only when a terms check is recorded in configuration and `robots.txt` allows it, and the repository ships no site at all. I didn't work around protection: a server that returns 403 to non-browsers stays blocked, and for DOU I recorded whom to ask for written consent.

**Result.** There's legal coverage through APIs, RSS, e-mail and forwarding, and every decision traces back to a checked clause in a document. The cost is explicit: less coverage than a scraper would have, some sources blocked until written consent, and one action per Telegram post. In return the tool never logs in to a job site and doesn't depend on fragile selectors of someone else's pages.

### Докази в репозиторії

- Вердикти по кожному з 65 записів і заборонений список: [sources.md](../sources.md); рішення: [ADR-007](../decisions.md#adr-007-source-policy-no-direct-scraping-of-linkedin-or-other-forbidden-sources), [ADR-035](../decisions.md#adr-035-generic-html-adapter-approved-sites-only), [ADR-036](../decisions.md#adr-036-feed-adapter-country-detection-and-what-stays-undetected), [ADR-045](../decisions.md#adr-045-new-sources-from-the-owners-list); розділ «Compliance» у [README](../../README.md#compliance).
- Адаптери: [`RemoteOkAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/remoteok/RemoteOkAdapter.java#L29), [`TelegramForwardAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/telegram/TelegramForwardAdapter.java#L81-L94), [`HtmlSiteAdapter`](../../backend/src/main/java/com/vacancytracker/collection/adapter/html/HtmlSiteAdapter.java#L100-L135), [`RobotsRules`](../../backend/src/main/java/com/vacancytracker/collection/adapter/html/RobotsRules.java), [`LinkedInAlertParser`](../../backend/src/main/java/com/vacancytracker/collection/mail/LinkedInAlertParser.java).
- Аналіз прогалин із читанням лише дозволених сторінок: [gap-analysis.md](../gap-analysis.md).
- Тести: [`HtmlSiteAdapterTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/html/HtmlSiteAdapterTest.java), [`RobotsRulesTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/html/RobotsRulesTest.java), [`HtmlSourcesBindingTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/html/HtmlSourcesBindingTest.java), [`TelegramForwardAdapterTest`](../../backend/src/test/java/com/vacancytracker/collection/adapter/telegram/TelegramForwardAdapterTest.java).

### Питання, що підуть далі

- *Чи не розумніше було б заскрейпити й не казати?* Ні: умови, судові прецеденти й крихкість, а головне, довіра: інструмент, що зберігає вашу пошту й нотатки, не повинен порушувати чужі умови.
- *Чи не втратили ви продуктову цінність?* Покриття менше, але воно легальне й стабільне; сповіщення LinkedIn і так надходять на пошту користувача, тож цінність досягається без порушення.
- *Хто ухвалював рішення?* Скажіть чесно: правила compliance зафіксовані в README і ADR, і всі джерела з вердиктом «blocked» чекають рішення власника; розкажіть, як ви їх узгоджували.

---

## Підсумок: що ці історії разом кажуть про рівень

Разом вони показують профіль інженера, а не окремі прийоми: бачить **клас проблем** (S1, S3), а не лише симптом; мислить **моделлю загроз** (S2) і **асиметрією помилок** (S4); проєктує **межі довіри** для AI (S5); будує **незалежну перевірку** замість віри в «зелені тести» (S6); і здатен **сказати «ні»** заради довгострокової довіри (S7). Кожна історія має названу ціну рішення й чесно названу прогалину; саме це відрізняє сеньйорну розповідь від переліку технологій.

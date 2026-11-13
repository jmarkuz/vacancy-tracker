# Third-party notices

Vacancy Tracker uses the open-source components below. Each keeps its own licence and copyright; none of their code is modified here. This list covers the **direct** dependencies. The transitive ones are pulled in by these and carry their own licences; a full report can be generated with a licence plugin (for example `license-maven-plugin` for Maven and `license-checker` for npm) before the code is distributed.

"Read" means the licence was read from the component's own `pom.xml` or `package.json` in the build. "Known" means the licence is the one the project publishes, but the file read did not state it (it sits in a parent POM); confirm these when the list is next regenerated.

## Backend (Java)

| Component | Licence | Basis |
|---|---|---|
| Spring Boot starters (web, actuator, validation, jdbc) | Apache-2.0 | Read |
| Apache POI (poi-ooxml) | Apache-2.0 | Read |
| Anthropic Java SDK | Apache-2.0 | Read |
| Rome (RSS and Atom) | Apache-2.0 | Read |
| jsoup | MIT | Read |
| PostgreSQL JDBC driver | BSD-2-Clause | Read |
| Apache PDFBox | Apache-2.0 | Known |
| Apache Commons Text | Apache-2.0 | Known |
| Jackson (dataformat-yaml) | Apache-2.0 | Known |
| Flyway (core, PostgreSQL) | Apache-2.0 | Known |
| Eclipse Angus Mail | EPL-2.0 or GPL-2.0 with Classpath Exception (dual) | Known |

Used only to build and test, not shipped with the application: ArchUnit (Apache-2.0, read), Testcontainers (MIT, read), GreenMail (Apache-2.0, known), Checkstyle (LGPL-2.1-or-later, read; a build-time tool), JaCoCo (EPL-2.0, known), and the Spring Boot test starters (Apache-2.0, read).

## Frontend

| Component | Licence | Basis |
|---|---|---|
| Preact | MIT | Read |

Used only to build and test: Vite, `@preact/preset-vite`, Vitest, `@vitest/coverage-v8` and jsdom (MIT, read); TypeScript (Apache-2.0, read); Playwright Test (Apache-2.0, known).

## Fonts

| Component | Licence | Where |
|---|---|---|
| Liberation Sans 2.1.5 | SIL Open Font License 1.1 | `backend/src/main/resources/fonts/`, with its licence text in `LiberationSans-LICENSE.txt` |

The resume PDF embeds this font. The licence allows that and requires that the licence text travels with the font, which it does.

## Design reference file

| Component | Licence | Where |
|---|---|---|
| `design file`, a design-analysis file copied unchanged from the a public design collection `awesome-design-md` collection | MIT, Copyright (c) 2026 a public design collection | `design file`, which carries the licence text and the source in its last section |

It is a record of an earlier design language (T-090) and is not part of the built application. It describes a public marketing site in an independent, "inspired" analysis; Vacancy Tracker is not affiliated with that company.

## Not included

The Beacon design (colours, typography, logo, favicon) comes from the owner's own brand package (`the design package`). No design, text, code or data of any other product is copied; see the independence note in [docs/gap-analysis.md](docs/gap-analysis.md).

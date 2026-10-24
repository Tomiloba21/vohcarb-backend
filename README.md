# Vohcarb Backend

API for **Vohcarb**, an open-source vocabulary app for learning words daily, built for me and my friends.
The mobile app (Kotlin Multiplatform) lives in its own repository, `vohcarb-app`.

Kotlin + Spring Boot 4, Java 21.

## Run

Requirements: JDK 21. Gradle comes through the wrapper.

```bash
./gradlew bootRun      # starts on http://localhost:8080
./gradlew test
```

By default it uses an embedded **H2 database in MariaDB mode**, stored in `data/`,
so you don't have to install anything.

- `/swagger-ui.html`: interactive API docs (spec at `/v3/api-docs`, use it to generate the app's client)
- `/h2-console`: browse the local database (JDBC URL `jdbc:h2:file:./data/vohcarb`, user `sa`)

## Load the word catalog

The catalog comes from [Open English WordNet](https://github.com/globalwordnet/english-wordnet) (CC BY 4.0).
Only single lowercase words are kept, about 70,000 words with 120,000 definitions.

```bash
mkdir -p data/raw
curl -L -o data/raw/english-wordnet-2025.xml.gz \
  https://github.com/globalwordnet/english-wordnet/releases/download/2025-edition/english-wordnet-2025.xml.gz
./gradlew bootRun --args='--spring.profiles.active=import --vohcarb.import.file=data/raw/english-wordnet-2025.xml.gz'
```

Add `--vohcarb.import.replace=true` to re-import over an existing catalog, and add `mariadb` to the
profiles (`--spring.profiles.active=mariadb,import`) to load a MariaDB database instead.

## API

| Endpoint | What it returns |
|---|---|
| `GET /api/v1/categories` | All categories with word counts |
| `GET /api/v1/words?q=eph&category=feelings&page=0&size=20` | Words by prefix and/or category, paged (`size` ≤ 100) |
| `GET /api/v1/words/{slug}` | One word: pronunciations, senses with examples, categories |
| `GET /api/v1/random-word?category=feelings` | A random word with full details |
| `GET /api/v1/info` | App name and API version |
| `GET /actuator/health` | Health check |

Errors use the standard [problem details](https://www.rfc-editor.org/rfc/rfc9457) JSON format.

## Using MariaDB / MySQL instead

Create the database once:

```sql
CREATE DATABASE vohcarb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'vohcarb'@'localhost' IDENTIFIED BY 'change-me';
GRANT ALL PRIVILEGES ON vohcarb.* TO 'vohcarb'@'localhost';
```

Then run with the `mariadb` profile:

```bash
SPRING_PROFILES_ACTIVE=mariadb DB_PASSWORD=change-me ./gradlew bootRun
```

Schema changes go through Flyway migrations in `src/main/resources/db/migration`.
Write them in SQL that works on MariaDB, MySQL and H2.

## Project layout

Code is organised by feature under `src/main/kotlin/app/vohcarb/`:
`info/`, `catalog/` (with `catalog/importer/`), then `auth/`, `user/`, `progress/`, `practice/`, `social/`,
`notify/` as they are built.

## License

Code: [Apache 2.0](LICENSE).

Word data: [Open English WordNet](https://github.com/globalwordnet/english-wordnet) by the Global WordNet
community, licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The app must show this
attribution. Data added later (e.g. Wiktionary, CC BY-SA) will keep its own license and credit.

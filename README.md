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
curl -L -o data/raw/en_full.txt \
  https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/en/en_full.txt
./gradlew bootRun --args='--spring.profiles.active=import --vohcarb.import.file=data/raw/english-wordnet-2025.xml.gz --vohcarb.import.frequency-file=data/raw/en_full.txt'
```

The second file is a word-frequency list from [FrequencyWords](https://github.com/hermitdave/FrequencyWords)
(OpenSubtitles 2018). It gives each word a difficulty level:

| Level | Name | Frequency rank | Words |
|---|---|---|---|
| 1 | Everyday | ≤ 5,000 | ~3,600 |
| 2 | Common | ≤ 20,000 | ~8,100 |
| 3 | Intermediate | ≤ 50,000 | ~11,000 |
| 4 | Advanced | ≤ 150,000 | ~13,600 |
| 5 | Rare | > 150,000 or not in the list | ~33,400 |

Either file can be imported on its own. Re-importing WordNet resets the levels, so re-run the frequency
import after it. Add `--vohcarb.import.replace=true` to re-import WordNet over an existing catalog, and add
`mariadb` to the profiles (`--spring.profiles.active=mariadb,import`) to load a MariaDB database instead.

## API

| Endpoint | What it returns |
|---|---|
| `GET /api/v1/categories` | All categories with word counts |
| `GET /api/v1/levels` | The 5 difficulty levels with word counts |
| `GET /api/v1/words?q=eph&category=feelings&difficulty=3&page=0&size=20` | Words by prefix, category and/or level, paged (`size` ≤ 100) |
| `GET /api/v1/words/{slug}` | One word: pronunciations, senses with examples, categories |
| `GET /api/v1/random-word?category=feelings&difficulty=3` | A random word with full details |
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
attribution. Frequency data: [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit Dave,
built from OpenSubtitles 2018, licensed [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

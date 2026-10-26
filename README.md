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
so you don't have to install anything. `bootRun` starts with the **`dev` profile**: dev login is on and
tokens are signed with a throwaway key.

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

Signed-in endpoints (send `Authorization: Bearer <accessToken>`):

| Endpoint | What it does |
|---|---|
| `POST /api/v1/auth/dev-login` `{"email": "...", "displayName": "..."}` | **Dev only.** Signs in as any email, creating the user on first use; returns `accessToken` |
| `GET /api/v1/me` | The signed-in user |
| `GET /api/v1/me/preferences` | Level range, words per day, timezone, categories, reminder times |
| `PUT /api/v1/me/preferences` | Replace them (all fields) |
| `GET /api/v1/me/feed?size=10` | Random words in your level range and categories that you haven't seen or marked known |
| `PATCH /api/v1/me/words/{slug}` `{"saved": true}` | Set any of `seen`, `liked`, `saved`, `known` while online |
| `GET /api/v1/me/words/{slug}` | Your flags for one word |
| `GET /api/v1/me/words?list=saved` | Your `saved`, `liked`, `known` or `seen` words, most recent first |
| `POST /api/v1/me/sync` | Offline sync (below) |

To try them in Swagger UI: call dev-login, copy `accessToken`, click **Authorize** and paste it.

Errors use the standard [problem details](https://www.rfc-editor.org/rfc/rfc9457) JSON format.

## Offline sync

The app keeps words and flags on the phone and syncs when it can:

```json
POST /api/v1/me/sync
{ "sinceRevision": 41,
  "changes": [ { "word": "ephemeral", "seen": true, "liked": false, "saved": true, "known": false,
                 "changedAt": "2026-10-26T09:15:00Z" } ] }

→ { "revision": 44, "hasMore": false, "changes": [ ...every word changed after revision 41... ], "unknownWords": [] }
```

- Every change gets the next number from a per-user **revision** counter. The app stores the `revision` it
  got back and sends it as `sinceRevision` next time, so pulls never depend on phone clocks. If `hasMore`
  is true, sync again straight away.
- Each change carries the word's full flags and when the change happened on the phone. If two devices
  change the same word, the **later change wins**. Times in the future are capped to the server's clock.
- The feed only skips words once they're marked `seen`, so the app should report words as seen when
  they're shown.

While users have saved words, the WordNet import refuses to replace the catalog, because that would
renumber the words.

## Sign-in

Every sign-in method ends in the same place: the backend issues **its own** JWT (HS256, 30 days), and
everything else only reads the user id from it. Dev login exists so the app can be built before
**Google Sign-In** (planned: `POST /api/v1/auth/google`, which verifies Google's ID token and links by email).

| Profile | Dev login | Token signing key |
|---|---|---|
| `dev` (default for `bootRun`) | on | built-in throwaway key |
| anything else, e.g. production | **off** | `JWT_SECRET` env var (≥ 32 chars). Startup fails without it |

Generate a production secret with `openssl rand -base64 48`.

## Using MariaDB / MySQL instead

Create the database once:

```sql
CREATE DATABASE vohcarb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'vohcarb'@'localhost' IDENTIFIED BY 'change-me';
GRANT ALL PRIVILEGES ON vohcarb.* TO 'vohcarb'@'localhost';
```

Then run with the `mariadb` profile:

```bash
SPRING_PROFILES_ACTIVE=dev,mariadb DB_PASSWORD=change-me ./gradlew bootRun
```

Schema changes go through Flyway migrations in `src/main/resources/db/migration`.
Write them in SQL that works on MariaDB, MySQL and H2.

## Project layout

Code is organised by feature under `src/main/kotlin/app/vohcarb/`:
`info/`, `catalog/` (with `catalog/importer/`), `auth/`, `user/`, `progress/`, then `practice/`, `social/`,
`notify/` as they are built.

## License

Code: [Apache 2.0](LICENSE).

Word data: [Open English WordNet](https://github.com/globalwordnet/english-wordnet) by the Global WordNet
community, licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The app must show this
attribution. Frequency data: [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit Dave,
built from OpenSubtitles 2018, licensed [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

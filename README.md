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
| `GET /api/v1/word-of-the-day?date=2026-10-26` | Everyone's word of the day (UTC day; `date` optional), e.g. for a widget |
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
| `GET /api/v1/me/practice/session?size=10` | Saved words to practise now (due reviews first, then new), each with a quiz question |
| `POST /api/v1/me/practice/reviews` | Record answers: `AGAIN`, `HARD`, `GOOD` or `EASY` (below) |
| `GET /api/v1/me/practice/stats` | Reviews due, new words, total cards, reviews today, streak |
| `POST /api/v1/groups` `{"name": "..."}` · `GET /api/v1/groups` | Create a group (you're the owner) · your groups |
| `POST /api/v1/groups/join` `{"code": "DK6H-GQFC"}` | Join with an invite code (case, spaces and dashes don't matter) |
| `GET /api/v1/groups/{id}` | Members (display names only) and the invite code |
| `POST /api/v1/groups/{id}/leave` | Leave; an owner hands over to the longest-standing member, the last one out deletes the group |
| `DELETE /api/v1/groups/{id}/members/{userId}` · `POST /api/v1/groups/{id}/invite-code` · `DELETE /api/v1/groups/{id}` | Owner only: remove a member · replace the code · delete the group |
| `GET` · `POST` `{"word", "note"}` · `DELETE /{slug}` on `/api/v1/groups/{id}/words` | The shared word list; whoever added a word, or the owner, can remove it |
| `GET /api/v1/groups/{id}/word-of-the-day` | The group's own word of the day, the same for every member |
| `POST /api/v1/me/devices` `{"token", "platform": "ANDROID"}` · `DELETE /api/v1/me/devices?token=` · `GET` | Register a phone's FCM token for reminders · remove · list |
| `POST /api/v1/me/devices/test` | Send yourself a test notification |
| `GET /api/v1/groups/{id}/leaderboard?days=7` | Members ranked by answers in the period, then streak; also words mastered (interval ≥ 21 days) |

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

While users have saved words, reviews or group words, the WordNet import refuses to replace the catalog, because that
would renumber the words.

## Practice (spaced repetition)

Saved words become flashcards scheduled with **SM-2** (the SuperMemo/Anki algorithm), in
[`Scheduler.kt`](src/main/kotlin/app/vohcarb/practice/srs/Scheduler.kt). It's plain Kotlin with no dependencies,
so the app can copy it and schedule reviews offline exactly like the server.

| Answer | Next review |
|---|---|
| `AGAIN` | in 10 minutes; the word starts over |
| `HARD` | a little later than last time; the word gets harder |
| `GOOD` | 1 day, then 3, then about × 2.5 each time (8, 20, 50…) |
| `EASY` | 4 days at first, then faster growth; the word gets easier |

Each session item carries a multiple-choice question: *what does this word mean?* or *which word means
this?* (with the word blanked out of its own definition). Wrong answers are other words of the same part of
speech and a similar level. The correct answer is included, so the app can grade offline. Answers are
uploaded with the time they happened (`reviewedAt`): re-sending the same answer is harmless, and answers
older than the card's last review only count towards stats. Streaks use the timezone from your preferences.

## Groups

Friends are the people in your groups: there's no separate friend list. Anyone with a group's 8-character
invite code can join (codes avoid look-alike characters such as 0/O and 1/I/L). The owner can remove members
and replace the code, which stops the old one working. Groups you're not in answer 404, so nobody can find out
which groups exist. Limits: 50 members per group, 20 groups per person.

Words of the day are worked out from the date (and the group), so nothing is stored and everyone sees the
same word. They're picked from levels 3–4 and are never sensitive words.

## Notifications

Each user picks reminder times (in their timezone) in their preferences. Every minute the server sends
reminders that are due to the user's registered phones: **"3 words are ready for review"** when practice is
due, otherwise **the word of the day**. A reminder still goes out up to 15 minutes late (e.g. after a
restart) and never twice.

Sending uses Firebase Cloud Messaging (Android, and iOS through FCM). Until `FCM_CREDENTIALS_FILE` points at
a Firebase service-account key, notifications are only written to the log. Devices that FCM reports as
uninstalled are forgotten automatically.

## Deployment

See **[docs/deploy.md](docs/deploy.md)**: a step-by-step guide for a Hetzner (or Contabo) VPS with Docker
Compose: MariaDB, the backend, Caddy for automatic HTTPS, nightly backups and updates. The files are in
[`deploy/`](deploy/) and the image is built from the [`Dockerfile`](Dockerfile).

## Sensitive words

About 120 words have a sense WordNet labels as a slur or obscenity (`SensitiveWords.kt`). They stay in
search and word lookups, but are never picked for you: not in the feed, the random word or quiz options.

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
Write them in SQL that works on MariaDB, MySQL and H2. Watch out for words that H2 accepts but MariaDB
reserves (e.g. `sensitive`), and for functions with different names (`random()` vs `rand()`). The test suite
runs on H2, so run new migrations and queries against MariaDB once (Docker is easiest).

## Project layout

Code is organised by feature under `src/main/kotlin/app/vohcarb/`:
`info/`, `catalog/` (with `catalog/importer/`), `auth/`, `user/`, `progress/`, `practice/`, `social/` and
`notify/`. Database migrations are in `src/main/resources/db/migration` (SQL) and `src/main/kotlin/db/migration`
(Kotlin, for data changes that share code with the app).

## License

Code: [Apache 2.0](LICENSE).

Word data: [Open English WordNet](https://github.com/globalwordnet/english-wordnet) by the Global WordNet
community, licensed [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The app must show this
attribution. Frequency data: [FrequencyWords](https://github.com/hermitdave/FrequencyWords) by Hermit Dave,
built from OpenSubtitles 2018, licensed [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

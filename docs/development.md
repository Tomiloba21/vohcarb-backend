# Development notes

Everything someone (or an AI assistant) needs to pick up Vohcarb where it was left: what was decided and why,
how to work on it, the traps already found, and what comes next. The [README](../README.md) documents the API
itself; [deploy.md](deploy.md) covers the server.

*Last updated: 2026-10-26, after backend phase 6.*

---

## What Vohcarb is

An open-source alternative to the paid "Vocabulary – Learn words daily" Android app (Monkey Taps), built for
the author and friends. Learn a few words a day from a feed, save them, practise them with spaced repetition,
and share words with friends in groups.

| Part | Repository | Status |
|---|---|---|
| Backend (this repo) | `github.com/Tomiloba21/vohcarb-backend` | Phases 0–6 done |
| App | `vohcarb-app` (to be created) | Not started: Kotlin Multiplatform, Android first, iOS later |

## Decisions (and why)

| Decision | Why |
|---|---|
| **Kotlin + Spring Boot 4, Java 21**, Gradle Kotlin DSL | JVM as requested; Kotlin shared with the KMP app |
| **Separate repos** for backend and app (not a monorepo) | Independent builds and deploys; the app talks to the backend only through the API |
| **The app's API client comes from the OpenAPI spec** (`/v3/api-docs`) | Replaces the shared-DTO module a monorepo would have had |
| **Package by feature** (`catalog/`, `practice/`, `social/`…), not by layer | One change stays in one folder; clear boundaries. Each package holds its entities, repositories, services, controllers, DTOs. **Do the same in the app.** |
| **H2 in MariaDB mode** locally, **MariaDB 11.4** in production | Zero setup for development; MariaDB on the VPS |
| **Flyway** migrations; Hibernate only validates (`ddl-auto: validate`) | Schema is identical everywhere |
| **Own JWTs** (HS256, 30 days); sign-in methods only produce a user | Google Sign-In can be added later without touching anything else |
| **Dev login** (`POST /auth/dev-login`) instead of Google for now | The author has no Google Cloud/Firebase project yet. Only exists in the `dev` and `test` profiles |
| **Google Sign-In: later** | Plan: `POST /api/v1/auth/google` verifies Google's ID token, then calls `UserService.signIn("google", sub, email, name)`; it links to existing accounts by email |
| **Push: FCM HTTP v1**, logged until `FCM_CREDENTIALS_FILE` is set | Same "works before Firebase exists" approach as login |
| **Word data: Open English WordNet 2025** (CC BY 4.0) + **FrequencyWords** (OpenSubtitles 2018, CC BY-SA 4.0) | Free licences. The app must show credits for both |
| **Levels 1–5** from frequency rank (≤5k, ≤20k, ≤50k, ≤150k, rest) | Level 5 is mostly jargon, so the default feed range is **2–4** |
| **SM-2** spaced repetition in plain Kotlin (`practice/srs/Scheduler.kt`) | No dependencies, so the app can copy the file and schedule offline identically |
| **Sync:** per-user revision counter + last-write-wins by device time | Pulls never depend on phone clocks; future clocks are capped |
| **Sensitive words** (slurs/obscenities labelled by WordNet) never picked for users | Still searchable; excluded from feed, random word, word of the day and quiz options |
| **Hosting:** one Hetzner CX22 (or Contabo) with Docker Compose + Caddy | Cheap and simple for a friend group |
| **Licence:** Apache 2.0 | Permissive, with a patent grant |

## Working on the backend

Needs **JDK 21** (`JAVA_HOME` pointing at it). Gradle comes through the wrapper (`gradlew.bat` on Windows).

```bash
./gradlew bootRun     # dev profile: H2 in data/, dev login on, http://localhost:8080/swagger-ui.html
./gradlew test        # ~70 tests on in-memory H2, about 30 s
```

**The word data isn't in git.** Download it once into `data/raw/` and import it (commands in the README,
section "Load the word catalog"). It's about 30 MB and the import takes under a minute.

To try signed-in endpoints: in Swagger, call `dev-login`, copy `accessToken`, click **Authorize**.

### Conventions

- New feature → new package with its own entities, repository, service, controller, DTOs, and tests next to it.
- Schema changes: a new `V<n>__name.sql` in `src/main/resources/db/migration`. **Never edit an applied SQL migration.**
  Kotlin migrations (`src/main/kotlin/db/migration`) are for data changes that share code with the app.
- Catalog entities are read-only (`@Immutable`): only the importer writes them, with batch SQL.
- Errors: throw `ResponseStatusException`; responses are RFC 9457 problem details.
- Groups and other per-user resources answer **404** to outsiders, so nobody can tell they exist.
- Tests: `@SpringBootTest` + MockMvc on the `test` profile. Each test makes its own user (`devLogin`) so tests
  don't depend on each other. Shared helpers are in `src/test/kotlin/app/vohcarb/TestSupport.kt`.
- Commits: one per phase or feature, with a descriptive message. Ask before pushing.

### Traps already found

1. **H2 is not MariaDB.** Two bugs passed every test and only showed up on real MariaDB:
   - `sensitive` is a **reserved word** in MariaDB/MySQL, so the column is `is_sensitive`.
   - HQL `random()` doesn't exist in MariaDB (`RAND()`), so the feed shuffles in Kotlin.

   **Run new migrations and queries against MariaDB once** before deploying (Docker: see "Testing on
   MariaDB" below). Adding a MariaDB job to CI (Testcontainers) is a planned improvement.
2. **Re-importing WordNet renumbers words**, so the importer refuses while users have saved words, reviews or
   group words. Before the next WordNet edition, the importer needs to keep word ids stable by slug (TODO in
   `WordNetImporter`).
3. **Re-importing WordNet resets levels**: run the frequency import after it (both flags in one run do this).
4. In the MockMvc Kotlin DSL with Jackson 3, compare JSON lists with Hamcrest (`value(contains(...))`), not `listOf(...)`.
5. **Outside the `dev` profile the server won't start without `JWT_SECRET`** (32+ characters). That's deliberate.

### Testing on MariaDB with Docker

```bash
docker network create vohcarb-local
docker run -d --name vohcarb-local-db --network vohcarb-local -e MARIADB_DATABASE=vohcarb -e MARIADB_USER=vohcarb \
  -e MARIADB_PASSWORD=devpass -e MARIADB_ROOT_PASSWORD=devpass mariadb:11.4
docker build -t vohcarb-backend:local .
docker run --rm --network vohcarb-local -v "$PWD/data/raw:/app/data/raw:ro" \
  -e SPRING_PROFILES_ACTIVE=mariadb,import -e DB_URL=jdbc:mariadb://vohcarb-local-db:3306/vohcarb \
  -e DB_USER=vohcarb -e DB_PASSWORD=devpass vohcarb-backend:local \
  --vohcarb.import.file=data/raw/english-wordnet-2025.xml.gz --vohcarb.import.frequency-file=data/raw/en_full.txt
docker run -d --name vohcarb-local-app --network vohcarb-local -p 127.0.0.1:18080:8080 \
  -e SPRING_PROFILES_ACTIVE=mariadb,dev -e DB_URL=jdbc:mariadb://vohcarb-local-db:3306/vohcarb \
  -e DB_USER=vohcarb -e DB_PASSWORD=devpass vohcarb-backend:local
# …try http://127.0.0.1:18080, then clean up:
docker rm -f vohcarb-local-app vohcarb-local-db && docker network rm vohcarb-local
```

On Windows, run these in Git Bash or WSL, or replace `$PWD` with `%cd%` in cmd.

## Status

| Phase | What | Commit |
|---|---|---|
| 0 | Skeleton: Spring Boot, H2, Flyway | `fa1ea8b` |
| 1 | Word catalog: schema, WordNet importer, catalog API, Swagger | `e50e1a6` |
| 1b | Difficulty levels from word frequency | `7cda588` |
| 2 | Users, JWT auth, dev login, `/me`, preferences | `eca4cea` |
| 3 | Feed, saved/liked/seen/known words, offline sync | `0e75b73` |
| 4 | Spaced-repetition practice, quizzes, streaks, sensitive-word filter | `e8b022d` |
| 5 | Friend groups, invite codes, shared lists, word of the day, leaderboard | `cf23fac` |
| 6 | Push reminders (FCM), Docker deployment, CI; MariaDB fixes | `a82f084` |

Not done yet, roughly in order:

1. **The app** (next; see below).
2. **Firebase project**: needed for Google Sign-In *and* push notifications. Free.
3. **Google Sign-In endpoint** (`POST /api/v1/auth/google`), once the Firebase/Google project exists.
4. **Buy the VPS and a domain**, then follow [deploy.md](deploy.md).
5. Smaller improvements: MariaDB in CI; stable word ids on re-import; livelier word of the day (current picks
   like *ironware* are dull; prefer words with example sentences); more words (WordNet lacks e.g. *petrichor*;
   Wiktionary, CC BY-SA, could fill gaps).

## Next: the app (`vohcarb-app`)

Plan, to be confirmed when starting:

- **Kotlin Multiplatform + Compose Multiplatform.** Android first; iOS later from the same code.
- **Organised by feature**, like the backend: `feed/`, `word/`, `saved/`, `practice/`, `groups/`, `settings/`.
- **API client generated from the backend's OpenAPI spec** (or a small hand-written Ktor client: the API is small).
- **Offline first:** a local database (SQLDelight or Room KMP) holds the user's words and flags, and syncs
  with `POST /api/v1/me/sync`. Store the returned `revision` and send it as `sinceRevision` next time.
- **The app must mark words `seen`** when it shows them, or the feed keeps offering them.
- **Practice works offline:** each session item includes its quiz question *and* the correct answer. Grade
  locally, schedule with a copy of `Scheduler.kt`, upload answers later with `reviewedAt` (re-sending is harmless).
- **Home-screen widget** (Jetpack Glance) showing `GET /api/v1/word-of-the-day`, which needs no login.
- **Notifications:** register the FCM token with `POST /api/v1/me/devices` on every start.
- **Sign-in:** dev login against a local backend for now. From the Android emulator, the host machine's
  `localhost` is `http://10.0.2.2:8080`. Debug builds need cleartext HTTP allowed for that address.
- **Credits screen** for WordNet (CC BY 4.0) and FrequencyWords (CC BY-SA 4.0).
- The paid app's look to aim for: one word per screen, swipe through, big serif type, themes.

Tools on the development PC: Android Studio (with the KMP plugin), JDK 21, an Android emulator or a phone
with USB debugging, and Git with access to GitHub.

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

- `GET /api/v1/info`: app name and API version
- `GET /actuator/health`: health check
- `/h2-console`: browse the local database (JDBC URL `jdbc:h2:file:./data/vohcarb`, user `sa`)

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
`info/` now, then `catalog/`, `auth/`, `user/`, `progress/`, `practice/`, `social/`, `notify/` as they are built.

## License

Code: [Apache 2.0](LICENSE). Word data imported later (Open English WordNet, Wiktionary)
keeps its own licenses (CC BY 4.0 / CC BY-SA) and will be credited in the app.

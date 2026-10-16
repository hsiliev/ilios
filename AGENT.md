# AGENT.md – notes for AI assistants working on this project

## What this is

"Слънце" (project/package name `ilios`): a small Kotlin web app that stores Open-Meteo weather for the user's places and shows it as charts, plus an estimate of solar production against a battery. The UI, advice texts, error messages and emails are **Bulgarian only** (dates `дд.ММ.гггг`, decimal comma). There is no language switch.

Guiding principle from the owner: **favour simplicity, speed and size over features.** Prefer no new dependencies, no build step for the frontend, no frameworks. Ask before adding libraries.

Stack (fixed by the owner): Kotlin, Ktor (CIO engine), Koin, Exposed, kotlinx.serialization/coroutines, embedded H2. Versions are in `build.gradle.kts` (Kotlin 2.4.x, Ktor 3.6, Exposed 1.5, Koin 4.2, JDK 25 in use). Angus Mail is used for SMTP.

## Run, build, test

```bash
./gradlew run                 # http://localhost:8080
./gradlew installDist         # build/install/ilios/bin/ilios
./gradlew buildFatJar         # build/libs/ilios-all.jar
./gradlew test                # in-process tests, throwaway database, no port, no network
```

- Configuration is by environment variables: optional `PORT` (default 8080; invalid values fail at start-up, see `portFrom` in `Main.kt`) and the mail pair: `MAIL_USERNAME` (the sending Gmail address) and `MAIL_PASSWORD` (its Google app password). There is no default account. If either is missing no mail is sent and reset links are printed to the server log (`PASSWORD RESET for …`).
- Everything else is deliberately hard-coded: DB `./data/ilios`, public Open-Meteo endpoints, `BASE_URL = http://localhost:$PORT` (used in reset links, host is always localhost), session cookie not `Secure`. These are in `Main.kt`, `Weather.kt`, `Mail.kt`. Changing any of them to be configurable is a product decision – ask first.
- The owner often has the app running locally on 8080 with their real data in `./data/`. **Never send test requests to 8080 and never `kill`/`pkill` by `MainKt`** – that kills the owner's instance and curl calls would hit their real database (an earlier session polluted it that way and had to clean up). For manual checks run a separate instance from a scratch directory on another port, e.g. `cd /tmp/scratch && PORT=18080 /path/to/build/install/ilios/bin/ilios`, and stop it by its PID. Automated checks: `./gradlew test`.

## Layout

```
src/main/kotlin/
  Main.kt       Ktor setup, Koin module, all routes (/api/...), request/response classes
  Db.kt         Exposed tables, H2 init, one-off migration of the old per-user settings table
  Auth.kt       PBKDF2 hashing, sessions and reset tokens (only SHA-256 of tokens stored)
  Mail.kt       Gmail SMTP sender (disabled unless MAIL_USERNAME and MAIL_PASSWORD are set)
  Weather.kt    Open-Meteo client, database refresh loop, database reads
  Advice.kt     Report model (21-day series, watering advice), pure functions
src/main/resources/static/   plain browser ES modules, no bundler
  index.html (shell) style.css app.js (header, routing) auth.js dashboard.js (place cards)
  charts.js (three SVG charts) settings.js util.js (DOM helper h(), api(), formats, icons)
src/test/kotlin/AppTest.kt   Ktor testApplication against a temp H2 file
```

## Data model (Exposed, H2)

- `users(id, email unique, pass_hash)`; `tokens(hash, user_id, kind session|reset, expires)`.
- `locations(id, user_id, name, country, lat, lon, tz, solar, battery_kwh, solar_kwp, pv_ratio)` – **solar settings are per place**; new places start with `solar = false`.
- `daily(location_id, day, t_max, t_min, rain_mm, radiation_mj, fetched_at)` – 14 past + 14 future days.
- `intraday(location_id, day, t, temp, rain_mm, event)` – 15-minute steps for 3 days.
- Foreign keys cascade on delete: deleting a place deletes its weather rows, deleting a user deletes places and tokens. (Covered by a test.)

## How weather works (important)

- **Pages read only from the database.** `WeatherService.days()` / `intraday()` never call the network.
- Only `WeatherService.refresh(loc)` talks to Open-Meteo: daily (`past_days=14`, `forecast_days=14`) and `minutely_15` (finest resolution; interpolated from hourly outside Open-Meteo's high-resolution regions).
- It is triggered when a place is added, and by `refreshLoop()` (launched in `Application.module()`), which checks every 10 minutes and refetches a place whose daily data is older than 3 hours **or** that has no intraday rows for today.
- Daily rows before today are kept as first stored; today and future rows are replaced. Intraday rows are replaced wholesale.
- A place with no data shows a "loading" card; an old but present dataset is kept if a refresh fails.

## Report and charts

- `GET /api/report` returns per place: `today`, `days` (21 days: previous, current, next week, Monday–Sunday), `watering` advice, `battery` (only `batteryKwh`, null when the place has no solar), `intraday` (today's 15-minute points with an `event`: thunder/snow/rain/wind/cloud).
- Solar "produced" kWh is an **estimate from radiation** (`kWp × MJ/m² ÷ 3.6 × performance ratio`), not metered; past days are labelled estimate, today and later forecast.
- Watering advice (plants/grass/trees, rule of thumb constants in `Advice.kt`) is shown only as small chips in each place's collapsed summary row; the detailed watering section and the battery recommendation text were intentionally removed from the UI.
- Charts in `charts.js` are hand-written SVG strings. The weekly chart deliberately uses two y-axes (temperature, rain) to match the owner's reference image, with both axes labelled; the dataviz guidance would normally avoid that. Icon rows above the plots use the same outline style.
- Keep chart markup free of user text (names are never put into the SVG strings).

## Frontend conventions

- `h(tag, attrs, ...children)` builds DOM; `cls`, `on*` attrs are special. Set `innerHTML` only for chart strings built from numbers/fixed words.
- Settings page (`#settings`): a "Места" header with "+ Добави място" opening a search panel, one card per place (solar checkbox panel with battery kWh / panel kWp / performance ratio), one **Запази** button for all places, delete icon per place.
- Dashboard: collapsible `<details>` per place with summary chips; open/closed state kept in `localStorage` (`ilios.open`).
- Static files are served by `staticResources("/", "static")`; after editing JS hard-refresh the browser.

## Gotchas learned

- Exposed 1.x packages are `org.jetbrains.exposed.v1.core.*` and `org.jetbrains.exposed.v1.jdbc.*`.
- `SchemaUtils.createMissingTablesAndColumns` adds columns/tables but **never drops** columns. `Daily.fetchedAt` is `NOT NULL` without a default, so it must stay in the table definition even if unused.
- Column `day` is an H2 keyword: Exposed quotes it as lowercase `"day"`. In the H2 shell use `SELECT "day" …`; other columns are unquoted/uppercase.
- Inside `MimeMessage.apply { }` identifiers like `from`/`sender` resolve to the message's properties – hence `mailFrom`.
- The H2 file is locked while the app runs; copy `data/ilios.mv.db` to inspect it.
- Open-Meteo geocoding is called with `language=bg`; the search UI needs the Търси button (no search-as-you-type yet).

## Git

- Remote: `git@github.com:hsiliev/ilios.git` (SSH; HTTPS has no stored credentials), branch `main`.
- Commit author for this repo is `hsiliev@gmail.com`; the machine's global git email is a work address, so set the author explicitly. Commit messages end with the `Co-Authored-By` trailer requested by the session; ask before pushing.
- `.gitignore` excludes `data/` (real database), `.idea/`, `.claude/`, build output. No secrets belong in the repo; the Gmail address and app password live only in the environment.

## Ideas not done (owner has not asked for them)

Search-as-you-type and a coordinates fallback in "add place"; a rename field; per-place or configurable base URL; Secure cookie / HTTPS support; configurable port/DB path; rate limiting on login/reset.

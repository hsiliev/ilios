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

- Configuration is by environment variables: optional `SOLAX_URL` (SolaX region host), optional `PORT` (default 8080; invalid values fail at start-up, see `portFrom` in `Main.kt`) and the mail pair: `MAIL_USERNAME` (the sending Gmail address) and `MAIL_PASSWORD` (its Google app password). There is no default account. If either is missing no mail is sent and reset links are printed to the server log (`PASSWORD RESET for …`).
- Everything else is deliberately hard-coded: DB `./data/ilios`, public Open-Meteo endpoints, `BASE_URL = http://localhost:$PORT` (used in reset links, host is always localhost), session cookie not `Secure`. These are in `Main.kt`, `Weather.kt`, `Mail.kt`. Changing any of them to be configurable is a product decision – ask first.
- The owner often has the app running locally on 8080 with their real data in `./data/`. **Never send test requests to 8080 and never `kill`/`pkill` by `MainKt`** – that kills the owner's instance and curl calls would hit their real database (an earlier session polluted it that way and had to clean up). For manual checks run a separate instance from a scratch directory on another port, e.g. `cd /tmp/scratch && PORT=18080 /path/to/build/install/ilios/bin/ilios`, and stop it by its PID. Automated checks: `./gradlew test`.

## Layout

```
src/main/kotlin/
  Main.kt       Ktor setup, Koin module, all routes (/api/...), request/response classes
  Db.kt         Exposed tables, H2 init, start-up cleanup of obsolete settings columns/tables
  Auth.kt       PBKDF2 hashing, sessions and reset tokens (only SHA-256 of tokens stored)
  Mail.kt       Gmail SMTP sender (disabled unless MAIL_USERNAME and MAIL_PASSWORD are set)
  Weather.kt    Open-Meteo client, database refresh loop, database reads
  Solax.kt      SolaX Cloud client: per-place credentials, sync loop, produced energy
  Advice.kt     Report model (21-day series, watering advice), pure functions
src/main/resources/static/   plain browser ES modules, no bundler
  index.html (shell) style.css app.js (header, routing) auth.js dashboard.js (place cards)
  charts.js (three SVG charts) settings.js util.js (DOM helper h(), api(), formats, icons)
src/test/kotlin/AppTest.kt   Ktor testApplication against a temp H2 file
src/test/kotlin/SolaxTest.kt SolaX sync against a fake server (Ktor mock engine)
solax-api.md                 SolaX API reference (read before touching Solax.kt)
```

## Data model (Exposed, H2)

- `users(id, email unique, pass_hash)`; `tokens(hash, user_id, kind session|reset, expires)`.
- `locations(id, user_id, name, country, lat, lon, tz, solar)` – **solar settings are per place**; new places start with `solar = false`. Panel power (kWp) and battery capacity (kWh) are **not** settings: they are read from SolaX and stored in `solax.pv_kwp` / `solax.battery_kwh` (the old `locations.battery_kwh`/`solar_kwp`/`pv_ratio` columns and the old `settings` table are dropped at start-up).
- `daily(location_id, day, t_max, t_min, rain_mm, radiation_mj, fetched_at)` – 14 past + 14 future days (past days are stored once and never replaced). `geocode_cache(query, json, fetched_at)` – place-search results.
- `solax(location_id, client_id, client_secret, plant_id, plant_name, business_type, pv_kwp, battery_kwh, produced_through, battery_days_through, battery_device_kwh, diag_devices, diag_history, history_try_at, plant_read_at, inverter_sns, soc, battery_remaining_kwh, battery_power_w, battery_read_at, battery_try_at, battery_status, token, token_expires, status, due_at)` – one row per connected place; `produced(location_id, day, kwh, ac_kwh)` – daily production from SolaX (`kwh` = `pvGeneration`, the panels' output, used for the chart and efficiency; `ac_kwh` = `inverterACOutputEnergy`, the SolaX app's "Inverter Output", only shown in the tooltip so numbers can be compared with the SolaX UI; days stored before `ac_kwh` existed are re-fetched once at the first start after the upgrade). Both cascade with the place.
- `intraday(location_id, day, t, temp, rain_mm, event)` – 15-minute steps for 3 days.
- Foreign keys cascade on delete: deleting a place deletes its weather rows, deleting a user deletes places and tokens. (Covered by a test.)

## How weather works (important)

- **Pages read only from the database.** `WeatherService.days()` / `intraday()` never call the network.
- Only `WeatherService.refresh(loc)` talks to Open-Meteo: daily (`past_days=14`, `forecast_days=14`) and `minutely_15` (finest resolution; interpolated from hourly outside Open-Meteo's high-resolution regions).
- It is triggered when a place is added, and by `refreshLoop()` (launched in `Application.module()`), which checks every 10 minutes and refetches a place whose daily data is older than 3 hours **or** that has no intraday rows for today.
- Daily rows before today are kept as first stored; today and future rows are replaced. Intraday rows are replaced wholesale.
- A place with no data shows a "loading" card; an old but present dataset is kept if a refresh fails.

## SolaX Cloud (produced energy)

- **API details live in [`solax-api.md`](solax-api.md)** (auth, the endpoints used, response codes, enumerations, open questions). Read it before changing `Solax.kt`; update it when you learn something new about the API.
- Flow in short: token (cached) → all plants of the account (`page_plant_info`, paged, both business types tried; stored as JSON in `solax.plants` for the settings **dropdown**; **the first plant is selected by default**, `solax.plant_id` holds the selection) → the selected plant's record carries `pvCapacity` kWp and `batteryCapacity` kWh (read on first connect and then weekly via `plant_read_at`, or again when credentials or the selected plant change, to save quota) → one `get_stat_data` call per month involved in the not-yet-fetched days → `pvGeneration` per day stored in `produced`.
- Battery charge has **its own schedule, separate from the 3-hourly production sync: once a day at 22:30 Europe/Sofia time** (a fixed zone, not the place's), plus one reading right after connecting. `readBatteryIfDue()` (called by `syncLoop` and after saving credentials) reads when the last reading is older than the latest 22:30 Sofia slot (`lastBatterySlot`, DST-safe) and retries failures at most hourly (`battery_try_at`); the outcome text is `battery_status`. It lists the inverter serials once (`page_device_info`, cached in `inverter_sns`), then `realtime_data` with `deviceType=2&requestSnType=1` returns `batterySOC`, `batteryRemainings`, `chargeDischargePower`; the app stores the average %, summed kWh and power with the reading time. Plants without a battery capacity are never asked. The UI shows a chip and a gauge and always shows when the reading was taken.
- **Battery history for the ✓/✗ icons (past days):** `batteryHistoryIfDue()` fetches, for each past day once, the battery history (`history_data`, `deviceType=2&requestSnType=1`, `timeInterval=30`, **at most 12 hours per request**, so two windows per day and three on the 25-hour day when summer time ends) and stores the day's highest charge (several batteries averaged per moment) in `battery_days(location_id, day, max_soc)`; `battery_days_through` is the progress marker, a backlog is worked off **one day per loop pass** (every 10 minutes) to stay under the rate limit, failures retry hourly (`history_try_at`). The chart shows ✓ for a past day when `max_soc ≥ 99`, ✗ otherwise; today/future/no history yet use the production-vs-capacity logic (tooltip says "по оценка" for past days).
- **Battery capacity has up to three figures** (shown side by side in settings by `capacityText`): the plant record's `batteryCapacity` (`solax.battery_kwh`, was observed to be too low: 5.8 kWh for a battery sold as 15 kWh), the battery devices' summed `ratedCapacity` from `page_device_info?deviceType=2` (`battery_device_kwh`, Wh for residential, read with the weekly plant record), and a **usable capacity** = remaining energy ÷ charge computed from the history points at ≥30 % charge (`battery_days.usable_kwh`, median of the last 14 days). `SolaxPlant.batteryKwh` is the one used (usable > devices > record). On the owner's real account the device list returned no capacity and the history points returned no `batteryRemainings`, so when either gives nothing the raw answer is stored in `solax.diag_devices` / `solax.diag_history` (no credentials in it) to inspect the real field names; the usable capacity can also be derived from the daily 22:30 reading (`soc`, `battery_remaining_kwh`) if the history lacks it.
- A solar place with no synced plant yet has no battery section and no estimate (`report.solar = true`, `battery = null`); a plant without a battery gets the chart without the capacity line and ✓/✗ icons.
- API calls are limited and nothing is polled on page loads. **Refresh schedule (owner's rules):** weather and the solar forecast every 3 hours (Open-Meteo; the forecast kWh is computed at report time from the stored radiation); **produced energy of past days is fetched once per day** (`produced_through` = last fetched day; on connecting it backfills from last week's Monday, then each new final day, yesterday after 02:00 local via `lastProductionDay`; days SolaX has no data for are never re-asked; today is not fetched); **battery charge once a day at 22:30 Sofia time**; panel power/battery capacity on connect then weekly. `syncLoop()` checks every 10 minutes (`syncIfDue`, `readBatteryIfDue`); `due_at` is only the 1-hour retry gate after a failure. Saving a place's credentials triggers an immediate `sync()` and battery read.
- Region host: env `SOLAX_URL` (default EU). The secret is stored in plain text in the database (needed for the API) and is **never** returned by the API or logged; keep it that way (status texts must not contain credentials).
- Each place keeps its own credentials, the selected plant (new credentials reset it to the first plant of the new account; a stale selection sent with new credentials is ignored) and a user-visible `status` string (it also reports the panel power and battery capacity SolaX returned).
- The power chart draws solid bars for measured past days and pale bars for estimates and forecasts (including today). `produced` is `null` in the report when not connected.
- Tests use Ktor's mock engine (`SolaxTest.kt`); never call the real SolaX API or send real credentials from tests. This project only **reads** from SolaX; never add inverter-control calls unless the owner asks.

## Call budget (keep it small)

Every outside call counts against a quota (Open-Meteo weights calls by variables and days; SolaX has a call allowance), so **never add polling, and store every fetched value in the database**. Current cost per place and day: Open-Meteo 8 refreshes x 2 requests (daily values with `past_days` only when past days are missing, plus the 15-minute series); SolaX about 4-5 calls (statistics for the new day, battery charge at 22:30, two history requests for the new day, plant record weekly). Place searches are cached in `geocode_cache` for 30 days. Rules that save calls: past days are stored once and never refetched; days with no data are marked done (`produced_through`, `battery_days_through`) rather than retried; credential changes for the same plant keep stored data (`configure`), a different account wipes it (`wipePlantHistory`); failures back off by cause (`SolaxError.retryMs`: 1 h ordinary/rate limit, 6 h allowance used up, 12 h wrong credentials/no access; `due_at`, `battery_try_at`, `history_try_at` hold the earliest next attempt) and changing the credentials resets the wait.

## Never run the app on a copy of the real database with live credentials

The real `data/ilios.mv.db` holds the owner's SolaX Client ID/Secret. Starting the app on a copy of it starts the sync loop, which **makes real SolaX calls and spends the owner's quota**. For migration or UI checks first neutralise the copy (`UPDATE SOLAX SET CLIENT_ID='fake', CLIENT_SECRET='fake', TOKEN=NULL, TOKEN_EXPIRES=0`) and start with `SOLAX_URL=http://127.0.0.1:1`. Read-only inspection with the H2 shell is fine, but never select `client_secret` or `token`.

## Report and charts

- `GET /api/report` returns per place: `today`, `days` (21 days: previous, current, next week, Monday–Sunday), `watering` advice, `battery` (only `batteryKwh`, null when the place has no solar), `intraday` (today's 15-minute points with an `event`: thunder/snow/rain/wind/cloud).
- Solar kWh in `days[].kwh` is an **estimate from radiation** (`kWp × MJ/m² ÷ 3.6 × efficiency`, where the efficiency is **not a setting**: `calibratedRatio()` in `Advice.kt` derives it from past days that have SolaX production and stored radiation, needs ≥3 usable days, is clamped to 0.3–1.0 and falls back to 0.75); `days[].produced` is SolaX's measured value when the place is connected and is preferred for past days.
- Watering advice (plants/grass/trees, rule of thumb constants in `Advice.kt`) is shown only as small chips in each place's collapsed summary row; the detailed watering section and the battery recommendation text were intentionally removed from the UI.
- Charts in `charts.js` are hand-written SVG strings. The weekly chart deliberately uses two y-axes (temperature, rain) to match the owner's reference image, with both axes labelled; the dataviz guidance would normally avoid that. Icon rows above the plots use the same outline style.
- Keep chart markup free of user text (names are never put into the SVG strings).

## Frontend conventions

- `h(tag, attrs, ...children)` builds DOM; `cls`, `on*` attrs are special. Set `innerHTML` only for chart strings built from numbers/fixed words.
- Settings page (`#settings`): a "Места" header with "+ Добави място" opening a search panel, one card per place (a panel headed by the **Solax Cloud** checkbox, which is the place's `solar` flag, with SolaX Client ID / Secret, a plant dropdown that appears once connected, and the connection and battery status lines; nothing else), one **Запази** button for all places, delete icon per place.
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

Encrypting the stored SolaX secret (needs a key setting); search-as-you-type and a coordinates fallback in "add place"; a rename field; per-place or configurable base URL; Secure cookie / HTTPS support; configurable port/DB path; rate limiting on login/reset.

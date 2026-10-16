# Ilios

Small Kotlin web app (Ktor, Koin, Exposed, kotlinx.serialization, embedded H2) that uses [Open-Meteo](https://open-meteo.com) to:

- chart max/min temperature and rain for the previous, current and next week per location, plus today's forecast in 15-minute steps with weather events (cloudy, windy, rain, thunderstorm, snow),
- recommend whether plants, grass and trees need watering,
- chart daily solar generation (kWh) for the same three weeks, using the energy actually produced when the place is connected to SolaX Cloud (forecasts are calculated from Open-Meteo's solar radiation, the panel power SolaX reports and an efficiency worked out from your own measured days), marking each day on which the battery could be fully charged.

Without SolaX there is no solar chart: the panel power and battery capacity needed for the forecast come from SolaX. Days for which SolaX has no measurement are drawn as pale estimates.

Accounts use email and password, with registration, login and password reset by email.

The interface, advice texts and emails are in Bulgarian, with dates as `дд.ММ.гггг` and a decimal comma. There is no language switch.

## Requirements

- JDK 17 or newer (developed on JDK 25)
- Internet access to Open-Meteo

Gradle is provided by the wrapper; nothing else needs installing.

## Configuration

Mail is configured with two environment variables, used to send password-reset emails through Gmail:

- `MAIL_USERNAME` – the Gmail address that sends the mail
- `MAIL_PASSWORD` – its Google [app password](https://support.google.com/accounts/answer/185833?hl=en)

```bash
export MAIL_USERNAME='you@gmail.com'
export MAIL_PASSWORD='xxxx xxxx xxxx xxxx'
```

If either is missing the app still runs, but no mail is sent: reset links are printed to the server log (look for `PASSWORD RESET for`).

Optional variables:

- `PORT` – HTTP port (default `8081`); reset links in emails point at `http://localhost:<PORT>`.
- `SOLAX_URL` – SolaX API host for your account's region (default `https://openapi-eu.solaxcloud.com`; the URL is under "My Account" in the SolaX developer portal).

Everything else is fixed: database in `./data/ilios`, the public Open-Meteo endpoints, Gmail SMTP on `smtp.gmail.com:587`, and the reset-link host (always `localhost`). Change these in the source (`Main.kt`, `Mail.kt`, `Weather.kt`) if needed.

## Run

```bash
./gradlew run
```

Open <http://localhost:8081>, create an account, then:

1. Open the settings page (cog icon in the header). At the bottom, under **Ново място**, search for a place (e.g. София, Садовец, Агиос Йоанис Продромос) and click **Добави**. Several places share a name, so check the country and coordinates.
2. Each place has its own card. Tick **Solax Cloud** for places with a SolaX solar system and enter that place's SolaX **Client ID** and **Client Secret** (see below), then click **Запази**. The panel power and battery capacity are read from SolaX, so they are not settings, and nothing else needs to be set for solar. Places without solar show only the weather charts and watering advice.

### Standalone jar

```bash
./gradlew buildFatJar
java -jar build/libs/ilios-all.jar
```

or `./gradlew installDist`, then `build/install/ilios/bin/ilios`.

## SolaX Cloud (produced energy)

Each place with a solar system can read its real daily production from [SolaX Cloud](https://developer.solaxcloud.com):

1. In the SolaX developer portal create an application and note its **Client ID** and **Client Secret**.
2. On the settings page tick **Solax Cloud** for the place and fill in **Client ID** and **Client Secret**, then **Запази**. Once they connect, a **Централа** dropdown appears with the plants of the SolaX account by name, and the first one is selected. Choose another plant and save to switch; each place can use a different plant of the same account.
3. The status line under the fields shows whether the connection works and what SolaX reported: panel power, battery capacity and the battery's current charge. Past days on the "Слънце и батерия" chart are then drawn from the inverter's AC output (solid bars; the panels' `pvGeneration` only if the output is missing); today and the forecast show the solar forecast estimated from the reported panel power and stay pale. The estimate's efficiency (how much of the ideal `kWp × radiation` the system really delivers) is not a setting: it is calculated from your own measured days once at least three exist, and until then 75 % is assumed. Until the first successful sync the card says it is waiting for SolaX. The battery charge (percentage, energy left and whether it is charging) is shown as a chip on the collapsed place and as a gauge above the solar chart; it is read **once a day at 22:30 Sofia time** (and once right after you connect the place), so it is the evening value with its date and time, not a live reading. If a reading fails it is retried hourly. The ✓/✗ icons above the solar chart show, **for past days, whether the battery really reached 100%** that day, taken from SolaX's battery history (99% or more counts as full); for today and the future they show whether the sun alone is expected to fill the battery, and a past day without history yet shows no icon (never a forecast).

SolaX counts every API call, so nothing is polled. Produced energy of a past day never changes, so each day is fetched **once**: the days since the start of last week when you connect the place, then yesterday every morning after 02:00 local time. Days SolaX has no data for are not asked for again. The panel power and battery capacity are read when the place is first connected and then about once a week. The settings page lists the battery capacity figures side by side (the plant record's, the battery devices' rated capacity, and a usable capacity calculated from the remaining energy and charge); the calculated one is used for the chart, the gauge and the ✓/✗ icons when available. SolaX limits the number of API calls, so failures such as an exhausted quota are retried only after an hour. Leave Client ID empty and save to disconnect; the stored history stays. The Client Secret is stored in the database (`data/`), because it is needed to call the API, and is never sent back to the browser, so protect that folder.

## Notes

- **What is refreshed when:**

  | Data | Source | When |
  |---|---|---|
  | Weather and the solar forecast | Open-Meteo | when a place is added, at start-up if older than 3 hours, then every 3 hours (the forecast kWh is calculated from the stored weather). Past days are stored once and only asked for again if some are missing |
  | Place search | Open-Meteo | once per distinct search; results are kept for 30 days |
  | Produced energy of past days | SolaX | each day once: on connecting, then yesterday after 02:00 local time |
  | Battery charge | SolaX | once a day at 22:30 Sofia time (and once on connecting) |
  | Highest battery charge of each past day | SolaX | each day once: on connecting one day per 10-minute pass until the last week is covered, then yesterday after 02:00 local time |
  | Panel power, battery capacity | SolaX | on connecting, then weekly |

- Weather is stored in the database and pages read only from it. A background job fetches a place when it is added, at start-up if its data is older than three hours, and whenever it becomes three hours old. Past days are kept as first stored; today and future days are replaced on each refresh.
- The database lives in `./data/`; back up or delete it to keep or reset all data.
- Session cookies are not marked `Secure`, so put the app behind HTTPS only after enabling that in `Main.kt`.

## Layout

```
src/main/kotlin/
  Main.kt       Ktor server, Koin module, routes
  Db.kt         Exposed tables and H2 setup
  Auth.kt       registration, login, sessions, reset tokens, password hashing
  Mail.kt       SMTP sender
  Weather.kt    Open-Meteo client, database refresh and reads
  Solax.kt      SolaX Cloud client (see solax-api.md)
  Advice.kt     report model: 21-day series, watering advice
src/main/resources/static/
  index.html    page shell
  style.css     styles
  app.js        entry point: header, routing between the forecast and settings views
  auth.js       login, registration and password-reset forms
  dashboard.js  collapsible place cards, watering and summary chips
  charts.js     the three SVG charts
  settings.js   settings page: per-place cards and the add-place form
  util.js       DOM helper, API calls, Bulgarian number/date formats, icons
```

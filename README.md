# Ilios

Small Kotlin web app (Ktor, Koin, Exposed, kotlinx.serialization, embedded H2) that uses [Open-Meteo](https://open-meteo.com) to:

- chart max/min temperature and rain for the previous, current and next week per location, plus today's forecast in 15-minute steps with weather events (cloudy, windy, rain, thunderstorm, snow),
- recommend whether plants, grass and trees need watering,
- chart estimated daily solar generation (kWh) for the same three weeks, marking each day on which the battery could be fully charged.

There is no inverter integration: "produced" kWh for past days is estimated from Open-Meteo's solar radiation, not metered.

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

Everything else is fixed: port 8080, database in `./data/ilios`, the public Open-Meteo endpoints, Gmail SMTP on `smtp.gmail.com:587`, and reset links pointing at `http://localhost:8080`. Change these in the source (`Main.kt`, `Mail.kt`, `Weather.kt`) if needed.

## Run

```bash
./gradlew run
```

Open <http://localhost:8080>, create an account, then:

1. Open the settings page (cog icon in the header). At the bottom, under **Ново място**, search for a place (e.g. София, Садовец, Агиос Йоанис Продромос) and click **Добави**. Several places share a name, so check the country and coordinates.
2. Each place has its own card. Tick **Соларна система** for places with solar panels, then set that place's battery capacity (kWh), solar field power (kWp) and performance ratio, and click **Запази**. Places without solar show only the weather charts and watering advice.

### Standalone jar

```bash
./gradlew buildFatJar
java -jar build/libs/ilios-all.jar
```

or `./gradlew installDist`, then `build/install/ilios/bin/ilios`.

## Notes

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

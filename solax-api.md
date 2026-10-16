# SolaX Cloud developer API – reference for this project

Notes distilled from the *SolaX Developer Portal* documentation (saved by the owner in October 2026). It is a summary in our own words, not the official text: **check the portal before relying on a detail**, especially anything marked *unverified*. Code that uses it: `src/main/kotlin/Solax.kt`; tests: `src/test/kotlin/SolaxTest.kt`.

## Basics

- **Base URL depends on the account's region.** The portal shows it under "My Account". The documentation's example is the EU host `https://openapi-eu.solaxcloud.com`. The app defaults to that and reads `SOLAX_URL` to override it.
- All paths below start with `/openapi/…`.
- **Calls are limited.** The API enforces a total call count and a call rate (codes `10405` and `10406`). Do not poll; store results and sync rarely (the app: every 3 hours).
- Using the API to reverse engineer or bulk-collect data is prohibited by the portal's usage rules.
- Credentials are a **Client ID** and **Client Secret** from an *application* created in the developer portal. With the `client_credentials` grant used here, the token covers **all devices of the account**.

## Authentication

`POST /openapi/auth/oauth/token`, body `application/x-www-form-urlencoded`:

| field | value |
|---|---|
| `client_id` | the application's Client ID |
| `client_secret` | the application's Client Secret |
| `grant_type` | fixed: `client_credentials` |

Response (note: **success is `code: 0` here**, not 10000):

```json
{ "code": 0, "result": { "access_token": "…", "token_type": "bearer", "expires_in": 2591999,
                          "scope": "all", "grant_type": "client_credentials", "auth_station": "all" } }
```

- The token is valid about **30 days** (`expires_in` is in seconds). Using it does not extend it; call this endpoint again for a new one. The app caches it and renews it one hour before expiry, or once when a call returns `10402`.
- Every other call sends `Authorization: bearer <access_token>` – `bearer` in lowercase, one space, then the token.
- An OAuth 2.0 authorization-code flow (user logs in on SolaX and the app receives a `code`) also exists for third-party apps acting for other users. This project does not use it.

## Calls the app uses

### List plants – `GET /openapi/v2/plant/page_plant_info`

Query: `businessType` (**required**: `1` residential, `4` commercial & industrial), optional `plantId`, `plantName`, `pageNo`.

Response `code: 10000`, `result.records[]` with, among others: `plantId` (string), `plantName`, `pvCapacity` (kWp), `batteryCapacity` (kWh), `plantTimeZone` (IANA), `latitude`, `longitude`, `plantState`, `plantAddress`. Also `total`, `pages`, `current`, `size` for paging (the app reads only the first page).

### Plant statistics – `POST /openapi/v2/plant/energy/get_stat_data`

Body (JSON; the documentation's example passes every value as a string, which the app copies):

```json
{ "plantId": "339663******618", "dateType": "2", "date": "2026-10", "businessType": "1" }
```

- `dateType` `1` = a year (`date` `YYYY`, one entry per month); `2` = a month (`date` `YYYY-MM`, one entry per day).
- Response `code: 10000`, `result.plantEnergyStatDataList[]`, each entry: `date` (`YYYY-MM` or `YYYY-MM-DD`), `pvGeneration`, `inverterACOutputEnergy`, `exportEnergy`, `importEnergy`, `loadConsumption`, `batteryCharged`, `batteryDischarged`, `earnings` – energies in **kWh**. Also `currencyCode`.
- **The app stores `pvGeneration` (PV energy generated) as "produced"** and keeps `inverterACOutputEnergy` next to it (`produced.ac_kwh`), shown only in the chart tooltip. They differ: the SolaX app's *Inverter Output* tile is the AC output, which includes energy the battery gave back and excludes PV energy that went into the battery, so it is usually lower than PV generation on a system with a battery. The two differ by battery and conversion effects; `pvGeneration` is the closest match for what the weather-based estimate predicts.
- One call per month is enough; the app asks for the months covering "start of last week … today".

## Other read endpoints (not used yet)

- `GET /openapi/v2/plant/realtime_data` (`plantId`, `businessType`) → `plantLocalTime`, `dailyYield`, `totalYield`, `dailyCharged/Discharged`, `dailyImported/Exported`, earnings. `dailyYield` is today's production so far – a candidate for a live "today" value without a monthly call.
- `GET /openapi/v2/device/page_device_info` (`deviceType` required, `plantId`, `deviceSn`, `pageNo`, `businessType`) – devices of the account.
- `GET /openapi/v2/device/realtime_data` (`snList` up to 10 serial numbers, `deviceType`, `businessType`) – live inverter/battery/meter values.
- `GET /openapi/v2/device/history_data` (`snList` up to 10, `deviceType`, `businessType`, `startTime`/`endTime` as 13-digit UTC millisecond timestamps with a window of at most 12 hours and inside the last year, `timeInterval` of 5, 10, 15, 30 or 60 minutes) – time series per device.
- `GET /openapi/v2/alarm/page_alarm_info` (`plantId`, `businessType`, `alarmState` 0 closed / 1 ongoing) – alarms.
- EMS system summaries (`/openapi/v2/device/ems_system/...`), plus `/openapi/v2/device/data/realtime`, heat-pump data and data-subscription (push) endpoints exist.

## Battery capacity and charge

Capacity and charge are **both used by the app** (capacity from the plant record on every sync; charge through the inverter/battery calls described below):

- **Capacity (used; three figures that can disagree):** plant list `pvCapacity` (installed PV, **kWp**) and `batteryCapacity` (installed storage, **kWh**); device list `ratedCapacity` for a battery device (**Wh** for `businessType` 1, **kWh** for 4 – convert accordingly); EMS system info `sysBatteryCapacity` (kWh).
- **Observed on the owner's account (residential, three-phase hybrid):** the plant record said 5.8 kWh for a battery sold as 15 kWh; the real-time reading gave 10 % and 1.2 kWh remaining, which implies about 12 kWh usable. The device list (`deviceType=2`) returned **no** `ratedCapacity` and the history points yielded **no** usable capacity, so the document's field list is not fully reliable. The app keeps the raw answer when this happens (`solax.diag_devices`, `solax.diag_history`).
- **State of charge (used):** `GET /openapi/v2/device/realtime_data` with `deviceType=2` returns `batterySOC` (%), `batteryRemainings` (energy left, kWh), `batterySOH` (health) and `chargeDischargePower` (W, positive = charging, negative = discharging). With `deviceType=2`, `requestSnType=1` means the serial in `snList` is an *inverter's* (returns all of its batteries) and `2` (the default) means a battery's own serial. EMS systems expose `sysBatterySOC`, `sysBatteryRemainings` and `sysBatterySOH` in their summary data.
- **History (used for the past-day ✓/✗):** `GET /openapi/v2/device/history_data` (`snList` = inverter serials, `deviceType=2`, `requestSnType=1`, `businessType`, `startTime`/`endTime` in UTC milliseconds, `timeInterval` 5/10/15/30/60 minutes) returns the battery fields as a flat list of data points (`dataTime` UTC, `plantLocalTime`, `deviceSn`, `batterySOC`, …). **A request may span at most 12 hours**, so a day takes two requests (three on the 25-hour day when clocks go back) and the data must be within the last year. The app takes, per day, the highest charge (several batteries averaged per timestamp) and counts 99 % or more as "reached 100 %".
- All of these are current or past readings; there is no forecast. Device calls need serial numbers first (`page_device_info`), and they use the same call quota.
- Charge/discharge cutoff SOC values (`chargeCutOffSoc`, `dischargeCutOffSoc`, `backupSoc`) appear on the work-mode *control* calls; do not use those to read settings from this project.

## Control endpoints (do not use without the owner's explicit request)

The portal also exposes calls that **change** the installation: inverter work modes, export/import limits, time-of-use schedules, battery heating, VPP modes, EV charger and heat-pump control. This project only reads data. Do not add write calls on your own initiative.

### Night charging from the grid (researched, **not implemented**)

How the API could start and stop grid charging overnight (all writes; the owner has not asked for them to be built):

- **Self Use mode with a charge window (preferred):** `POST /openapi/v2/device/inverter_work_mode/batch_set_spontaneity_self_use` with `snList` (1–10 inverter serials), `businessType`, `minSoc`, `chargeUpperSoc`, `chargeFromGridEnable` (0/1) and `chargeStartTimePeriod1`/`chargeEndTimePeriod1` (`hh:mm`), optionally discharge periods and a second period (`enableTimePeriod2`). The inverter then charges inside the window by itself every day. Not supported on the A1-Hybrid-G2.
- **Time-of-use rule (residential only):** `POST /openapi/v2/device/config/tou/add_tou_set` with `snList`, `businessType`, `startDate`/`endDate` (`MM-dd`), `minSoc` and `runModeInfoList[]` (`runMode` 1 self use, 2 battery standby, 3 peak shaving, **4 charging**, 5 discharging; for charging: `startTime`/`endTime`, `chargePower` in W, `targetSoc`, `chargeGridEnableFlag` 0/1). Returns a `ruleId`; `query_tou_set` and `delete_tou_set` manage rules.
- **Forced charge/stop:** `POST /openapi/v2/device/inverter_work_mode/batch_set_manual_mode` with `manualMode` 0 stop charging and discharging, 1 force charging, 2 force discharging. Documented as mainly for debugging; a missed "stop" would leave the battery charging, so it needs a reliable scheduler.
- **Results are asynchronous.** A control call returns `requestId` and a per-device `status`: 1 device offline, 2 command failed, 3 command delivered, 4 device received and started, 5 execution failed, 6 timed out. Query the final result with `POST /openapi/apiRequestLog/listByCondition` (`requestId`).
- Unknown: whether a developer application's credentials are permitted to send control commands (token scope is `all`, but the document does not promise it). Design idea if ever built: a per-place switch (off by default); the 22:30 reading plus tomorrow's forecast decide whether to enable grid charging or what `targetSoc` to use; the inverter's own schedule does the start/stop.

## Retrying failures

Every failed call still counts against the allowance, so the app waits by cause before trying again: **12 hours** for wrong credentials, a refused token or no access (`10401`, `10402` twice, `10403`, `10500`, `10505`, `10506`: it will not fix itself, and changing the credentials ends the wait), **6 hours** when the call allowance is used up (`10405`), and **1 hour** for rate limits (`10406`), other error codes and network problems. The same waits apply to the daily battery read and the battery history. When the credentials are changed for the same plant, everything already stored (production, battery history, capacity) is kept and only the plant list is read again; a different account's plant list wipes the old plant's data.

## Response codes

All calls except the token request return HTTP 200 with a `code` in the body and succeed on **`10000`**. The token request succeeds on `0`.

| code | meaning | what the app does |
|---|---|---|
| 10000 | success | – |
| 10001 | operation failed | generic error status |
| 10200 | abnormal operation, see `message` | generic error status |
| 10400 | request not authenticated | generic error status |
| 10401 | wrong username/password (here: bad client id/secret on the token call) | status: check Client ID and Secret |
| 10402 | access token failed validation | fetch a new token and retry once |
| 10403 | interface not permitted | status: no access |
| 10405 | API call count used up | status: quota exhausted, retry after 1 hour |
| 10406 | API call rate limit reached | status: rate limit, retry after 1 hour |
| 10500 | no device data permission | status: no access |
| 10505 | device unauthorized | status: no access |
| 10506 | plant unauthorized | status: no access |
| 11500 | system busy, retry later | generic error status |

## Enumerations

- `businessType`: `1` Residential, `4` Commercial & Industrial. It must accompany plant, device and statistics calls. The app does not ask the user: it tries `1`, then `4`, and remembers the one that works.
- `deviceType`: `1` inverter, `2` battery, `3` meter, `4` EV charger, `100` EMS system.
- `plantState` (residential): `0` connecting, `1` offline, above 1 online. Commercial: `0` offline, `1` normal, `2` failure, `3` warning, `4` connecting.

## How the project uses it

- Flow (`SolaxService.sync`): token (cached) → all plants of the account (`page_plant_info` without a `plantId` filter, `pageNo` 1…`pages`, at most five pages) → the selected plant's record, which supplies `pvCapacity` and `batteryCapacity` for the forecast estimate and the chart's capacity line (**read when the place is first connected, then about weekly**, or again when the credentials or the selected plant change, because it rarely changes and every call counts against the quota) →
- Plant choice: the settings page shows a dropdown of the account's plants (stored in `solax.plants`); the **first plant is the default** and the user may pick another per place. New credentials reset the choice to the first plant of the new account. A selected plant that no longer exists gives a status asking to choose another; the list stays available.
- Per place: `client_id`, `client_secret`, the selected plant id and the account's plant list, the plant's `pv_kwp` and `battery_kwh` (read time in `plant_read_at`), token, status and `due_at` live in the `solax` table; daily values in `produced`. Deleting a place deletes both.
- Battery history of past days: for each past day once, two requests per day, one day per 10-minute loop pass (a new connection backfills last week and this week in about two hours, about 28 calls spread out), then yesterday after 02:00 local time; failures retry hourly.
- Battery charge: **once a day at 22:30 Europe/Sofia time** (and once right after connecting), only when the plant reports a battery capacity. `page_device_info` (`plantId`, `deviceType=1`, `businessType`) lists the inverters (serials cached in `inverter_sns`), then `realtime_data` (`snList` = up to 10 inverter serials, `deviceType=2`, `requestSnType=1`, `businessType`) returns one entry per battery. The app averages `batterySOC`, sums `batteryRemainings` and `chargeDischargePower`, and stores them with the time. A failure here is reported in the status text but never blocks the production sync. That is one call a day (two the first time), retried at most hourly on failure.
- Production is fetched **once per day**: on connecting it backfills from last week's Monday to the last final day, then each new final day (yesterday, from 02:00 local time) is added with a single monthly call. `produced_through` records the last fetched day, so days with no data are not asked for again and today (still incomplete) is never fetched. Failures back off by cause (see below); saving changed credentials syncs immediately.
- The Client Secret is stored as given (needed for the API). It must never be returned by the app's own API, logged, or put into a status text.

## Unverified or open points

- Whether `get_stat_data` dates are in the plant's time zone (assumed) and how complete today's value is during the day. The chart treats today's `pvGeneration` as "so far".
- The request body types: the documentation's table says integer for `dateType` and `businessType`, its example uses strings. The app sends strings.
- The battery history response format (assumed from the documentation: a flat list of points with `batterySOC` and a timestamp) and whether `endTime` is inclusive; both are unverified against the real API.
- Whether `pvGeneration` covers third-party PV on EMS/C&I plants the same way as on a residential inverter.
- The exact call quota and rate for a developer account is not stated in the document; the app makes about one statistics call a day per place (two while backfilling across a month boundary) plus one battery call a day; the plant record is read only on first connect and then weekly, and the token and inverter serials are cached.

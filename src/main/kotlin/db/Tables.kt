@file:Suppress("StringLiteralDuplication") // repeated property and column names read better than constants

package ilios.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Users. */
object Users : Table("users") {
    val id = long("id").autoIncrement()
    val email = varchar("email", 254).uniqueIndex()
    val passHash = varchar("pass_hash", 200)
    override val primaryKey = PrimaryKey(id)
}

/** Session and password-reset tokens; only the SHA-256 of the token is stored. */
object Tokens : Table("tokens") {
    val hash = varchar("hash", 64)
    val userId = long("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE).index()
    val kind = varchar("kind", 10) // "session" | "reset"
    val expires = long("expires") // epoch millis
    override val primaryKey = PrimaryKey(hash)
}

object Locations : Table("locations") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE).index()
    val name = varchar("name", 120)
    val country = varchar("country", 80).nullable()
    val lat = double("lat")
    val lon = double("lon")
    val tz = varchar("tz", 60).default("UTC")
    val position = integer("position").default(0) // order of the user's places (ascending; ties by id)
    val solar = bool("solar").default(false) // whether a solar system applies here

    // battery charge (%) the inverter keeps back and never discharges below (0-50)
    val reservedPct = integer("reserved_pct").default(DEFAULT_RESERVE)

    // the owner permits managing the inverter through SolaX Cloud
    val allowControl = bool("allow_control").default(false)

    // power cap (W) of the grid charging programmed into the inverter
    val maxChargeW = integer("max_charge_w").default(DEFAULT_MAX_CHARGE_W)

    // share (%) of the charging power that reaches the battery (the rest is lost); scales charge times and savings.
    // Typed in by the owner; null = use the measured one

    // the same share measured on the night charges in the stored battery history (see measuredChargeEffPct)
    val chargeEffMeasured = integer("charge_eff_measured").nullable()

    // electricity supplier (key of TARIFFS) for the night-charge advice
    val supplier = varchar("supplier", 20).default(DEFAULT_SUPPLIER)
    override val primaryKey = PrimaryKey(id)
}

/** SolaX Cloud connection of a place: API credentials, discovered plant, cached token and sync state. */
object Solax : Table("solax") {
    val locationId = long("location_id").references(Locations.id, onDelete = ReferenceOption.CASCADE)
    val clientId = varchar("client_id", 100)

    // needed to call the API, so stored as given; protect the database file
    val clientSecret = varchar("client_secret", 200)

    // the selected plant: the first one by default, changeable in the settings
    val plantId = varchar("plant_id", 40).nullable()
    val plants = varchar("plants", 4000).nullable() // JSON list of the account's plants (id and name) for the dropdown
    val plantName = varchar("plant_name", 120).nullable()
    val businessType = integer("business_type").nullable() // SolaX: 1 residential, 4 commercial & industrial
    val pvKwp = double("pv_kwp").nullable() // installed PV power of the plant, from SolaX
    val batteryKwh = double("battery_kwh").nullable() // installed battery capacity of the plant, from SolaX

    // raw device-list answer, kept only when it gave no capacity (no credentials in it)
    val diagDevices = varchar("diag_devices", 1500).nullable()

    // raw battery-history answer, kept only when it gave no usable capacity
    val diagHistory = varchar("diag_history", 1500).nullable()

    // sum of the battery devices' own rated capacity (device list)
    val batteryDeviceKwh = double("battery_device_kwh").nullable()
    val plantReadAt = long("plant_read_at").nullable() // when the plant record (name, kWp, battery kWh) was last read
    val inverterSns = varchar("inverter_sns", 200).nullable() // comma-separated serials, cached to save API calls
    val soc = double("soc").nullable() // battery state of charge, %, at the last sync
    val batteryRemainingKwh = double("battery_remaining_kwh").nullable()
    val batteryPowerW = double("battery_power_w").nullable() // positive = charging, negative = discharging
    val batteryReadAt = long("battery_read_at").nullable() // epoch millis of that reading

    // last day whose battery history was fetched
    val batteryDaysThrough = varchar("battery_days_through", 10).nullable()
    val historyTryAt = long("history_try_at").nullable() // last failed history attempt, so it is retried at most hourly
    val batteryTryAt = long("battery_try_at").nullable() // last attempt, so failures are retried only hourly
    val batteryStatus = varchar("battery_status", 200).nullable() // user-visible outcome of the last battery read
    val token = varchar("token", 200).nullable()
    val tokenExpires = long("token_expires").default(0)
    val status = varchar("status", 400).default("")

    // last day whose production was fetched; days are fetched once
    val producedThrough = varchar("produced_through", 10).nullable()
    val dueAt = long("due_at").default(0) // retry gate: a failed production sync waits until then
    override val primaryKey = PrimaryKey(locationId)
}

/** Energy produced per day as reported by SolaX (kWh). */
object Produced : Table("produced") {
    val locationId = long("location_id").references(Locations.id, onDelete = ReferenceOption.CASCADE).index()
    val day = varchar("day", 10) // ISO date
    val kwh = double("kwh") // PV generation: what the panels produced

    // inverter AC output (the SolaX app's "Inverter Output"), kept to compare with kwh
    val acKwh = double("ac_kwh").nullable()

    // the home's total consumption that day (heat pump included), for the battery forecast
    val loadKwh = double("load_kwh").nullable()

    // SolaX returned an impossible kwh (more than the panels can make in a day): kept to mark it, never used
    val bad = bool("bad").default(false)
    override val primaryKey = PrimaryKey(locationId, day)
}

/** Place-search results (Open-Meteo geocoding) by normalised query, so a repeated search needs no new request. */
object GeocodeCache : Table("geocode_cache") {
    val query = varchar("query", 120)
    val json = text("json")
    val fetchedAt = long("fetched_at")
    override val primaryKey = PrimaryKey(query)
}

/** The highest battery charge (%) a past day reached, from SolaX's battery history; fetched once per day. */
object BatteryDays : Table("battery_days") {
    val locationId = long("location_id").references(Locations.id, onDelete = ReferenceOption.CASCADE).index()
    val day = varchar("day", 10) // ISO date
    val maxSoc = double("max_soc")

    // usable capacity that day: remaining energy / charge, from the history points
    val usableKwh = double("usable_kwh").nullable()
    override val primaryKey = PrimaryKey(locationId, day)
}

/**
 * Today's battery charge (%) and panel power (W) in 5-minute steps, the finest SolaX's history offers; replaced on
 * every hourly read.
 */
object SolarToday : Table("solar_today") {
    val locationId = long("location_id").references(Locations.id, onDelete = ReferenceOption.CASCADE).index()
    val day = varchar("day", 10) // ISO date, local to the place
    val t = varchar("t", 5) // HH:mm, local to the place
    val soc = double("soc").nullable()
    val pvW = double("pv_w").nullable()
    val batW = double("bat_w").nullable() // battery power: positive = charging, negative = discharging
    val gridW = double("grid_w").nullable() // power drawn from the grid, positive = import
    override val primaryKey = PrimaryKey(locationId, day, t)
}

/**
 * The battery's discharge power (W), the home's consumption (W) and the power drawn from the grid (W) in 5-minute
 * steps of the finished days of
 * last week and this week, from SolaX's battery and inverter history. The discharge is the base of the estimate for
 * the rest of today.
 */
object DischargeDays : Table("discharge_days") {
    val locationId = long("location_id").references(Locations.id, onDelete = ReferenceOption.CASCADE).index()
    val day = varchar("day", 10) // ISO date, local to the place
    val t = varchar("t", 5) // HH:mm, local to the place
    val dischargeW = double("discharge_w")
    val loadW = double("load_w").nullable() // the home's consumption; null when the inverter history has no such field
    val gridW = double("grid_w").nullable() // power drawn from the grid, positive = import; null when not reported
    val soc = double("soc").nullable() // battery charge (%), for measuring the charging efficiency
    val chargeW = double("charge_w").nullable() // battery charging power (W), 0 while discharging
    override val primaryKey = PrimaryKey(locationId, day, t)
}

/**
 * The compensation energy worked out for a coming day while the battery sat at its minimum at night (one row per
 * place and day, updated at each check).
 */
object NightPlans : Table("night_plans") {
    val locationId = long("location_id").references(Locations.id, onDelete = ReferenceOption.CASCADE).index()
    val day = varchar("day", 10) // ISO date of the day the plan is for, local to the place
    val foundAt = long("found_at") // when the battery was first found at its minimum
    val checkedAt = long("checked_at")
    val dayStartKwh = double("day_start_kwh")
    val solarKwh = double("solar_kwh")
    val totalKwh = double("total_kwh")
    val targetSoc = double("target_soc")
    val sunAt = varchar("sun_at", 5).nullable() // HH:mm from which the sun can power the whole house
    val stopBy = varchar("stop_by", 5) // HH:mm: charging must be over when the day zone starts
    val socNow = double("soc_now").nullable()
    val reached = bool("reached")

    // epoch ms until which the inverter was told to charge from the grid (SolaX remote mode with a timer); null when
    // no charge is running
    val chargeUntil = long("charge_until").nullable()
    val control = varchar("control", 200).nullable() // outcome of the last attempt to control the inverter

    // epoch ms of the latest moment the charge can start and still end in time; the battery is read often around it
    val chargeFrom = long("charge_from").nullable()
    override val primaryKey = PrimaryKey(locationId, day)
}

/** Stored 15-minute forecast steps (today and the next days); replaced on every refresh. */
object Intraday15 : Table("intraday") {
    val locationId = long("location_id").references(Locations.id, onDelete = ReferenceOption.CASCADE).index()
    val day = varchar("day", 10) // ISO date
    val t = varchar("t", 5) // HH:mm
    val temp = double("temp").nullable()
    val rainMm = double("rain_mm")
    val event = varchar("event", 10).nullable()
    val radiationWm2 = double("radiation_wm2").nullable() // shortwave radiation, W/m²
    override val primaryKey = PrimaryKey(locationId, day, t)
}

/** Stored daily Open-Meteo values, one row per location and day. */
object Daily : Table("daily") {
    val locationId = long("location_id").references(Locations.id, onDelete = ReferenceOption.CASCADE).index()
    val day = varchar("day", 10) // ISO date
    val tMax = double("t_max").nullable()
    val tMin = double("t_min").nullable()
    val rainMm = double("rain_mm").nullable()
    val radiationMj = double("radiation_mj").nullable() // shortwave radiation sum, MJ/m²
    val fetchedAt = long("fetched_at")
    override val primaryKey = PrimaryKey(locationId, day)
}

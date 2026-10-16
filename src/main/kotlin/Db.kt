package ilios

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.exists
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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
    val userId = long("user_id").references(Users.id, onDelete = ReferenceOption.CASCADE)
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
    val solar = bool("solar").default(false) // whether a solar system applies here
    val batteryKwh = double("battery_kwh").default(10.0)
    val solarKwp = double("solar_kwp").default(5.0)
    val pvRatio = double("pv_ratio").default(0.75) // system losses / performance ratio
    override val primaryKey = PrimaryKey(id)
}

/** Stored 15-minute forecast steps (today and the next days); replaced on every refresh. */
object Intraday15 : Table("intraday") {
    val locationId = long("location_id").references(Locations.id, onDelete = ReferenceOption.CASCADE)
    val day = varchar("day", 10) // ISO date
    val t = varchar("t", 5) // HH:mm
    val temp = double("temp").nullable()
    val rainMm = double("rain_mm")
    val event = varchar("event", 10).nullable()
    override val primaryKey = PrimaryKey(locationId, day, t)
}

/** Stored daily Open-Meteo values, one row per location and day. */
object Daily : Table("daily") {
    val locationId = long("location_id").references(Locations.id, onDelete = ReferenceOption.CASCADE)
    val day = varchar("day", 10) // ISO date
    val tMax = double("t_max").nullable()
    val tMin = double("t_min").nullable()
    val rainMm = double("rain_mm").nullable()
    val radiationMj = double("radiation_mj").nullable() // shortwave radiation sum, MJ/m²
    val fetchedAt = long("fetched_at")
    override val primaryKey = PrimaryKey(locationId, day)
}

fun initDb(path: String) {
    Database.connect("jdbc:h2:file:$path;DB_CLOSE_DELAY=-1", "org.h2.Driver")
    transaction {
        SchemaUtils.createMissingTablesAndColumns(Users, Tokens, Locations, Daily, Intraday15)
        migrateUserSettings()
    }
}

private object OldSettings : Table("settings") {
    val userId = long("user_id")
}

/** Energy settings used to be per user; copy them onto that user's places, then drop the old table. */
private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.migrateUserSettings() {
    if (!OldSettings.exists()) return
    exec(
        "UPDATE locations l SET battery_kwh = (SELECT s.battery_kwh FROM settings s WHERE s.user_id = l.user_id), " +
            "solar_kwp = (SELECT s.solar_kwp FROM settings s WHERE s.user_id = l.user_id), " +
            "pv_ratio = (SELECT s.pv_ratio FROM settings s WHERE s.user_id = l.user_id) " +
            "WHERE EXISTS (SELECT 1 FROM settings s WHERE s.user_id = l.user_id)",
    )
    exec("DROP TABLE settings")
}

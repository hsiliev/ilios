package ilios.db

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.io.File
import java.sql.DriverManager
import java.util.Locale

private val log = KotlinLogging.logger {}

/**
 * H2 never gives back the space of deleted rows (the file reached 18 MB for 0.3 MB of data), so an existing database is
 * compacted before anything else opens it; a failure only costs the space, not the start.
 */
private fun compact(path: String) {
    val file = File("$path.mv.db")
    if (!file.exists()) return
    val before = file.length()
    runCatching {
        DriverManager.getConnection("jdbc:h2:file:$path").use { it.createStatement().execute("SHUTDOWN COMPACT") }
    }
        .onSuccess { log.info { "Database compacted: ${megabytes(before)} -> ${megabytes(file.length())}" } }
        .onFailure { log.warn { "Compacting the database failed: ${it::class.simpleName}" } }
}

private const val BYTES_PER_MB = 1_048_576.0

private fun megabytes(bytes: Long) = "%.1f MB".format(Locale.ROOT, bytes / BYTES_PER_MB)

/** Compacts, then opens (and creates or upgrades) the embedded H2 database at [path]. */
fun initDb(path: String) {
    compact(path)
    Database.connect("jdbc:h2:file:$path;DB_CLOSE_DELAY=-1", "org.h2.Driver")
    log.info { "Database opened: $path" }
    transaction {
        // days stored before the AC column existed are fetched again once
        val hadAc = runCatching { exec("SELECT ac_kwh FROM produced WHERE 1 = 0") }.isSuccess
        val hadLoad = runCatching { exec("SELECT load_kwh FROM produced WHERE 1 = 0") }.isSuccess
        // connected places read the battery devices' capacity together with the plant record: do that once after the
        // upgrade
        val hadDeviceKwh = runCatching { exec("SELECT battery_device_kwh FROM solax WHERE 1 = 0") }.isSuccess
        // days stored before the consumption and grid columns existed are fetched again once
        val hadLoadW = runCatching { exec("SELECT load_w FROM discharge_days WHERE 1 = 0") }.isSuccess
        // the charging efficiency became measured: days stored without the charge are fetched again once
        val hadSoc = runCatching { exec("SELECT soc FROM discharge_days WHERE 1 = 0") }.isSuccess
        val tables =
            arrayOf(
                Users, Tokens, Locations, Daily, Intraday15, Solax, Produced, BatteryDays,
                SolarToday, DischargeDays, NightPlans, GeocodeCache,
            )
        // only additions: H2 refuses to drop the indexes behind foreign keys, and nothing is meant to be dropped here
        MigrationUtils.statementsRequiredForDatabaseMigration(*tables).filterNot {
            it.startsWith("DROP ")
        }.forEach { exec(it) }
        if (!hadLoadW || !hadSoc) exec("DELETE FROM discharge_days")
        if (!hadAc || !hadLoad) exec("UPDATE solax SET produced_through = NULL")
        if (!hadDeviceKwh) exec("UPDATE solax SET plant_read_at = NULL")
        // a day above kWp x 24 is a bad cloud value, also in rows stored before the check existed
        exec(
            "UPDATE produced SET bad = TRUE WHERE kwh > " +
                "(SELECT pv_kwp * 24 FROM solax WHERE solax.location_id = produced.location_id)",
        )
        // obsolete: the old per-user settings table and the typed-in performance ratio (now calibrated from SolaX data)
        exec("DROP TABLE IF EXISTS settings")
        exec("DROP TABLE IF EXISTS tuya") // the abandoned Tuya Cloud connection
        exec("ALTER TABLE locations DROP COLUMN IF EXISTS pv_ratio")
        // battery capacity and PV power now come from SolaX instead of being typed in
        exec("ALTER TABLE locations DROP COLUMN IF EXISTS battery_kwh")
        exec("ALTER TABLE locations DROP COLUMN IF EXISTS solar_kwp")
        // the charging efficiency is no longer typed in (measured, else a default)
        exec("ALTER TABLE locations DROP COLUMN IF EXISTS charge_eff_pct")
    }
}

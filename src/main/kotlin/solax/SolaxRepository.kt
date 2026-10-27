package ilios.solax

import ilios.db.Solax
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private const val MAX_STATUS = 200

/** The connection row of a place (`solax`): what the sync loops and the settings read and write. No network calls. */
object SolaxRepository {
    /** Applies [change] to the place's `solax` row. */
    fun update(locationId: Long, change: (UpdateStatement) -> Unit) {
        transaction { Solax.update({ Solax.locationId eq locationId }) { change(it) } }
    }

    /** The place's `solax` row, or null when it has no SolaX connection. */
    fun row(locationId: Long): ResultRow? =
        transaction { Solax.selectAll().where { Solax.locationId eq locationId }.singleOrNull() }

    // ---- database-only reads and configuration ----

    fun plant(locationId: Long): SolaxPlant? = transaction {
        Solax.selectAll().where { Solax.locationId eq locationId }.singleOrNull()
            ?.let { r ->
                val device = r[Solax.batteryDeviceKwh]?.takeIf { it > 0 }
                SolaxPlant(
                    r[Solax.pvKwp]?.takeIf { it > 0 },
                    device,
                    r[Solax.soc],
                    r[Solax.batteryRemainingKwh],
                    r[Solax.batteryPowerW],
                    r[Solax.batteryReadAt],
                    deviceKwh = device,
                )
            }
    }

    fun infos(locationIds: List<Long>): Map<Long, SolaxInfo> = transaction {
        Solax.selectAll().where { Solax.locationId inList locationIds }
            .associate {
                val id = it[Solax.locationId]
                id to SolaxInfo(
                    it[Solax.clientId],
                    it[Solax.plantId],
                    it[Solax.status],
                    it[Solax.batteryStatus].orEmpty(),
                    decodePlants(it[Solax.plants]),
                    capacityText(plant(id)),
                )
            }
    }

    /**
     * No battery (or no data): forget any old reading. A non-null [readAt] marks "asked then", so the next attempt
     * waits for the next slot.
     */
    internal fun clearBattery(locationId: Long, readAt: Long?) = transaction {
        Solax.update({ Solax.locationId eq locationId }) {
            it[Solax.soc] = null
            it[Solax.batteryRemainingKwh] = null
            it[Solax.batteryPowerW] = null
            it[Solax.batteryReadAt] = readAt
        }
        Unit
    }

    internal fun setBatteryStatus(locationId: Long, text: String?) = transaction {
        Solax.update({ Solax.locationId eq locationId }) { it[Solax.batteryStatus] = text?.take(MAX_STATUS) }
        Unit
    }

    internal fun hasBattery(row: ResultRow) = row[Solax.batteryDeviceKwh] ?: 0.0 > 0
}

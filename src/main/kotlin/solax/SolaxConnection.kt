package ilios.solax

import ilios.badRequest
import ilios.db.BatteryDays
import ilios.db.DischargeDays
import ilios.db.Produced
import ilios.db.SolarToday
import ilios.db.Solax
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Saving and changing a place's SolaX credentials and plant, and dropping what belongs to a plant that is no longer
 * connected.
 */
object SolaxConnection {
    private const val MAX_CLIENT_ID = 100
    private const val MAX_SECRET = 200
    private const val MAX_PLANT_ID = 40

    /** Forgets everything derived from the selected plant (not its status or retry gate). */
    private fun UpdateBuilder<*>.clearPlantData() {
        this[Solax.plantName] = null
        this[Solax.pvKwp] = null
        this[Solax.batteryKwh] = null
        this[Solax.batteryDeviceKwh] = null
        this[Solax.diagDevices] = null
        this[Solax.diagHistory] = null
        this[Solax.producedThrough] = null
        this[Solax.batteryDaysThrough] = null
        this[Solax.historyTryAt] = null
        this[Solax.plantReadAt] = null
        this[Solax.inverterSns] = null
        this[Solax.soc] = null
        this[Solax.batteryRemainingKwh] = null
        this[Solax.batteryPowerW] = null
        this[Solax.batteryReadAt] = null
        this[Solax.batteryTryAt] = null
        this[Solax.batteryStatus] = null
    }

    /** Like [clearPlantData], and makes the next sync run at once. */
    private fun UpdateBuilder<*>.resetPlantData() {
        clearPlantData()
        this[Solax.dueAt] = 0
        this[Solax.status] = "Изчаква синхронизация…"
    }

    /** The data stored for a plant belongs to that plant only: when the place switches plants it must go. */
    internal fun wipePlantHistory(locationId: Long) = transaction {
        Produced.deleteWhere { Produced.locationId eq locationId }
        BatteryDays.deleteWhere { BatteryDays.locationId eq locationId }
        SolarToday.deleteWhere { SolarToday.locationId eq locationId }
        DischargeDays.deleteWhere { DischargeDays.locationId eq locationId }
        Solax.update({ Solax.locationId eq locationId }) { it.clearPlantData() }
        Unit
    }

    /**
     * Saves a place's SolaX settings. A blank client ID disconnects; a blank secret keeps the stored one. [plantId]
     * is the
     * dropdown's selection and only matters while the credentials stay the same (new credentials start again with
     * the first
     * plant). Returns true when the connection changed and should be synced now.
     */
    fun configure(locationId: Long, clientId: String, secret: String, plantId: String): Boolean {
        val id = clientId.trim()
        val sec = secret.trim()
        val plant = plantId.trim().ifEmpty { null }
        if (id.length > MAX_CLIENT_ID ||
            sec.length > MAX_SECRET ||
            plant?.length ?: 0 > MAX_PLANT_ID
        ) {
            badRequest("Стойността е твърде дълга")
        }
        return transaction {
            val row = Solax.selectAll().where { Solax.locationId eq locationId }.singleOrNull()
            when {
                id.isEmpty() -> disconnect(locationId, row)
                row == null -> connect(locationId, id, sec)
                id != row[Solax.clientId] || sec.isNotEmpty() -> changeCredentials(locationId, id, sec)
                plant != null && plant != row[Solax.plantId] -> choosePlant(locationId, row, plant)
                else -> false
            }
        }
    }

    private fun disconnect(locationId: Long, row: ResultRow?): Boolean {
        if (row != null) Solax.deleteWhere { Solax.locationId eq locationId }
        return false
    }

    private fun connect(locationId: Long, id: String, secret: String): Boolean {
        if (secret.isEmpty()) badRequest("Въведете SolaX Client Secret")
        Solax.insert {
            it[Solax.locationId] = locationId
            it[Solax.clientId] = id
            it[Solax.clientSecret] = secret
            it[Solax.status] = "Изчаква първа синхронизация…"
        }
        return true
    }

    /**
     * New credentials: the account's plant list must be read again, but what is stored for the selected plant is
     * kept, because most often it is
     * the same plant (a rotated secret) and re-fetching it all would cost many calls. If the new account turns out
     * to have a different plant,
     * the next sync wipes the old plant's data.
     */
    private fun changeCredentials(locationId: Long, id: String, secret: String): Boolean {
        Solax.update({ Solax.locationId eq locationId }) {
            it[Solax.clientId] = id
            if (secret.isNotEmpty()) it[Solax.clientSecret] = secret
            it[Solax.plants] = null
            it[Solax.businessType] = null
            it[Solax.token] = null
            it[Solax.tokenExpires] = 0
            it[Solax.plantReadAt] = null
            it[Solax.dueAt] = 0
            it[Solax.batteryTryAt] = null
            it[Solax.historyTryAt] = null
            it[Solax.status] = "Изчаква синхронизация…"
        }
        return true
    }

    /** Another plant chosen in the dropdown: what was stored for the old one goes. */
    private fun choosePlant(locationId: Long, row: ResultRow, plant: String): Boolean {
        val offered = decodePlants(row[Solax.plants])
        if (offered.isNotEmpty() && offered.none { it.id == plant }) badRequest("Невалидна централа")
        Produced.deleteWhere { Produced.locationId eq locationId }
        BatteryDays.deleteWhere { BatteryDays.locationId eq locationId }
        Solax.update({ Solax.locationId eq locationId }) {
            it[Solax.plantId] = plant
            it.resetPlantData()
        }
        return true
    }
}

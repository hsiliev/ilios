package ilios.solax

import ilios.solax.api.plantsJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A plant of the SolaX account, as offered in the settings dropdown. */
@Serializable
class PlantOption(val id: String, val name: String)

internal fun decodePlants(s: String?): List<PlantOption> = if (s.isNullOrBlank()) {
    emptyList()
} else {
    runCatching {
        plantsJson.decodeFromString(ListSerializer(PlantOption.serializer()), s)
    }.getOrDefault(emptyList())
}

/** Installed PV power (kWp) and battery capacity (kWh) of the connected plant, as reported by SolaX. */
class SolaxPlant(
    val kwp: Double?,
    /** The battery capacity the app works with: the devices' hardware rated capacity. */
    val batteryKwh: Double?,
    val soc: Double? = null,
    val remainingKwh: Double? = null,
    val powerW: Double? = null,
    val readAt: Long? = null,
    val deviceKwh: Double? = null,
)

/** What the settings page may see about a place's connection. The secret itself is never exposed. */
class SolaxInfo(
    val clientId: String,
    val plantId: String?,
    val status: String,
    val batteryStatus: String,
    val plants: List<PlantOption>,
)

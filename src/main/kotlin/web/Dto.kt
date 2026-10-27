package ilios.web

import ilios.db.DEFAULT_MAX_CHARGE_W
import ilios.db.DEFAULT_RESERVE
import ilios.db.DEFAULT_SUPPLIER
import ilios.solax.PlantOption
import kotlinx.serialization.Serializable

// Request and response bodies of the /api routes.

@Serializable class Credentials(val email: String = "", val password: String = "")

@Serializable class EmailReq(val email: String = "")

@Serializable class ResetReq(val token: String = "", val password: String = "")

@Serializable class Me(val email: String)

@Serializable class NewLocation(
    val name: String = "",
    val country: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val tz: String? = null,
)

@Serializable class PlaceSettings(
    val solar: Boolean,
    val supplier: String? = null,
    val reservedPct: Int? = null,
    val allowControl: Boolean? = null,
    val maxChargeW: Int? = null,
    val solaxClientId: String = "",
    val solaxClientSecret: String = "",
    val solaxPlantId: String = "",
)

@Serializable class LocationOut(
    val id: Long,
    val name: String,
    val country: String?,
    val solar: Boolean,
    val supplier: String = DEFAULT_SUPPLIER,
    val reservedPct: Int = DEFAULT_RESERVE,
    val allowControl: Boolean = false,
    val maxChargeW: Int = DEFAULT_MAX_CHARGE_W,
    val solaxClientId: String = "",
    val solaxPlantId: String = "",
    val solaxHasSecret: Boolean = false,
    val solaxStatus: String = "",
    val solaxBatteryStatus: String = "",
    val solaxBatteryInfo: String = "",
    val solaxPlants: List<PlantOption> = emptyList(), // the secret is never sent back
)

@Serializable class PlaceOrder(val ids: List<Long> = emptyList())

@Serializable class Msg(val message: String)

package ilios.db

import ilios.PERCENT
import org.jetbrains.exposed.v1.core.ResultRow

const val DEFAULT_SUPPLIER = "electrohold" // key of the regulated tariffs in TARIFFS

/**
 * The battery charge (%) reserved by default, and the range a place may set: below it the house is served from the
 * grid.
 */
const val DEFAULT_RESERVE = 0
const val MAX_RESERVE = 50
const val DEFAULT_MAX_CHARGE_W = 2000
const val MIN_MAX_CHARGE_W = 500
const val MAX_MAX_CHARGE_W = 6000

/** The share (%) of the charging power that ends up stored in the battery, and the range a place may set. */
const val DEFAULT_CHARGE_EFF_PCT = 70
const val MIN_CHARGE_EFF_PCT = 30
const val MAX_CHARGE_EFF_PCT = 100

/** A place as the services work with it: the stored settings of one `locations` row. */
class Loc(
    val id: Long,
    val name: String,
    val lat: Double,
    val lon: Double,
    val tz: String,
    val solar: Boolean,
    val country: String? = null,
    val supplier: String = DEFAULT_SUPPLIER,
    val reservedPct: Int = DEFAULT_RESERVE,
    val allowControl: Boolean = false,
    val maxChargeW: Int = DEFAULT_MAX_CHARGE_W,
    val chargeEffPct: Int = DEFAULT_CHARGE_EFF_PCT,
) {
    /** [chargeEffPct] as a factor. */
    val chargeEfficiency get() = chargeEffPct / PERCENT.toDouble()
}

fun locOf(r: ResultRow) = Loc(
    r[Locations.id], r[Locations.name], r[Locations.lat], r[Locations.lon], r[Locations.tz], r[Locations.solar],
    r[Locations.country], r[Locations.supplier], r[Locations.reservedPct],
    r[Locations.allowControl], r[Locations.maxChargeW], r[Locations.chargeEffPct],
)

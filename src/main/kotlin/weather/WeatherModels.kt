package ilios.weather

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
class GeoHit(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    val admin1: String? = null,
    val timezone: String? = null,
)

class Day(val date: LocalDate, val tMax: Double?, val tMin: Double?, val rainMm: Double, val radiationMj: Double)

/**
 * One step of today's forecast at the finest resolution Open-Meteo offers. event: thunder | snow | rain | wind |
 * cloud
 */
@Serializable
class IntraPoint(val t: String, val temp: Double?, val rainMm: Double, val event: String?, val radWm2: Double? = null)

@Serializable
class Intraday(
    val now: String,
    val stepMin: Int,
    val points: List<IntraPoint>,
    val prev: List<IntraPoint> = emptyList(),
    val next: List<IntraPoint> = emptyList(),
)

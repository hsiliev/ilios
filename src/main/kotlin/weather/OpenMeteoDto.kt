package ilios.weather

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Response shapes of the Open-Meteo APIs (only the fields the app reads).
@Serializable
internal class GeoResp(val results: List<GeoHit> = emptyList())

@Serializable
internal class ForecastResp(val timezone: String = "UTC", val daily: DailyResp)

@Serializable
internal class DailyResp(
    val time: List<String>,
    @SerialName("temperature_2m_max") val tMax: List<Double?>,
    @SerialName("temperature_2m_min") val tMin: List<Double?>,
    @SerialName("precipitation_sum") val rain: List<Double?>,
    @SerialName("shortwave_radiation_sum") val radiation: List<Double?>,
)

@Serializable
internal class IntraResp(val timezone: String = "UTC", @SerialName("minutely_15") val m: IntraArrays)

@Serializable
internal class IntraArrays(
    val time: List<String>,
    @SerialName("temperature_2m") val temp: List<Double?>,
    val precipitation: List<Double?>,
    @SerialName("weather_code") val code: List<Int?>,
    @SerialName("cloud_cover") val cloud: List<Double?>,
    @SerialName("wind_speed_10m") val wind: List<Double?>,
    @SerialName("wind_gusts_10m") val gust: List<Double?>,
    @SerialName("shortwave_radiation") val rad: List<Double?> = emptyList(),
)

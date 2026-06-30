package com.example.voicegmail.voice

import com.example.voicegmail.debug.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Data returned by the time API. */
data class TimeInfo(val hour: Int, val minute: Int, val isAm: Boolean)

/** Data returned by the weather API. */
data class WeatherInfo(
    val currentTemp: String,
    val condition: String,
    val highToday: String,
    val lowToday: String,
    val highTomorrow: String,
    val lowTomorrow: String,
    val conditionTomorrow: String
)

/** Caches geocoding result for a zip code. */
private data class GeoResult(val lat: Double, val lon: Double, val timezone: String)

@Singleton
class TimeWeatherRepository @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val tag = "TimeWeatherRepository"

    /** In-memory cache for geocoding lookups. */
    private val geoCache = mutableMapOf<String, GeoResult>()

    // ── Public API ───────────────────────────────────────────────────────

    /** Get current time for the given zip code (independent of device clock). */
    suspend fun getTimeInfo(zipCode: String): Result<TimeInfo> = runCatching {
        val geo = geocode(zipCode)
        val body = httpGet(
            "https://timeapi.io/api/Time/current/zone?timeZone=${geo.timezone}"
        )
        val json = JSONObject(body)
        val hour = json.getInt("hour")
        val minute = json.getInt("minute")
        val isAm = hour < 12
        val hour12 = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        DebugLogger.verbose(tag, "Time for $zipCode (${geo.timezone}): $hour12:$minute ${if (isAm) "AM" else "PM"}")
        TimeInfo(hour12, minute, isAm)
    }

    /** Get current weather + today/tomorrow forecast for the given zip code. */
    suspend fun getWeather(zipCode: String): Result<WeatherInfo> = runCatching {
        val geo = geocode(zipCode)
        val body = httpGet(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=${geo.lat}&longitude=${geo.lon}" +
                "&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code" +
                "&timezone=${java.net.URLEncoder.encode(geo.timezone, "UTF-8")}" +
                "&forecast_days=3&temperature_unit=fahrenheit"
        )
        val json = JSONObject(body)

        // Current
        val currentJson = json.getJSONObject("current")
        val currentTemp = currentJson.getDouble("temperature_2m").toInt()
        val currentCode = currentJson.getInt("weather_code")

        // Daily (today + tomorrow)
        val daily = json.getJSONObject("daily")
        val highToday = daily.getJSONArray("temperature_2m_max").getInt(0)
        val lowToday = daily.getJSONArray("temperature_2m_min").getInt(0)
        val codeToday = daily.getJSONArray("weather_code").getInt(0)
        val highTomorrow = daily.getJSONArray("temperature_2m_max").getInt(1)
        val lowTomorrow = daily.getJSONArray("temperature_2m_min").getInt(1)
        val codeTomorrow = daily.getJSONArray("weather_code").getInt(1)

        WeatherInfo(
            currentTemp = currentTemp.toString(),
            condition = weatherCodeToText(currentCode),
            highToday = highToday.toString(),
            lowToday = lowToday.toString(),
            highTomorrow = highTomorrow.toString(),
            lowTomorrow = lowTomorrow.toString(),
            conditionTomorrow = weatherCodeToText(codeTomorrow)
        )
    }

    // ── Geocoding ────────────────────────────────────────────────────────

    private suspend fun geocode(zipCode: String): GeoResult {
        geoCache[zipCode]?.let { return it }
        val body = httpGet(
            "https://geocoding-api.open-meteo.com/v1/search" +
                "?name=$zipCode&count=1&language=en&format=json"
        )
        val json = JSONObject(body)
        val results = json.getJSONArray("results")
        if (results.length() == 0) throw IllegalArgumentException("Zip code not found")
        val r = results.getJSONObject(0)
        val geo = GeoResult(
            lat = r.getDouble("latitude"),
            lon = r.getDouble("longitude"),
            timezone = r.getString("timezone")
        )
        geoCache[zipCode] = geo
        return geo
    }

    // ── HTTP helper ──────────────────────────────────────────────────────

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        DebugLogger.verbose(tag, "GET $url")
        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code}")
        response.body?.string() ?: throw RuntimeException("Empty response")
    }

    // ── WMO weather code → text ──────────────────────────────────────────

    private fun weatherCodeToText(code: Int): String = when (code) {
        0 -> "Clear sky"
        1 -> "Mainly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Foggy"
        51, 53, 55 -> "Drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Slight rain"
        63 -> "Moderate rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71, 73, 75 -> "Snow"
        77 -> "Snow grains"
        80, 81, 82 -> "Rain showers"
        85, 86 -> "Snow showers"
        95 -> "Thunderstorm"
        96, 99 -> "Thunderstorm with hail"
        else -> "Unknown"
    }
}

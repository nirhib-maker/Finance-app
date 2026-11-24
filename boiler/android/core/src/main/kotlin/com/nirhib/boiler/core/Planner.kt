package com.nirhib.boiler.core

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Kotlin port of boiler/js/model.js (planning part). model.js is the reference: the golden test
// (PlannerGoldenTest) checks this file produces exactly the same plans. Change both together.

const val KWH_PER_L_DEG = 4.186 / 3600
const val SHOWER_TEMP = 40.0
const val MIN_USABLE_TEMP = 45.0

@JsonIgnoreProperties(ignoreUnknown = true)
data class Location(val name: String = "תל אביב", val lat: Double = 32.08, val lon: Double = 34.78)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Tank(val liters: Double = 150.0, val heaterKw: Double = 2.5, val maxTemp: Double = 65.0, val lossWPerDeg: Double = 2.0)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Solar(val enabled: Boolean = true, val areaM2: Double = 4.0, val efficiency: Double = 0.35)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Sessions(val morning: String = "06:30", val evening: String = "20:00") {
    fun of(session: String) = if (session == "morning") morning else evening
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Peak(val avoid: Boolean = false, val start: String = "17:00", val end: String = "22:00")

@JsonIgnoreProperties(ignoreUnknown = true)
data class Member(
    val id: String = "", val name: String = "", val kind: String = "shower",
    val minutes: Double = 0.0, val session: String = "evening",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SwitcherConfig(val type: String = "v4", val ip: String = "", val id: String = "", val key: String = "") {
    val configured get() = ip.isNotBlank() && id.isNotBlank() && key.isNotBlank()
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Settings(
    val location: Location = Location(),
    val tank: Tank = Tank(),
    val solar: Solar = Solar(),
    val showerFlowLpm: Double = 8.0,
    val bathLiters: Double = 80.0,
    val sessions: Sessions = Sessions(),
    val readyBufferMin: Double = 10.0,
    val pricePerKwh: Double = 0.64,
    val peak: Peak = Peak(),
    val calibration: Double = 1.0,
    val members: List<Member> = listOf(
        Member("m1", "אבא", "shower", 8.0, "evening"),
        Member("m2", "אמא", "shower", 10.0, "evening"),
        Member("m3", "ילד/ה", "bath", 0.0, "evening"),
    ),
    val switcher: SwitcherConfig = SwitcherConfig(),
) {
    companion object {
        val mapper: ObjectMapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        fun fromJson(json: String): Settings = mapper.readValue(json, Settings::class.java)
    }
}

data class Hour(val time: String, val temp: Double, val radiation: Double, val cloud: Double?)
data class Day(
    val date: String, val meanTemp: Double, val maxTemp: Double, val minTemp: Double,
    val radiationKwhM2: Double, val cloud: Long?,
)
data class Weather(val hourly: List<Hour>, val days: List<Day>)

data class SessionPlan(
    val date: String, val session: String, val sessionTime: String, val users: List<String>,
    val minutes: Int, val start: String? = null, val end: String? = null, val startMin: Int? = null,
    val targetTemp: Long? = null, val sunTemp: Long? = null, val sunKwh: Double = 0.0,
    val kwh: Double = 0.0, val cost: Double = 0.0, val warnings: List<String> = emptyList(),
)

data class DayPlan(val date: String, val coldTemp: Double, val sessions: List<SessionPlan>, val kwh: Double, val cost: Double)

// JS Math.round semantics (half up), and round(x, d) from model.js
private fun jsRound(x: Double): Long = floor(x + 0.5).toLong()
fun round(x: Double, d: Int = 1): Double {
    var f = 1.0; repeat(d) { f *= 10 }
    return jsRound(x * f) / f
}
private fun clamp(x: Double, lo: Double, hi: Double) = min(hi, max(lo, x))

fun toMin(hhmm: String): Int { val (h, m) = hhmm.split(":").map { it.trim().toInt() }; return h * 60 + m }
fun fmtTime(minutes: Double): String {
    val m = ((jsRound(minutes) % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

fun memberLiters(m: Member, s: Settings) = if (m.kind == "bath") s.bathLiters else s.showerFlowLpm * m.minutes

fun coldWaterTemp(meanAirTemps: List<Double>): Double {
    val vals = meanAirTemps.filter { it.isFinite() }
    if (vals.isEmpty()) return 16.0
    val avg = vals.reduce { a, b -> a + b } / vals.size
    return round(clamp(avg + 2, 12.0, 26.0))
}

fun solarGain(hourly: List<Hour>, date: String, untilMin: Int, s: Settings): Double {
    if (!s.solar.enabled) return 0.0
    var wh = 0.0
    for (h in hourly) {
        if (!h.time.startsWith(date)) continue
        val end = toMin(h.time.substring(11, 16))
        val covered = clamp((untilMin - (end - 60)).toDouble(), 0.0, 60.0) / 60
        wh += h.radiation * covered
    }
    return round((wh / 1000) * s.solar.areaM2 * s.solar.efficiency, 2)
}

fun planSession(date: String, session: String, weather: Weather, s: Settings, coldTemp: Double): SessionPlan {
    val users = s.members.filter { it.session == session }
    val sessionMin = toMin(s.sessions.of(session))
    val base = SessionPlan(date, session, s.sessions.of(session), users.map { it.name }, 0)
    if (users.isEmpty()) return base

    val v = s.tank.liters
    val usable = 0.85
    val liters = users.fold(0.0) { a, m -> a + memberLiters(m, s) }
    val demandKwh = liters * (SHOWER_TEMP - coldTemp) * KWH_PER_L_DEG

    var targetTemp = coldTemp + demandKwh / (v * KWH_PER_L_DEG * usable)
    targetTemp = max(targetTemp, MIN_USABLE_TEMP)
    val warnings = mutableListOf<String>()
    if (targetTemp > s.tank.maxTemp) {
        warnings += "הדוד (${fmtNum(v)} ל׳) קטן מדי לכל המקלחות ברצף – כדאי לפצל או לחמם שוב באמצע"
        targetTemp = s.tank.maxTemp
    }

    val sunKwh = if (session == "evening") solarGain(weather.hourly, date, sessionMin, s) else 0.0
    val sunTemp = min(coldTemp + sunKwh / (v * KWH_PER_L_DEG), s.tank.maxTemp + 10)

    var endMin = sessionMin - s.readyBufferMin
    val needKwh = max(0.0, v * KWH_PER_L_DEG * (targetTemp - sunTemp))
    var minutes = if (needKwh > 0) (needKwh / (s.tank.heaterKw * 0.98)) * 60 else 0.0
    val peakS = toMin(s.peak.start); val peakE = toMin(s.peak.end)
    if (s.peak.avoid && minutes > 0 && endMin > peakS && endMin - minutes < peakE && sessionMin > peakS) {
        endMin = peakS.toDouble()
    }
    val idleHours = max(0.0, (sessionMin - endMin) / 60)
    val lossKwh = if (needKwh > 0) (s.tank.lossWPerDeg / 1000) * (targetTemp - 20) * idleHours else 0.0

    var kwh = (needKwh + lossKwh) * (if (s.calibration == 0.0) 1.0 else s.calibration)
    minutes = if (kwh > 0) ceil(((kwh / (s.tank.heaterKw * 0.98)) * 60) / 5) * 5 else 0.0
    if (minutes > 0 && minutes < 10) minutes = 10.0
    kwh = round((minutes / 60) * s.tank.heaterKw, 2)
    val mins = minutes.toInt()

    return base.copy(
        minutes = mins,
        start = if (mins > 0) fmtTime(endMin - minutes) else null,
        end = if (mins > 0) fmtTime(endMin) else null,
        startMin = if (mins > 0) (endMin - minutes).toInt() else null,
        targetTemp = jsRound(targetTemp), sunTemp = jsRound(sunTemp), sunKwh = sunKwh,
        kwh = kwh, cost = round(kwh * s.pricePerKwh, 2), warnings = warnings,
    )
}

private fun fmtNum(x: Double) = if (x == floor(x)) x.toLong().toString() else x.toString()

/** Normalize an Open-Meteo response (see weatherUrl) into hourly values and daily summaries. */
fun parseOpenMeteo(json: JsonNode): Weather {
    val h = json["hourly"]
    val times = h["time"]
    val hourly = (0 until times.size()).map { i ->
        Hour(
            times[i].asText(), h["temperature_2m"][i].asDouble(),
            h["shortwave_radiation"][i]?.takeUnless { it.isNull }?.asDouble() ?: 0.0,
            h["cloud_cover"]?.get(i)?.takeUnless { it.isNull }?.asDouble(),
        )
    }
    val days = hourly.groupBy { it.time.substring(0, 10) }.map { (date, xs) ->
        Day(
            date,
            round(xs.fold(0.0) { a, x -> a + x.temp } / xs.size),
            xs.maxOf { it.temp }, xs.minOf { it.temp },
            round(xs.fold(0.0) { a, x -> a + x.radiation } / 1000, 2),
            if (xs[0].cloud == null) null else jsRound(xs.fold(0.0) { a, x -> a + (x.cloud ?: 0.0) } / xs.size),
        )
    }
    return Weather(hourly, days)
}

fun weatherUrl(loc: Location) =
    "https://api.open-meteo.com/v1/forecast?latitude=${loc.lat}&longitude=${loc.lon}" +
        "&hourly=temperature_2m,shortwave_radiation,cloud_cover&timezone=Asia%2FJerusalem&past_days=7&forecast_days=3"

fun planDays(weather: Weather, s: Settings, today: String): List<DayPlan> {
    val past = weather.days.filter { it.date < today }.takeLast(7).map { it.meanTemp }
    val coldTemp = coldWaterTemp(past.ifEmpty { weather.days.map { it.meanTemp } })
    return weather.days.filter { it.date >= today }.take(3).map { day ->
        val sessions = listOf("morning", "evening").map { planSession(day.date, it, weather, s, coldTemp) }
        val kwh = round(sessions.fold(0.0) { a, x -> a + x.kwh }, 2)
        DayPlan(day.date, coldTemp, sessions, kwh, round(kwh * s.pricePerKwh, 2))
    }
}

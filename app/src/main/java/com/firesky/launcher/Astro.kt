package com.firesky.launcher

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the sun, moon and planets are, from the standard published formulas: Meeus for the sun and
 * moon, JPL's approximate orbital elements for the planets. Good to a small fraction of a degree,
 * which is a pixel or two on the TV. Angles in degrees unless named otherwise.
 */
object Astro {
    const val D = PI / 180

    /** A direction in the sky as seen from the TV: altitude above the horizon, azimuth from north through east. */
    class Hz(val alt: Double, val az: Double)

    class Eq(val ra: Double, val dec: Double) // radians

    fun julian(ms: Long) = ms / 86_400_000.0 + 2440587.5
    private fun centuries(jd: Double) = (jd - 2451545.0) / 36525.0
    private fun norm(a: Double) = ((a % 360) + 360) % 360

    /** Local sidereal time in radians: which way the stars face right now. */
    fun lst(ms: Long, lon: Double): Double {
        val jd = julian(ms); val t = centuries(jd)
        val gmst = 280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - t * t * t / 38710000
        return norm(gmst + lon) * D
    }

    /** Equatorial to the local sky. [lstRad] from [lst]. */
    fun horizon(e: Eq, lstRad: Double, lat: Double): Hz {
        val h = lstRad - e.ra; val phi = lat * D
        val sa = sin(phi) * sin(e.dec) + cos(phi) * cos(e.dec) * cos(h)
        val az = atan2(-cos(e.dec) * sin(h), sin(e.dec) * cos(phi) - cos(e.dec) * sin(phi) * cos(h))
        return Hz(asin(sa.coerceIn(-1.0, 1.0)) / D, norm(az / D))
    }

    private fun obliquity(t: Double) = 23.439291 - 0.0130042 * t

    private fun eclToEq(lon: Double, lat: Double, eps: Double): Eq {
        val l = lon * D; val b = lat * D; val e = eps * D
        val ra = atan2(sin(l) * cos(e) - Math.tan(b) * sin(e), cos(l))
        val dec = asin(sin(b) * cos(e) + cos(b) * sin(e) * sin(l))
        return Eq(if (ra < 0) ra + 2 * PI else ra, dec)
    }

    /** The sun's apparent ecliptic longitude, and its distance in AU. */
    private fun sunLon(t: Double): Pair<Double, Double> {
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = (357.52911 + 35999.05029 * t - 0.0001537 * t * t) * D
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) + (0.019993 - 0.000101 * t) * sin(2 * m) + 0.000289 * sin(3 * m)
        val e = 0.016708634 - 0.000042037 * t
        val v = m + c * D
        val r = 1.000001018 * (1 - e * e) / (1 + e * cos(v))
        val om = (125.04 - 1934.136 * t) * D
        return norm(l0 + c - 0.00569 - 0.00478 * sin(om)) to r
    }

    fun sun(ms: Long): Eq {
        val t = centuries(julian(ms)); val om = (125.04 - 1934.136 * t) * D
        return eclToEq(sunLon(t).first, 0.0, obliquity(t) + 0.00256 * cos(om))
    }

    /** The sun's direction as a unit vector in the equatorial frame: which side of the Earth is lit. */
    fun sunVector(ms: Long): DoubleArray { val e = sun(ms); return doubleArrayOf(cos(e.dec) * cos(e.ra), cos(e.dec) * sin(e.ra), sin(e.dec)) }

    class Moon(val eq: Eq, val lon: Double, val lat: Double)

    fun moon(ms: Long): Moon {
        val t = centuries(julian(ms))
        val lp = 218.3164477 + 481267.88123421 * t
        val d = (297.8501921 + 445267.1114034 * t) * D
        val m = (357.5291092 + 35999.0502909 * t) * D
        val mp = (134.9633964 + 477198.8675055 * t) * D
        val f = (93.2720950 + 483202.0175233 * t) * D
        val e = 1 - 0.002516 * t
        val l = 6288774 * sin(mp) + 1274027 * sin(2 * d - mp) + 658314 * sin(2 * d) + 213618 * sin(2 * mp) -
            185116 * e * sin(m) - 114332 * sin(2 * f) + 58793 * sin(2 * d - 2 * mp) + 57066 * e * sin(2 * d - m - mp) +
            53322 * sin(2 * d + mp) + 45758 * e * sin(2 * d - m) - 40923 * e * sin(m - mp) - 34720 * sin(d) -
            30383 * e * sin(m + mp) + 15327 * sin(2 * d - 2 * f) - 12528 * sin(mp + 2 * f) + 10980 * sin(mp - 2 * f) +
            10675 * sin(4 * d - mp) + 10034 * sin(3 * mp) + 8548 * sin(4 * d - 2 * mp) - 7888 * e * sin(2 * d + m - mp) -
            6766 * e * sin(2 * d + m) - 5163 * sin(d - mp)
        val b = 5128122 * sin(f) + 280602 * sin(mp + f) + 277693 * sin(mp - f) + 173237 * sin(2 * d - f) +
            55413 * sin(2 * d - mp + f) + 46271 * sin(2 * d - mp - f) + 32573 * sin(2 * d + f) + 17198 * sin(2 * mp + f) +
            9266 * sin(2 * d + mp - f) + 8822 * sin(2 * mp - f) + 8216 * e * sin(2 * d - m - f) + 4324 * sin(2 * d - 2 * mp - f) +
            4200 * sin(2 * d + mp + f)
        val lon = norm(lp + l / 1e6); val lat = b / 1e6
        return Moon(eclToEq(lon, lat, obliquity(t)), lon, lat)
    }

    /** The moon from the TV rather than from the Earth's centre: about a degree lower near the horizon. */
    fun moonFromHere(h: Hz) = Hz(h.alt - 0.95 * cos(h.alt * D), h.az)

    /** How much of the moon is lit, 0 to 1. */
    fun moonLit(ms: Long): Double {
        val mo = moon(ms); val t = centuries(julian(ms)); val sl = sunLon(t).first
        val psi = Math.acos((cos(mo.lat * D) * cos((mo.lon - sl) * D)).coerceIn(-1.0, 1.0))
        val i = PI - psi
        return (1 + cos(i)) / 2
    }

    /** Waxing when the moon is less than half a turn ahead of the sun. */
    fun moonWaxing(ms: Long): Boolean { val t = centuries(julian(ms)); return norm(moon(ms).lon - sunLon(t).first) < 180 }

    class Planet(val name: String, val eq: Eq, val mag: Double)

    // JPL approximate elements, 1800-2050: a, e, I, L, long. perihelion, long. node; then rates per century.
    private val ELEMENTS = mapOf(
        "Mercury" to doubleArrayOf(0.38709927, 0.20563593, 7.00497902, 252.25032350, 77.45779628, 48.33076593, 0.00000037, 0.00001906, -0.00594749, 149472.67411175, 0.16047689, -0.12534081),
        "Venus" to doubleArrayOf(0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255, 0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418),
        "Earth" to doubleArrayOf(1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0, 0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0),
        "Mars" to doubleArrayOf(1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891, 0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343),
        "Jupiter" to doubleArrayOf(5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909, -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106),
        "Saturn" to doubleArrayOf(9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448, -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794),
    )
    // Brightness at 1 AU from both sun and Earth, and how much it dims per degree of phase.
    private val ABS_MAG = mapOf("Mercury" to (-0.42 to .038), "Venus" to (-4.40 to .0009), "Mars" to (-1.52 to .016), "Jupiter" to (-9.40 to .005), "Saturn" to (-8.88 to .044))

    private fun helio(name: String, t: Double): DoubleArray {
        val k = ELEMENTS.getValue(name)
        val a = k[0] + k[6] * t; val e = k[1] + k[7] * t; val i = (k[2] + k[8] * t) * D
        val l = k[3] + k[9] * t; val wbar = k[4] + k[10] * t; val node = (k[5] + k[11] * t) * D
        val w = wbar * D - node
        var m = norm(l - wbar); if (m > 180) m -= 360
        val mr = m * D
        var ea = mr + e * sin(mr)
        repeat(8) { ea -= (ea - e * sin(ea) - mr) / (1 - e * cos(ea)) }
        val xp = a * (cos(ea) - e); val yp = a * sqrt(1 - e * e) * sin(ea)
        return doubleArrayOf(
            (cos(w) * cos(node) - sin(w) * sin(node) * cos(i)) * xp + (-sin(w) * cos(node) - cos(w) * sin(node) * cos(i)) * yp,
            (cos(w) * sin(node) + sin(w) * cos(node) * cos(i)) * xp + (-sin(w) * sin(node) + cos(w) * cos(node) * cos(i)) * yp,
            sin(w) * sin(i) * xp + cos(w) * sin(i) * yp,
        )
    }

    fun planets(ms: Long): List<Planet> {
        val t = centuries(julian(ms)); val earth = helio("Earth", t); val eps = 23.43928 * D
        return ABS_MAG.keys.map { name ->
            val p = helio(name, t)
            val x = p[0] - earth[0]; val y = p[1] - earth[1]; val z = p[2] - earth[2]
            val ye = y * cos(eps) - z * sin(eps); val ze = y * sin(eps) + z * cos(eps)
            val dist = sqrt(x * x + y * y + z * z); val r = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); val re = sqrt(earth[0] * earth[0] + earth[1] * earth[1] + earth[2] * earth[2])
            val phase = Math.acos(((r * r + dist * dist - re * re) / (2 * r * dist)).coerceIn(-1.0, 1.0)) / D
            val (h, k) = ABS_MAG.getValue(name)
            var ra = atan2(ye, x); if (ra < 0) ra += 2 * PI
            // Mercury and Venus brighten and fade unevenly with their phase (Mallama's fits); the rest are near enough linear.
            val ph = if (name == "Mercury") -0.613 + 6.328e-2 * phase - 1.6336e-3 * phase * phase + 3.3644e-5 * phase.pow(3) - 3.4265e-7 * phase.pow(4) + 1.6893e-9 * phase.pow(5) - 3.0334e-12 * phase.pow(6) - h
                else if (name == "Venus") -4.384 - 1.044e-3 * phase + 3.687e-4 * phase * phase - 2.814e-6 * phase.pow(3) + 8.938e-9 * phase.pow(4) - h
                else k * phase
            Planet(name, Eq(ra, asin(ze / dist)), h + 5 * log10(r * dist) + ph)
        }
    }

    /** Degrees between two points in the sky. */
    fun separation(a: Hz, b: Hz): Double {
        val c = sin(a.alt * D) * sin(b.alt * D) + cos(a.alt * D) * cos(b.alt * D) * cos((a.az - b.az) * D)
        return Math.acos(c.coerceIn(-1.0, 1.0)) / D
    }
}

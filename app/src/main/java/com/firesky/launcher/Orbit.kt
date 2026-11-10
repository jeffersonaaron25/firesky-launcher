package com.firesky.launcher

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The space station's orbit. CelesTrak publishes its latest orbit as two lines of numbers (a TLE),
 * and SGP4 turns those into a position at any moment. This is satellite.js's SGP4 (MIT, © 2013
 * Shashwat Kandadai, UCSC Jack Baskin School of Engineering; licenses/satellite-js.txt) for near-Earth orbits, which is all the station needs.
 */
class Orbit(line1: String, line2: String) {
    private val epochJd: Double
    private val bstar: Double; private val ecco: Double; private val argpo: Double; private val inclo: Double
    private val mo: Double; private var no: Double; private val nodeo: Double
    private var isimp = 0
    private var con41 = 0.0; private var cc1 = 0.0; private var cc4 = 0.0; private var cc5 = 0.0
    private var d2 = 0.0; private var d3 = 0.0; private var d4 = 0.0; private var delmo = 0.0; private var eta = 0.0
    private var argpdot = 0.0; private var omgcof = 0.0; private var sinmao = 0.0
    private var t2cof = 0.0; private var t3cof = 0.0; private var t4cof = 0.0; private var t5cof = 0.0
    private var x1mth2 = 0.0; private var x7thm1 = 0.0; private var mdot = 0.0; private var nodedot = 0.0
    private var xlcof = 0.0; private var xmcof = 0.0; private var nodecf = 0.0; private var aycof = 0.0

    init {
        val yr = line1.substring(18, 20).trim().toInt()
        val days = line1.substring(20, 32).trim().toDouble()
        bstar = (line1.substring(53, 54) + "." + line1.substring(54, 59).trim() + "E" + line1.substring(59, 61).trim()).toDouble()
        inclo = line2.substring(8, 16).trim().toDouble() * DEG
        nodeo = line2.substring(17, 25).trim().toDouble() * DEG
        ecco = ("." + line2.substring(26, 33).trim()).toDouble()
        argpo = line2.substring(34, 42).trim().toDouble() * DEG
        mo = line2.substring(43, 51).trim().toDouble() * DEG
        no = line2.substring(52, 63).trim().toDouble() / (1440.0 / (2 * PI))
        val year = if (yr < 57) 2000 + yr else 1900 + yr
        epochJd = jan0(year) + days
        init()
    }

    private fun jan0(year: Int): Double = 367.0 * year - floor(7 * (year + floor(10 / 12.0)) * .25) + floor(275 * 1 / 9.0) + 0 + 1721013.5

    private fun init() {
        val ss = 78.0 / RE + 1.0
        val qzms2t = ((120.0 - 78.0) / RE).pow(4)
        // initl
        val eccsq = ecco * ecco; val omeosq = 1.0 - eccsq; val rteosq = sqrt(omeosq)
        val cosio = cos(inclo); val cosio2 = cosio * cosio
        val ak = (XKE / no).pow(X2O3)
        val d1 = 0.75 * J2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
        var delPrime = d1 / (ak * ak)
        val adel = ak * (1.0 - delPrime * delPrime - delPrime * (1.0 / 3.0 + 134.0 * delPrime * delPrime / 81.0))
        delPrime = d1 / (adel * adel)
        no /= 1.0 + delPrime
        val ao = (XKE / no).pow(X2O3)
        val sinio = sin(inclo); val po = ao * omeosq
        val con42 = 1.0 - 5.0 * cosio2; con41 = -con42 - cosio2 - cosio2
        val posq = po * po; val rp = ao * (1.0 - ecco)
        // sgp4init, near Earth
        if (rp < 220.0 / RE + 1.0) isimp = 1
        var sfour = ss; var qzms24 = qzms2t
        val perige = (rp - 1.0) * RE
        if (perige < 156.0) {
            sfour = if (perige < 98.0) 20.0 else perige - 78.0
            qzms24 = ((120.0 - sfour) / RE).pow(4)
            sfour = sfour / RE + 1.0
        }
        val pinvsq = 1.0 / posq
        val tsi = 1.0 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta; val eeta = ecco * eta; val psisq = abs(1.0 - etasq)
        val coef = qzms24 * tsi.pow(4); val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * no * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) + 0.375 * J2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
        cc1 = bstar * cc2
        val cc3 = if (ecco > 1.0e-4) -2.0 * coef * tsi * J3OJ2 * no * sinio / ecco else 0.0
        x1mth2 = 1.0 - cosio2
        cc4 = 2.0 * no * coef1 * ao * omeosq * (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) - J2 * tsi / (ao * psisq) *
            (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) + 0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)))
        cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * J2 * pinvsq * no; val temp2 = 0.5 * temp1 * J2 * pinvsq; val temp3 = -0.46875 * J4 * pinvsq * pinvsq * no
        mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4) + temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = if (ecco > 1.0e-4) -X2O3 * coef * bstar / eeta else 0.0
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        xlcof = -0.25 * J3OJ2 * sinio * (3.0 + 5.0 * cosio) / (if (abs(cosio + 1.0) > 1.5e-12) 1.0 + cosio else 1.5e-12)
        aycof = -0.5 * J3OJ2 * sinio
        delmo = (1.0 + eta * cos(mo)).pow(3)
        sinmao = sin(mo)
        x7thm1 = 7.0 * cosio2 - 1.0
        if (isimp != 1) {
            val cc1sq = cc1 * cc1
            d2 = 4.0 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3.0
            d3 = (17.0 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
            t3cof = d2 + 2.0 * cc1sq
            t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
            t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
        }
    }

    /** Position in km, in the frame SGP4 uses (close enough to the equatorial frame of date). Null if the orbit has decayed. */
    fun position(ms: Long): DoubleArray? {
        val t = (Astro.julian(ms) - epochJd) * 1440.0
        val xmdf = mo + mdot * t; val argpdf = argpo + argpdot * t; val nodedf = nodeo + nodedot * t
        var argpm = argpdf; var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t; var tempe = bstar * cc4 * t; var templ = t2cof * t2
        if (isimp != 1) {
            val delomg = omgcof * t
            val delm = xmcof * ((1.0 + eta * cos(xmdf)).pow(3) - delmo)
            val temp = delomg + delm
            mm = xmdf + temp; argpm = argpdf - temp
            val t3 = t2 * t; val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        val nm0 = no; var em = ecco; val inclm = inclo
        if (nm0 <= 0.0) return null
        val am = (XKE / nm0).pow(X2O3) * tempa * tempa
        val nm = XKE / am.pow(1.5)
        em -= tempe
        if (em >= 1.0 || em < -0.001) return null
        if (em < 1.0e-6) em = 1.0e-6
        mm += no * templ
        var xlm = mm + argpm + nodem
        nodem %= TWO_PI; argpm %= TWO_PI; xlm %= TWO_PI
        mm = (xlm - argpm - nodem) % TWO_PI
        val sinip = sin(inclm); val cosip = cos(inclm)
        val ep = em; val argpp = argpm; val nodep = nodem; val mp = mm
        val axnl = ep * cos(argpp)
        var temp = 1.0 / (am * (1.0 - ep * ep))
        val aynl = ep * sin(argpp) + temp * aycof
        val xl = mp + argpp + nodep + temp * xlcof * axnl
        val u = (xl - nodep) % TWO_PI
        var eo1 = u; var tem5 = 9999.9; var ktr = 1
        var sineo1 = 0.0; var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1); coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 += tem5; ktr++
        }
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) return null
        val rl = am * (1.0 - ecose)
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu; val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * J2 * temp; val temp2 = temp1 * temp
        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        if (mrt < 1.0) return null
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = inclm + 1.5 * temp2 * cosip * sinip * cos2u
        val sinsu = sin(su); val cossu = cos(su); val snod = sin(xnode); val cnod = cos(xnode); val sini = sin(xinc); val cosi = cos(xinc)
        val xmx = -snod * cosi; val xmy = cnod * cosi
        return doubleArrayOf((xmx * sinsu + cnod * cossu) * mrt * RE, (xmy * sinsu + snod * cossu) * mrt * RE, sini * sinsu * mrt * RE)
    }

    /** Where it is in the TV's sky, how far, and how lit by the sun (0 in the Earth's shadow, 1 in sunlight). */
    class Seen(val alt: Double, val az: Double, val km: Double, val lit: Double)

    fun seen(ms: Long, lat: Double, lon: Double): Seen? {
        val r = position(ms) ?: return null
        val g = gst(ms)
        val x = r[0] * cos(g) + r[1] * sin(g); val y = -r[0] * sin(g) + r[1] * cos(g); val z = r[2]
        val la = lat * DEG; val lo = lon * DEG
        val a = 6378.137; val b = 6356.7523142; val f = (a - b) / a; val e2 = 2 * f - f * f
        val n = a / sqrt(1 - e2 * sin(la) * sin(la))
        val ox = n * cos(la) * cos(lo); val oy = n * cos(la) * sin(lo); val oz = n * (1 - e2) * sin(la)
        val rx = x - ox; val ry = y - oy; val rz = z - oz
        val s = sin(la) * cos(lo) * rx + sin(la) * sin(lo) * ry - cos(la) * rz
        val e = -sin(lo) * rx + cos(lo) * ry
        val zt = cos(la) * cos(lo) * rx + cos(la) * sin(lo) * ry + sin(la) * rz
        val range = sqrt(rx * rx + ry * ry + rz * rz)
        val sun = Astro.sunVector(ms)
        val dot = r[0] * sun[0] + r[1] * sun[1] + r[2] * sun[2]
        val px = r[0] - dot * sun[0]; val py = r[1] - dot * sun[1]; val pz = r[2] - dot * sun[2]
        val lit = if (dot > 0) 1.0 else ((sqrt(px * px + py * py + pz * pz) - 6340) / 80).coerceIn(0.0, 1.0)
        return Seen(asin(zt / range) / DEG, ((atan2(-e, s) + PI) / DEG) % 360, range, lit)
    }

    /** A pass you could see: the station sunlit, high enough, while it's dark where you are. */
    class Pass(val start: Long, val end: Long, val maxAlt: Double, val fromAz: Double, val toAz: Double)

    fun passes(from: Long, to: Long, lat: Double, lon: Double): List<Pass> {
        val out = ArrayList<Pass>()
        var start = -1L; var maxAlt = 0.0; var fromAz = 0.0; var toAz = 0.0; var last = 0L
        var sunAlt = 0.0; var sunAt = from - 300_000
        var t = from
        while (t < to) {
            if (t - sunAt >= 300_000) { sunAlt = Astro.horizon(Astro.sun(t), Astro.lst(t, lon), lat).alt; sunAt = t }
            val s = if (sunAlt < -6) seen(t, lat, lon) else null
            if (s != null && s.alt > 10 && s.lit > .5) {
                if (start < 0) { start = t; maxAlt = s.alt; fromAz = s.az }
                if (s.alt > maxAlt) maxAlt = s.alt
                toAz = s.az; last = t
            } else if (start >= 0) {
                out += Pass(start, last, maxAlt, fromAz, toAz); start = -1
            }
            t += 10_000
        }
        if (start >= 0) out += Pass(start, last, maxAlt, fromAz, toAz)
        return out
    }

    companion object {
        private const val DEG = PI / 180
        private const val TWO_PI = 2 * PI
        private const val RE = 6378.135
        private const val MU = 398600.8
        private val XKE = 60.0 / sqrt(RE * RE * RE / MU)
        private const val J2 = 0.001082616
        private const val J3 = -0.00000253881
        private const val J4 = -0.00000165597
        private const val J3OJ2 = J3 / J2
        private const val X2O3 = 2.0 / 3.0
        private const val MAX_AGE_MS = 24 * 3_600_000L

        /** Sidereal time at Greenwich, in radians, the way SGP4 counts it. */
        fun gst(ms: Long): Double {
            val tut1 = (Astro.julian(ms) - 2451545.0) / 36525.0
            var temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 + (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841
            temp = (temp * DEG / 240.0) % TWO_PI
            return if (temp < 0) temp + TWO_PI else temp
        }

        private fun file(ctx: Context) = File(ctx.filesDir, "iss.tle")

        /** The orbit worked out from the file as it was at this time, so the same file gives the same orbit. */
        @Volatile private var held: Pair<Long, Orbit>? = null

        /** The station's orbit, refreshed daily: it drifts out of date in about a week. */
        fun iss(ctx: Context): Orbit? = runCatching {
            val f = file(ctx)
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) runCatching {
                val c = URL("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val text = c.inputStream.use { it.bufferedReader().readText() }
                if (text.lines().count { it.startsWith("1 ") || it.startsWith("2 ") } == 2) {
                    val tmp = File(f.path + ".part"); tmp.writeText(text); tmp.renameTo(f)
                }
            }
            val stamp = f.lastModified()
            held?.takeIf { it.first == stamp }?.second ?: run {
                val lines = f.readLines()
                Orbit(lines.first { it.startsWith("1 ") }, lines.first { it.startsWith("2 ") }).also { held = stamp to it }
            }
        }.getOrNull()
    }
}

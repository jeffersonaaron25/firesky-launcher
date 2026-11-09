package com.firesky.launcher

/** A stretch when an app was really on screen. */
class Session(val pkg: String, val start: Long, val end: Long)

/**
 * Turns the TV's raw event log into real use. An app only counts while it is in front and the
 * screen is on, for at least [MIN_MS]: waking the TV resumes the last app for a second or two, and
 * Fire OS also cycles apps while the TV is asleep. Neither of those is you using it.
 */
object Sessions {
    // UsageEvents.Event type numbers.
    const val FOREGROUND = 1
    const val BACKGROUND = 2
    const val SCREEN_ON = 15
    const val SCREEN_OFF = 16

    const val MIN_MS = 30_000L

    class Ev(val time: Long, val type: Int, val pkg: String?)

    /** A TV shows one app at a time, so one "front" is enough. An app still in front ends at [now]. */
    fun from(events: List<Ev>, now: Long): List<Session> {
        val out = ArrayList<Session>()
        var front: String? = null
        var since = 0L
        var screenOn = true
        fun stop(t: Long) {
            val p = front
            if (p != null && since > 0 && t - since >= MIN_MS) out += Session(p, since, t)
            since = 0
        }
        for (e in events) when (e.type) {
            FOREGROUND -> {
                stop(e.time)
                front = e.pkg
                if (screenOn) since = e.time
            }
            BACKGROUND -> if (e.pkg == front) {
                stop(e.time)
                front = null
            }
            SCREEN_OFF -> {
                stop(e.time)
                screenOn = false
            }
            SCREEN_ON -> {
                screenOn = true
                if (front != null && since == 0L) since = e.time
            }
        }
        stop(now)
        return out
    }
}

package com.firesky.launcher

import com.firesky.launcher.Sessions.BACKGROUND
import com.firesky.launcher.Sessions.Ev
import com.firesky.launcher.Sessions.FOREGROUND
import com.firesky.launcher.Sessions.SCREEN_OFF
import com.firesky.launcher.Sessions.SCREEN_ON
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

private const val NETFLIX = "com.netflix.ninja"
private const val YOUTUBE = "com.amazon.firetv.youtube"
private const val APP = "com.firesky.launcher"
private const val AMAZON = "com.amazon.tv.launcher"

class SessionsTest {
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private fun t(s: String) = fmt.parse("2025-03-$s")!!.time
    private fun ev(s: String, type: Int, pkg: String? = "android") = Ev(t(s), type, pkg)
    private fun List<Session>.of(pkg: String) = filter { it.pkg == pkg }.map { fmt.format(it.start).drop(8) to fmt.format(it.end).drop(8) }

    /** A sample usage log, shaped like the TV's own: Netflix one evening, the screen off, YouTube the next morning. */
    private val realLog = listOf(
        ev("05 17:31:23", FOREGROUND, APP), ev("05 17:31:23", BACKGROUND, APP),
        ev("05 17:55:27", FOREGROUND, APP), ev("05 17:55:27", SCREEN_ON),
        ev("05 17:55:28", BACKGROUND, APP), ev("05 17:55:28", FOREGROUND, NETFLIX),
        ev("05 17:55:29", BACKGROUND, NETFLIX), ev("05 17:55:29", FOREGROUND, APP),
        ev("05 17:55:42", BACKGROUND, APP), ev("05 17:55:42", FOREGROUND, NETFLIX),
        ev("05 19:24:53", SCREEN_OFF), ev("05 19:24:54", BACKGROUND, NETFLIX),
        ev("05 19:33:53", FOREGROUND, NETFLIX), ev("05 19:33:53", BACKGROUND, NETFLIX),
        ev("05 19:33:54", FOREGROUND, AMAZON), ev("05 19:33:54", BACKGROUND, AMAZON),
        ev("05 19:33:55", FOREGROUND, APP), ev("05 19:33:55", BACKGROUND, APP),
        ev("06 07:11:06", SCREEN_ON), ev("06 07:11:07", FOREGROUND, APP),
        ev("06 07:11:31", BACKGROUND, APP), ev("06 07:11:32", FOREGROUND, YOUTUBE),
    )

    @Test fun netflixLastUsedWhenTheScreenWentOff() {
        val s = Sessions.from(realLog, t("06 07:44:29"))
        assertEquals(listOf("05 17:55:42" to "05 19:24:53"), s.of(NETFLIX))
    }

    @Test fun appStillOnScreenRunsUntilNow() {
        val s = Sessions.from(realLog, t("06 07:44:29"))
        assertEquals(listOf("06 07:11:32" to "06 07:44:29"), s.of(YOUTUBE))
    }

    @Test fun wakeFlickerIsNotUse() {
        val log = listOf(ev("05 17:55:28", FOREGROUND, NETFLIX), ev("05 17:55:29", BACKGROUND, NETFLIX))
        assertEquals(emptyList<Session>(), Sessions.from(log, t("05 18:00:00")))
    }

    @Test fun appResumedWhileAsleepDoesNotCountUntilTheScreenIsOn() {
        val log = listOf(
            ev("05 22:00:00", SCREEN_OFF),
            ev("05 23:00:00", FOREGROUND, NETFLIX),
            ev("06 07:00:00", SCREEN_ON),
            ev("06 07:00:02", BACKGROUND, NETFLIX),
        )
        assertEquals(emptyList<Session>(), Sessions.from(log, t("06 08:00:00")))
    }

    @Test fun appThatStaysInFrontAfterWakeStartsAtWake() {
        val log = listOf(
            ev("05 22:00:00", SCREEN_OFF),
            ev("06 06:59:59", FOREGROUND, NETFLIX),
            ev("06 07:00:00", SCREEN_ON),
            ev("06 08:00:00", BACKGROUND, NETFLIX),
        )
        assertEquals(listOf("06 07:00:00" to "06 08:00:00"), Sessions.from(log, t("06 09:00:00")).of(NETFLIX))
    }

    @Test fun missingBackgroundEventEndsWhenTheNextAppOpens() {
        val log = listOf(ev("05 20:00:00", FOREGROUND, NETFLIX), ev("05 21:00:00", FOREGROUND, YOUTUBE))
        assertEquals(listOf("05 20:00:00" to "05 21:00:00"), Sessions.from(log, t("05 22:00:00")).of(NETFLIX))
    }
}

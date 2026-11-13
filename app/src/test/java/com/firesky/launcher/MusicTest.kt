package com.firesky.launcher

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MusicTest {
    private fun cover(vararg halves: Int): Bitmap {
        val b = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        for (y in 0 until 40) for (x in 0 until 40) b.setPixel(x, y, halves[(x * halves.size / 40).coerceAtMost(halves.size - 1)])
        return b
    }

    private fun song(title: String, art: Bitmap) = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, title).putString(MediaMetadata.METADATA_KEY_ARTIST, "An Artist")
        .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art).build()
    private val playing = PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0, 1f).build()

    /** Lets the player's word settle, then waits for the cover's colours, worked out on a thread of their own. */
    private fun settle(done: () -> Boolean) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Music.SETTLE_MS + 50))
        for (i in 0 until 100) { if (done()) return; Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle() }
    }

    /** Spotify now and then gives its own logo as the cover for a moment: the screen never shows it. */
    @Test fun aCoverThatComesAndGoesNeverShows() {
        val pkg = "com.spotify.tv.android"
        val art = cover(Color.rgb(200, 30, 40)); val logo = cover(Color.rgb(30, 215, 96))
        val seen = ArrayList<Music.Track?>()
        Music.changed = { seen += it }
        try {
            Music.report(pkg, playing, song("A Song", art))
            settle { seen.size == 1 }
            assertEquals(1, seen.size)
            Music.report(pkg, playing, song("A Song", logo))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            Music.report(pkg, playing, song("A Song", art))
            settle { false }
            assertEquals("the logo never showed", 1, seen.size)
            assertEquals(Music.print(art), Music.print(Music.now!!.cover!!))

            // A new song, first with the last one's cover and then its own, changes once.
            val next = cover(Color.rgb(30, 60, 210))
            Music.report(pkg, playing, song("Another Song", art))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            Music.report(pkg, playing, song("Another Song", next))
            settle { seen.size == 2 }
            assertEquals(2, seen.size)
            assertEquals(Music.print(next), Music.print(Music.now!!.cover!!))

            // A song that starts with only the app's logo, its own cover a second and more later, never shows the logo.
            val third = cover(Color.rgb(240, 200, 40))
            Music.report(pkg, playing, MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "A Third Song")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "An Artist").putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, logo).build())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
            Music.report(pkg, playing, song("A Third Song", third))
            settle { seen.size == 3 }
            assertEquals(3, seen.size)
            assertEquals(Music.print(third), Music.print(Music.now!!.cover!!))
        } finally {
            Music.changed = null
            Music.report(pkg, null, null)
            settle { Music.now == null }
        }
    }

    @Test fun musicAppsCountAndSoDoesAnythingWithAnAlbum() {
        assertTrue(Music.isMusic("com.spotify.tv.android", null, null))
        assertTrue(Music.isMusic("some.player", "An Album", "An Artist"))
        assertFalse(Music.isMusic("com.netflix.ninja", null, null))
        assertFalse(Music.isMusic("some.player", "", "An Artist"))
    }

    /** A new song can come with the last one's cover: the cover's own fingerprint tells them apart. */
    @Test fun coversAreToldApartByWhatTheyShow() {
        val red = cover(Color.rgb(200, 30, 40)); val blue = cover(Color.rgb(30, 60, 210))
        assertNotEquals(Music.print(red), Music.print(blue))
        assertEquals(Music.print(red), Music.print(cover(Color.rgb(200, 30, 40))))
    }

    @Test fun aCoverGivesTwoColoursWellApart() {
        val c = Music.colours(cover(Color.rgb(210, 40, 50), Color.rgb(210, 40, 50), Color.rgb(40, 90, 220)))
        assertTrue("first red: ${Integer.toHexString(c[0])}", Color.red(c[0]) > Color.blue(c[0]))
        assertTrue("second blue: ${Integer.toHexString(c[1])}", Color.blue(c[1]) > Color.red(c[1]))
        // All grey: the soft default.
        val g = Music.colours(cover(Color.rgb(120, 120, 120)))
        assertEquals(2, g.size)
    }
}

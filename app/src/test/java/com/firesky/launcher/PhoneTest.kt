package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URL

/** The phone page for the TMDB key, served for real on this computer. */
@RunWith(RobolectricTestRunner::class)
class PhoneTest {
    private fun call(url: String, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return code to text
    }

    @Test fun servesTheFormOnlyAtItsOwnAddressAndRemovesTheKey() {
        var got: String? = "unset"
        val p = Phone(hasKey = { true }) { got = it }
        val url = p.start() ?: return // No network here: nothing to test.
        try {
            val (code, page) = call(url)
            assertEquals(200, code)
            assertTrue(page.contains("name=key"))
            assertTrue(page.contains("Remove the key"))
            // Anyone guessing at the TV's address finds nothing.
            assertEquals(404, call(url.substringBeforeLast('/') + "/guess").first)
            // An empty key is caught before asking TMDB.
            assertTrue(call(url, "key=").second.contains("Paste the key first"))
            assertEquals("unset", got)
            assertTrue(call(url, "remove=1").second.contains("removed"))
            assertNull(got)
        } finally { p.stop() }
    }

    @Test fun drawsAQrCode() {
        val q = Phone.qr("http://192.168.1.50:41234/abcdef")
        assertTrue(q.width in 25..60 && q.width == q.height)
    }
}

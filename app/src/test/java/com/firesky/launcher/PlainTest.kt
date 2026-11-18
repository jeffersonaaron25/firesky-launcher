package com.firesky.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlainTest {
    @Test fun titlesWrittenForAWebPageReadAsThemselves() {
        assertEquals("Love, Death & Robots", plain("Love, Death &amp; Robots"))
        assertEquals("Tom & Jerry", plain("Tom &#38; Jerry"))
        assertEquals("Tom & Jerry", plain("Tom &#x26; Jerry"))
        assertEquals("Bob's Burgers", plain("Bob&#39;s Burgers"))
        assertEquals("\"Quoted\" <b>", plain("&quot;Quoted&quot; &lt;b&gt;"))
        assertEquals("Mr. & Mrs. Smith", plain("Mr. &amp;amp; Mrs. Smith"))
    }

    @Test fun anythingElseIsLeftAsItIs() {
        val s = "Mr. & Mrs. Smith"
        assertSame(s, plain(s))
        assertEquals("R&D", plain("R&D"))
        assertEquals("Fish &chips; and &unknownthing;", plain("Fish &chips; and &unknownthing;"))
        assertEquals("&#0;", plain("&#0;"))
    }
}

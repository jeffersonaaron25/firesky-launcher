package com.firesky.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PicksTest {
    private val now = 1_800_000_000_000L
    private fun p(role: String, name: String) = Facts.Tag(role, name)
    private fun k(name: String) = Facts.Tag("kw", name)
    private fun info(key: String, title: String, vararg tags: Facts.Tag, collection: Int = 0) =
        Facts.Info(key, title, 2015, tags.toList(), emptyList(), listOf("com.netflix.ninja"), collection, 7.5, 1000, 50.0, "/a.jpg", now)
    private fun cand(key: String, title: String, vararg tags: Facts.Tag, pkg: String = "com.netflix.ninja", vote: Double = 7.0, votes: Int = 100,
                     feed: String = "", partSeen: Int = 0, partOf: Int = 0, part: Int = 0, firstSeen: String = "", skies: Set<String> = emptySet()) =
        Picks.Cand(key, title, pkg, "/b.jpg", null, tags.toList(), vote, votes, feed, partSeen, partOf, part, firstSeen, skies)

    private val sicario = info("title:m273481", "Sicario", p("director", "Denis Villeneuve"), p("dp", "Roger Deakins"), p("writer", "Taylor Sheridan"), k("cartel"), k("thriller"))
    private val br2049 = info("title:m335984", "Blade Runner 2049", p("director", "Denis Villeneuve"), p("dp", "Roger Deakins"), k("dystopia"))
    private val witcher = info("title:t71912", "The Witcher", k("fantasy"), k("monsters"))
    private fun watched() = listOf(
        Picks.Watched(sicario, Taste.Verdict.FINISHED, false, now),
        Picks.Watched(br2049, Taste.Verdict.FINISHED, false, now),
        Picks.Watched(witcher, Taste.Verdict.LEFT, true, now),
    )
    private fun pool(vararg c: Picks.Cand) = Picks.Pool(Picks.profile(watched(), now), c.toList(), now)
    private fun row(pool: Picks.Pool, sky: String? = null, room: Int = 8, hidden: Set<String> = emptySet(), ok: (Picks.Cand) -> Boolean = { true }, week: Int = 1) =
        Picks.row(pool, sky, room, hidden, ok, week)

    @Test fun aPickSaysWhyInPlainWords() {
        val prisoners = cand("title:m146233", "Prisoners", p("director", "Denis Villeneuve"), p("dp", "Roger Deakins"), k("kidnapping"))
        val wind = cand("title:m395834", "Wind River", p("director", "Taylor Sheridan"), k("snow"))
        val pl = pool(prisoners, wind)
        val th = Picks.threads(prisoners, pl.profile)
        assertEquals("Directed by Denis Villeneuve, as were Sicario and Blade Runner 2049", Picks.say(th.first { it.tag.role == "director" }))
        assertEquals("Shot by Roger Deakins, as were Sicario and Blade Runner 2049", Picks.say(th.first { it.tag.role == "dp" }))
        // The same person in another job, and one title: which, and how it went.
        assertEquals("Directed by Taylor Sheridan, writer of Sicario, which you finished", Picks.say(Picks.threads(wind, pl.profile).first()))
    }

    @Test fun theRestOfASeriesOfFilmsComesFirst() {
        val dune2 = cand("title:m693134", "Dune: Part Two", partSeen = 1, partOf = 2, part = 2, firstSeen = "Dune")
        val wake = cand("title:m812583", "Wake Up Dead Man", partSeen = 2, partOf = 3, part = 3, firstSeen = "Knives Out")
        val prisoners = cand("title:m146233", "Prisoners", p("director", "Denis Villeneuve"), p("dp", "Roger Deakins"))
        val r = row(pool(prisoners, wake, dune2))
        assertEquals(listOf("Dune: Part Two", "Wake Up Dead Man", "Prisoners"), r.map { it.cand.title })
        assertEquals("You've seen Dune. This is part two", r[0].why)
        assertEquals("You've seen two of the three", r[1].why)
        assertEquals("Unfinished business", r[0].tag)
    }

    @Test fun aLookupThatFailsIsTriedAgainButOneThatFindsNothingWaitsAWeek() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        Prefs.init(ctx)
        Prefs.set(Prefs.TMDB, "test")
        java.io.File(ctx.filesDir, "matches.json").delete()
        Facts.forget(ctx)
        val real = Facts.search
        try {
            Facts.search = { _, _ -> throw java.io.IOException("offline") }
            assertEquals(null, Facts.match(ctx, "Arrival", false))
            assertEquals(null, Facts.known(ctx, "Arrival", false))
            Facts.search = { _, _ -> JSONObject("{\"results\":[]}") }
            assertEquals("", Facts.match(ctx, "Arrival", false))
            Facts.search = { _, _ -> JSONObject("{\"results\":[{\"id\":329865,\"title\":\"Arrival\"}]}") }
            assertEquals("", Facts.match(ctx, "Arrival", false))
            // Found, and kept: asked no more.
            assertEquals("title:m329865", Facts.match(ctx, "Arrival", null))
            Facts.search = { _, _ -> throw AssertionError("asked again") }
            assertEquals("title:m329865", Facts.match(ctx, "Arrival", null))
        } finally { Facts.search = real }
    }

    @Test fun aGenreOnlyNudges() {
        val liked = (1..20).map { Picks.Watched(info("title:m$it", "Action $it", Facts.Tag("genre", "Action")), Taste.Verdict.FINISHED, false, now) } +
            Picks.Watched(info("title:m99", "Sicario", p("director", "Denis Villeneuve")), Taste.Verdict.FINISHED, false, now)
        val profile = Picks.profile(liked, now)
        val action = cand("title:m500", "Loud", Facts.Tag("genre", "Action"))
        val denis = cand("title:m501", "Quiet", p("director", "Denis Villeneuve"))
        assertTrue(Picks.score(denis, Picks.threads(denis, profile)) > Picks.score(action, Picks.threads(action, profile)))
    }

    @Test fun onlyTheNextFilmInASeries() {
        val lotr = listOf("a", "b", "c").map { Facts.Part(it, it, "2001-12-19") }
        assertEquals(1, Picks.nextPart(lotr, setOf("a")))
        assertEquals(2, Picks.nextPart(lotr, setOf("a", "b")))
        // Started in the middle: the next one on, and only when there's none, the one before.
        assertEquals(2, Picks.nextPart(lotr, setOf("b")))
        assertEquals(0, Picks.nextPart(lotr, setOf("c")))
        assertEquals(null, Picks.nextPart(lotr, setOf("a", "b", "c")))
        assertEquals(null, Picks.nextPart(lotr, emptySet()))
        // Elsewhere in the row too: never the third before the second, never a series from its middle.
        assertTrue(Picks.inOrder("a", lotr, emptySet()))
        assertTrue(Picks.inOrder("b", lotr, setOf("b")))
        assertFalse(Picks.inOrder("c", lotr, setOf("b")))
        assertFalse(Picks.inOrder("b", lotr, emptySet()))
    }

    @Test fun givingUpEarlyCountsAgainst() {
        val fantasy = cand("title:m1", "Swords", k("fantasy"), k("monsters"))
        val pl = pool(fantasy)
        assertTrue(Picks.score(fantasy, Picks.threads(fantasy, pl.profile)) < 0)
        assertTrue(row(pl).isEmpty())
    }

    @Test fun hiddenUnavailableAndUnreasonedTitlesStayOut() {
        val prisoners = cand("title:m146233", "Prisoners", p("director", "Denis Villeneuve"), p("dp", "Roger Deakins"))
        val enemy = cand("title:m181886", "Enemy", p("director", "Denis Villeneuve"), pkg = "com.hulu.plus")
        val genre = cand("title:m2", "A Thriller", Facts.Tag("genre", "Thriller"))
        val pl = pool(prisoners, enemy, genre)
        assertEquals(setOf("Prisoners", "Enemy"), row(pl).map { it.cand.title }.toSet())
        assertEquals(listOf("Enemy"), row(pl, hidden = setOf("title:m146233")).map { it.cand.title })
        assertEquals(listOf("Prisoners"), row(pl, ok = { it.pkg != "com.hulu.plus" }).map { it.cand.title })
    }

    @Test fun oneForTheWeather() {
        val prisoners = cand("title:m146233", "Prisoners", p("director", "Denis Villeneuve"), p("dp", "Roger Deakins"))
        val fargo = cand("title:m275", "Fargo", p("dp", "Roger Deakins"), k("snow"), skies = setOf("snow"))
        val pl = pool(prisoners, fargo)
        val snowy = row(pl, sky = "snow").first { it.cand.title == "Fargo" }
        assertEquals("For the snow", snowy.tag)
        assertEquals("Snow outside, and snow in it. Shot by Roger Deakins, as were Sicario and Blade Runner 2049", snowy.why)
        assertTrue(row(pl, sky = "night").none { it.tag.startsWith("For ") })
        assertEquals("rain", Picks.sky(Sky.RAIN, false, false))
        assertEquals("storm", Picks.sky(Sky.RAIN, true, true))
        assertEquals("night", Picks.sky(Sky.CLEAR, false, true))
        assertEquals(null, Picks.sky(Sky.CLEAR, false, false))
    }

    @Test fun commonKeywordsAreNoReasonAndReasonsArentRepeated() {
        val gorgon = info("title:t1", "Tides of the Gorgon", k("based on novel or book"), k("greek mythology"), k("demigod"))
        val pl = Picks.Pool(Picks.profile(listOf(Picks.Watched(gorgon, Taste.Verdict.FINISHED, true, now)), now), listOf(
            Picks.Cand("title:m10", "The Odyssey", "com.netflix.ninja", "/b.jpg", null, listOf(k("greek mythology"), k("based on novel or book")), 7.0, 100,
                recFrom = listOf("Tides of the Gorgon")),
            cand("title:m11", "Jason and the Argonauts", k("greek mythology"), k("demigod")),
            cand("title:m12", "Any Book", k("based on novel or book")),
        ), now)
        val r = row(pl)
        assertEquals(setOf("The Odyssey", "Jason and the Argonauts"), r.map { it.cand.title }.toSet())
        assertTrue(r.none { it.why.contains("novel") })
        assertEquals(2, r.map { it.why }.toSet().size)
        // A keyword from one title is thin; being recommended after it says more.
        assertEquals("Often watched after Tides of the Gorgon", r.first { it.cand.title == "The Odyssey" }.why)
        assertEquals("Demigod, like Tides of the Gorgon, which you've been watching", r.first { it.cand.title == "Jason and the Argonauts" }.why)
    }

    @Test fun oneSharedKeywordAloneIsntEnough() {
        val gorgon = info("title:t1", "Tides of the Gorgon", k("greek mythology"), k("monster"), k("demigod"))
        val profile = Picks.profile(listOf(Picks.Watched(gorgon, Taste.Verdict.FINISHED, true, now)), now)
        val dune = cand("title:m438631", "Dune", k("monster"), k("desert"))
        val odyssey = Picks.Cand("title:m10", "The Odyssey", "com.netflix.ninja", "/b.jpg", null, listOf(k("greek mythology")), 7.0, 100, recFrom = listOf("Tides of the Gorgon"))
        val jason = cand("title:m11", "Jason and the Argonauts", k("greek mythology"), k("demigod"))
        assertEquals(listOf("Jason and the Argonauts", "The Odyssey"), row(Picks.Pool(profile, listOf(dune, odyssey, jason), now)).map { it.cand.title }.sorted())
        assertTrue(row(Picks.Pool(profile, listOf(dune), now)).isEmpty())
    }

    @Test fun nothingLearnedMeansNoPicks() {
        val empty = Picks.Pool(emptyMap(), listOf(cand("title:m1", "Anything")), now)
        assertTrue(row(empty).isEmpty())
    }

    @Test fun theWildCardHoldsForAWeek() {
        val strong = (1..6).map { cand("title:m${100 + it}", "Close $it", p("director", "Denis Villeneuve"), p("dp", "Roger Deakins"), k("thriller")) }
        val far = (1..4).map { cand("title:m${200 + it}", "Far $it", p("writer", "Taylor Sheridan"), Facts.Tag("genre", "Romance"), vote = 8.0, votes = 5000) }
        val pl = pool(*(strong + far).toTypedArray())
        val a = row(pl, room = 12, week = 202646).filter { it.tag == "Wild card" }
        val b = row(pl, room = 12, week = 202646).filter { it.tag == "Wild card" }
        assertEquals(1, a.size)
        assertEquals(a.single().cand.key, b.single().cand.key)
        assertTrue(a.single().why.startsWith("Far from your usual. Written by Taylor Sheridan, as was Sicario"))
    }

    @Test fun whatsKeptReadsBackTheSame() {
        val pl = pool(cand("title:m146233", "Prisoners", p("dp", "Roger Deakins"), skies = setOf("storm"), feed = "new"))
        val back = Picks.fromJson(JSONObject(Picks.toJson(pl).toString()))
        assertEquals(pl.profile.keys, back.profile.keys)
        assertEquals(pl.profile.getValue("p:Roger Deakins").w, back.profile.getValue("p:Roger Deakins").w, .001f)
        assertEquals(row(pl).map { it.why }, row(back).map { it.why })
        assertEquals(setOf("storm"), back.cands.single().skies)
    }

    @Test fun tmdbsAnswerBecomesTags() {
        val j = JSONObject("""
            {"id": 335984, "title": "Blade Runner 2049", "release_date": "2017-10-04", "vote_average": 7.5, "vote_count": 14000, "popularity": 40.1,
             "backdrop_path": "/x.jpg", "belongs_to_collection": {"id": 422837, "name": "Blade Runner Collection"},
             "genres": [{"id": 878, "name": "Science Fiction"}],
             "credits": {"cast": [{"name": "Ryan Gosling"}, {"name": "Harrison Ford"}], "crew": [
                {"job": "Director", "name": "Denis Villeneuve"}, {"job": "Director of Photography", "name": "Roger Deakins"},
                {"job": "Original Music Composer", "name": "Hans Zimmer"}, {"job": "Screenplay", "name": "Hampton Fancher"},
                {"job": "Screenplay", "name": "Michael Green"}, {"job": "Writer", "name": "Third Writer"}, {"job": "Editor", "name": "Joe Walker"}]},
             "keywords": {"keywords": [{"name": "dystopia"}, {"name": "android"}]},
             "recommendations": {"results": [{"id": 78, "media_type": "movie"}, {"id": 1399, "media_type": "tv"}]},
             "watch/providers": {"results": {"US": {"flatrate": [{"provider_id": 1899}, {"provider_id": 119}, {"provider_id": 9}, {"provider_id": 337}]}}}}
        """.trimIndent())
        val i = Facts.parse("title:m335984", j, true, "US")
        assertEquals("Blade Runner 2049", i.title)
        assertEquals(2017, i.year)
        val by = i.tags.groupBy({ it.role }, { it.name })
        assertEquals(listOf("Denis Villeneuve"), by["director"])
        assertEquals(listOf("Roger Deakins"), by["dp"])
        assertEquals(listOf("Hans Zimmer"), by["music"])
        assertEquals(listOf("Hampton Fancher", "Michael Green"), by["writer"])
        assertEquals(listOf("Ryan Gosling", "Harrison Ford"), by["actor"])
        assertEquals(listOf("dystopia", "android"), by["kw"])
        assertEquals(listOf("Science Fiction"), by["genre"])
        assertEquals(listOf("title:m78", "title:t1399"), i.recs)
        assertEquals(listOf("com.hbo.hbonow", "com.amazon.firebat"), i.pkgs)
        assertEquals(422837, i.collection)
        assertFalse(i.tags.any { it.name == "Joe Walker" })
    }

    @Test fun namesAreComparedLoosely() {
        assertEquals(Facts.norm("Amélie"), Facts.norm("amelie"))
        assertEquals(Facts.norm("Fast & Furious"), Facts.norm("Fast and Furious"))
        assertEquals("dune part two", Facts.norm("Dune: Part Two"))
    }
}

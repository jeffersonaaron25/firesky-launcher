package com.firesky.launcher

import androidx.compose.ui.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DetailsTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    /** TMDB's answer for a made-up film, in the shape it really comes in. */
    private val film = JSONObject(
        """{"id":77,"title":"The Long Quiet","overview":" A lighthouse keeper hears a voice on the radio. ","release_date":"2024-03-08","runtime":148,
        "vote_average":7.84,"vote_count":5120,"genres":[{"name":"Drama"},{"name":"Mystery"},{"name":"Thriller"}],
        "videos":{"results":[
          {"site":"YouTube","type":"Clip","official":true,"iso_639_1":"en","key":"clip","published_at":"2024-04-01"},
          {"site":"YouTube","type":"Teaser","official":true,"iso_639_1":"en","key":"teaser","published_at":"2024-02-01"},
          {"site":"YouTube","type":"Trailer","official":false,"iso_639_1":"en","key":"fan","published_at":"2024-03-01"},
          {"site":"YouTube","type":"Trailer","official":true,"iso_639_1":"en","key":"first","published_at":"2023-12-01"},
          {"site":"YouTube","type":"Trailer","official":true,"iso_639_1":"en","key":"final","published_at":"2024-02-20"},
          {"site":"YouTube","type":"Trailer","official":true,"iso_639_1":"de","key":"german","published_at":"2024-03-02"},
          {"site":"Vimeo","type":"Trailer","official":true,"iso_639_1":"en","key":"vimeo","published_at":"2024-03-05"}]},
        "release_dates":{"results":[
          {"iso_3166_1":"US","release_dates":[{"certification":"","type":1},{"certification":"R","type":4},{"certification":"PG-13","type":3}]},
          {"iso_3166_1":"GB","release_dates":[{"certification":"12A","type":3}]},
          {"iso_3166_1":"FR","release_dates":[{"certification":"","type":3}]}]},
        "credits":{"cast":[{"name":"Ada Reyes"},{"name":"Tom Okafor"},{"name":"Mina Sato"},{"name":"Fourth Name"}],
          "crew":[{"name":"Someone Else","job":"Producer"},{"name":"Lena Marsh","job":"Director"}]},
        "keywords":{"keywords":[{"id":1,"name":"lighthouse"},{"id":2,"name":"radio"},{"id":3,"name":"sea"}]},
        "images":{"logos":[
          {"file_path":"/fr.png","iso_639_1":"fr","vote_average":9.0},
          {"file_path":"/en-low.png","iso_639_1":"en","vote_average":3.2},
          {"file_path":"/en-best.png","iso_639_1":"en","vote_average":5.4},
          {"file_path":"/en-drawn.svg","iso_639_1":"en","vote_average":8.0},
          {"file_path":"/plain.png","iso_639_1":null,"vote_average":7.0}]}}""",
    )

    private val show = JSONObject(
        """{"id":12,"name":"Harbour Lights","overview":"","first_air_date":"2019-01-10","last_air_date":"2023-05-02","status":"Ended",
        "number_of_seasons":4,"vote_average":8.1,"vote_count":12,"genres":[{"name":"Comedy"}],
        "created_by":[{"name":"Ravi Patel"},{"name":"Jo Lind"}],
        "content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-14"},{"iso_3166_1":"DE","rating":"12"}]},
        "videos":{"results":[]},"credits":{"cast":[],"crew":[]}}""",
    )

    @Before fun clean() { Details.forget(ctx) }

    @Test fun readsAFilm() {
        val i = Details.parse(film, true, "GB", "en-GB")
        assertEquals("title:m77", i.key)
        assertEquals("The Long Quiet", i.title)
        assertEquals("A lighthouse keeper hears a voice on the radio.", i.overview)
        assertEquals("2024", i.years)
        assertEquals("2 h 28 min", i.length)
        assertEquals(listOf("Drama", "Mystery"), i.genres)
        assertEquals(listOf("Ada Reyes", "Tom Okafor", "Mina Sato"), i.cast)
        assertEquals("Directed by Lena Marsh", i.by)
        // The length is said once, where it's turned into when the film would end.
        assertEquals("2024 · Drama, Mystery", Details.facts(i))
        assertEquals(148, i.minutes)
        val nine = java.util.Calendar.getInstance().apply { set(2026, 10, 10, 21, 0, 0) }.timeInMillis
        assertEquals("2 h 28 min · ends ${Prefs.timeOfDay(23, 28)} if you start now", Details.tonight(i, nine))
        assertEquals("7.8", Details.score(i))
        assertEquals("5,120 votes", Details.votes(i))
    }

    @Test fun readsAShow() {
        val i = Details.parse(show, false, "US", "en-US")
        assertEquals("title:t12", i.key)
        assertEquals("2019–2023", i.years)
        assertEquals("4 seasons", i.length)
        assertEquals("2019–2023 · 4 seasons · Comedy", Details.facts(i))
        // TMDB didn't say how long an episode is, so nothing is claimed about tonight.
        assertNull(Details.tonight(i, 0))
        val timed = Details.parse(JSONObject(show.toString()).put("episode_run_time", org.json.JSONArray(listOf(55))), false, "US", "en-US")
        val nine = java.util.Calendar.getInstance().apply { set(2026, 10, 10, 21, 0, 0) }.timeInMillis
        assertEquals("Episodes about 55 min · the first ends ${Prefs.timeOfDay(21, 55)}", Details.tonight(timed, nine))
        assertEquals("TV-14", i.rated)
        assertEquals("Created by Ravi Patel and Jo Lind", i.by)
        assertNull(i.trailer)
        assertNull(i.trailerLink)
        // A dozen votes isn't a score worth showing.
        assertNull(Details.score(i))
    }

    @Test fun theAgeRatingIsTheOneWhereTheTvIs() {
        assertEquals("12A", Details.rated(film, true, "GB"))
        // The cinema's rating, not the one for the disc.
        assertEquals("PG-13", Details.rated(film, true, "US"))
        // A country with none, or with a blank one, gets the American rating.
        assertEquals("PG-13", Details.rated(film, true, "FR"))
        assertEquals("PG-13", Details.rated(film, true, "JP"))
        assertEquals("12", Details.rated(show, false, "DE"))
        assertEquals("", Details.rated(JSONObject("{}"), true, "US"))
    }

    @Test fun picksTheNewestOfficialTrailerInYourLanguage() {
        val videos = film.getJSONObject("videos").getJSONArray("results")
        assertEquals("final", Details.trailer(videos, "en-US"))
        assertEquals("german", Details.trailer(videos, "de-DE"))
        // No trailer in French, so an English one will do.
        assertEquals("final", Details.trailer(videos, "fr-FR"))
        assertEquals("https://www.youtube.com/watch?v=final", Details.parse(film, true, "US", "en-US").trailerLink)
        assertNull(Details.trailer(null, "en-US"))
    }

    @Test fun aTeaserWillDoWhenTheresNoTrailer() {
        val only = JSONObject("""{"r":[{"site":"YouTube","type":"Teaser","official":true,"iso_639_1":"en","key":"teaser","published_at":"2024-02-01"},
            {"site":"YouTube","type":"Featurette","official":true,"iso_639_1":"en","key":"making","published_at":"2024-05-01"}]}""").getJSONArray("r")
        assertEquals("teaser", Details.trailer(only, "en-US"))
    }

    @Test fun readsWhereItsSetAndItsLettering() {
        val i = Details.parse(film, true, "US", "en-US")
        assertEquals(listOf("lighthouse", "radio", "sea"), i.keywords)
        // English before wordless, the best liked of them, and never a drawing the TV can't show.
        assertEquals("/en-best.png", i.logo)
        assertEquals("/fr.png", Details.parse(film, true, "FR", "fr-FR").logo)
        assertEquals("/plain.png", Details.logo(film.getJSONObject("images").getJSONArray("logos").let { a ->
            org.json.JSONArray((0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("iso_639_1") !in setOf("en", "fr") })
        }, "en-US"))
        assertNull(Details.parse(show, false, "US", "en-US").logo)
        assertEquals(Places.WATER, Places.story(i.keywords))
    }

    @Test fun whatsFetchedIsKeptForNextTime() {
        assertNull(Details.cached(ctx, "title:m77"))
        Details.keep(ctx, Details.parse(film, true, "US", "en-US"))
        Details.keep(ctx, Details.parse(show, false, "US", "en-US"))
        val i = Details.cached(ctx, "title:m77")!!
        assertEquals("The Long Quiet", i.title)
        assertEquals(listOf("Drama", "Mystery"), i.genres)
        assertEquals("final", i.trailer)
        assertEquals(148, i.minutes)
        assertEquals(listOf("lighthouse", "radio", "sea"), i.keywords)
        assertEquals("/en-best.png", i.logo)
        assertNull(Details.cached(ctx, "title:t12")!!.trailer)
        Details.forget(ctx)
        assertNull(Details.cached(ctx, "title:m77"))
    }

    private fun card(hideId: String?) = Card("t-title:m77", Kind.TITLE, "com.netflix.ninja", "New", "The Long Quiet", "Netflix", Color.Red, hideId = hideId)

    @Test fun thePageOffersOnlyWhatItCanDo() {
        val withTrailer = Details.parse(film, true, "US", "en-US")
        val without = Details.parse(show, false, "US", "en-US")
        assertEquals(listOf(DetailAct.PLAY, DetailAct.TRAILER, DetailAct.LIKED, DetailAct.DISLIKED, DetailAct.HIDE), Detail.acts(card("title:m77"), withTrailer, youtube = true))
        assertEquals(listOf(DetailAct.PLAY, DetailAct.LIKED, DetailAct.DISLIKED, DetailAct.HIDE), Detail.acts(card("title:m77"), withTrailer, youtube = false))
        // A card that isn't a title FireSky offered can only be hidden.
        assertEquals(listOf(DetailAct.PLAY, DetailAct.HIDE), Detail.acts(card("watch:x:1"), without, youtube = true))
        assertEquals(listOf(DetailAct.PLAY), Detail.acts(card(null), without, youtube = true))
    }

    @Test fun movingAlongTheButtonsStopsAtTheEnds() {
        val d = Detail(card("x"), Details.parse(film, true, "US", "en-US"), listOf(DetailAct.PLAY, DetailAct.TRAILER))
        assertEquals(1, d.at(1).index)
        assertEquals(1, d.at(2).index)
        assertEquals(0, d.at(-1).index)
    }
}

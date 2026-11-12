package com.firesky.launcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.pow

/**
 * Picks for the row, each with its reason. From what's been watched on the TV ([Taste]) and what
 * TMDB knows about each title ([Facts]): finishing a title counts for everything it shares with
 * others, watching it twice counts double, giving up early or hiding a pick counts against. Picks
 * come from TMDB's recommendations after what you liked, the rest of series of films you've
 * started, films that suit the weather, and the day's New and Trending list, and only from apps on
 * the TV. What you watched stays here: TMDB is asked about titles by name, and nothing else is sent.
 */
object Picks {
    private const val TAG = "FireSkyPicks"
    private const val MAX_AGE_MS = 20 * 3_600_000L
    /** Watched titles looked up on TMDB in one go; a big library is found over a few days. */
    private const val MATCHES = 40
    /** Watched titles whose facts are asked for in one go. */
    private const val WATCHED_FACTS = 50
    /** A genre is in so much that it only ever nudges: at most this much, however much of it you watch. */
    private const val GENRE_MAX = .7f
    /** How much each kind of thread counts. People count more than what a film is about. */
    val ROLE = mapOf("director" to 1.5f, "creator" to 1.5f, "dp" to 1.4f, "writer" to 1.4f, "music" to 1.3f, "actor" to 1.2f, "kw" to 1f, "genre" to .35f)
    private val CREW = setOf("dp", "music", "writer")
    /** TMDB keywords so common they say nothing about taste: never a reason, and not counted. */
    val GENERIC = setOf(
        "based on novel or book", "based on comic", "based on manga", "based on young adult novel", "based on true story",
        "based on short story", "based on play or musical", "based on video game", "based on tv series", "based on memoir or autobiography",
        "sequel", "prequel", "remake", "reboot", "duringcreditsstinger", "aftercreditsstinger", "woman director", "live action remake",
        "anime", "adult animation", "lgbt", "female protagonist", "male protagonist", "teenage protagonist", "miniseries", "biography",
        "new love", "friendship", "family", "love", "murder", "death", "revenge", "father son relationship", "mother daughter relationship",
        "father daughter relationship", "mother son relationship", "husband wife relationship", "brother brother relationship",
        "sister sister relationship", "brother sister relationship", "duringcreditstinger", "short film", "independent film", "3d",
    )
    private fun counts(t: Facts.Tag) = !(t.role == "kw" && t.name.lowercase() in GENERIC)
    private const val HALF_LIFE_MS = 365 * 86_400_000.0

    /** A title the row could show. */
    class Cand(
        val key: String,
        val title: String,
        val pkg: String,
        val backdrop: String,
        val link: String?,
        val tags: List<Facts.Tag>,
        val vote: Double,
        val votes: Int,
        /** On the day's New or Trending list: "new", "trending", or "". */
        val feed: String = "",
        /** In a series of films you've started: how many of it you've seen, how many there are, which this is, and the first seen. */
        val partSeen: Int = 0,
        val partOf: Int = 0,
        val part: Int = 0,
        val firstSeen: String = "",
        /** The weather it suits: "storm", "snow" or "night". */
        val skies: Set<String> = emptySet(),
        /** What you liked that TMDB recommends it after. */
        val recFrom: List<String> = emptyList(),
    ) {
        val movie get() = key.startsWith("title:m")
        val id get() = key.substringAfter("title:").drop(1).toInt()
        fun item() = Tmdb.Item(id, movie, title, feed == "new", pkg, backdrop, 0.0, link)
    }

    /** A title this one was learned from, and how to say so. */
    class From(val title: String, val role: String, val tail: String, val good: Boolean)

    /** Something titles share, and how much it counts for or against. */
    class Weight(val id: String, val name: String, val role: String, var w: Float, val from: MutableList<From>)

    class Pool(val profile: Map<String, Weight>, val cands: List<Cand>, val at: Long) {
        val empty get() = profile.isEmpty() || cands.isEmpty()
    }

    class Pick(val cand: Cand, val tag: String, val why: String)

    class Thread(val tag: Facts.Tag, val weight: Weight) {
        val w get() = weight.w
    }

    // ---------- What's been learned ----------

    /** One watched title, how it went, and when it was last watched. */
    class Watched(val info: Facts.Info, val verdict: Taste.Verdict, val series: Boolean, val at: Long, val hidden: Boolean = false)

    private fun tail(v: Watched) = when {
        v.hidden -> ", which you hid"
        v.verdict == Taste.Verdict.LEFT -> ", which you left early"
        v.verdict == Taste.Verdict.TWICE -> if (v.series) ", which you've watched a lot of" else ", which you watched twice"
        v.series -> ", which you've been watching"
        else -> ", which you finished"
    }

    fun profile(watched: List<Watched>, now: Long): Map<String, Weight> {
        val m = LinkedHashMap<String, Weight>()
        for (v in watched) {
            val base = if (v.hidden) -.5f else v.verdict.weight
            if (base == 0f) continue
            val w = base * .5.pow((now - v.at).coerceAtLeast(0) / HALF_LIFE_MS).toFloat()
            for (t in v.info.tags.distinctBy { it.id }.filter(::counts)) {
                val e = m.getOrPut(t.id) { Weight(t.id, t.name, t.role, 0f, ArrayList()) }
                e.w += w * (ROLE[t.role] ?: 1f)
                e.from += From(v.info.title, t.role, tail(v), base > 0)
            }
        }
        return m
    }

    fun threads(c: Cand, profile: Map<String, Weight>): List<Thread> =
        c.tags.distinctBy { it.id }.filter(::counts).mapNotNull { t -> profile[t.id]?.takeIf { kotlin.math.abs(it.w) > .01f }?.let { Thread(t, it) } }
            .sortedByDescending { kotlin.math.abs(it.w) }

    fun score(c: Cand, th: List<Thread>): Float =
        th.sumOf { (if (it.tag.role == "genre") it.w.coerceIn(-GENRE_MAX, GENRE_MAX) else it.w).toDouble() }.toFloat() +
            1.2f * minOf(3, c.recFrom.size) + if (c.votes >= 300) ((c.vote - 6.8) * .4).toFloat() else 0f

    /**
     * Enough to stand on: someone it shares with what you liked, or two things it's about, or TMDB
     * recommending it after something you liked. One shared keyword alone is chance.
     */
    fun solid(c: Cand, th: List<Thread>): Boolean {
        val good = th.filter { it.w >= .6f && it.tag.role != "genre" }
        return good.any { it.tag.role != "kw" } || good.size >= 2 || (good.isNotEmpty() && c.recFrom.isNotEmpty())
    }

    /** The thread to give as the reason: the strongest one for it, and a genre only if there's nothing better. */
    fun reason(th: List<Thread>): Thread? = th.firstOrNull { it.w > 0 && it.tag.role != "genre" } ?: th.firstOrNull { it.w > 0 }

    private fun names(l: List<String>) = if (l.size <= 1) l.joinToString() else l.dropLast(1).joinToString(", ") + " and " + l.last()
    private val PREFIX = mapOf("director" to "Directed by", "dp" to "Shot by", "writer" to "Written by", "music" to "Score by", "creator" to "From")
    private val NOUN = mapOf("director" to "director of", "dp" to "cinematographer of", "writer" to "writer of", "music" to "composer of", "creator" to "creator of", "actor" to "from")

    /** One thread, said the way a person would. */
    fun say(th: Thread): String {
        val good = th.weight.from.filter { it.good == (th.w > 0) }.distinctBy { it.title }
        val list = good.take(2).map { it.title }
        val tail = if (good.size == 1) good[0].tail else ""
        val t = th.tag
        return when {
            t.role == "kw" || t.role == "genre" -> "${t.name.replaceFirstChar { it.uppercase() }}, like ${names(list)}$tail"
            t.role == "actor" -> "${t.name}, from ${names(list)}$tail"
            good.all { it.role == t.role } -> "${PREFIX[t.role]} ${t.name}, as ${if (good.size == 1) "was" else "were"} ${names(list)}$tail"
            else -> "${PREFIX[t.role]} ${t.name}, ${NOUN[good.first().role]} ${names(list)}$tail"
        }
    }

    private val WORD = listOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
    private fun word(n: Int) = WORD.getOrElse(n) { n.toString() }

    fun partWhy(c: Cand): String = when {
        c.partSeen == 1 -> "You've seen ${c.firstSeen}. This is part ${word(c.part)}"
        c.partSeen == c.partOf - 1 -> "You've seen ${word(c.partSeen)} of the ${word(c.partOf)}"
        else -> "You've seen ${word(c.partSeen)} of them"
    }

    // ---------- The weather ----------

    /** Keywords that make a film suit the sky outside. */
    val SKIES = mapOf(
        "storm" to listOf("storm", "thunderstorm", "hurricane", "rain", "tornado"),
        "snow" to listOf("snow", "blizzard", "snowstorm", "winter"),
        "night" to listOf("outer space", "space travel", "astronaut", "space mission"),
    )
    private val SKY_TAG = mapOf("storm" to "For the storm", "rain" to "For the rain", "snow" to "For the snow", "night" to "For a clear night")
    private val SKY_WHY = mapOf(
        "storm" to "A storm outside, and one in it", "rain" to "Rain outside, and rain in it",
        "snow" to "Snow outside, and snow in it", "night" to "Stars out tonight, and up there in it",
    )

    /** The sky outside, as picks see it: "storm", "rain", "snow", "night", or null when it has no story. */
    fun sky(kind: Int, storm: Boolean, night: Boolean): String? = when {
        storm -> "storm"
        kind == Sky.RAIN -> "rain"
        kind == Sky.SNOW -> "snow"
        kind == Sky.CLEAR && night -> "night"
        else -> null
    }

    /**
     * Where to go next in a series of films, by its place in [all]: the first not seen after the
     * furthest one seen, or the first not seen at all. Only ever one, so a trilogy is one card.
     */
    internal fun nextPart(all: List<Facts.Part>, seen: Set<String>): Int? {
        val last = all.indexOfLast { it.key in seen }
        if (last < 0) return null
        return (last + 1 until all.size).firstOrNull { all[it].key !in seen } ?: all.indices.firstOrNull { all[it].key !in seen }
    }

    /** Whether [key], one of the films in [all], is where to start them or one of the [next] you're on to. */
    internal fun inOrder(key: String, all: List<Facts.Part>, next: Set<String>) = key in next || all.isEmpty() || all.first().key == key

    private fun skiesOf(tags: List<Facts.Tag>): Set<String> {
        val kws = tags.filter { it.role == "kw" }.map { it.name.lowercase() }.toSet()
        return SKIES.filterValues { l -> l.any { it in kws } }.keys
    }

    // ---------- The row ----------

    private class Scored(val c: Cand, val th: List<Thread>, val s: Float)

    /**
     * Up to [room] picks for the row, in order. The rest of a series of films you've started comes
     * first, then the best few by taste, one through a person behind the camera, one for the
     * weather, a wild card that changes each [week], and the best of the day's New and Trending.
     */
    fun row(pool: Pool, sky: String?, room: Int, hidden: Set<String>, ok: (Cand) -> Boolean, week: Int): List<Pick> {
        if (pool.empty || room <= 0) return emptyList()
        val scored = pool.cands.filter { it.key !in hidden && ok(it) }.map { c -> threads(c, pool.profile).let { Scored(c, it, score(c, it)) } }
        val out = ArrayList<Pick>()
        val used = HashSet<String>()
        val said = HashSet<String>()
        fun add(c: Cand, tag: String, why: String) { if (out.size < room && used.add(c.key)) out += Pick(c, tag, why) }
        // The best reason not yet given in the row: the same one twice reads like a list, not a choice.
        // A strong reason that others share counts for a little less, so each pick can have its own.
        val share = HashMap<String, Int>()
        fun fresh(x: Scored): Thread? = x.th.filter { it.w >= .6f && it.tag.role != "genre" && it.tag.id !in said }
            .maxByOrNull { it.w / kotlin.math.sqrt((share[it.tag.id] ?: 1).toFloat()) }
        // A keyword learned from one title is a thin reason; when TMDB recommends the pick after
        // something you watched, that says more.
        fun after(x: Scored) = x.c.recFrom.firstOrNull()?.let { "rf:$it" }?.takeIf { it !in said }
        fun because(x: Scored): String? {
            val t = fresh(x)
            val thin = t != null && t.tag.role == "kw" && t.weight.from.count { it.good } <= 1
            val a = after(x)
            if ((t == null || thin) && a != null) { said += a; return "Often watched after ${names(x.c.recFrom.take(2))}" }
            return t?.let { said += it.tag.id; say(it) }
        }
        fun liked(x: Scored) { because(x)?.let { add(x.c, "Because you liked", it) } }
        // A reason worth giving: something real it shares, not just a genre or a whisker of weight.
        val ranked = scored.filter { x -> x.s > .5f && solid(x.c, x.th) }.sortedByDescending { it.s }
        ranked.forEach { x -> x.th.forEach { share[it.tag.id] = (share[it.tag.id] ?: 0) + 1 } }

        scored.filter { it.c.partSeen > 0 }.sortedBy { it.c.part }.forEach { add(it.c, "Unfinished business", partWhy(it.c)) }
        // The one for the weather is set aside first, so a taste pick doesn't take it.
        val skyPick = sky?.let { k ->
            val want = if (k == "rain") "storm" else k
            (ranked + scored.sortedByDescending { it.s }).firstOrNull { it.c.key !in used && want in it.c.skies && it.s > -.5f }
        }
        val free = { x: Scored -> x.c.key !in used && x !== skyPick && x.c.key != skyPick?.c?.key }
        ranked.firstOrNull(free)?.let(::liked)
        val crew = { t: Thread -> t.w > 0 && t.tag.role in CREW && t.tag.id !in said }
        ranked.firstOrNull { free(it) && it.th.any(crew) }?.let { x -> x.th.first(crew).let { said += it.tag.id; add(x.c, "Thread · ${it.tag.name}", say(it)) } }
        if (sky != null && skyPick != null) {
            add(skyPick.c, SKY_TAG.getValue(sky), SKY_WHY.getValue(sky) + (reason(skyPick.th)?.takeIf { it.tag.role != "genre" }?.let { ". " + say(it) } ?: ""))
        }
        ranked.firstOrNull { free(it) && (fresh(it) != null || after(it) != null) }?.let(::liked)
        wild(scored, ranked, week, used)?.let { (x, t) -> add(x.c, "Wild card", "Far from your usual. ${say(t)}") }
        ranked.filter { it.c.feed.isNotEmpty() && it.c.key !in used }.maxByOrNull { it.s }?.let {
            add(it.c, if (it.c.feed == "new") "New · your kind" else "Trending · your kind",
                (if (it.c.feed == "new") "New this month. " else "Popular this week. ") + say(fresh(it) ?: reason(it.th)!!))
        }
        // Then more, each with a reason of its own where there is one, and the same ones again only after.
        ranked.forEach { if (it.c.key !in used) liked(it) }
        ranked.forEach { if (it.c.key !in used) add(it.c, "Because you liked", say(reason(it.th)!!)) }
        return out
    }

    /**
     * Once a week, something well liked that isn't your usual kind of thing, joined to what you
     * watch by one person. The same one all week.
     */
    private fun wild(scored: List<Scored>, ranked: List<Scored>, week: Int, used: Set<String>): Pair<Scored, Thread>? {
        val usual = ranked.take(6).map { it.s }.minOrNull() ?: return null
        return scored.filter { x -> x.c.key !in used && x.c.vote >= 7.3 && x.c.votes >= 500 && x.s < usual * .6f }
            .mapNotNull { x -> x.th.firstOrNull { it.w >= .8f && it.tag.role !in setOf("kw", "genre") }?.let { x to it } }
            .minByOrNull { (x, _) -> (x.c.key + week).hashCode() }
    }

    // ---------- Gathering, once a day ----------

    private fun file(ctx: Context) = File(ctx.filesDir, "picks.json")
    @Volatile private var held: Pool? = null

    /** Bumped when what's kept changes shape, so an older pool is made again rather than misread. */
    private const val VERSION = 2

    fun cached(ctx: Context): Pool? = held ?: runCatching {
        JSONObject(file(ctx).readText()).takeIf { it.optInt("v") == VERSION }?.let(::fromJson)
    }.getOrNull()?.also { held = it }

    fun stale(ctx: Context) = cached(ctx) == null || System.currentTimeMillis() - file(ctx).lastModified() > MAX_AGE_MS

    fun forget(ctx: Context) { held = null; file(ctx).delete() }

    /** Back to the picks as kept, after trying out a pretend history. */
    fun drop() { held = null }

    /**
     * Learns from what's been watched and gathers what the row could show. Off the main thread: it
     * asks TMDB about each title once, then keeps the answers for two weeks.
     */
    fun refresh(ctx: Context, feed: List<Tmdb.Item>, have: Set<String>, use: Map<String, Double>, hidden: Set<String>, pretend: List<Taste.Seen>? = null): Pool? {
        if (!Tmdb.on) return null
        val now = System.currentTimeMillis()
        // What's been watched, found on TMDB. A [pretend] history, for trying picks out, is kept nowhere.
        try { return gather(ctx, feed, have, use, hidden, pretend, now) } finally { Facts.flush(ctx) }
    }

    private fun gather(ctx: Context, feed: List<Tmdb.Item>, have: Set<String>, use: Map<String, Double>, hidden: Set<String>, pretend: List<Taste.Seen>?, now: Long): Pool {
        if (pretend == null) Taste.seed(ctx)
        val seen = (pretend ?: Taste.all(ctx)).sortedByDescending { it.lastAt }
        // Each title is found once. Not found, it's tried again in a week; TMDB out of reach, next time.
        // Netflix's list names films and shows alike; elsewhere a film is told by its length.
        var asks = MATCHES
        for (s in seen) if (s.key.isNullOrEmpty()) {
            val kind = if (s.series) true else if (s.pkg == "com.netflix.ninja") null else false
            val k = Facts.known(ctx, s.title, kind) ?: (if (asks-- > 0) Facts.match(ctx, s.title, kind) else null)
            if (k.isNullOrEmpty()) continue
            s.key = k
            if (pretend == null) Taste.found(ctx, s, k)
        }
        // What each is, as kept, and asked for again once it's two weeks old, a few dozen a day.
        var facts = WATCHED_FACTS
        fun about(k: String) = Facts.cached(ctx, k)?.takeIf { now - it.at < 14 * 86_400_000L }
            ?: (if (facts-- > 0) Facts.get(ctx, k) else Facts.cached(ctx, k))
        val watched = seen.mapNotNull { s ->
            val v = Taste.verdict(s, now)
            s.key?.takeIf { it.isNotEmpty() }?.let(::about)?.let { Watched(it, v, s.series || !it.movie, s.lastAt) }
        } + hidden.filter { it.startsWith("title:") }.mapNotNull { k -> about(k)?.let { Watched(it, Taste.Verdict.LEFT, !it.movie, now, hidden = true) } }
        val profile = profile(watched, now)
        if (profile.isEmpty()) { Log.i(TAG, "nothing learned yet from ${seen.size} titles"); return Pool(profile, emptyList(), now) }
        val seenKeys = watched.map { it.info.key }.toSet()
        val seenNames = watched.mapTo(HashSet()) { Facts.norm(it.info.title) } + seen.map { Facts.norm(it.title) }

        // Where picks could come from.
        val liked = watched.filter { !it.hidden && it.verdict.weight > 0 }.sortedByDescending { it.verdict.weight * .5.pow((now - it.at) / HALF_LIFE_MS) }
        val recFrom = HashMap<String, MutableList<String>>()
        liked.take(10).forEach { v -> v.info.recs.forEach { recFrom.getOrPut(it) { ArrayList() } += v.info.title } }
        val fromRecs = recFrom.entries.sortedByDescending { it.value.size }.map { it.key }
        val parts = HashMap<String, Triple<Int, Int, Pair<Int, String>>>()
        for (v in liked) if (v.info.movie && v.info.collection > 0) {
            val all = Facts.collection(ctx, v.info.collection)
            val seenHere = all.filter { it.key in seenKeys }
            nextPart(all, seenKeys)?.let { i -> parts[all[i].key] = Triple(seenHere.size, all.size, (i + 1) to seenHere.first().title) }
        }
        val providers = have.mapNotNull(Tmdb::providerOf)
        val skyKeys = HashMap<String, MutableSet<String>>()
        for ((sky, words) in SKIES) {
            val ids = words.mapNotNull { Facts.keyword(ctx, it) }
            Facts.about(ctx, ids, providers).forEach { skyKeys.getOrPut(it) { HashSet() } += sky }
        }
        val feedTop = feed.filter { it.pkg in have }.groupBy { it.pkg }.values.flatMap { l -> l.sortedByDescending { it.popularity }.take(5) }
        val feedOf = feedTop.associateBy { it.key }

        // Asked about at most this many new titles a day; the rest wait for tomorrow.
        var budget = 70
        val wanted = (parts.keys + fromRecs.take(40) + skyKeys.keys + feedTop.map { it.key }).distinct().filter { it !in seenKeys }
        val cands = ArrayList<Cand>()
        for (k in wanted) {
            val info = Facts.cached(ctx, k)?.takeIf { now - it.at < 14 * 86_400_000L }
                ?: (if (budget-- > 0) Facts.get(ctx, k) else Facts.cached(ctx, k)) ?: continue
            // From a series of films, only where you'd start it or the next you haven't seen.
            if (info.movie && info.collection > 0 && !inOrder(k, Facts.collection(ctx, info.collection), parts.keys)) continue
            // Another film by the name of one you've seen reads as that one again.
            if (Facts.norm(info.title) in seenNames) continue
            val f = feedOf[k]
            val pkg = f?.pkg ?: info.pkgs.filter { it in have }.maxByOrNull { use[it] ?: 0.0 } ?: continue
            val art = info.backdrop ?: f?.backdrop ?: continue
            val part = parts[k]
            cands += Cand(
                k, info.title, pkg, art, f?.link, info.tags, info.vote, info.votes,
                feed = if (f == null) "" else if (f.isNew) "new" else "trending",
                partSeen = part?.first ?: 0, partOf = part?.second ?: 0, part = part?.third?.first ?: 0, firstSeen = part?.third?.second.orEmpty(),
                skies = skiesOf(info.tags) + skyKeys[k].orEmpty(),
                recFrom = recFrom[k].orEmpty(),
            )
        }
        // Direct links into the apps, for the ones likely to be shown.
        val pool0 = Pool(profile, cands, now)
        val likely = row(pool0, null, 16, hidden, { true }, 0).map { it.cand } + cands.filter { it.skies.isNotEmpty() }.take(6)
        val links = runCatching { Tmdb.linksFor(likely.filter { it.link == null }.distinctBy { it.key }.map { it.item() }) }.getOrDefault(emptyMap())
        val pool = Pool(profile, cands.map { c -> links[c.key]?.let { l -> Cand(c.key, c.title, c.pkg, c.backdrop, l, c.tags, c.vote, c.votes, c.feed, c.partSeen, c.partOf, c.part, c.firstSeen, c.skies, c.recFrom) } ?: c }, now)
        if (pretend == null) runCatching {
            val tmp = File(file(ctx).path + ".part")
            tmp.writeText(toJson(pool).toString())
            tmp.renameTo(file(ctx))
        }
        held = pool
        Log.i(TAG, "learned ${profile.size} threads from ${watched.size} titles; ${cands.size} to pick from, ${budget.coerceAtLeast(0)} lookups to spare")
        return pool
    }

    // ---------- Kept on disk ----------

    private fun tagsJson(l: List<Facts.Tag>) = JSONArray().apply { l.forEach { put(JSONArray().put(it.role).put(it.name)) } }
    private fun tagsOf(a: JSONArray?) = List(a?.length() ?: 0) { a!!.getJSONArray(it).let { t -> Facts.Tag(t.getString(0), t.getString(1)) } }

    internal fun toJson(p: Pool) = JSONObject().put("v", VERSION).put("at", p.at)
        .put("profile", JSONArray().apply {
            p.profile.values.forEach { w ->
                put(JSONObject().put("id", w.id).put("n", w.name).put("r", w.role).put("w", w.w.toDouble())
                    .put("from", JSONArray().apply { w.from.forEach { put(JSONArray().put(it.title).put(it.role).put(it.tail).put(it.good)) } }))
            }
        })
        .put("cands", JSONArray().apply {
            p.cands.forEach { c ->
                put(JSONObject().put("k", c.key).put("t", c.title).put("p", c.pkg).put("b", c.backdrop).put("l", c.link ?: "")
                    .put("g", tagsJson(c.tags)).put("v", c.vote).put("n", c.votes).put("f", c.feed)
                    .put("ps", c.partSeen).put("po", c.partOf).put("pt", c.part).put("fs", c.firstSeen).put("s", JSONArray(c.skies.toList()))
                    .put("rf", JSONArray(c.recFrom)))
            }
        })

    internal fun fromJson(o: JSONObject): Pool {
        val pa = o.getJSONArray("profile")
        val profile = LinkedHashMap<String, Weight>()
        for (n in 0 until pa.length()) {
            val w = pa.getJSONObject(n)
            val f = w.getJSONArray("from")
            profile[w.getString("id")] = Weight(w.getString("id"), w.getString("n"), w.getString("r"), w.getDouble("w").toFloat(),
                MutableList(f.length()) { f.getJSONArray(it).let { a -> From(a.getString(0), a.getString(1), a.getString(2), a.getBoolean(3)) } })
        }
        val ca = o.getJSONArray("cands")
        val cands = List(ca.length()) {
            val c = ca.getJSONObject(it)
            val s = c.optJSONArray("s")
            Cand(c.getString("k"), c.getString("t"), c.getString("p"), c.getString("b"), c.optString("l").ifEmpty { null },
                tagsOf(c.getJSONArray("g")), c.optDouble("v"), c.optInt("n"), c.optString("f"),
                c.optInt("ps"), c.optInt("po"), c.optInt("pt"), c.optString("fs"), List(s?.length() ?: 0) { i -> s!!.getString(i) }.toSet(),
                c.optJSONArray("rf")?.let { a -> List(a.length()) { i -> a.getString(i) } }.orEmpty())
        }
        return Pool(profile, cands, o.optLong("at"))
    }
}

package com.firesky.launcher

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class Kind { APP, RESUME, KODI, USUAL, TITLE, PS5, SETTINGS, ALL, WEATHER, SETUP, UPDATE }

/**
 * Anything you can select: a dock tile, a Tonight card or the weather. The hero shows its label, title and meta.
 * Two cards that say the same thing are equal, so a row that hasn't changed isn't drawn again.
 */
data class Card(
    val key: String,
    val kind: Kind,
    val pkg: String?,
    val label: String,
    val title: String,
    val meta: String,
    val hue: Color,
    val progress: Float? = null,
    val on: Boolean = false,
    val port: Int = 0,
    /** Which tile this is in the dock, or null for a Tonight card. */
    val dockId: String? = null,
    /** What to remember when this card is hidden. */
    val hideId: String? = null,
    val link: String? = null,
    /** A second, quieter line under the meta: what's left of the season, what's coming. */
    val sub: String = "",
    /** Short points shown under the title in place of the meta: what an update brings. */
    val notes: List<String> = emptyList(),
)

enum class MenuAct(val label: String) {
    MOVE("Move"), HIDE("Hide"), HIDE_CARD("Hide this"), RESET("Reset the dock"), AMAZON("Amazon home"), FIRESKY("FireSky settings"),
    ADD_DOCK("Add to dock"), UNDOCK("Remove from dock"), HIDE_APP("Hide from All apps"), DETAILS("Details")
}

class TileMenu(val card: Card, val items: List<MenuAct>)

/** Where the clock and weather sit on screen. */
const val STATUS = "status"

/** Where trailers play. */
const val YOUTUBE = "com.amazon.firetv.youtube"

private val PS5_HUE = Color(0xFF1F58D6)
private val GREY = Color(0xFF4A5060)
private val SKY = Color(0xFF3A6EA5)

class HomeState {
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
    var lastUsed by mutableStateOf<Map<String, Usage.LastUse>>(emptyMap())
    var resume by mutableStateOf<Resume.Info?>(null)
    var usual by mutableStateOf<Usage.Usual?>(null)
    var kodi by mutableStateOf<Kodi.Item?>(null)
    /** What apps say you're partway through, by name: one per app. */
    var watching by mutableStateOf<List<Watching.Item>>(emptyList())
    /** What TMDB knows about each show being watched, by title. */
    var shows by mutableStateOf<Map<String, Shows.Info>>(emptyMap())
    var hdmi by mutableStateOf<List<Hdmi.Dev>>(emptyList())
    var wx by mutableStateOf<Weather.Wx?>(null)
    var idle by mutableStateOf(false)
    var allApps by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)
    var homeTick by mutableIntStateOf(0)
    @Volatile var lastInput = SystemClock.uptimeMillis()
    /** Whether the home screen is what's on the TV. The idle timer only counts while it is. */
    @Volatile var shown = false
    /** Counts each return to the home screen, so the clock and anything timed are right the moment you're back. */
    var backTick by mutableIntStateOf(0)
    /** Until then OK is ignored: an app is opening, and a second press would open it again. */
    @Volatile var openingUntil = 0L

    var panel by mutableStateOf(false)
    /** The day's brief, while it's up in place of the cards on the first wake of the day. */
    var brief by mutableStateOf<Brief.Text?>(null)
    /** A title's own page, when one is open. */
    var detail by mutableStateOf<Detail?>(null)
    /** What TMDB says about the titles on show, by [Details.key]. */
    var details by mutableStateOf<Map<String, Details.Info>>(emptyMap())
    /** FireSky's own settings, and the line selected in them. */
    var settings by mutableStateOf(false)
    var settingsIndex by mutableIntStateOf(0)
    /** Bumped when a setting changes, so whatever shows it is drawn again. */
    var settingsTick by mutableIntStateOf(0)
    /** The phone page's address while its QR code is up ("" if it couldn't start). */
    var phoneUrl by mutableStateOf<String?>(null)
    /** The steps for the one-time setup from a computer. */
    var guide by mutableStateOf(false)
    /** Whether Home opens FireSky. Fire TV allows it only after a one-time step from a computer. */
    var homeReady by mutableStateOf(true)
    /** A newer release, downloaded and waiting for you to say install. */
    var update by mutableStateOf<Updater.Ready?>(null)
    var menu by mutableStateOf<TileMenu?>(null)
    var menuIndex by mutableIntStateOf(0)
    /** The press that opened the menu is still down; its release mustn't pick anything. */
    var menuArmed = false
    var moving by mutableStateOf<String?>(null)
    var wakeTick by mutableIntStateOf(0)
    var focusKey by mutableStateOf<String?>(null)
    var focusTick by mutableIntStateOf(0)
    var liveOk by mutableStateOf(true)
    /** You've moved since coming home, so focus should stay on your choice as the row fills in. */
    var moved = false
    var live by mutableStateOf<LiveView?>(null)
    /** A new frame of the backdrop has reached the screen. */
    var drawn: () -> Unit = {}
    /** The wake-up rise has started drawing. */
    var risen: () -> Unit = {}
    val rects = HashMap<String, Rect>()

    var titles by mutableStateOf<List<Tmdb.Item>>(emptyList())
    var useMinutes by mutableStateOf<Map<String, Double>>(emptyMap())
    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    /** The colour each card's art gives the backdrop. */
    val hues = mutableStateMapOf<String, Color>()

    private var layout: Layout? = null
    var order by mutableStateOf<List<String>>(emptyList())
    var hidden by mutableStateOf<Set<String>>(emptySet())
    var hiddenCards by mutableStateOf<Set<String>>(emptySet())
    /** Apps you added to the dock from All apps, and apps you hid from All apps altogether. */
    var added by mutableStateOf<Set<String>>(emptySet())
    var hiddenApps by mutableStateOf<Set<String>>(emptySet())
    val edited get() = order.isNotEmpty() || hidden.isNotEmpty() || added.isNotEmpty()

    fun load(ctx: Context) {
        val l = Layout(ctx)
        layout = l
        order = l.order; hidden = l.hidden; hiddenCards = l.cards; added = l.added; hiddenApps = l.apps
    }

    /** What All apps lists: everything but what you've hidden. */
    val shownApps get() = apps.filter { it.pkg !in hiddenApps }

    /** An app's tile in All apps. Its key is its own, apart from the same app's dock tile. */
    fun allCard(a: AppEntry) = appCard(a).copy(key = "all-${a.pkg}")

    fun addToDock(pkg: String) {
        hidden = hidden - pkg; added = added + pkg
        layout?.hidden = hidden; layout?.added = added
        // In before the PlayStation, Settings and All apps tiles, which stay at the end.
        val ids = dock().mapNotNull { it.dockId }.filter { it != pkg }.toMutableList()
        val at = ids.indexOfFirst { it == "ps5" || it == "settings" || it == "all" }.let { if (it < 0) ids.size else it }
        ids.add(at, pkg)
        order = ids + order.filter { it !in ids }
        layout?.order = order
        toast = "${app(pkg)?.label ?: "It"} is in the dock"
    }

    fun hideApp(pkg: String) {
        hiddenApps = hiddenApps + pkg
        layout?.apps = hiddenApps
        toast = "${app(pkg)?.label ?: "It"} hidden. FireSky settings can show it again"
    }

    fun showHiddenApps() { hiddenApps = emptySet(); layout?.apps = hiddenApps }

    fun app(pkg: String?) = apps.firstOrNull { it.pkg == pkg }
    private val ps5 get() = hdmi.firstOrNull { it.name.contains("PlayStation", ignoreCase = true) }

    fun appCard(a: AppEntry) = Card("a-${a.pkg}", Kind.APP, a.pkg, "", a.label, Fmt.used(lastUsed[a.pkg]), a.hue, dockId = a.pkg)

    fun wxCard(): Card? = wx?.let { w ->
        Card("wx", Kind.WEATHER, null, "Now", w.condition, "Feels ${w.feels}° · Wind ${w.wind} ${Prefs.windUnit}", SKY)
    }

    /** The Tonight row, best guess first: a PS5 that is on, what you paused, what you usually open now, then what's new. */
    fun tonight(): List<Card> {
        settingsTick
        val out = mutableListOf<Card>()
        // Until Home opens FireSky, the first thing here says how.
        if (!homeReady) out += Card("t-setup", Kind.SETUP, null, "Finish setting up", "Finish setup", "Make the Home button open FireSky", SKY)
        // What it brings shows while the card is selected; a release without notes just says it's ready.
        update?.let {
            out += if (it.notes.isEmpty()) Card("t-update", Kind.UPDATE, null, "Update", "FireSky ${it.version}", "Ready to install · OK to update", SKY)
            else Card("t-update", Kind.UPDATE, null, "Update · OK to install", "FireSky ${it.version}", "", SKY, notes = it.notes)
        }
        ps5?.takeIf { it.on }?.let {
            out += Card("t-ps5", Kind.PS5, null, "HDMI ${it.port}", it.name, "On", PS5_HUE, on = true, port = it.port)
        }
        val r = resume
        val k = kodi
        // Kodi's own record knows the title; it replaces a bare Kodi resume.
        val kodiCard = k?.let { app(Kodi.PKG) }?.let { a ->
            Card("t-kodi", Kind.KODI, a.pkg, "Continue", k.title, k.left, hues["t-kodi"] ?: a.hue, progress = k.progress, hideId = "kodi:${k.path}")
        }
        val now = System.currentTimeMillis()
        val named = watching.mapNotNull { w ->
            app(w.pkg)?.let { a ->
                // When it ends if you carry on now, and, from TMDB, what's left of the season and what's coming.
                val left = if (w.durationMs > 0) w.durationMs - w.progressMs else 0L
                val ep = if (w.season != null && w.episode != null) "S${w.season} E${w.episode}" else null
                val meta = listOfNotNull(ep, if (left > 60_000) Shows.endsAt(now, left) else null).joinToString(" · ").ifEmpty { a.label }
                val info = shows[w.title]
                val name = info?.name?.ifBlank { null } ?: w.title
                val sub = if (info != null && w.season != null && w.episode != null) listOfNotNull(Shows.rest(info, w.season, w.episode), Shows.coming(info, w.season)).joinToString(" · ") else ""
                w to Card(w.key, Kind.RESUME, a.pkg, "Continue", name, meta, hues[w.key] ?: a.hue, progress = w.progress, hideId = "watch:${w.pkg}:${w.id}", sub = sub)
            }
        }
        // A new episode or season of something you watch, out in the last couple of days.
        val fresh = watching.mapNotNull { w ->
            val info = shows[w.title] ?: return@mapNotNull null
            val e = Shows.fresh(info, w.season, w.episode) ?: return@mapNotNull null
            app(w.pkg)?.let { a ->
                Card("t-new-${info.id}", Kind.TITLE, a.pkg, if (e.episode <= 1) "New season" else "New episode", info.name.ifBlank { w.title },
                    "S${e.season} E${e.episode} · out ${if (e.date == java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(now))) "today" else "now"}",
                    hues[w.key] ?: a.hue, hideId = "new:${info.id}:${e.season}:${e.episode}", link = w.page)
            }
        }
        // A bare resume only knows the app. The app's own named one replaces it, unless the bare one
        // is clearly newer, which means the app hasn't caught up yet.
        val resumeCard = r?.takeIf { !(it.pkg == Kodi.PKG && kodiCard != null) }
            ?.takeIf { named.none { (w, _) -> w.pkg == r.pkg && w.watchedAt > r.savedAt - CAUGHT_UP_MS } }
            ?.let { app(it.pkg) }?.let { a ->
                Card("t-resume", Kind.RESUME, a.pkg, "Resume", a.label, Fmt.position(r.positionMs), a.hue, progress = r.progress, hideId = "resume:${a.pkg}:${r.savedAt}")
            }
        // Whichever was watched most recently comes first.
        val going = listOfNotNull(kodiCard?.let { c -> k?.let { c to it.playedAt } }, resumeCard?.let { c -> r?.let { c to it.savedAt } }) +
            named.map { (w, c) -> c to w.watchedAt }
        out += going.sortedByDescending { it.second }.map { it.first }
        out += fresh
        usual?.let { u ->
            if (u.pkg != r?.pkg && going.none { it.first.pkg == u.pkg }) app(u.pkg)?.let { a ->
                out += Card("t-usual", Kind.USUAL, a.pkg, "Usually now", a.label, u.whenText, a.hue, hideId = "usual:${a.pkg}")
            }
        }
        val mine = out.filter { it.hideId == null || it.hideId !in hiddenCards }
        val room = MAX_CARDS - mine.size
        val picks = Tmdb.pick(titles.filter { app(it.pkg) != null && "t-${it.key}" in thumbs }, useMinutes, hiddenCards, room.coerceAtLeast(0))
        return mine + picks.map { t ->
            val a = app(t.pkg)!!
            Card("t-${t.key}", Kind.TITLE, t.pkg, if (t.isNew) "New" else "Trending", t.title, a.label, hues["t-${t.key}"] ?: a.hue, hideId = t.key, link = t.link)
        }
    }

    /** The dock stays in your order, so muscle memory keeps working. It never reorders itself. */
    fun dock(): List<Card> {
        settingsTick
        val cards = LinkedHashMap<String, Card>()
        (Apps.DOCK + added).distinct().filter { it !in hiddenApps }.mapNotNull { app(it) }.forEach { cards[it.pkg] = appCard(it) }
        // A PlayStation gets a tile when one is plugged in, or always, at the port you set.
        val p = ps5
        if (p != null || Prefs.ps5Port > 0) cards["ps5"] = Card(
            "d-ps5", Kind.PS5, null, "HDMI ${p?.port ?: Prefs.ps5Port}", p?.name ?: "PlayStation 5",
            if (p?.on == true) "On" else "Standby", PS5_HUE, on = p?.on == true, port = p?.port ?: Prefs.ps5Port, dockId = "ps5",
        )
        cards["settings"] = Card("d-settings", Kind.SETTINGS, null, "", "Settings", "Fire TV settings", GREY, dockId = "settings")
        cards["all"] = Card("d-all", Kind.ALL, null, "", "All apps", if (apps.isEmpty()) "" else "${shownApps.size} apps", GREY, dockId = "all")
        val ids = order.filter { it in cards } + cards.keys.filter { it !in order }
        return ids.filter { it !in hidden }.map(cards::getValue)
    }

    /** Where OK on a card goes: a named one's own link, worked out now because it expires. */
    fun linkFor(c: Card): String? = watching.firstOrNull { it.key == c.key }?.linkAt(System.currentTimeMillis()) ?: c.link

    /** Which title a card is about, for its page: a New or Trending card's own, or the show you're partway through. */
    fun detailKey(c: Card): String? = when {
        c.key.startsWith("t-title:") -> c.key.removePrefix("t-")
        c.key.startsWith("t-new-") -> c.key.removePrefix("t-new-").toIntOrNull()?.let { Details.key(false, it) }
        // Only a series: a film you're watching has no season, and its name alone could be some show's.
        else -> watching.firstOrNull { it.key == c.key && it.season != null }?.let { shows[it.title] }?.let { Details.key(false, it.id) }
    }

    /** The page for a card, if enough is known about its title to fill one. */
    fun detailOf(c: Card): Detail? = detailKey(c)?.let(details::get)?.let { Detail(c, it, Detail.acts(c, it, app(YOUTUBE) != null)) }

    fun openMenu(c: Card) {
        val items = when {
            c.key.startsWith("all-") -> listOf(if (dock().any { it.dockId == c.pkg }) MenuAct.UNDOCK else MenuAct.ADD_DOCK, MenuAct.HIDE_APP)
            c.dockId == "settings" -> listOf(MenuAct.FIRESKY, MenuAct.MOVE, MenuAct.AMAZON)
            c.dockId == "all" -> listOf(MenuAct.MOVE)
            c.dockId != null -> listOf(MenuAct.MOVE, MenuAct.HIDE)
            c.hideId != null -> listOfNotNull(MenuAct.HIDE_CARD, MenuAct.DETAILS.takeIf { detailOf(c) != null })
            else -> return
        } + if (c.dockId != null && edited && !c.key.startsWith("all-")) listOf(MenuAct.RESET) else emptyList()
        menuIndex = 0; menuArmed = false
        menu = TileMenu(c, items)
    }

    /** Moves a dock tile one place along. */
    fun shift(id: String, by: Int): Boolean {
        val ids = dock().mapNotNull { it.dockId }.toMutableList()
        val i = ids.indexOf(id); val j = i + by
        if (i < 0 || j !in ids.indices) return false
        ids[i] = ids[j]; ids[j] = id
        // Keep hidden tiles in the saved order, so unhiding puts them back near where they were.
        order = ids + order.filter { it !in ids }
        layout?.order = order
        return true
    }

    fun hide(c: Card, next: String?) {
        val id = c.dockId
        if (id != null) { hidden = hidden + id; layout?.hidden = hidden; toast = "${c.title} moved to All apps" }
        else c.hideId?.let { hiddenCards = hiddenCards + it; layout?.cards = hiddenCards; toast = "${c.title} hidden" }
        focus(next)
    }

    fun resetDock() {
        order = emptyList(); hidden = emptySet(); added = emptySet()
        layout?.order = order; layout?.hidden = hidden; layout?.added = added
        toast = "Dock reset"
    }

    fun focus(key: String?) { focusKey = key; focusTick++ }

    /** The tile that has focus right now, for the key handler. */
    var current: String? = null
    /** The tile focus has really reached. [current] changes first, a frame or two before focus follows. */
    var focusAt: String? = null
    /** The dock and the Tonight row as they're shown, so the key handler needn't work them out on every press. */
    var rows: List<List<Card>> = emptyList()

    fun home() {
        allApps = false
        idle = false
        panel = false
        detail = null
        menu = null
        moving = null
        moved = false
        lastInput = SystemClock.uptimeMillis()
        homeTick++
    }

    /** Back on screen from anywhere: never straight into the idle screen, whatever happened while away. */
    fun back() {
        backTick++
        shown = true
        idle = false
        lastInput = SystemClock.uptimeMillis()
    }

    fun opening() { openingUntil = SystemClock.uptimeMillis() + OPENING_MS }

    /** Nothing is open over the home screen. */
    val plain get() = !idle && !allApps && !panel && detail == null && menu == null && moving == null

    /** The first card: where Home and waking put you. */
    fun first(): String? = (tonight().firstOrNull() ?: dock().firstOrNull())?.key

    /** The tiles on screen in order, and the one selected: what a picture of the home screen shows. */
    fun shape(selected: String?): String =
        (if (wx != null) "wx," else "") + tonight().joinToString(",") { it.key } + "|" + dock().joinToString(",") { it.key } + ">" + selected

    fun idleDue(now: Long = SystemClock.uptimeMillis()) =
        shown && !idle && !allApps && !panel && detail == null && menu == null && moving == null && now - lastInput > IDLE_MS

    fun woke() {
        home()
        wakeTick++
        live?.dawn()
    }

    companion object {
        const val MAX_CARDS = 8
        const val IDLE_MS = 180_000L
        const val OPENING_MS = 3000L
        const val CAUGHT_UP_MS = 3_600_000L
    }
}

/** Your dock order and what you've hidden, kept across restarts. */
private class Layout(ctx: Context) {
    private val p = ctx.getSharedPreferences("dock", Context.MODE_PRIVATE)
    var order: List<String>
        get() = p.getString("order", "").orEmpty().split(",").filter { it.isNotEmpty() }
        set(v) { p.edit().putString("order", v.joinToString(",")).apply() }
    var hidden: Set<String>
        get() = p.getStringSet("hidden", emptySet()).orEmpty().toSet()
        set(v) { p.edit().putStringSet("hidden", v).apply() }
    var cards: Set<String>
        get() = p.getStringSet("cards", emptySet()).orEmpty().toSet()
        set(v) { p.edit().putStringSet("cards", v).apply() }
    var added: Set<String>
        get() = p.getStringSet("added", emptySet()).orEmpty().toSet()
        set(v) { p.edit().putStringSet("added", v).apply() }
    var apps: Set<String>
        get() = p.getStringSet("apps", emptySet()).orEmpty().toSet()
        set(v) { p.edit().putStringSet("apps", v).apply() }
}

object Fmt {
    fun position(ms: Long): String {
        val m = ms / 60_000
        return if (m < 60) "$m min in" else "${m / 60} h ${m % 60} min in"
    }

    /** Blank when the app has never really been used, rather than a guess. */
    fun used(l: Usage.LastUse?): String {
        if (l == null) return ""
        if (l.approx) return "Last used over a week ago"
        val now = System.currentTimeMillis()
        val d = now - l.at
        val days = ((midnight(now) - midnight(l.at)) / 86_400_000.0).roundToInt()
        val weeks = days / 7
        return "Last used " + when {
            d < 60_000 -> "just now"
            d < 3_600_000 -> "${d / 60_000} min ago"
            days == 0 || d < 12 * 3_600_000 -> "${d / 3_600_000} h ago"
            days == 1 -> "yesterday"
            days < 7 -> "on " + SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(l.at))
            days < 60 -> "$weeks ${if (weeks == 1) "week" else "weeks"} ago"
            else -> "${days / 30} months ago"
        }
    }

    private fun midnight(t: Long) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

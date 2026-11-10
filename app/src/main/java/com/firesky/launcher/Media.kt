package com.firesky.launcher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/** Where you paused, remembered even after the app's session goes away. */
object Resume {
    class Info(val pkg: String, val positionMs: Long, val durationMs: Long, val savedAt: Long) {
        val progress: Float? get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else null
    }

    private val SKIP = setOf("com.spotify.tv.android", "com.amazon.bueller.music")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("resume", Context.MODE_PRIVATE)

    /** Prime playback is reported by its bare player; resuming should open the Prime Video app. */
    private val ALIAS = mapOf("com.amazon.avod" to "com.amazon.firebat")

    fun alias(pkg: String) = ALIAS[pkg] ?: pkg

    /** Called, on the main thread, each time a position is saved. */
    @Volatile var changed: (() -> Unit)? = null

    /** Longest a player plausibly runs without reporting. Beyond this its old state is stale. */
    private const val MAX_GAP_MS = 4 * 3_600_000L

    private fun positionNow(st: PlaybackState): Long? {
        if (st.state != PlaybackState.STATE_PLAYING || st.lastPositionUpdateTime <= 0) return st.position
        val gap = SystemClock.elapsedRealtime() - st.lastPositionUpdateTime
        return if (gap > MAX_GAP_MS) null else st.position + (gap * st.playbackSpeed).toLong()
    }

    /**
     * Called each time a player reports a change. Players often only report when playback starts,
     * and Netflix's stop arrives with position 0, so a stop counts forward from [was], the last
     * report while playing.
     */
    fun save(ctx: Context, from: String, st: PlaybackState?, was: PlaybackState?, meta: MediaMetadata?) {
        val pkg = alias(from)
        if (st == null || pkg in SKIP) return
        if (pkg.startsWith("com.amazon.") && pkg != "com.amazon.firebat" && pkg != "com.amazon.firetv.youtube") return
        val pos = when (st.state) {
            PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED -> positionNow(st)
            PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE -> was?.let(::positionNow)
            else -> null
        } ?: return
        if (pos < 60_000) return
        val dur = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        prefs(ctx).edit()
            .putString("pkg", pkg).putLong("pos", pos).putLong("dur", dur)
            .putLong("at", System.currentTimeMillis())
            .apply()
        changed?.invoke()
    }

    /** Shown for 12 hours after you last watched, unless you were within 3 minutes of the end. */
    fun get(ctx: Context): Info? {
        val p = prefs(ctx)
        val pkg = p.getString("pkg", null) ?: return null
        val info = Info(pkg, p.getLong("pos", 0), p.getLong("dur", 0), p.getLong("at", 0))
        if (System.currentTimeMillis() - info.savedAt > 12 * 3_600_000L) return null
        if (info.durationMs > 0 && info.positionMs > info.durationMs - 180_000) return null
        return info
    }
}

/**
 * Runs in the background once allowed, so pausing Netflix and pressing Home is remembered even
 * though the home screen wasn't open at the time.
 */
class MediaListener : NotificationListenerService() {
    private var msm: MediaSessionManager? = null
    private val held = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    private val main = Handler(Looper.getMainLooper())
    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { attach(it ?: emptyList()) }

    /**
     * The TV waking up, if Home handling hasn't already caught it in the log. Screen-on reaches
     * apps one at a time over more than a second, so this asks to be near the front of the queue.
     */
    private val wake = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!Wake.claim()) return
            startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("wake", true))
        }
    }
    private var wakeOn = false

    override fun onListenerConnected() {
        if (!wakeOn) { registerReceiver(wake, IntentFilter(Intent.ACTION_SCREEN_ON).apply { priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1 }); wakeOn = true }
        val m = getSystemService(MediaSessionManager::class.java) ?: return
        msm = m
        val me = ComponentName(this, MediaListener::class.java)
        runCatching {
            m.addOnActiveSessionsChangedListener(changed, me, main)
            attach(m.getActiveSessions(me))
        }
    }

    override fun onListenerDisconnected() {
        if (wakeOn) { runCatching { unregisterReceiver(wake) }; wakeOn = false }
        runCatching { msm?.removeOnActiveSessionsChangedListener(changed) }
        detach()
    }

    private fun attach(list: List<MediaController>) {
        detach()
        for (c in list) {
            val cb = object : MediaController.Callback() {
                private var playing = c.playbackState?.takeIf { it.state == PlaybackState.STATE_PLAYING }
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    Resume.save(this@MediaListener, c.packageName, state, playing, c.metadata)
                    playing = state?.takeIf { it.state == PlaybackState.STATE_PLAYING }
                }
            }
            c.registerCallback(cb, main)
            held += c to cb
        }
    }

    private fun detach() {
        held.forEach { (c, cb) -> runCatching { c.unregisterCallback(cb) } }
        held.clear()
    }
}

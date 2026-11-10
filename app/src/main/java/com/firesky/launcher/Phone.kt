package com.firesky.launcher

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * A page on this TV for typing on a phone what's a chore on a remote: the TMDB key. It's served on
 * your home network only while its QR code is on screen, at an address with a random part, so only
 * whoever can see the TV can open it.
 */
class Phone(private val hasKey: () -> Boolean, private val onKey: (String?) -> Unit) {
    private val token = ByteArray(9).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var server: ServerSocket? = null

    /** Starts serving and returns the page's address, or null when the TV isn't on a network. */
    fun start(): String? {
        val ip = lanAddress() ?: return null
        val s = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        server = s
        thread(name = "phone-page", isDaemon = true) {
            while (!s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { c.use(::serve) }.onFailure { Log.w(TAG, "page: $it") } }
            }
        }
        return "http://$ip:${s.localPort}/$token"
    }

    fun stop() { runCatching { server?.close() }; server = null }

    private fun serve(c: Socket) {
        c.soTimeout = 15_000
        val input = BufferedInputStream(c.getInputStream())
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, path) = lines.first().split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        if (path.substringBefore('?') != "/$token") return reply(c, 404, page("Not found", "<p>This address has expired. Scan the code on the TV again.</p>"))
        if (method == "GET") return reply(c, 200, form(null))
        if (method != "POST") return reply(c, 405, page("FireSky", ""))
        val length = lines.firstOrNull { it.startsWith("content-length:", ignoreCase = true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = ByteArray(length.coerceIn(0, 4096)).also { b -> var n = 0; while (n < b.size) { val r = input.read(b, n, b.size - n); if (r < 0) break; n += r } }
        val fields = String(body).split('&').mapNotNull { kv ->
            kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { (k, v) -> k to URLDecoder.decode(v, "UTF-8").trim() }
        }.toMap()
        if (fields["remove"] != null) {
            onKey(null)
            return reply(c, 200, page("Done", "<p>The key is removed, and New and Trending are off.</p>"))
        }
        val key = fields["key"].orEmpty().filter { it.isLetterOrDigit() }
        if (key.isEmpty()) return reply(c, 200, form("Paste the key first."))
        if (!works(key)) return reply(c, 200, form("TMDB didn't accept that key. Copy the <b>API Key</b>, not the longer Read Access Token."))
        onKey(key)
        reply(c, 200, page("All set", "<p>New and Trending will appear on the TV in a minute. You can close this page.</p>"))
    }

    private fun readHead(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        var last = 0
        while (out.size() < 8192) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            last = (last shl 8) or b
            if (last == 0x0d0a0d0a) return out.toString("UTF-8")
        }
        return null
    }

    private fun reply(c: Socket, code: Int, html: String) {
        val body = html.toByteArray()
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Method Not Allowed" }
        c.getOutputStream().apply {
            write("HTTP/1.1 $code $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            write(body)
            flush()
        }
    }

    private fun form(error: String?) = page(
        "New and Trending",
        """
        <p>FireSky uses <a href="https://www.themoviedb.org">TMDB</a> to show what's new on your apps. It needs a free key:</p>
        <ol>
          <li><a href="https://www.themoviedb.org/signup">Make a free TMDB account</a> and confirm your email.</li>
          <li>Open <a href="https://www.themoviedb.org/settings/api">Settings &rsaquo; API</a>, choose <b>Create</b>, then <b>Developer</b>, and fill in the short form. Personal use is fine.</li>
          <li>Copy the <b>API Key</b> and paste it here.</li>
        </ol>
        ${error?.let { "<p class=err>$it</p>" } ?: ""}
        <form method=post>
          <input name=key autocomplete=off autocapitalize=off spellcheck=false placeholder="API Key" aria-label="TMDB API Key">
          <button>Save to the TV</button>
        </form>
        ${if (hasKey()) "<form method=post><input type=hidden name=remove value=1><button class=quiet>Remove the key</button></form>" else ""}
        """.trimIndent(),
    )

    private fun page(title: String, body: String) = """
        <!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
        <title>FireSky · $title</title>
        <style>
          :root{color-scheme:dark}
          body{margin:0;padding:28px 20px;font:17px/1.5 system-ui,-apple-system,sans-serif;background:#0b0e16;color:#eef1f7}
          main{max-width:460px;margin:auto}
          h1{font-size:15px;letter-spacing:.12em;text-transform:uppercase;color:#9aa6c0;margin:0 0 4px}
          h2{font-size:26px;margin:0 0 16px}
          a{color:#8fb6ff} ol{padding-left:20px} li{margin:6px 0}
          input{box-sizing:border-box;width:100%;font:inherit;padding:14px;border-radius:12px;border:1px solid #34405a;background:#151b29;color:inherit;margin:8px 0}
          button{width:100%;font:inherit;font-weight:600;padding:14px;border:0;border-radius:12px;background:#eef1f7;color:#0b0e16;margin-top:4px}
          button.quiet{background:none;color:#9aa6c0;font-weight:400}
          .err{color:#ffb3a8}
        </style></head><body><main><h1>FireSky</h1><h2>$title</h2>$body</main></body></html>
    """.trimIndent()

    /** Asks TMDB whether the key works, so a mistyped one is caught on the phone, not later on the TV. */
    private fun works(key: String): Boolean = runCatching {
        val u = URL("https://api.themoviedb.org/3/configuration?api_key=$key").openConnection() as HttpURLConnection
        u.connectTimeout = 8000; u.readTimeout = 8000
        try { u.responseCode == 200 } finally { u.disconnect() }
    }.getOrDefault(false)

    companion object {
        private const val TAG = "FireSkyPhone"

        /** The TV's address on the home network. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        /** A QR code for [text], one pixel per module: draw it scaled up, unfiltered. */
        fun qr(text: String): ImageBitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
            val px = IntArray(m.width * m.height) { i -> if (m[i % m.width, i / m.width]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
            return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}

package com.firesky.launcher

import android.content.Context
import android.media.tv.TvInputManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * What is plugged into the TV. Fire OS keeps the HDMI-CEC service away from apps, but the standard
 * TV-input list names each CEC device ("PlayStation 5") with an id like HDMI200004, whose first
 * digit after HDMI is the port. Whether a PlayStation is on comes from the PS5 itself.
 */
object Hdmi {
    class Dev(val name: String, val port: Int, val on: Boolean)

    private val CEC_ID = Regex("""/HDMI(\d)\d{3}\d+$""")

    fun devices(ctx: Context): List<Dev> = runCatching {
        val tim = ctx.getSystemService(TvInputManager::class.java) ?: return emptyList()
        tim.tvInputList.mapNotNull { info ->
            val port = CEC_ID.find(info.id)?.groupValues?.get(1)?.toInt() ?: return@mapNotNull null
            val name = info.loadLabel(ctx).toString()
            // With its tile turned off, a PlayStation isn't asked whether it's on.
            val on = if (name.contains("PlayStation", ignoreCase = true)) !Prefs.ps5Off && Ps5.isOn() else true
            Dev(name, port, on)
        }.distinctBy { it.port }
    }.onFailure { Log.w("FireSkyHdmi", "input list failed", it) }.getOrDefault(emptyList())
}

/**
 * Asks the PS5 over the network, using the discovery message Remote Play uses. It answers
 * "200 Ok" when on and "620 Server Standby" in rest mode. Remembers its address after the first find.
 */
object Ps5 {
    @Volatile private var addr: InetAddress? = null
    private val MSG = "SRCH * HTTP/1.1\ndevice-discovery-protocol-version:00030010\n".toByteArray()

    fun isOn(): Boolean = runCatching {
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 700
            val targets = listOfNotNull(addr, InetAddress.getByName("255.255.255.255"))
            repeat(2) {
                for (t in targets) sock.send(DatagramPacket(MSG, MSG.size, t, 9302))
                val buf = ByteArray(1024)
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                    val text = String(buf, 0, pkt.length)
                    if ("host-type:PS5" in text || "host-type:PS4" in text) {
                        addr = pkt.address
                        return@runCatching text.startsWith("HTTP/1.1 200")
                    }
                } catch (_: SocketTimeoutException) { }
            }
            false
        }
    }.getOrDefault(false)
}

/**
 * Switches inputs through Amazon's own input service. It is exported without a permission and does
 * the same switch as the Inputs menu, including parental-control checks. Its "inputType" uses
 * Amazon's numbering: HDMI 1-4 are 2, 3, 4 and 10.
 */
object Input {
    private val TYPE = mapOf(1 to 2, 2 to 3, 3 to 4, 4 to 10)

    fun switchTo(ctx: Context, port: Int): Boolean {
        val type = TYPE[port] ?: return false
        val i = android.content.Intent()
            .setClassName("com.amazon.tv.inputpreference.service", "com.amazon.tv.inputpreference.service.KaminoInputSwitchService")
            .putExtra("inputType", type)
        return runCatching { ctx.startService(i) != null }.getOrDefault(false)
    }
}

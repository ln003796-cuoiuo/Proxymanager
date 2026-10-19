package app.dnsmanager

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Локальный VPN только для DNS: в туннель попадает один фиктивный адрес (FAKE),
 * который система использует как DNS-сервер. Запросы пересылаются на ваши серверы по порядку списка.
 * Остальной трафик идёт мимо VPN. Само приложение исключено из VPN, поэтому имена серверов
 * разрешаются обычным DNS сети и петли не возникает.
 */
class DnsVpnService : VpnService() {
    companion object {
        @Volatile var running = false
        @Volatile var last = "—"
        const val FAKE = "10.111.222.2"
    }

    private var tun: ParcelFileDescriptor? = null
    private var pool: ExecutorService? = null
    private val cache = ConcurrentHashMap<String, Pair<ByteArray, Long>>()
    private val learned = ConcurrentHashMap<String, String>()

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == "stop") { stop(); return START_NOT_STICKY }
        if (running) return START_STICKY
        val b = Builder()
            .setSession("DNS Manager")
            .setMtu(1500)
            .addAddress("10.111.222.1", 32)
            .addRoute(FAKE, 32)
            .addDnsServer(FAKE)
            .setBlocking(true)
        try { b.addDisallowedApplication(packageName) } catch (e: Exception) { }
        val fd = b.establish() ?: return START_NOT_STICKY
        tun = fd
        running = true
        pool = Executors.newCachedThreadPool()
        Thread { loop(fd) }.start()
        return START_STICKY
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val inp = FileInputStream(fd.fileDescriptor)
        val out = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = inp.read(buf)
                if (n < 0) break
                if (n < 28) continue
                val p = buf.copyOf(n)
                if ((p[0].toInt() shr 4) != 4) continue
                val ihl = (p[0].toInt() and 15) * 4
                if (p[9].toInt() != 17 || n < ihl + 8) continue
                val dport = ((p[ihl + 2].toInt() and 255) shl 8) or (p[ihl + 3].toInt() and 255)
                if (dport != 53) continue
                val dns = p.copyOfRange(ihl + 8, n)
                pool?.execute {
                    val r = resolve(dns)
                    val pkt = reply(p, ihl, r)
                    synchronized(out) { try { out.write(pkt) } catch (e: Exception) { } }
                }
            }
        } catch (e: Exception) { }
        stop()
    }

    private fun reply(p: ByteArray, ihl: Int, r: ByteArray): ByteArray {
        val len = ihl + 8 + r.size
        val o = ByteArray(len)
        System.arraycopy(p, 0, o, 0, ihl)
        System.arraycopy(p, 16, o, 12, 4)
        System.arraycopy(p, 12, o, 16, 4)
        o[2] = (len shr 8).toByte(); o[3] = len.toByte()
        o[8] = 64
        o[10] = 0; o[11] = 0
        var s = 0
        var i = 0
        while (i < ihl) { s += ((o[i].toInt() and 255) shl 8) or (o[i + 1].toInt() and 255); i += 2 }
        while ((s shr 16) != 0) s = (s and 0xffff) + (s shr 16)
        val c = s.inv() and 0xffff
        o[10] = (c shr 8).toByte(); o[11] = c.toByte()
        o[ihl] = p[ihl + 2]; o[ihl + 1] = p[ihl + 3]; o[ihl + 2] = p[ihl]; o[ihl + 3] = p[ihl + 1]
        val ul = 8 + r.size
        o[ihl + 4] = (ul shr 8).toByte(); o[ihl + 5] = ul.toByte()
        o[ihl + 6] = 0; o[ihl + 7] = 0
        System.arraycopy(r, 0, o, ihl + 8, r.size)
        return o
    }

    private fun resolve(q: ByteArray): ByteArray {
        if (q.size < 12) return q
        val key = String(q, 2, q.size - 2, Charsets.ISO_8859_1)
        cache[key]?.let {
            if (it.second > System.currentTimeMillis()) {
                val r = it.first.copyOf(); r[0] = q[0]; r[1] = q[1]; return r
            }
        }
        for (s in Store.load(this).filter { it.on }) {
            val order = if (s.proto == "AUTO")
                (listOfNotNull(learned[s.addr]) + listOf("DoT", "DoH", "UDP")).distinct()
            else listOf(s.proto)
            for (pr in order) {
                try {
                    val r = ask(s.addr, pr, q)
                    if (r.size >= 12) {
                        last = "${s.name} · $pr"
                        if (s.proto == "AUTO") learned[s.addr] = pr
                        if ((r[3].toInt() and 15) == 0) {
                            if (cache.size > 2000) cache.clear()
                            cache[key] = Pair(r, System.currentTimeMillis() + 60_000)
                        }
                        return r
                    }
                } catch (e: Exception) { }
            }
        }
        last = "нет ответа ни от одного сервера"
        val r = q.copyOf()
        r[2] = (0x80 or (q[2].toInt() and 1)).toByte()
        r[3] = 0x82.toByte()
        for (i in 6..11) r[i] = 0
        return r
    }

    private fun ask(addr: String, pr: String, q: ByteArray): ByteArray = when (pr) {
        "DoT" -> {
            val host = addr.substringBefore("/")
            val raw = Socket()
            raw.connect(InetSocketAddress(host, 853), 2500)
            raw.soTimeout = 3000
            val s = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 853, true) as SSLSocket
            s.use {
                it.sslParameters = it.sslParameters.also { p -> p.endpointIdentificationAlgorithm = "HTTPS" }
                it.startHandshake()
                val o = DataOutputStream(it.outputStream)
                o.writeShort(q.size); o.write(q); o.flush()
                val i = DataInputStream(it.inputStream)
                val r = ByteArray(i.readUnsignedShort())
                i.readFully(r)
                r
            }
        }
        "DoH" -> {
            val url = URL(if (addr.contains("/")) "https://$addr" else "https://$addr/dns-query")
            val c = url.openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 2500
                c.readTimeout = 3000
                c.setRequestProperty("Content-Type", "application/dns-message")
                c.setRequestProperty("Accept", "application/dns-message")
                c.outputStream.use { it.write(q) }
                if (c.responseCode != 200) throw IOException("http ${c.responseCode}")
                c.inputStream.use { it.readBytes() }
            } finally { c.disconnect() }
        }
        else -> DatagramSocket().use { s ->
            s.soTimeout = 2000
            val a = InetAddress.getByName(addr.substringBefore("/"))
            s.send(DatagramPacket(q, q.size, a, 53))
            val b = ByteArray(4096)
            val p = DatagramPacket(b, b.size)
            s.receive(p)
            b.copyOf(p.length)
        }
    }

    private fun stop() {
        running = false
        try { tun?.close() } catch (e: Exception) { }
        tun = null
        pool?.shutdownNow()
        stopSelf()
    }

    override fun onRevoke() { stop() }
    override fun onDestroy() { running = false; try { tun?.close() } catch (e: Exception) { }; super.onDestroy() }
}

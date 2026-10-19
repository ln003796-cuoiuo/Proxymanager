package app.dnsmanager

import android.content.Context

data class Srv(val name: String, val addr: String, val proto: String, val on: Boolean)

object Store {
    private const val K = "servers"

    fun load(c: Context): MutableList<Srv> {
        val s = c.getSharedPreferences("dns", 0).getString(K, null)
            ?: return defaults().also { save(c, it) }
        return s.lines().filter { it.isNotBlank() }.mapNotNull {
            val a = it.split("\t")
            if (a.size == 4) Srv(a[0], a[1], a[2], a[3] == "1") else null
        }.toMutableList()
    }

    fun save(c: Context, l: List<Srv>) {
        val s = l.joinToString("\n") { "${it.name}\t${it.addr}\t${it.proto}\t${if (it.on) "1" else "0"}" }
        c.getSharedPreferences("dns", 0).edit().putString(K, s).apply()
    }

    fun defaults() = mutableListOf(
        Srv("WebShift VPN", "d.webshiftvpn.ru", "AUTO", true),
        Srv("Nulls Proxy", "dns.nullsproxy.com", "AUTO", true),
        Srv("Cloudflare (запасной)", "1.1.1.1", "DoT", false)
    )
}

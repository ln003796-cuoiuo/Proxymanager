package app.dnsmanager

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private lateinit var btn: Button
    private lateinit var status: TextView
    private var items = mutableListOf<Srv>()
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val on = DnsVpnService.running
            btn.text = if (on) "Остановить" else "Запустить"
            status.text = if (on) "Работает. Последний ответ: ${DnsVpnService.last}" else "Выключено"
            h.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        items = Store.load(this)
        val root = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(24))
        }
        root.addView(col)
        col.addView(TextView(this).apply { text = "DNS Manager"; textSize = 24f; typeface = Typeface.DEFAULT_BOLD })
        status = TextView(this).apply { setPadding(0, dp(8), 0, dp(8)) }
        col.addView(status)
        btn = Button(this)
        btn.setOnClickListener { toggle() }
        col.addView(btn)
        col.addView(TextView(this).apply {
            text = "Серверы опрашиваются сверху вниз. Если в настройках Android включён «Частный DNS» с именем хоста, " +
                "переключите его на «Отключено» или «Автоматически», иначе он обойдёт этот список."
            textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)

        col.addView(TextView(this).apply { text = "Добавить сервер"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(20), 0, 0) })
        val name = EditText(this).apply { hint = "Название" }
        val addr = EditText(this).apply {
            hint = "Адрес: 1.1.1.1 или dns.example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("AUTO", "DoT", "DoH", "UDP"))
        val add = Button(this).apply { text = "Добавить" }
        add.setOnClickListener {
            val a = addr.text.toString().trim()
            if (a.isEmpty() || a.any { it.isWhitespace() }) {
                Toast.makeText(this, "Введите адрес без пробелов", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val n = name.text.toString().trim().replace(Regex("[\t\n]"), " ").ifEmpty { a }
            items.add(Srv(n, a, sp.selectedItem.toString(), true))
            Store.save(this, items)
            name.setText(""); addr.setText("")
            refresh()
        }
        col.addView(name); col.addView(addr); col.addView(sp); col.addView(add)
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        items.forEachIndexed { i, s ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val cb = CheckBox(this).apply {
                isChecked = s.on
                setOnCheckedChangeListener { _, c -> items[i] = s.copy(on = c); Store.save(this@MainActivity, items) }
            }
            val tv = TextView(this).apply {
                text = "${i + 1}. ${s.name}\n${s.addr} · ${s.proto}"
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            fun act(label: String, enabled: Boolean, f: () -> Unit) = TextView(this).apply {
                text = label; textSize = 18f; alpha = if (enabled) 1f else 0.3f
                setPadding(dp(12), dp(12), dp(12), dp(12))
                if (enabled) setOnClickListener { f(); Store.save(this@MainActivity, items); refresh() }
            }
            row.addView(cb); row.addView(tv)
            row.addView(act("▲", i > 0) { java.util.Collections.swap(items, i, i - 1) })
            row.addView(act("▼", i < items.size - 1) { java.util.Collections.swap(items, i, i + 1) })
            row.addView(act("✕", true) { items.removeAt(i) })
            list.addView(row)
        }
    }

    private fun toggle() {
        if (DnsVpnService.running) {
            startService(Intent(this, DnsVpnService::class.java).setAction("stop"))
            return
        }
        if (items.none { it.on }) {
            Toast.makeText(this, "Включите хотя бы один сервер", Toast.LENGTH_SHORT).show()
            return
        }
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 1) else go()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(r: Int, c: Int, d: Intent?) {
        if (r == 1 && c == RESULT_OK) go()
    }

    private fun go() { startService(Intent(this, DnsVpnService::class.java).setAction("start")) }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }
}

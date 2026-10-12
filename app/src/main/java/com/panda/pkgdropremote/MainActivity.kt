package com.panda.pkgdropremote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Pantalla principal: estado de la PS4 (descubrimiento DDP + IP manual),
 * entrada de URLs, selector de PKG local (SAF) y cola con progreso real.
 *
 * UI con vistas clásicas del framework construidas en código; sin Compose,
 * sin appcompat, sin más dependencias que core-ktx.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var db: QueueDb
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunnable: Runnable? = null

    private lateinit var ps4Status: TextView
    private lateinit var rpiDot: TextView
    private lateinit var ipField: EditText
    private lateinit var portField: EditText
    private lateinit var urlField: EditText
    private lateinit var queueBox: LinearLayout

    companion object {
        private const val REQ_PICK_PKG = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = QueueDb(this)
        askNotificationPermission()

        val root = ScrollView(this).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 64)
                addView(title("🎮 PkgDrop Remote", 26f))
                addView(subtitle("Compañera del homebrew PkgDrop — alimenta la PS4 con enlaces o archivos."))
                addView(section("📺 PlayStation 4"))
                ps4Status = text("Sin configurar")
                addView(ps4Status)
                rpiDot = text("RPI: ?")
                addView(rpiDot)
                val row1 = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                row1.addView(button("🔍 Buscar PS4") { discoverPs4() })
                addView(row1)
                ipField = edit("IP manual (ej. 192.168.1.88)", prefs.ps4Ip)
                addView(ipField)
                portField = edit("Puerto RPI (12800)", prefs.rpiPort.toString())
                addView(portField)
                addView(button("💾 Guardar PS4") { saveManual() })
                addView(section("🔗 Agregar descargas"))
                addView(text("Pega una o varias URLs (una por línea). Se delegan al PkgDrop de la PS4."))
                urlField = edit("https://…/juego.pkg", "")
                urlField.minLines = 3
                urlField.isSingleLine = false
                addView(urlField)
                addView(button("⬇ Encolar URLs") { enqueueUrls() })
                addView(button("📁 Elegir PKG del teléfono") { pickPkg() })
                addView(text("El PKG elegido se sirve desde el teléfono (:8080) y el RPI lo instala."))
                addView(section("📋 Cola"))
                queueBox = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                addView(queueBox)
                addView(button("🧹 Limpiar terminados") {
                    RemoteService.control(this@MainActivity, RemoteService.ACTION_CLEAR_FINISHED, -1)
                    refreshQueue()
                })
            })
        }
        setContentView(root)
        refreshPs4Status()
        checkRpiHealth()
    }

    override fun onResume() {
        super.onResume()
        refreshQueue()
        val r = object : Runnable {
            override fun run() {
                refreshQueue()
                handler.postDelayed(this, 2000)
            }
        }
        refreshRunnable = r
        handler.postDelayed(r, 2000)
    }

    override fun onPause() {
        super.onPause()
        refreshRunnable?.let { handler.removeCallbacks(it) }
        refreshRunnable = null
    }

    override fun onDestroy() {
        db.close()
        super.onDestroy()
    }

    private fun pickPkg() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "application/*"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(Intent.createChooser(i, "Elegir PKG"), REQ_PICK_PKG)
    }

    @Deprecated("SAF clásico sin activity-ktx")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_PKG && resultCode == Activity.RESULT_OK) {
            val uri: Uri? = data?.data
            if (uri != null) {
                RemoteService.enqueueFile(this, uri)
                toast("PKG encolado: se servirá desde el teléfono")
            }
        }
    }

    // ------------------------------------------------------------------

    private fun discoverPs4() {
        toast("Buscando PS4 en la red…")
        Thread {
            val found = Ps4Discovery.discover(4000)
            runOnUiThread {
                if (found.isEmpty()) {
                    toast("No se encontró ninguna PS4. Escribe la IP manual.")
                } else {
                    val ps4 = found[0]
                    prefs.ps4Ip = ps4.ip
                    prefs.ps4Name = ps4.hostname
                    val extra = if (found.size > 1) " (+${found.size - 1} más)" else ""
                    toast("PS4 encontrada: ${ps4.label}$extra")
                    refreshPs4Status()
                    checkRpiHealth()
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun saveManual() {
        val ip = ipField.text.toString().trim()
        if (ip.isEmpty()) {
            toast("Escribe una IP")
            return
        }
        prefs.ps4Ip = ip
        prefs.ps4Name = ""
        prefs.rpiPort = portField.text.toString().trim().toIntOrNull() ?: RpiClient.DEFAULT_PORT
        toast("PS4 guardada: $ip")
        refreshPs4Status()
        checkRpiHealth()
    }

    private fun refreshPs4Status() {
        ps4Status.text = if (prefs.hasPs4()) {
            val name = prefs.ps4Name
            if (name.isNotBlank()) "PS4: $name (${prefs.ps4Ip})" else "PS4: ${prefs.ps4Ip}"
        } else {
            "Sin configurar — busca o escribe la IP"
        }
        ipField.setText(prefs.ps4Ip)
        portField.setText(prefs.rpiPort.toString())
    }

    private fun checkRpiHealth() {
        val ip = prefs.ps4Ip
        if (ip.isBlank()) {
            rpiDot.text = "RPI: sin PS4"
            return
        }
        rpiDot.text = "RPI: comprobando…"
        Thread {
            val res = RpiClient.isHealthy(ip, prefs.rpiPort)
            runOnUiThread {
                rpiDot.text = when (res) {
                    is RpiClient.Outcome.Ok ->
                        if (res.value) "RPI: ● en línea" else "RPI: ● responde (revisar)"
                    is RpiClient.Outcome.Err -> "RPI: ○ ${res.message}"
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun enqueueUrls() {
        val urls = urlField.text.toString().lines()
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
        if (urls.isEmpty()) {
            toast("Pega al menos una URL http(s)")
            return
        }
        RemoteService.enqueueUrls(this, urls)
        urlField.setText("")
        toast("${urls.size} enlace(s) encolado(s) → PkgDrop")
        refreshQueue()
    }

    private fun refreshQueue() {
        queueBox.removeAllViews()
        val items = db.all()
        if (items.isEmpty()) {
            queueBox.addView(text("Cola vacía."))
            return
        }
        for (item in items) queueBox.addView(queueRow(item))
    }

    private fun queueRow(item: QueueDb.Item): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }
        col.addView(text(item.fileName, 16f))
        val pct = if (item.total > 0) item.transferred * 100.0 / item.total else -1.0
        val status = when (item.state) {
            QueueDb.State.PENDING -> "⏳ En cola"
            QueueDb.State.WORKING -> "⚙ Trabajando…"
            QueueDb.State.PAUSED -> "⏸ Pausado"
            QueueDb.State.DELEGATED -> "📤 " + (item.error ?: "Delegado a PkgDrop")
            QueueDb.State.DONE -> "✅ Instalado"
            QueueDb.State.ERROR -> "❌ ${item.error ?: "Error"}"
        }
        col.addView(text(status, 14f))
        if (pct >= 0) {
            val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = pct.toInt().coerceIn(0, 100)
            }
            col.addView(bar)
            val detail = StringBuilder()
            detail.append(String.format("%.1f%%", pct))
            detail.append(" · ${fmtBytes(item.transferred)} / ${fmtBytes(item.total)}")
            if (item.restSec > 0) detail.append(" · ETA ${item.restSec}s")
            col.addView(text(detail.toString(), 13f))
        }
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        when (item.state) {
            QueueDb.State.WORKING -> {
                btnRow.addView(smallButton("⏸") {
                    RemoteService.control(this, RemoteService.ACTION_PAUSE, item.id)
                })
                btnRow.addView(smallButton("✖") {
                    RemoteService.control(this, RemoteService.ACTION_CANCEL, item.id)
                })
            }
            QueueDb.State.PAUSED -> {
                btnRow.addView(smallButton("▶") {
                    RemoteService.control(this, RemoteService.ACTION_RESUME, item.id)
                })
                btnRow.addView(smallButton("✖") {
                    RemoteService.control(this, RemoteService.ACTION_CANCEL, item.id)
                })
            }
            QueueDb.State.ERROR, QueueDb.State.DONE, QueueDb.State.DELEGATED -> {
                btnRow.addView(smallButton("🗑") {
                    RemoteService.control(this, RemoteService.ACTION_CANCEL, item.id)
                    refreshQueue()
                })
            }
            else -> { }
        }
        if (btnRow.childCount > 0) col.addView(btnRow)
        return col
    }

    // ------------------------------------------------------------------
    // Vistas de ayuda
    // ------------------------------------------------------------------

    private fun title(t: String, size: Float) = TextView(this).apply {
        text = t; textSize = size
        setPadding(0, 0, 0, 8)
    }

    private fun subtitle(t: String) = TextView(this).apply {
        text = t; textSize = 14f
        setPadding(0, 0, 0, 16)
    }

    private fun section(t: String) = TextView(this).apply {
        text = t; textSize = 19f
        setPadding(0, 24, 0, 8)
    }

    private fun text(t: String, size: Float = 14f) = TextView(this).apply {
        text = t; textSize = size
        setPadding(0, 4, 0, 4)
    }

    private fun edit(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setPadding(16, 16, 16, 16)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun smallButton(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.START; marginEnd = 16 }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun fmtBytes(b: Long): String {
        if (b < 0) return "?"
        val gb = b / 1073741824.0
        if (gb >= 1) return String.format("%.2f GB", gb)
        val mb = b / 1048576.0
        if (mb >= 1) return String.format("%.1f MB", mb)
        return String.format("%d KB", b / 1024)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }
}

package com.panda.pkgdropremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.net.NetworkInterface

/**
 * Servicio foreground que orquesta la cola:
 * - URLs → pre-chequeo RPI (`is_exists`) → delegar a PkgDrop (`POST /add`).
 * - Archivos del teléfono → servirlos por HTTP (`PhoneHttpServer`) →
 *   ordenar `install` al RPI → polling de progreso real por `task_id`
 *   (pausar/reanudar/cancelar).
 *
 * Patrón del ecosistema (servicio con cola + polling por task_id);
 * implementación propia.
 */
class RemoteService : Service() {

    companion object {
        const val ACTION_ENQUEUE_URLS = "com.panda.pkgdropremote.ENQUEUE_URLS"
        const val ACTION_ENQUEUE_FILE = "com.panda.pkgdropremote.ENQUEUE_FILE"
        const val ACTION_PAUSE = "com.panda.pkgdropremote.PAUSE"
        const val ACTION_RESUME = "com.panda.pkgdropremote.RESUME"
        const val ACTION_CANCEL = "com.panda.pkgdropremote.CANCEL"
        const val ACTION_CLEAR_FINISHED = "com.panda.pkgdropremote.CLEAR_FINISHED"
        const val ACTION_STOP = "com.panda.pkgdropremote.STOP"
        const val EXTRA_URLS = "urls"
        const val EXTRA_URI = "uri"
        const val EXTRA_ID = "id"

        private const val NOTIF_ID = 42
        private const val CHANNEL_ID = "pkgdrop_remote"

        fun enqueueUrls(ctx: Context, urls: List<String>) {
            val i = Intent(ctx, RemoteService::class.java).apply {
                action = ACTION_ENQUEUE_URLS
                putStringArrayListExtra(EXTRA_URLS, ArrayList(urls))
            }
            ctx.startForegroundService(i)
        }

        fun enqueueFile(ctx: Context, uri: Uri) {
            val i = Intent(ctx, RemoteService::class.java).apply {
                action = ACTION_ENQUEUE_FILE
                putExtra(EXTRA_URI, uri.toString())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startForegroundService(i)
        }

        fun control(ctx: Context, action: String, id: Long) {
            val i = Intent(ctx, RemoteService::class.java).apply {
                this.action = action
                putExtra(EXTRA_ID, id)
            }
            ctx.startForegroundService(i)
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var db: QueueDb
    private var server: PhoneHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var running = true
    private var worker: Thread? = null

    // Progreso para la notificación (solo el ítem activo).
    @Volatile private var activeLabel = ""
    @Volatile private var activePct = -1.0
    @Volatile private var activeDetail = ""

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = QueueDb(this)
        // Nada puede quedar colgado en WORKING tras reiniciar el servicio.
        for (it in db.all()) {
            if (it.state == QueueDb.State.WORKING) db.setState(it.id, QueueDb.State.PENDING)
        }
        createChannel()
        startForeground(NOTIF_ID, buildNotification("PkgDrop Remote listo", "", -1.0))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PkgDropRemote:queue").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
        worker = Thread({ workerLoop() }, "RemoteService-worker").apply {
            isDaemon = true; start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE_URLS -> {
                val urls = intent.getStringArrayListExtra(EXTRA_URLS) ?: arrayListOf()
                for (u in urls) {
                    val t = u.trim()
                    if (t.isNotEmpty()) db.insertUrl(t, QueueDb.fileNameFromUrl(t))
                }
            }
            ACTION_ENQUEUE_FILE -> {
                intent.getStringExtra(EXTRA_URI)?.let { uriStr ->
                    val uri = Uri.parse(uriStr)
                    // Tomar permiso persistente para leer el documento luego.
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) { }
                    val (name, size) = queryDisplayName(uri)
                    db.insertFile(uriStr, name, size)
                }
            }
            ACTION_PAUSE -> {
                val id = intent.getLongExtra(EXTRA_ID, -1)
                db.get(id)?.let { item ->
                    if (item.state == QueueDb.State.WORKING) db.setState(id, QueueDb.State.PAUSED)
                }
            }
            ACTION_RESUME -> {
                val id = intent.getLongExtra(EXTRA_ID, -1)
                db.get(id)?.let { item ->
                    if (item.state == QueueDb.State.PAUSED) db.setState(id, QueueDb.State.WORKING)
                }
            }
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_ID, -1)
                cancelItem(id)
            }
            ACTION_CLEAR_FINISHED -> db.clearFinished()
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        try { wakeLock?.release() } catch (_: Exception) { }
        server?.stop()
        db.close()
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Worker
    // ------------------------------------------------------------------

    private fun workerLoop() {
        while (running) {
            try {
                val item = db.nextPending()
                if (item == null) {
                    setActive("", -1.0, "")
                    Thread.sleep(3000)
                    continue
                }
                process(item)
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun process(item: QueueDb.Item) {
        val ip = prefs.ps4Ip
        if (ip.isBlank()) {
            db.setState(item.id, QueueDb.State.ERROR, "Configura la IP de la PS4 primero")
            return
        }
        val port = prefs.rpiPort
        db.setState(item.id, QueueDb.State.WORKING)

        // Pre-chequeo de salud del RPI: nunca fallar a ciegas.
        when (val h = RpiClient.isHealthy(ip, port)) {
            is RpiClient.Outcome.Err -> {
                db.setState(item.id, QueueDb.State.ERROR, h.message)
                return
            }
            is RpiClient.Outcome.Ok -> { /* responde; seguimos */ }
        }

        when (item.kind) {
            QueueDb.Kind.URL -> delegateToPkgDrop(item, ip)
            QueueDb.Kind.FILE -> serveAndInstall(item, ip, port)
        }
        setActive("", -1.0, "")
    }

    /** Modo A: la PS4 descarga sola vía el homebrew PkgDrop. */
    private fun delegateToPkgDrop(item: QueueDb.Item, ip: String) {
        val url = item.url ?: run {
            db.setState(item.id, QueueDb.State.ERROR, "URL vacía")
            return
        }
        setActive(item.fileName, -1.0, "Delegando a PkgDrop…")
        when (val r = PkgDropBridge.postAdd(ip, listOf(url))) {
            is PkgDropBridge.Outcome.Err -> db.setState(item.id, QueueDb.State.ERROR, r.message)
            is PkgDropBridge.Outcome.Ok ->
                db.setState(item.id, QueueDb.State.DELEGATED,
                    "Encolado en PkgDrop (${r.value.enqueued}) — la PS4 lo descarga e instala")
        }
    }

    /** Modo B: el teléfono sirve el archivo y el RPI lo instala. */
    private fun serveAndInstall(item: QueueDb.Item, ip: String, port: Int) {
        val uriStr = item.fileUri ?: run {
            db.setState(item.id, QueueDb.State.ERROR, "Archivo sin Uri")
            return
        }
        val uri = Uri.parse(uriStr)
        val srv = try {
            getServer()
        } catch (e: Exception) {
            db.setState(item.id, QueueDb.State.ERROR,
                "No se pudo abrir el servidor :8080 (${e.message ?: "ocupado"})")
            return
        }
        val phoneIp = localIpv4() ?: run {
            db.setState(item.id, QueueDb.State.ERROR, "No se pudo determinar la IP del teléfono")
            return
        }
        // Registrar el archivo en el servidor.
        val token: String = try {
            val (name, size) = queryDisplayName(uri)
            val sz = if (size > 0) size else item.sizeBytes
            if (sz <= 0) {
                db.setState(item.id, QueueDb.State.ERROR, "No se pudo leer el tamaño del archivo")
                return
            }
            srv.addUri(uri, name.ifEmpty { item.fileName }, sz)
        } catch (e: Exception) {
            db.setState(item.id, QueueDb.State.ERROR, "No se pudo abrir el archivo: ${e.message}")
            return
        }
        val localUrl = srv.urlFor(token, phoneIp) ?: run {
            db.setState(item.id, QueueDb.State.ERROR, "No se pudo publicar el archivo")
            return
        }

        // Ordenar la instalación.
        setActive(item.fileName, 0.0, "Ordenando instalación…")
        val taskId = when (val r = RpiClient.install(ip, port, localUrl)) {
            is RpiClient.Outcome.Err -> {
                db.setState(item.id, QueueDb.State.ERROR, r.message)
                srv.remove(token)
                return
            }
            is RpiClient.Outcome.Ok -> r.value
        }
        db.setTaskId(item.id, taskId)

        // Polling de progreso real.
        var lastBytes = 0L
        var lastAt = System.currentTimeMillis()
        var pausedSent = false
        while (running) {
            val cur = db.get(item.id) ?: break // cancelado (fila borrada)
            when (cur.state) {
                QueueDb.State.PAUSED -> {
                    if (!pausedSent) {
                        RpiClient.pauseTask(ip, port, taskId)
                        pausedSent = true
                        setActive(item.fileName, curProgressPct(cur), "Pausado")
                    }
                    Thread.sleep(1000)
                    continue
                }
                QueueDb.State.WORKING -> {
                    if (pausedSent) {
                        RpiClient.resumeTask(ip, port, taskId)
                        pausedSent = false
                    }
                }
                else -> break // DONE/ERROR/DELEGATED por otro lado
            }
            when (val p = RpiClient.getProgress(ip, port, taskId)) {
                is RpiClient.Outcome.Err -> {
                    // Un fallo aislado de polling no aborta; reintenta.
                    Thread.sleep(2000)
                    continue
                }
                is RpiClient.Outcome.Ok -> {
                    val pr = p.value
                    db.setProgress(item.id, pr.transferred, pr.total, pr.restSec)
                    val now = System.currentTimeMillis()
                    val dt = (now - lastAt).coerceAtLeast(1)
                    val speed = if (pr.transferred >= lastBytes)
                        (pr.transferred - lastBytes) * 1000 / dt else 0L
                    lastBytes = pr.transferred
                    lastAt = now
                    val detail = if (pr.total > 0) {
                        "${fmtBytes(pr.transferred)} / ${fmtBytes(pr.total)} · ${fmtSpeed(speed)}" +
                                if (pr.restSec > 0) " · ETA ${fmtEta(pr.restSec)}" else ""
                    } else "Preparando… ${pr.preparingPercent}%"
                    setActive(item.fileName, pr.percent, detail)
                    if (pr.total > 0 && pr.transferred >= pr.total) {
                        db.setState(item.id, QueueDb.State.DONE, "Instalado en la PS4")
                        // Dejar el archivo publicado un rato por si el RPI reintenta.
                        break
                    }
                }
            }
            Thread.sleep(2000)
        }
        setActive("", -1.0, "")
    }

    private fun cancelItem(id: Long) {
        val item = db.get(id) ?: return
        // Si hay tarea RPI viva, detenerla y desregistrarla.
        val ip = prefs.ps4Ip
        val taskId = item.taskId
        if (ip.isNotBlank() && !taskId.isNullOrBlank() && item.state == QueueDb.State.WORKING) {
            Thread {
                RpiClient.stopTask(ip, prefs.rpiPort, taskId)
                RpiClient.unregisterTask(ip, prefs.rpiPort, taskId)
            }.also { it.isDaemon = true; it.start() }
        }
        db.delete(id)
    }

    private fun stopEverything() {
        running = false
        worker?.interrupt()
        server?.stop()
        stopSelf()
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    @Synchronized
    private fun getServer(): PhoneHttpServer {
        var s = server
        if (s == null) {
            s = PhoneHttpServer(contentResolver)
            if (!s.start()) throw IllegalStateException("No se pudo abrir el puerto 8080")
            server = s
        }
        return s
    }

    private fun queryDisplayName(uri: Uri): Pair<String, Long> {
        var name = "pkg.pkg"
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) c.getString(ni)?.let { if (it.isNotBlank()) name = it }
                    val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (si >= 0) size = c.getLong(si)
                }
            }
        } catch (_: Exception) { }
        return name to size
    }

    private fun curProgressPct(item: QueueDb.Item): Double =
        if (item.total > 0) item.transferred * 100.0 / item.total else 0.0

    private fun setActive(label: String, pct: Double, detail: String) {
        activeLabel = label
        activePct = pct
        activeDetail = detail
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(
            if (label.isEmpty()) "PkgDrop Remote listo" else label, detail, pct))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "PkgDrop Remote",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String, pct: Double): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemoteService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (text.isNotEmpty()) b.setContentText(text)
        if (pct >= 0) b.setProgress(100, pct.toInt().coerceIn(0, 100), false)
        return b.build()
    }

    private fun localIpv4(): String? {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return null
            while (ifs.hasMoreElements()) {
                val nif = ifs.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) {
                        return a.hostAddress
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun fmtBytes(b: Long): String {
        if (b < 0) return "?"
        val gb = b / 1073741824.0
        if (gb >= 1) return String.format("%.2f GB", gb)
        val mb = b / 1048576.0
        if (mb >= 1) return String.format("%.1f MB", mb)
        return String.format("%d KB", b / 1024)
    }

    private fun fmtSpeed(bps: Long): String = fmtBytes(bps) + "/s"

    private fun fmtEta(sec: Long): String {
        if (sec < 0) return "?"
        val m = sec / 60
        val s = sec % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}

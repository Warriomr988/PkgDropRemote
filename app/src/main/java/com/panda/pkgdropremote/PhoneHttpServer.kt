package com.panda.pkgdropremote

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servidor HTTP del teléfono (`:<port>`) para que la PS4 jale el PKG por
 * HTTP con el descargador del sistema (vía RPI-direct).
 *
 * - Sirve `/pkg/<token>/<nombre>` con soporte `Range` (`206 Partial
 *   Content` + `Content-Range`, `416` ante rangos imposibles, `HEAD`).
 * - Tracking de cobertura por token: registra los rangos servidos y sabe
 *   si la PS4 jaló el archivo completo (`wasFullyServed`).
 * - Orígenes: archivo local (`File`) o documento SAF (`Uri`).
 *
 * Patrón del ecosistema (servidor con Range + tracking por token);
 * implementación propia.
 */
class PhoneHttpServer(
    private val resolver: ContentResolver,
    val port: Int = 8080
) {

    /** Origen servible: o archivo o Uri SAF. */
    sealed class Origin {
        data class FileOrigin(val file: File) : Origin()
        data class UriOrigin(val uri: Uri) : Origin()
    }

    private data class Entry(
        val token: String,
        val name: String,
        val size: Long,
        val origin: Origin,
        val served: MutableList<LongRange> = mutableListOf()
    )

    private val entries = Collections.synchronizedMap(mutableMapOf<String, Entry>())
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(8)
    private var acceptThread: Thread? = null

    /** Registra un archivo local y devuelve su token. */
    fun addFile(file: File, displayName: String): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        entries[token] = Entry(token, sanitize(displayName), file.length(), Origin.FileOrigin(file))
        return token
    }

    /** Registra un documento SAF y devuelve su token. */
    fun addUri(uri: Uri, displayName: String, size: Long): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        entries[token] = Entry(token, sanitize(displayName), size, Origin.UriOrigin(uri))
        return token
    }

    fun remove(token: String) {
        entries.remove(token)
    }

    fun entryCount(): Int = entries.size

    /** URL local para que el RPI la jale. */
    fun urlFor(token: String, phoneIp: String): String? {
        val e = entries[token] ?: return null
        return "http://$phoneIp:$port/pkg/$token/${urlEncode(e.name)}"
    }

    /**
     * ¿La PS4 ya jaló el archivo completo? Une los intervalos servidos y
     * verifica cobertura total de [0, size).
     */
    @Synchronized
    fun wasFullyServed(token: String): Boolean {
        val e = entries[token] ?: return false
        if (e.size <= 0) return false
        val sorted = e.served.sortedBy { it.first }
        var covered = 0L
        for (r in sorted) {
            if (r.first > covered) return false
            covered = maxOf(covered, r.last + 1)
            if (covered >= e.size) return true
        }
        return covered >= e.size
    }

    @Synchronized
    private fun markServed(token: String, start: Long, end: Long) {
        entries[token]?.served?.add(start..end)
    }

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val ss = ServerSocket(port)
            ss.reuseAddress = true
            serverSocket = ss
            running.set(true)
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val sock = ss.accept()
                        pool.execute { handle(sock) }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            }, "PhoneHttpServer-accept").also { it.isDaemon = true; it.start() }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) { }
        serverSocket = null
    }

    // ------------------------------------------------------------------

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 15000
            val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
            val reqLine = reader.readLine() ?: run { sock.close(); return }
            val parts = reqLine.split(" ")
            if (parts.size < 2) { sock.close(); return }
            val method = parts[0].uppercase()
            val rawPath = parts[1].substringBefore("?")
            var rangeHeader: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                if (l.isEmpty()) break
                if (l.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = l.substringAfter(":").trim()
                }
            }

            val segs = rawPath.trim('/').split("/")
            // /pkg/<token>/<nombre...>
            if (segs.size < 3 || segs[0] != "pkg") {
                sendStatus(sock, 404, "Not Found"); sock.close(); return
            }
            val token = segs[1]
            val entry = entries[token]
            if (entry == null) {
                sendStatus(sock, 404, "Not Found"); sock.close(); return
            }

            val total = entry.size
            val parsed: Triple<Long, Long, Boolean> = if (rangeHeader != null) {
                val pr = parseRange(rangeHeader, total)
                if (pr == null) {
                    sendStatus(sock, 416, "Range Not Satisfiable",
                        "Content-Range: bytes */$total\r\nContent-Length: 0\r\n")
                    sock.close(); return
                }
                Triple(pr.first, pr.second, true)
            } else {
                Triple(0L, total - 1, false)
            }
            val (rs, re, partial) = parsed

            val toSend = re - rs + 1
            val out = sock.getOutputStream()
            val head = StringBuilder()
                .append(if (partial) "HTTP/1.0 206 Partial Content\r\n" else "HTTP/1.0 200 OK\r\n")
                .append("Content-Type: application/octet-stream\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: ").append(toSend).append("\r\n")
            if (partial) head.append("Content-Range: bytes ").append(rs).append('-').append(re)
                .append('/').append(total).append("\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.UTF_8))
            out.flush()

            if (method != "HEAD" && toSend > 0) {
                streamRange(entry, rs, toSend, out)
                markServed(token, rs, re)
            }
            out.flush()
            sock.close()
        } catch (_: Exception) {
            try { sock.close() } catch (_: Exception) { }
        }
    }

    private fun parseRange(header: String, total: Long): Pair<Long, Long>? {
        // Formatos: bytes=A-B, bytes=A-, bytes=-N
        val m = Regex("""bytes=(\d*)-(\d*)""").find(header.trim()) ?: return null
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        return when {
            a.isNotEmpty() && b.isNotEmpty() -> {
                val s = a.toLongOrNull() ?: return null
                var e = b.toLongOrNull() ?: return null
                if (s >= total) return null
                if (e >= total) e = total - 1
                if (e < s) return null
                s to e
            }
            a.isNotEmpty() -> {
                val s = a.toLongOrNull() ?: return null
                if (s >= total) return null
                s to (total - 1)
            }
            b.isNotEmpty() -> {
                val n = b.toLongOrNull() ?: return null
                if (n <= 0) return null
                maxOf(0L, total - n) to (total - 1)
            }
            else -> null
        }
    }

    private fun streamRange(entry: Entry, start: Long, count: Long, out: java.io.OutputStream) {
        val buf = ByteArray(32 * 1024)
        var remaining = count
        var pos = start
        when (val o = entry.origin) {
            is Origin.FileOrigin -> {
                RandomAccessFile(o.file, "r").use { raf ->
                    raf.seek(pos)
                    while (remaining > 0) {
                        val want = minOf(remaining, buf.size.toLong()).toInt()
                        val r = raf.read(buf, 0, want)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        pos += r
                        remaining -= r
                    }
                }
            }
            is Origin.UriOrigin -> {
                var pfd: ParcelFileDescriptor? = null
                try {
                    pfd = resolver.openFileDescriptor(o.uri, "r") ?: return
                    val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                    pfd = null // el stream ahora es dueño
                    input.use { ins ->
                        var skipped = pos
                        while (skipped > 0) {
                            val s = ins.skip(skipped)
                            if (s <= 0) break
                            skipped -= s
                        }
                        while (remaining > 0) {
                            val want = minOf(remaining, buf.size.toLong()).toInt()
                            val r = ins.read(buf, 0, want)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                            remaining -= r
                        }
                    }
                } finally {
                    try { pfd?.close() } catch (_: Exception) { }
                }
            }
        }
    }

    private fun sendStatus(sock: Socket, code: Int, text: String, extra: String = "") {
        val head = "HTTP/1.0 $code $text\r\n$extra" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n"
        try {
            sock.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
            sock.getOutputStream().flush()
        } catch (_: Exception) { }
    }

    private fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.replace(Regex("""[^A-Za-z0-9._\- ]"""), "_").trim()
        return clean.ifEmpty { "pkg.pkg" }
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

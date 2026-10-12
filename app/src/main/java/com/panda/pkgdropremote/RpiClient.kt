package com.panda.pkgdropremote

import java.net.InetSocketAddress
import java.net.Socket
import org.json.JSONObject

/**
 * Cliente completo del Remote Package Installer (RPI) de GoldHEN.
 *
 * Patrón observado en el análisis del ecosistema (sender de PKGs vía RPI):
 * ciclo de vida por task_id (install → poll → pause/resume/stop/unregister),
 * `/api/is_exists` como pre-chequeo de salud del RPI y normalización del
 * task_id hexadecimal (`0x…` → decimal).
 *
 * Transporte: socket crudo con POST HTTP/1.0 simple (sin `Expect:
 * 100-continue` ni chunked) porque el RPI es quisquilloso con el formato.
 * Implementación propia; no se copió código de terceros.
 */
object RpiClient {

    const val DEFAULT_PORT = 12800
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 7000

    /** Resultado de una llamada al RPI. */
    sealed class Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>()
        data class Err(val message: String) : Outcome<Nothing>()
    }

    /** Progreso real de una tarea de instalación. */
    data class TaskProgress(
        val transferred: Long,
        val total: Long,
        val restSec: Long,
        val preparingPercent: Int
    ) {
        val percent: Double
            get() = if (total > 0) transferred * 100.0 / total else 0.0
    }

    // ------------------------------------------------------------------
    // Transporte
    // ------------------------------------------------------------------

    /**
     * POST JSON a [path] del RPI. Devuelve el cuerpo de la respuesta
     * (o el error en español).
     */
    private fun post(ip: String, port: Int, path: String, jsonBody: String): Outcome<String> {
        return try {
            val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                sock.soTimeout = READ_TIMEOUT_MS
                val out = sock.getOutputStream()
                val req = StringBuilder()
                    .append("POST ").append(path).append(" HTTP/1.0\r\n")
                    .append("Host: ").append(ip).append(':').append(port).append("\r\n")
                    .append("Content-Type: application/json\r\n")
                    .append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                    .append("Connection: close\r\n")
                    .append("\r\n")
                    .toString()
                out.write(req.toByteArray(Charsets.UTF_8))
                out.write(bodyBytes)
                out.flush()
                val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
                val statusLine = reader.readLine()
                    ?: return Outcome.Err("El RPI no respondió (conectó pero no envió nada)")
                if (!statusLine.contains("200")) {
                    return Outcome.Err("El RPI respondió: ${statusLine.trim()}")
                }
                // Saltar cabeceras; el cuerpo es JSON.
                val sb = StringBuilder()
                var line: String?
                var inBody = false
                while (reader.readLine().also { line = it } != null) {
                    if (inBody) sb.append(line)
                    else if (line!!.isEmpty()) inBody = true
                }
                Outcome.Ok(sb.toString())
            }
        } catch (e: java.net.SocketTimeoutException) {
            Outcome.Err("El RPI no responde (timeout en $ip:$port). ¿GoldHEN activo?")
        } catch (e: java.net.ConnectException) {
            Outcome.Err("No se pudo conectar al RPI en $ip:$port. ¿GoldHEN activo?")
        } catch (e: Exception) {
            Outcome.Err(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun taskIdJson(taskId: String): String =
        if (taskId.all { it.isDigit() }) taskId else "\"${taskId.replace("\"", "")}\""

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    /**
     * Ordena la instalación de [pkgUrl]. Devuelve el task_id normalizado
     * (decimal aunque el RPI lo devuelva en hex `0x…`).
     */
    fun install(ip: String, port: Int, pkgUrl: String): Outcome<String> {
        val safe = pkgUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        return when (val r = post(ip, port, "/api/install",
            """{"type":"direct","packages":["$safe"]}""")) {
            is Outcome.Err -> r
            is Outcome.Ok -> {
                val raw = extractTaskId(r.value)
                    ?: return Outcome.Err("El RPI no devolvió task_id: ${r.value.take(120)}")
                Outcome.Ok(normalizeTaskId(raw))
            }
        }
    }

    /**
     * Busca si ya existe una tarea para [contentId] (anti-duplicados).
     * Devuelve el task_id si existe, null si no.
     */
    fun findTask(ip: String, port: Int, contentId: String?, subType: Int): Outcome<String?> {
        if (contentId.isNullOrBlank()) return Outcome.Ok(null)
        val safe = contentId.replace("\"", "")
        return when (val r = post(ip, port, "/api/find_task",
            """{"content_id":"$safe","sub_type":$subType}""")) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(extractTaskId(r.value)?.let(::normalizeTaskId))
        }
    }

    /** Progreso real de la tarea [taskId]. */
    fun getProgress(ip: String, port: Int, taskId: String): Outcome<TaskProgress> {
        return when (val r = post(ip, port, "/api/get_task_progress",
            """{"task_id":${taskIdJson(taskId)}}""")) {
            is Outcome.Err -> r
            is Outcome.Ok -> try {
                val j = JSONObject(r.value)
                val total = j.optLong("length_total", j.optLong("length", -1))
                val transferred = j.optLong("transferred_total", j.optLong("transferred", 0))
                val rest = j.optLong("rest_sec_total", j.optLong("rest_sec", -1))
                val prep = j.optInt("preparing_percent", 0)
                Outcome.Ok(TaskProgress(transferred, total, rest, prep))
            } catch (e: Exception) {
                Outcome.Err("Respuesta de progreso ilegible: ${e.message}")
            }
        }
    }

    fun pauseTask(ip: String, port: Int, taskId: String): Outcome<Unit> =
        simpleTaskCall(ip, port, "/api/pause_task", taskId)

    fun resumeTask(ip: String, port: Int, taskId: String): Outcome<Unit> =
        simpleTaskCall(ip, port, "/api/resume_task", taskId)

    fun stopTask(ip: String, port: Int, taskId: String): Outcome<Unit> =
        simpleTaskCall(ip, port, "/api/stop_task", taskId)

    fun unregisterTask(ip: String, port: Int, taskId: String): Outcome<Unit> =
        simpleTaskCall(ip, port, "/api/unregister_task", taskId)

    private fun simpleTaskCall(ip: String, port: Int, path: String, taskId: String): Outcome<Unit> {
        return when (val r = post(ip, port, path, """{"task_id":${taskIdJson(taskId)}}""")) {
            is Outcome.Err -> r
            is Outcome.Ok -> Outcome.Ok(Unit)
        }
    }

    /**
     * Pre-chequeo de salud del RPI. Devuelve Ok(true) si el RPI responde y
     * está operativo; Ok(false) si responde pero reporta fallo; Err si no
     * hay conexión. Llamar SIEMPRE antes de enviar una instalación en vez
     * de fallar a ciegas.
     */
    fun isHealthy(ip: String, port: Int, titleId: String = "CUSA00000"): Outcome<Boolean> {
        return when (val r = post(ip, port, "/api/is_exists",
            """{"title_id":"${titleId.replace("\"", "")}"}""")) {
            is Outcome.Err -> r
            is Outcome.Ok -> {
                val body = r.value
                val alive = body.contains("\"status\"") &&
                        (body.contains("success") || body.contains("exists"))
                Outcome.Ok(alive)
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private fun extractTaskId(body: String): String? {
        // 1) JSON formal
        try {
            val j = JSONObject(body)
            val v = j.opt("task_id") ?: return null
            return v.toString()
        } catch (_: Exception) { /* cae al regex */ }
        // 2) regex tolerante
        val m = Regex(""""task_id"\s*:\s*"?([^",}\s]+)"?""").find(body)
        return m?.groupValues?.get(1)
    }

    /**
     * Normaliza el task_id: el RPI a veces lo devuelve en hexadecimal
     * (`0x…`); lo convertimos a decimal para usarlo en el resto de llamadas.
     */
    fun normalizeTaskId(raw: String): String {
        val t = raw.trim().removeSurrounding("\"")
        return if (t.startsWith("0x", ignoreCase = true)) {
            t.substring(2).toLongOrNull(16)?.toString() ?: t
        } else t
    }
}

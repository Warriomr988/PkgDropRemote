package com.panda.pkgdropremote

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Puente con el homebrew PkgDrop: envía URLs a su panel
 * `http://<ps4>:8080/add` para que la PS4 las descargue sola.
 *
 * Formato verificado contra el fuente del homebrew:
 * `POST /add`, `Content-Type: application/x-www-form-urlencoded`,
 * cuerpo `urls=<una URL por línea, url-encoded>` (compat: `url=` simple).
 */
object PkgDropBridge {

    const val PKGDROP_PORT = 8080

    sealed class Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>()
        data class Err(val message: String) : Outcome<Nothing>()
    }

    data class AddResult(val enqueued: Int)

    /**
     * Encola [urls] en el PkgDrop de la PS4. Devuelve cuántas aceptó.
     */
    fun postAdd(ps4Ip: String, urls: List<String>): Outcome<AddResult> {
        val clean = urls.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return Outcome.Err("No hay URLs para enviar")
        return try {
            val body = "urls=" + URLEncoder.encode(clean.joinToString("\n"), "UTF-8")
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val conn = (URL("http://$ps4Ip:$PKGDROP_PORT/add").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 8000
                readTimeout = 15000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Content-Length", bodyBytes.size.toString())
            }
            conn.outputStream.use { it.write(bodyBytes) }
            val code = conn.responseCode
            val resp = try {
                conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            } catch (_: Exception) {
                conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            } finally {
                conn.disconnect()
            }
            if (code != 200) return Outcome.Err("PkgDrop respondió HTTP $code")
            // El homebrew responde "N encolada(s), ..."; extraemos el número.
            val n = Regex("""(\d+)\s+encolada""").find(resp)?.groupValues?.get(1)?.toIntOrNull()
                ?: clean.size
            Outcome.Ok(AddResult(n))
        } catch (e: java.net.SocketTimeoutException) {
            Outcome.Err("PkgDrop no responde en $ps4Ip:$PKGDROP_PORT. ¿La app está abierta en la PS4?")
        } catch (e: java.net.ConnectException) {
            Outcome.Err("No se pudo conectar a PkgDrop en $ps4Ip:$PKGDROP_PORT. ¿La app está abierta?")
        } catch (e: Exception) {
            Outcome.Err(e.message ?: e.javaClass.simpleName)
        }
    }
}

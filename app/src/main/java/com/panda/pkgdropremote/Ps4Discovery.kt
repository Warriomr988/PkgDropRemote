package com.panda.pkgdropremote

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Descubrimiento de la PS4 en la red local (patrón DDP — Device Discovery
 * Protocol de PlayStation).
 *
 * Envía `SRCH * HTTP/1.1` por UDP broadcast al puerto 987 y parsea las
 * respuestas (hostname, firmware, modelo). Implementación propia a partir
 * del formato del protocolo; no se copió código de terceros.
 */
object Ps4Discovery {

    const val DDP_PORT = 987
    private const val DDP_REQUEST =
        "SRCH * HTTP/1.1\r\ndevice-discovery-protocol-version:00020020\r\n\r\n"

    data class Ps4(
        val ip: String,
        val hostname: String,
        val firmware: String,
        val model: String
    ) {
        val label: String
            get() = if (hostname.isNotBlank()) "$hostname ($ip)" else ip
    }

    /**
     * Busca PS4s durante [timeoutMs]. Devuelve la lista encontrada
     * (puede estar vacía).
     */
    fun discover(timeoutMs: Int = 3000): List<Ps4> {
        val found = linkedMapOf<String, Ps4>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            val req = DDP_REQUEST.toByteArray(Charsets.UTF_8)
            val broadcast = InetAddress.getByName("255.255.255.255")
            socket.send(DatagramPacket(req, req.size, broadcast, DDP_PORT))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val ip = pkt.address.hostAddress ?: continue
                    val body = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    if (!body.contains("device-discovery-protocol-version", ignoreCase = true)) continue
                    if (found.containsKey(ip)) continue
                    found[ip] = Ps4(
                        ip = ip,
                        hostname = headerValue(body, "host-name"),
                        firmware = parseFirmware(headerValue(body, "system-version")),
                        model = parseModel(headerValue(body, "host-type"), body)
                    )
                } catch (_: java.net.SocketTimeoutException) {
                    break
                }
            }
        } catch (_: Exception) {
            // Sin red / sin permiso: se devuelve lo encontrado (vacío).
        } finally {
            try { socket?.close() } catch (_: Exception) { }
        }
        return found.values.toList()
    }

    private fun headerValue(body: String, name: String): String {
        for (line in body.lines()) {
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals(name, ignoreCase = true)) {
                return line.substring(idx + 1).trim()
            }
        }
        return ""
    }

    /**
     * `system-version` puede venir como "11.00000" o en hexadecimal.
     * Devuelve la mejor representación legible.
     */
    private fun parseFirmware(raw: String): String {
        if (raw.isBlank()) return ""
        if (raw.contains('.')) return raw
        // Intento hex → bytes ASCII (ej. "30392e3030303030" = "09.00000")
        return try {
            val clean = raw.trim()
            if (clean.length % 2 != 0 || !clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                return raw
            }
            val bytes = ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            val ascii = String(bytes, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
            if (ascii.isNotBlank() && ascii.all { it.isDigit() || it == '.' }) ascii else raw
        } catch (_: Exception) {
            raw
        }
    }

    private fun parseModel(hostType: String, body: String): String {
        if (hostType.isNotBlank()) return hostType
        // Algunos firmwares traen el modelo como CUH-xxxx en otra cabecera.
        val m = Regex("""CUH-\d+[A-Z]?""").find(body)
        return m?.value ?: ""
    }
}

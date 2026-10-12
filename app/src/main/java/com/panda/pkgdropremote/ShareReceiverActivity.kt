package com.panda.pkgdropremote

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Recibe `ACTION_SEND` (compartir enlace desde cualquier app o navegador):
 * extrae las URLs del texto y las encola para delegarlas a PkgDrop.
 */
class ShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            val urls = extractUrls(text)
            if (urls.isEmpty()) {
                Toast.makeText(this, "No se encontró ningún enlace para compartir", Toast.LENGTH_SHORT).show()
            } else {
                RemoteService.enqueueUrls(this, urls)
                Toast.makeText(
                    this,
                    if (urls.size == 1) "Enlace encolado → PkgDrop"
                    else "${urls.size} enlaces encolados → PkgDrop",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        finish()
    }

    private fun extractUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return Regex("""https?://[^\s"'<>]+""")
            .findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ')', ']') }
            .filter { it.length > 12 }
            .distinct()
            .toList()
    }
}

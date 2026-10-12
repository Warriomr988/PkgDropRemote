package com.panda.pkgdropremote

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Cola de trabajos: URLs para delegar a PkgDrop o archivos para servir
 * desde el teléfono. Persiste entre sesiones.
 */
class QueueDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    /** Origen del trabajo. */
    enum class Kind { URL, FILE }

    /** Estado del trabajo. */
    enum class State {
        PENDING,      // encolado, sin procesar
        WORKING,      // procesándose (delegando / sirviendo / instalando)
        PAUSED,       // pausado por el usuario
        DELEGATED,    // entregado a PkgDrop (él sigue)
        DONE,         // instalado OK
        ERROR
    }

    data class Item(
        val id: Long,
        val kind: Kind,
        val url: String?,
        val fileUri: String?,
        val fileName: String,
        val sizeBytes: Long,
        val state: State,
        val taskId: String?,
        val transferred: Long,
        val total: Long,
        val restSec: Long,
        val error: String?,
        val createdAt: Long
    )

    companion object {
        private const val DB_NAME = "pkgdropremote.db"
        private const val DB_VERSION = 1

        /** Nombre de archivo legible desde una URL. */
        fun fileNameFromUrl(url: String): String {
            return try {
                var p = url.substringBefore("?").substringAfterLast("/")
                if (p.isBlank()) p = "pkg.pkg"
                java.net.URLDecoder.decode(p, "UTF-8")
                    .replace(Regex("""[^A-Za-z0-9._\- ]"""), "_")
                    .ifEmpty { "pkg.pkg" }
            } catch (_: Exception) {
                "pkg.pkg"
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE queue(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                url TEXT,
                file_uri TEXT,
                file_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL DEFAULT -1,
                state TEXT NOT NULL DEFAULT 'PENDING',
                task_id TEXT,
                transferred INTEGER NOT NULL DEFAULT 0,
                total INTEGER NOT NULL DEFAULT -1,
                rest_sec INTEGER NOT NULL DEFAULT -1,
                error TEXT,
                created_at INTEGER NOT NULL
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { }

    @Synchronized
    fun insertUrl(url: String, fileName: String): Long {
        val cv = ContentValues().apply {
            put("kind", Kind.URL.name)
            put("url", url)
            put("file_name", fileName)
            put("state", State.PENDING.name)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("queue", null, cv)
    }

    @Synchronized
    fun insertFile(fileUri: String, fileName: String, sizeBytes: Long): Long {
        val cv = ContentValues().apply {
            put("kind", Kind.FILE.name)
            put("file_uri", fileUri)
            put("file_name", fileName)
            put("size_bytes", sizeBytes)
            put("state", State.PENDING.name)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("queue", null, cv)
    }

    @Synchronized
    fun update(id: Long, values: ContentValues) {
        writableDatabase.update("queue", values, "id=?", arrayOf(id.toString()))
    }

    fun setState(id: Long, state: State, error: String? = null) {
        val cv = ContentValues().apply {
            put("state", state.name)
            if (error != null) put("error", error) else putNull("error")
        }
        update(id, cv)
    }

    fun setTaskId(id: Long, taskId: String?) {
        val cv = ContentValues().apply {
            if (taskId == null) putNull("task_id") else put("task_id", taskId)
        }
        update(id, cv)
    }

    fun setProgress(id: Long, transferred: Long, total: Long, restSec: Long) {
        val cv = ContentValues().apply {
            put("transferred", transferred)
            put("total", total)
            put("rest_sec", restSec)
        }
        update(id, cv)
    }

    @Synchronized
    fun delete(id: Long) {
        writableDatabase.delete("queue", "id=?", arrayOf(id.toString()))
    }

    @Synchronized
    fun clearFinished() {
        writableDatabase.delete(
            "queue", "state IN (?,?,?)",
            arrayOf(State.DONE.name, State.DELEGATED.name, State.ERROR.name)
        )
    }

    @Synchronized
    fun get(id: Long): Item? {
        readableDatabase.query("queue", null, "id=?", arrayOf(id.toString()),
            null, null, null)?.use { c ->
            if (c.moveToFirst()) return row(c)
        }
        return null
    }

    @Synchronized
    fun all(): List<Item> {
        val out = mutableListOf<Item>()
        readableDatabase.query("queue", null, null, null, null, null, "created_at ASC")?.use { c ->
            while (c.moveToNext()) out.add(row(c))
        }
        return out
    }

    @Synchronized
    fun nextPending(): Item? {
        readableDatabase.query("queue", null, "state=?", arrayOf(State.PENDING.name),
            null, null, "created_at ASC", "1")?.use { c ->
            if (c.moveToFirst()) return row(c)
        }
        return null
    }

    private fun row(c: android.database.Cursor): Item {
        fun s(name: String): String? = c.getString(c.getColumnIndexOrThrow(name))
        fun l(name: String): Long = c.getLong(c.getColumnIndexOrThrow(name))
        return Item(
            id = l("id"),
            kind = Kind.valueOf(s("kind") ?: "URL"),
            url = s("url"),
            fileUri = s("file_uri"),
            fileName = s("file_name") ?: "?",
            sizeBytes = l("size_bytes"),
            state = try { State.valueOf(s("state") ?: "PENDING") } catch (_: Exception) { State.PENDING },
            taskId = s("task_id"),
            transferred = l("transferred"),
            total = l("total"),
            restSec = l("rest_sec"),
            error = s("error"),
            createdAt = l("created_at")
        )
    }
}

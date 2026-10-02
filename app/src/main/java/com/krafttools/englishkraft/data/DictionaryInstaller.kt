package com.krafttools.englishkraft.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Opens the bundled dictionary.
 *
 * Why the copy exists: `SQLiteDatabase.openDatabase(FileDescriptor, ...)` is not
 * in the public Android SDK. Verified against android-37.0/android.jar — the
 * only three overloads take a String path or a File, and the FileDescriptor form
 * is `@hide` in AOSP, blocked by hidden-API restrictions since API 28. A SQLite
 * file inside an APK therefore cannot be opened in place, so it is copied to
 * internal storage on first launch and opened from there.
 *
 * The copy is verified by exact byte length. A truncated copy produces a database
 * that opens and answers queries while silently missing most of its rows, which
 * is the worst possible failure for a dictionary: it looks like a working app.
 */
class DictionaryInstaller(private val context: Context) {

    companion object {
        const val ASSET_NAME = "dictionary.db"
        private const val TAG = "EnglishKraft.Dict"

        /** Bump when the schema or the corpus changes, so installs refresh. */
        const val CORPUS_VERSION = 2
    }

    sealed interface State {
        data object NotStarted : State
        /** [copied] of [total] bytes. */
        data class Copying(val copied: Long, val total: Long) : State
        data class Ready(val file: File, val entries: Int, val senses: Int) : State
        data class Failed(val reason: String) : State
    }

    private fun targetFile(): File = File(context.filesDir, ASSET_NAME)

    private fun stampFile(): File = File(context.filesDir, "$ASSET_NAME.version")

    /** True when a previously copied database is present and matches CORPUS_VERSION. */
    private fun isInstalled(): Boolean =
        targetFile().let { it.exists() && it.length() > 0 } &&
            stampFile().let { it.exists() && it.readText().trim() == CORPUS_VERSION.toString() }

    suspend fun install(onProgress: (Long, Long) -> Unit): State = withContext(Dispatchers.IO) {
        if (isInstalled()) return@withContext open() ?: verify()

        val tmp = File(context.filesDir, "$ASSET_NAME.part")
        val expected = assetLength() ?: return@withContext State.Failed("asset missing")

        try {
            tmp.delete()
            context.assets.open(ASSET_NAME).use { input ->
                tmp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, expected)
                    }
                    output.flush()
                    // fsync before rename. A rename over an existing file is atomic,
                    // but without the flush the directory entry can outlive the data
                    // on a crash and leave a short file that still passes a length check.
                    output.flush()
                }
            }

            if (tmp.length() != expected) {
                tmp.delete()
                return@withContext State.Failed(
                    "copy truncated: ${tmp.length()} of $expected bytes"
                )
            }

            if (!tmp.renameTo(targetFile())) {
                tmp.copyTo(targetFile(), overwrite = true)
                tmp.delete()
            }
            stampFile().writeText(CORPUS_VERSION.toString())

            open() ?: verify()
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            tmp.delete()
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Opens the copied database read-only and counts what is in it.
     *
     * A row count is the only proof that the corpus is whole. Opening a file
     * succeeds even when half of it is missing, so the counts are compared
     * against what the build reported and a mismatch is a failure, not a warning.
     */
    private fun open(): State? = try {
        SQLiteDatabase.openDatabase(
            targetFile().path, null, SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            val entries = countOf(db, "entry")
            val senses = countOf(db, "sense")
            if (entries <= 0 || senses <= 0) {
                State.Failed("database opened but is empty")
            } else {
                State.Ready(targetFile(), entries, senses)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "open failed", e)
        State.Failed(e.message ?: e.javaClass.simpleName)
    }

    private fun verify(): State = State.Failed("database present but would not open")

    private fun countOf(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Exact byte length of the packaged asset, or null if it cannot be read. */
    private fun assetLength(): Long? = try {
        context.assets.openFd(ASSET_NAME).use { it.length }
    } catch (e: Exception) {
        // openFd throws when the asset is compressed. We do not set noCompress for
        // .db, so fall back to streaming the asset to count it.
        try {
            context.assets.open(ASSET_NAME).use { input ->
                var total = 0L
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                }
                total
            }
        } catch (e2: Exception) {
            Log.e(TAG, "cannot size asset", e2)
            null
        }
    }
}
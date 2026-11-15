package com.iameberhard.icharge.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * The one model this app runs: Gemma 4 E2B instruction-tuned, 4-bit (Q4_K_M), from Unsloth's GGUF
 * repo. ~3.1 GB on disk, ~3.6 GB of RAM while loaded. Fetched on first use with a resumable
 * download, and never trusted until the file has the GGUF magic and the exact published size:
 * a truncated or HTML-spliced file produces gibberish rather than an error.
 */
object GemmaModel {
    const val NAME = "Gemma 4 E2B"
    const val FILE = "gemma-4-E2B-it-Q4_K_M.gguf"
    const val DOWNLOAD_URL = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/$FILE"
    const val SIZE = 3_106_738_272L
    const val CONTEXT = 4096
    const val RAM_MB = 3600

    fun dir(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "models").apply { mkdirs() }
    fun file(c: Context): File = File(dir(c), FILE)
    fun part(c: Context): File = File(dir(c), "$FILE.part")

    /** Null when the file looks right; otherwise why it can't be used. */
    fun damage(f: File): String? {
        if (!f.exists()) return "not downloaded"
        val len = f.length()
        val head = runCatching { RandomAccessFile(f, "r").use { r -> ByteArray(4).also { r.readFully(it) } } }.getOrNull() ?: return "unreadable"
        if (String(head, Charsets.US_ASCII) != "GGUF") return "not a GGUF file (a web page was saved instead?)"
        if (len != SIZE) return if (len < SIZE) "incomplete (${len * 100 / SIZE}%)" else "wrong size"
        return null
    }

    fun installed(c: Context): Boolean = damage(file(c)) == null

    /** Bytes already on disk, for the progress line before a download resumes. */
    fun partialBytes(c: Context): Long = part(c).takeIf { it.exists() }?.length() ?: 0L

    fun delete(c: Context) { file(c).delete(); part(c).delete() }

    /**
     * Downloads (or resumes) the model. Progress is reported in bytes. Throws on any failure after
     * leaving the partial file in place for the next attempt, except when the partial file itself
     * is bad, which deletes it.
     */
    suspend fun download(c: Context, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val part = part(c)
        val target = file(c)
        if (damage(target) == null) return@withContext
        target.delete()
        var have = part.length()
        if (have > SIZE) { part.delete(); have = 0 }
        if (have >= 4) {
            val head = RandomAccessFile(part, "r").use { r -> ByteArray(4).also { r.readFully(it) } }
            if (String(head, Charsets.US_ASCII) != "GGUF") { part.delete(); have = 0 }
        }
        var attempt = 0
        while (have < SIZE) {
            coroutineContext.ensureActive()
            attempt++
            try {
                val conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "iCharge")
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                val type = conn.contentType ?: ""
                if (type.contains("text/html")) throw IllegalStateException("Hugging Face answered with a web page, not the model (try again later)")
                when {
                    code == 200 -> { have = 0 }                      // server ignored the range: start over
                    code == 206 -> {
                        val range = conn.getHeaderField("Content-Range") ?: ""
                        val start = range.substringAfter("bytes ").substringBefore('-').toLongOrNull()
                        if (start != have) throw IllegalStateException("resume mismatch ($range)")
                    }
                    else -> throw IllegalStateException("HTTP $code")
                }
                conn.inputStream.use { input ->
                    RandomAccessFile(part, "rw").use { out ->
                        out.setLength(have); out.seek(have)
                        val buf = ByteArray(256 * 1024)
                        var lastReport = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            if (have - lastReport > 2L * 1024 * 1024 || have == SIZE) { onProgress(have, SIZE); lastReport = have }
                        }
                    }
                }
                if (have < SIZE && attempt >= 8) throw IllegalStateException("the connection keeps closing early")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w("GemmaModel", "download attempt $attempt failed at $have: ${e.message}")
                if (attempt >= 8) throw e
                kotlinx.coroutines.delay(2000L * attempt)
            }
        }
        val why = damage(part)
        if (why != null) { part.delete(); throw IllegalStateException("Downloaded file is $why; deleted, please download again") }
        if (!part.renameTo(target)) throw IllegalStateException("could not move the model into place")
    }
}

/** A loaded engine. Create with [load], generate, [close] to free ~3.6 GB. */
class GemmaEngine private constructor(private val handle: Long) {
    companion object {
        suspend fun load(c: Context): GemmaEngine = withContext(Dispatchers.Default) {
            check(LlamaNative.available) { "This build has no AI engine" }
            val f = GemmaModel.file(c)
            GemmaModel.damage(f)?.let { throw IllegalStateException("Model file is $it") }
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val h = LlamaNative.init(f.absolutePath, GemmaModel.CONTEXT, threads)
            check(h != 0L) { "The model could not be loaded (not enough free memory?)" }
            GemmaEngine(h)
        }
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** Runs one completion for an already-formatted Gemma prompt, streaming pieces of text. */
    suspend fun generate(prompt: String, maxTokens: Int = 700, temperature: Float = 0.6f, onText: (String) -> Unit): Int =
        withContext(Dispatchers.Default) {
            _busy.value = true
            val job = coroutineContext[kotlinx.coroutines.Job]
            try {
                val stops = listOf("<turn|>", "<|turn>")
                val sb = StringBuilder()
                var sent = 0
                val rc = LlamaNative.generate(handle, prompt, maxTokens, temperature, object : LlamaNative.TokenListener {
                    override fun onToken(bytes: ByteArray): Boolean {
                        sb.append(String(bytes, Charsets.UTF_8))
                        val text = sb.toString()
                        val stopAt = stops.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull()
                        if (stopAt != null) {
                            if (stopAt > sent) onText(text.substring(sent, stopAt)); sent = stopAt
                            return false
                        }
                        // Hold back a tail that could be the start of a stop marker.
                        val safe = text.length - stops.maxOf { s -> (1 until s.length).lastOrNull { k -> text.endsWith(s.take(k)) } ?: 0 }
                        if (safe > sent) { onText(text.substring(sent, safe)); sent = safe }
                        return job?.isActive != false
                    }
                })
                val text = sb.toString()
                val stopAt = stops.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: text.length
                if (stopAt > sent) onText(text.substring(sent, stopAt))
                rc
            } finally {
                _busy.value = false
            }
        }

    fun stop() = LlamaNative.stop(handle)
    fun close() = LlamaNative.free(handle)
}

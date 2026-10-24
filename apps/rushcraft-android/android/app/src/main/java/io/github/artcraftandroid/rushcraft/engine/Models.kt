package io.github.artcraftandroid.rushcraft.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.rushcraft.core.ModelPackage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads the models captions and natural voices need, after the user agreed: each file from
 * its pinned address into the package's folder, checked against its SHA-256 before it is kept.
 * An interrupted file carries on where it stopped. One download at a time; it keeps going while
 * the user moves around the app.
 */
object ModelDownloads {
    /** A download in progress (or just failed: [error]). */
    data class State(val id: String, val name: String, val done: Long, val total: Long, val error: String? = null) {
        val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    }

    var state by mutableStateOf<State?>(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val busy: Boolean get() = state?.let { it.error == null } == true

    /** Download [pkg]'s missing files; [onDone] runs on success (on a background thread). */
    fun start(pkg: ModelPackage, onDone: () -> Unit = {}) {
        if (busy) return
        state = State(pkg.id, pkg.name, 0, pkg.missing.toLong())
        job = scope.launch {
            try {
                download(pkg)
                state = null
                onDone()
            } catch (e: kotlinx.coroutines.CancellationException) {
                state = null
                throw e
            } catch (e: Exception) {
                state = state?.copy(error = e.message?.takeIf { it.isNotBlank() } ?: "The download failed.")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
        state = null
    }

    /** Forget a failed download's message. */
    fun dismiss() {
        if (state?.error != null) state = null
    }

    private suspend fun download(pkg: ModelPackage) = withContext(Dispatchers.IO) {
        val dir = File(pkg.dir).apply { mkdirs() }
        if (!dir.isDirectory) throw IOException("The app's folder for models can't be used.")
        var done = 0L
        val total = pkg.missing.toLong()
        for (f in pkg.files) {
            val target = File(dir, f.name)
            if (target.isFile && target.length() == f.size.toLong()) continue
            val part = File(dir, f.name + ".part")
            if (part.length() > f.size.toLong()) part.delete()
            val sha = MessageDigest.getInstance("SHA-256")
            // what an earlier try left counts towards the checksum
            if (part.isFile) part.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sha.update(buf, 0, n)
                }
            }
            var have = part.length()
            done += have
            state = state?.copy(done = done)
            val conn = (URL(f.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                val append = code == HttpURLConnection.HTTP_PARTIAL && have > 0
                if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) throw IOException("The download failed (HTTP $code). Try again later.")
                if (!append && have > 0) {
                    // the server started over: so do we
                    done -= have
                    have = 0
                    sha.reset()
                }
                conn.inputStream.use { input ->
                    FileOutputStream(part, append).use { out ->
                        val buf = ByteArray(1 shl 16)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            sha.update(buf, 0, n)
                            have += n
                            done += n
                            if (have > f.size.toLong()) throw IOException("${f.name} is bigger than expected.")
                            state = state?.copy(done = done.coerceAtMost(total))
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (have != f.size.toLong() || !hex.equals(f.sha256, ignoreCase = true)) {
                part.delete()
                throw IOException("${f.name} didn't download correctly. Try again.")
            }
            if (!part.renameTo(target)) throw IOException("${f.name} couldn't be saved.")
        }
    }
}

/** "154 MB" */
fun megabytes(bytes: Long): String = "${(bytes + 500_000) / 1_000_000} MB"

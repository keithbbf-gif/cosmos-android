package com.cosmos.voice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks the small English VOSK model (~40 MB zip) on first run.
 * The model is NOT bundled in the APK or the repo — it is fetched once from the
 * official alphacephei URL into app-private storage, after which recognition is
 * fully offline.
 *
 * Hardening:
 *  - download size cap (a hijacked/renamed URL cannot fill the disk)
 *  - unpack size cap (zip-bomb guard, counted on DECOMPRESSED bytes)
 *  - zip-slip guard compares against the canonical root WITH a trailing
 *    separator (a sibling dir like ".../files_evil" passes a bare
 *    ".../files" prefix test)
 *  - ATOMIC install: unzip into a staging dir, then rename into place, so a
 *    crash or truncated archive can never leave a half-written model where
 *    isReady() might half-pass
 *  - optional pinned SHA-256, verified when set
 */
object ModelManager {

    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    // The real zip is ~40 MB; anything past this is not the model we asked for.
    private const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024
    // Decompressed cap — the real model unpacks to well under this.
    private const val MAX_UNPACKED_BYTES = 512L * 1024 * 1024

    // Integrity pin: set when a manifest/release publishes one; verified if set.
    private val EXPECTED_SHA256: String? = null

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_NAME)

    /** True when the unpacked model looks complete. */
    fun isReady(context: Context): Boolean {
        val d = modelDir(context)
        return File(d, "am/final.mdl").exists() && File(d, "conf/mfcc.conf").exists()
    }

    /**
     * Blocking download + unzip. Call on Dispatchers.IO.
     * onProgress(percent, phase) with phase "downloading" or "unzipping".
     */
    fun download(context: Context, onProgress: (Int, String) -> Unit) {
        val zipFile = File(context.cacheDir, "$MODEL_NAME.zip")
        val staging = File(context.filesDir, ".staging-$MODEL_NAME")
        try {
            // 1. Download the zip (size-capped).
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            try {
                val total = conn.contentLengthLong
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw IllegalStateException(
                        "Model download too large: $total bytes (cap $MAX_DOWNLOAD_BYTES)."
                    )
                }
                conn.inputStream.use { input ->
                    FileOutputStream(zipFile).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > MAX_DOWNLOAD_BYTES) {
                                throw IllegalStateException(
                                    "Model download exceeded the " +
                                        "${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB cap — aborted."
                                )
                            }
                            out.write(buf, 0, n)
                            if (total > 0) {
                                onProgress(((done * 100) / total).toInt(), "downloading")
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            // 1b. Verify the pinned checksum when one is published.
            EXPECTED_SHA256?.let { expected ->
                val actual = sha256(zipFile)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IllegalStateException(
                        "Model checksum mismatch: expected $expected, got $actual."
                    )
                }
            }

            // 2. Unzip into STAGING. Entries are prefixed
            //    "vosk-model-small-en-us-0.15/", so the model lands at
            //    staging/<MODEL_NAME>/ and is renamed into place afterwards.
            onProgress(100, "unzipping")
            staging.deleteRecursively()
            staging.mkdirs()
            // Zip-slip guard root: canonical path WITH trailing separator, so a
            // sibling like ".staging-xxx_evil" can never pass the prefix test.
            val rootPrefix = staging.canonicalPath + File.separator
            var unpacked = 0L
            ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val outFile = File(staging, entry.name)
                    if (!outFile.canonicalPath.startsWith(rootPrefix)) {
                        zin.closeEntry()
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            unpacked = copyCapped(zin, fos, unpacked, MAX_UNPACKED_BYTES)
                        }
                    }
                    zin.closeEntry()
                }
            }

            // 3. ATOMIC install: staged model dir -> final location (same
            //    filesystem, single rename).
            val staged = File(staging, MODEL_NAME)
            if (!staged.isDirectory) {
                throw IllegalStateException("Archive did not contain $MODEL_NAME/.")
            }
            val dest = modelDir(context)
            dest.deleteRecursively()
            if (!staged.renameTo(dest)) {
                throw IllegalStateException("Could not move the unpacked model into place.")
            }

            if (!isReady(context)) {
                throw IllegalStateException("Model unzip finished but model files are missing.")
            }
        } finally {
            zipFile.delete()
            staging.deleteRecursively()
        }
    }

    /** Copy [input] to [out], adding to [already]; throws past [cap]. Returns the new total. */
    internal fun copyCapped(input: InputStream, out: FileOutputStream, already: Long, cap: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = already
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) {
                throw IllegalStateException(
                    "Archive expands past the ${cap / (1024 * 1024)} MB unpack cap — aborted."
                )
            }
            out.write(buf, 0, n)
        }
        return total
    }

    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

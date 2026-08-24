package com.cosmos.voice

import android.content.Context
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and unpacks the Piper VITS voice for the bundled sherpa-onnx TTS
 * (~20 MB tar.bz2) on first run — the exact same shape as ModelManager does
 * for the VOSK speech-IN model. The voice is NOT bundled in the APK or the
 * repo — it is fetched once from the official k2-fsa/sherpa-onnx release URL
 * into app-private storage, after which speech-OUT is fully offline with ZERO
 * dependency on any device TTS engine or Google service.
 *
 * The archive bundles espeak-ng-data/, which Piper voices REQUIRE for
 * phonemization — the completeness check below verifies it, not just the
 * .onnx, because a model dir that "exists" but lacks espeak-ng-data loads
 * and then fails at synthesis time.
 *
 * Hardening (mirrors ModelManager): download + unpack size caps, tar-slip
 * guard with a trailing-separator root prefix, ATOMIC staged install, and an
 * optional pinned SHA-256 verified when set.
 */
object TtsModelManager {

    // int8-quantized Amy (en_US, low quality tier): 20 MB download, ~34 MB on
    // disk, decent small English voice. The un-quantized variant is 64 MB for
    // a marginal quality gain — not worth 3x the first-run download.
    const val MODEL_DIR_NAME = "vits-piper-en_US-amy-low-int8"
    const val MODEL_FILE = "en_US-amy-low.onnx"
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low-int8.tar.bz2"

    // The real archive is ~20 MB; anything past this is not the voice we asked for.
    private const val MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024
    // Decompressed cap — the real voice unpacks to ~34 MB.
    private const val MAX_UNPACKED_BYTES = 256L * 1024 * 1024

    // Integrity pin: set when a manifest/release publishes one; verified if set.
    private val EXPECTED_SHA256: String? = null

    fun modelDir(context: Context): File = File(context.filesDir, MODEL_DIR_NAME)

    /** True when the unpacked voice looks complete: model + tokens + the
     *  espeak-ng-data files Piper needs for phonemization. */
    fun isReady(context: Context): Boolean {
        val d = modelDir(context)
        return File(d, MODEL_FILE).exists() &&
            File(d, "tokens.txt").exists() &&
            File(d, "espeak-ng-data/phontab").exists() &&
            File(d, "espeak-ng-data/en_dict").exists()
    }

    /**
     * Blocking download + untar. Call on Dispatchers.IO.
     * onProgress(percent, phase) with phase "downloading voice" or "unpacking voice".
     */
    fun download(context: Context, onProgress: (Int, String) -> Unit) {
        val archive = File(context.cacheDir, "$MODEL_DIR_NAME.tar.bz2")
        val staging = File(context.filesDir, ".staging-$MODEL_DIR_NAME")
        try {
            // 1. Download the tar.bz2 (GitHub 302-redirects to its CDN; https->https
            //    redirects are followed automatically). Size-capped.
            val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            try {
                val total = conn.contentLengthLong
                if (total > MAX_DOWNLOAD_BYTES) {
                    throw IllegalStateException(
                        "Voice download too large: $total bytes (cap $MAX_DOWNLOAD_BYTES)."
                    )
                }
                conn.inputStream.use { input ->
                    FileOutputStream(archive).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > MAX_DOWNLOAD_BYTES) {
                                throw IllegalStateException(
                                    "Voice download exceeded the " +
                                        "${MAX_DOWNLOAD_BYTES / (1024 * 1024)} MB cap — aborted."
                                )
                            }
                            out.write(buf, 0, n)
                            if (total > 0) {
                                onProgress(((done * 100) / total).toInt(), "downloading voice")
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            // 1b. Verify the pinned checksum when one is published.
            EXPECTED_SHA256?.let { expected ->
                val actual = ModelManager.sha256(archive)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IllegalStateException(
                        "Voice checksum mismatch: expected $expected, got $actual."
                    )
                }
            }

            // 2. Untar into STAGING. Entries are prefixed
            //    "vits-piper-en_US-amy-low-int8/", so the voice lands at
            //    staging/<MODEL_DIR_NAME>/ and is renamed into place afterwards.
            onProgress(100, "unpacking voice")
            staging.deleteRecursively()
            staging.mkdirs()
            // Tar-slip guard root: canonical path WITH trailing separator (same
            // class as the zip-slip guard in ModelManager — a bare prefix test
            // lets a sibling like ".staging-xxx_evil" through).
            val rootPrefix = staging.canonicalPath + File.separator
            var unpacked = 0L
            TarArchiveInputStream(
                BZip2CompressorInputStream(archive.inputStream().buffered())
            ).use { tin ->
                while (true) {
                    val entry = tin.nextEntry ?: break
                    val outFile = File(staging, entry.name)
                    if (!outFile.canonicalPath.startsWith(rootPrefix)) {
                        continue
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            unpacked = ModelManager.copyCapped(tin, fos, unpacked, MAX_UNPACKED_BYTES)
                        }
                    }
                }
            }

            // 3. ATOMIC install: staged voice dir -> final location.
            val staged = File(staging, MODEL_DIR_NAME)
            if (!staged.isDirectory) {
                throw IllegalStateException("Archive did not contain $MODEL_DIR_NAME/.")
            }
            val dest = modelDir(context)
            dest.deleteRecursively()
            if (!staged.renameTo(dest)) {
                throw IllegalStateException("Could not move the unpacked voice into place.")
            }

            if (!isReady(context)) {
                throw IllegalStateException("Voice unpack finished but voice files are missing.")
            }
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }
}

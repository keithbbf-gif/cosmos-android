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
 */
object TtsModelManager {

    // int8-quantized Amy (en_US, low quality tier): 20 MB download, ~34 MB on
    // disk, decent small English voice. The un-quantized variant is 64 MB for
    // a marginal quality gain — not worth 3x the first-run download.
    const val MODEL_DIR_NAME = "vits-piper-en_US-amy-low-int8"
    const val MODEL_FILE = "en_US-amy-low.onnx"
    private const val MODEL_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low-int8.tar.bz2"

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

        // 1. Download the tar.bz2 (GitHub 302-redirects to its CDN; https->https
        //    redirects are followed automatically).
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        try {
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(archive).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            onProgress(((done * 100) / total).toInt(), "downloading voice")
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        // 2. Untar into filesDir. Entries are prefixed
        //    "vits-piper-en_US-amy-low-int8/", so the voice lands at modelDir().
        onProgress(100, "unpacking voice")
        val destRoot = context.filesDir
        TarArchiveInputStream(
            BZip2CompressorInputStream(archive.inputStream().buffered())
        ).use { tin ->
            while (true) {
                val entry = tin.nextEntry ?: break
                val outFile = File(destRoot, entry.name)
                // Tar-slip guard (same class as the zip-slip guard in ModelManager).
                if (!outFile.canonicalPath.startsWith(destRoot.canonicalPath)) {
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> tin.copyTo(fos) }
                }
            }
        }
        archive.delete()

        if (!isReady(context)) {
            throw IllegalStateException("Voice unpack finished but voice files are missing.")
        }
    }
}

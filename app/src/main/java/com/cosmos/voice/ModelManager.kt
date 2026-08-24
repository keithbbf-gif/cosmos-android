package com.cosmos.voice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Downloads and unpacks the small English VOSK model (~40 MB zip) on first run.
 * The model is NOT bundled in the APK or the repo — it is fetched once from the
 * official alphacephei URL into app-private storage, after which recognition is
 * fully offline.
 */
object ModelManager {

    private const val MODEL_NAME = "vosk-model-small-en-us-0.15"
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

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

        // 1. Download the zip.
        val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        conn.instanceFollowRedirects = true
        try {
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                FileOutputStream(zipFile).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            onProgress(((done * 100) / total).toInt(), "downloading")
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        // 2. Unzip into filesDir. Entries are prefixed "vosk-model-small-en-us-0.15/",
        //    so the model lands at modelDir(context).
        onProgress(100, "unzipping")
        val destRoot = context.filesDir
        ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val outFile = File(destRoot, entry.name)
                // Zip-slip guard.
                if (!outFile.canonicalPath.startsWith(destRoot.canonicalPath)) {
                    zin.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zin.copyTo(fos) }
                }
                zin.closeEntry()
            }
        }
        zipFile.delete()

        if (!isReady(context)) {
            throw IllegalStateException("Model unzip finished but model files are missing.")
        }
    }
}

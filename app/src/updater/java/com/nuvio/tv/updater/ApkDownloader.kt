package com.nuvio.tv.updater

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when the bytes written do not match the declared Content-Length. */
class IncompleteDownloadException(
    val downloadedBytes: Long,
    val totalBytes: Long
) : IllegalStateException("Incomplete download: $downloadedBytes/$totalBytes bytes")

@Singleton
class ApkDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient
) {

    suspend fun download(
        url: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Result<File> {
        return runCatching {
            destinationFile.parentFile?.mkdirs()
            if (destinationFile.exists()) destinationFile.delete()

            val request = Request.Builder()
                .url(url)
                .build()

            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed: HTTP ${response.code}")
                }

                val body = response.body ?: error("Empty download body")
                val total = body.contentLength().takeIf { it > 0 }

                body.byteStream().use { input ->
                    FileOutputStream(destinationFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                        output.flush()
                    }
                }

                // Fork: a silent truncation used to surface later as a bogus
                // "signature mismatch" (or a missing file). Fail here instead.
                val written = destinationFile.length()
                if (total != null && written != total) {
                    destinationFile.delete()
                    throw IncompleteDownloadException(written, total)
                }
            }

            destinationFile
        }
    }
}

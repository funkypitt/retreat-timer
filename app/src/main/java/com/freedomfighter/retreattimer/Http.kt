package com.freedomfighter.retreattimer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal blocking HTTP GET helpers with manual redirect following — the same
 * code proven in the kDrive client. Keeps the app dependency-free. Used to read
 * podcast RSS feeds and stream their enclosure downloads. All calls must run off
 * the main thread.
 */
object Http {

    fun getString(url: String): String =
        open(url).second.use { it.readBytes().toString(Charsets.UTF_8) }

    /** Stream [url] into [dest]; deletes the partial file and rethrows on failure. */
    fun download(url: String, dest: File) {
        try {
            open(url).second.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
    }

    private fun open(urlStr: String, redirects: Int = 0): Pair<Long, InputStream> {
        if (redirects > 5) throw IOException("Too many redirects")
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = false
        }
        val code = conn.responseCode
        if (code in 300..399) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrEmpty()) throw IOException("Redirect with no Location")
            return open(URL(URL(urlStr), location).toString(), redirects + 1)
        }
        if (code !in 200..299) {
            val err = runCatching { conn.errorStream?.readBytes()?.toString(Charsets.UTF_8) }.getOrNull()
            conn.disconnect()
            throw IOException("HTTP $code${if (err != null) ": ${err.take(200)}" else ""}")
        }
        return conn.contentLengthLong to conn.inputStream
    }
}

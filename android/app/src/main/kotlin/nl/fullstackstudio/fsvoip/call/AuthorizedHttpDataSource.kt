// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The HTTP source ExoPlayer reads audio with. The stock source sends the same request headers to every hop of a redirect, so a
// redirect to another host would carry the device token along. This one follows redirects itself ([MediaAuthPolicy]) and sends the
// `Authorization` header to the host of the API only, also after a redirect.

package nl.fullstackstudio.fsvoip.call

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InputStream
import nl.fullstackstudio.fsvoip.core.MediaAuthPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

@UnstableApi
class AuthorizedHttpDataSource(
    private val client: OkHttpClient,
    private val apiBaseUrl: String,
    private val headers: Map<String, String>,
    private val userAgent: String,
) : BaseDataSource(/* isNetwork = */ true) {
    private var response: Response? = null
    private var stream: InputStream? = null
    private var uri: Uri? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)

        var url = dataSpec.uri.toString()
        var hops = 0
        var current: Response

        while (true) {
            val builder = Request.Builder().url(url).header("User-Agent", userAgent)

            for ((name, value) in MediaAuthPolicy.headersFor(url, apiBaseUrl, headers)) builder.header(name, value)

            if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1).toString()
                builder.header("Range", "bytes=${dataSpec.position}-$end")
            }

            current = try {
                client.newCall(builder.build()).execute()
            } catch (error: IOException) {
                throw HttpDataSource.HttpDataSourceException.createForIOException(error, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }

            if (!current.isRedirect) break

            val next = MediaAuthPolicy.resolveRedirect(url, current.header("Location"))
            current.close()

            if (next == null || ++hops > MediaAuthPolicy.MAX_REDIRECTS) {
                throw HttpDataSource.HttpDataSourceException("Redirect refused", dataSpec, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
            }

            url = next
        }

        if (!current.isSuccessful) {
            val code = current.code
            val message = current.message
            val fields = current.headers.toMultimap()
            current.close()

            throw HttpDataSource.InvalidResponseCodeException(code, message, null, fields, dataSpec, ByteArray(0))
        }

        response = current
        uri = Uri.parse(url)

        val body = current.body ?: throw IOException("Empty response")
        val input = body.byteStream()
        stream = input

        // A server that ignores `Range` answers 200 with everything: skip to where the player wants to be.
        if (dataSpec.position > 0 && current.code != 206) {
            var toSkip = dataSpec.position

            while (toSkip > 0) {
                val skipped = input.skip(toSkip)

                if (skipped <= 0) break

                toSkip -= skipped
            }
        }

        val length = body.contentLength()
        remaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            length >= 0 -> if (current.code == 206 || dataSpec.position == 0L) length else (length - dataSpec.position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)

        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val count = try {
            stream?.read(buffer, offset, toRead) ?: C.RESULT_END_OF_INPUT
        } catch (error: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(error, DataSpec.Builder().setUri(uri ?: Uri.EMPTY).build(), HttpDataSource.HttpDataSourceException.TYPE_READ)
        }

        if (count == -1) return C.RESULT_END_OF_INPUT

        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        bytesTransferred(count)

        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            stream?.close()
        } finally {
            stream = null
            response?.close()
            response = null
            uri = null

            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(
        private val client: OkHttpClient,
        private val apiBaseUrl: String,
        private val headers: Map<String, String>,
        private val userAgent: String,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = AuthorizedHttpDataSource(client, apiBaseUrl, headers, userAgent)
    }
}

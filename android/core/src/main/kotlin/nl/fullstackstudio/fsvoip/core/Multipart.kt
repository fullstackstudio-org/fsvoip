// SPDX-License-Identifier: AGPL-3.0-or-later
//
// `multipart/form-data` for the one upload of the API (`POST /pbx/sounds`: exactly the parts `name` and `file`). Built by hand so
// the body is a plain byte array the transport can send, and the tests can read.

package nl.fullstackstudio.fsvoip.core

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

object Multipart {
    sealed interface Part {
        val name: String

        data class Text(override val name: String, val value: String) : Part

        data class File(override val name: String, val fileName: String, val contentType: String, val bytes: ByteArray) : Part {
            override fun equals(other: Any?): Boolean = this === other

            override fun hashCode(): Int = System.identityHashCode(this)
        }
    }

    fun randomBoundary(): String {
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }

        return "fsvoip" + bytes.joinToString("") { "%02x".format(it) }
    }

    fun build(parts: List<Part>, boundary: String = randomBoundary()): RawBody {
        val out = ByteArrayOutputStream()

        fun write(text: String) = out.write(text.toByteArray(Charsets.UTF_8))

        for (part in parts) {
            write("--$boundary\r\n")

            when (part) {
                is Part.Text -> {
                    write("Content-Disposition: form-data; name=\"${escape(part.name)}\"\r\n\r\n")
                    write(part.value)
                }
                is Part.File -> {
                    write("Content-Disposition: form-data; name=\"${escape(part.name)}\"; filename=\"${escape(part.fileName)}\"\r\n")
                    write("Content-Type: ${part.contentType}\r\n\r\n")
                    out.write(part.bytes)
                }
            }

            write("\r\n")
        }

        write("--$boundary--\r\n")

        return RawBody(out.toByteArray(), "multipart/form-data; boundary=$boundary")
    }

    /** A quote or a line break in a name must not end the header early. */
    private fun escape(value: String): String = value.replace("\r", " ").replace("\n", " ").replace("\"", "%22")
}

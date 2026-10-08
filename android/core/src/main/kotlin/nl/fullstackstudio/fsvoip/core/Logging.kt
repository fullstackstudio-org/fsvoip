// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import android.util.Log

/**
 * Removes everything that must never reach a log, a crash report or a bug report from a message.
 *
 * Redacted: FSVoip tokens (`fss_vapp_...`, `fss_vpair_...`), `Bearer` credentials, values of keys that look like
 * secrets (`password`, `token`, ...), SIP `Authorization` headers, long hex strings and FCM tokens.
 */
object LogRedactor {
    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""fss_(vapp|vpair)_[A-Za-z0-9_-]{6,}""") to "fss_$1_[redacted]",
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]{6,}""") to "Bearer [redacted]",
        Regex("""(?i)\b(proxy-authorization|authorization)\s*:[^\r\n]*""") to "$1: [redacted]",
        Regex(
            """(?i)(?<![A-Za-z0-9_])("?(?:password|passwd|pass|secret|devicetoken|pushtoken|alertpushtoken|token|apikey|api_key)"?\s*[:=]\s*)("(?:[^"\\]|\\.)*"|[^\s,;&}\])]+)""",
        ) to "$1\"[redacted]\"",
        Regex("""\b[0-9a-fA-F]{64,}\b""") to "[redacted-hex]",
        // FCM registration tokens: `<instance id>:APA91b<long base64url>`.
        Regex("""[A-Za-z0-9_-]{8,}:APA91[A-Za-z0-9_-]{20,}""") to "[redacted-fcm]",
    )

    fun redact(text: String): String = rules.fold(text) { result, (regex, template) -> regex.replace(result, template) }
}

enum class LogLevel { DEBUG, INFO, NOTICE, ERROR }

fun interface LogSink {
    /** Receives text that is ALREADY redacted. */
    fun write(level: LogLevel, category: String, message: String)
}

/** Logcat. Messages are redacted before they get here. */
object LogcatSink : LogSink {
    override fun write(level: LogLevel, category: String, message: String) {
        val tag = "FSVoip/$category"

        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.NOTICE -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }
}

/** Collects messages in memory (tests). */
class MemoryLogSink : LogSink {
    private val storage = mutableListOf<String>()

    @Synchronized
    override fun write(level: LogLevel, category: String, message: String) {
        storage += message
    }

    val messages: List<String>
        @Synchronized get() = storage.toList()
}

/** The only logger the app uses. Every message passes the [LogRedactor]. */
class FsLogger(
    val category: String,
    private val sink: LogSink = LogcatSink,
    private val minimumLevel: LogLevel = LogLevel.DEBUG,
) {
    fun log(level: LogLevel, message: () -> String) {
        if (level < minimumLevel) {
            return
        }

        sink.write(level, category, LogRedactor.redact(message()))
    }

    fun debug(message: String) = log(LogLevel.DEBUG) { message }
    fun info(message: String) = log(LogLevel.INFO) { message }
    fun notice(message: String) = log(LogLevel.NOTICE) { message }
    fun error(message: String) = log(LogLevel.ERROR) { message }
}

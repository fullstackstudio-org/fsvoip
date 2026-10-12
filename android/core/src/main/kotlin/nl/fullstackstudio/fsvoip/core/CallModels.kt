// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of `/calls` and `/voicemail` in `shared/openapi.yaml`. An admin pairing sees every call (with recordings)
// and every voicemail box of the PBX; a `user` pairing sees the team history without recordings and only its own box. The
// server decides; these types are the same for both.

package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PbxCallDirection {
    @SerialName("inbound") INBOUND,
    @SerialName("outbound") OUTBOUND,
    @SerialName("internal") INTERNAL,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class PbxCallOutcome {
    @SerialName("answered") ANSWERED,

    /** Not answered (inbound: missed; outbound/internal: not picked up). */
    @SerialName("missed") MISSED,
    @SerialName("voicemail") VOICEMAIL,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class PbxLineType {
    @SerialName("fixed") FIXED,
    @SerialName("mobile") MOBILE,
    @SerialName("unknown") UNKNOWN,
}

@Serializable
enum class PbxDestinationCategory {
    @SerialName("fixed") FIXED,
    @SerialName("mobile") MOBILE,
    @SerialName("special") SPECIAL,
    @SerialName("short") SHORT,
    @SerialName("emergency") EMERGENCY,
    @SerialName("satellite") SATELLITE,
    @SerialName("unknown") UNKNOWN_DESTINATION,

    /** A category this app version does not know. */
    OTHER,
}

/** One call of the PBX. The labels are formatted by the server in the language of `?locale=` (`nl`/`en`). */
@Serializable
data class PbxCall(
    val id: String,
    val direction: PbxCallDirection = PbxCallDirection.UNKNOWN,
    val outcome: PbxCallOutcome = PbxCallOutcome.UNKNOWN,
    /** The other party, formatted; `null` = anonymous/unknown (or internal: then see [extensionNumber]). */
    val number: String? = null,
    /** One of our own numbers that was called / used. */
    val ourNumber: String? = null,
    @SerialName("extension") val extensionNumber: String? = null,
    val extensionName: String? = null,
    val country: String? = null,
    val countryName: String? = null,
    val lineType: PbxLineType? = null,
    val category: PbxDestinationCategory? = null,
    val startedLabel: String = "",
    /** ISO instant for sorting; `null` = unknown. */
    val startedSort: String? = null,
    val durationLabel: String = "",
    val durationSeconds: Int? = null,
    /** There is a recording to play (admin only; always `false` for a `user` pairing). */
    val hasRecording: Boolean = false,
    /** There was a recording but it is older than the retention period (admin only). */
    val recordingExpired: Boolean = false,
) {
    val startedAt: Instant?
        get() = startedSort?.let(FsJson::parseTimestamp)
}

/** `GET /calls?month=YYYY-MM`. */
@Serializable
data class PbxCallsPage(
    /** `YYYY-MM` (Europe/Amsterdam). */
    val month: String,
    /** The months you can choose, newest first. */
    val months: List<String> = emptyList(),
    val calls: List<PbxCall> = emptyList(),
    /** There are more calls than the list shows. */
    val truncated: Boolean = false,
)

// MARK: Voicemail

@Serializable
data class VoicemailBox(
    /** For a `user` pairing this equals the id of its own extension. */
    val id: String,
    val name: String,
    val number: String? = null,
    /** A shared mailbox (as opposed to the box of one extension). */
    val shared: Boolean = false,
)

@Serializable
data class VoicemailMessage(
    /** A sealed reference, valid for this PBX and box only. Never the message id of the PBX. */
    val ref: String,
    val boxId: String,
    val boxName: String = "",
    val boxNumber: String? = null,
    val caller: String? = null,
    val callerName: String? = null,
    val receivedLabel: String = "",
    val receivedSort: String? = null,
    val durationLabel: String = "",
    val isNew: Boolean = false,
    val transcription: String? = null,
    /** Whole days left before the PBX removes the message; `null` = unknown. */
    val daysLeft: Int? = null,
) {
    val key: String
        get() = "$boxId/$ref"
}

/** `GET /voicemail?box=<boxId>`. */
@Serializable
data class VoicemailPage(
    val boxes: List<VoicemailBox> = emptyList(),
    /** The chosen box; `null` = all boxes (admin). */
    val boxId: String? = null,
    val messages: List<VoicemailMessage> = emptyList(),
    /** `false` = the PBX did not answer: show a notice, not an empty list. */
    val available: Boolean = true,
)

// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Kotlin mirror of `/calls` and `/voicemail` in `shared/openapi.yaml`. An admin pairing sees every call (with recordings) and every
// voicemail box of the PBX; a `user` pairing the team's calls without recordings and its own box. The server decides; these
// types are the same for both. Unknown enum values never fail the whole answer.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable(with = CallDirection.Serializer::class)
enum class CallDirection(override val wire: String) : WireEnum {
    INBOUND("inbound"),
    OUTBOUND("outbound"),
    INTERNAL("internal"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<CallDirection>("CallDirection", entries, UNKNOWN, strictEncode = false)
}

@Serializable(with = CallOutcome.Serializer::class)
enum class CallOutcome(override val wire: String) : WireEnum {
    ANSWERED("answered"),

    /** Not answered (inbound: missed; outbound/internal: not picked up). */
    MISSED("missed"),
    VOICEMAIL("voicemail"),

    /** The server does not know, or this app version does not know the value. */
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<CallOutcome>("CallOutcome", entries, UNKNOWN, strictEncode = false)
}

@Serializable(with = CallLineType.Serializer::class)
enum class CallLineType(override val wire: String) : WireEnum {
    FIXED("fixed"),
    MOBILE("mobile"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<CallLineType>("CallLineType", entries, UNKNOWN, strictEncode = false)
}

@Serializable(with = CallDestinationCategory.Serializer::class)
enum class CallDestinationCategory(override val wire: String) : WireEnum {
    FIXED("fixed"),
    MOBILE("mobile"),
    SPECIAL("special"),
    SHORT("short"),
    EMERGENCY("emergency"),
    SATELLITE("satellite"),

    /** The server says `unknown`. */
    UNKNOWN_DESTINATION("unknown"),

    /** A category this app version does not know. */
    OTHER("other"),
    ;

    object Serializer : TolerantEnumSerializer<CallDestinationCategory>("CallDestinationCategory", entries, OTHER, strictEncode = false)
}

/** One call of the PBX. The labels are formatted by the server in the language of `?locale=` (`nl`/`en`). */
@Serializable
data class CallItem(
    val id: String,
    val direction: CallDirection = CallDirection.UNKNOWN,
    val outcome: CallOutcome = CallOutcome.UNKNOWN,
    /** The other party, formatted; `null` = anonymous/unknown (or internal: then see [extensionNumber]). */
    val number: String? = null,
    /** One of our own numbers that was called / used. */
    val ourNumber: String? = null,
    @SerialName("extension") val extensionNumber: String? = null,
    val extensionName: String? = null,
    /** ISO country of the other number; [countryName] is in the reader's language. */
    val country: String? = null,
    val countryName: String? = null,
    val lineType: CallLineType? = null,
    val category: CallDestinationCategory? = null,
    val startedLabel: String = "",
    /** ISO instant for sorting; `null` = unknown. */
    val startedSort: String? = null,
    val durationLabel: String = "",
    val durationSeconds: Int? = null,
    /** There is a recording to play (admin only; always `false` for a `user` pairing). */
    val hasRecording: Boolean = false,
    /** There was a recording but it is older than the retention period (admin only). */
    val recordingExpired: Boolean = false,
)

/** `GET /calls?month=YYYY-MM`. */
@Serializable
data class CallsPage(
    /** `YYYY-MM` (Europe/Amsterdam). */
    val month: String,
    /** The months you can choose, newest first. */
    val months: List<String> = emptyList(),
    val calls: List<CallItem> = emptyList(),
    /** There are more calls than the list shows. */
    val truncated: Boolean = false,
)

// MARK: Voicemail

@Serializable
data class VoicemailBox(
    /** For a `user` pairing this equals the id of its own extension. */
    val id: String,
    val name: String = "",
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
    val id: String get() = "$boxId/$ref"
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

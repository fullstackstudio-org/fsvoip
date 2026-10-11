// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Role and capabilities of `GET /me`. Every missing or unknown value falls back to the restrictive choice.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.Serializable

/** The role of this pairing. `admin` may manage the PBX from the app; everything else is `user`. */
@Serializable(with = AppRole.Serializer::class)
enum class AppRole(override val wire: String) : WireEnum {
    USER("user"),
    ADMIN("admin"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<AppRole>("AppRole", entries, UNKNOWN)
}

@Serializable(with = VoicemailAccess.Serializer::class)
enum class VoicemailAccess(override val wire: String) : WireEnum {
    ALL("all"),
    OWN("own"),
    NONE("none"),
    ;

    object Serializer : TolerantEnumSerializer<VoicemailAccess>("VoicemailAccess", entries, NONE, strictEncode = false)
}

@Serializable(with = CallsAccess.Serializer::class)
enum class CallsAccess(override val wire: String) : WireEnum {
    /** Every call of the PBX, with recordings (admin). */
    ALL("all"),

    /** Every call of the PBX without recordings: the team history (`user`). */
    TEAM("team"),

    /** Only the calls of the own extension (a server from before the team history). */
    OWN("own"),
    ;

    object Serializer : TolerantEnumSerializer<CallsAccess>("CallsAccess", entries, OWN, strictEncode = false)
}

/** Who may manage the sounds of the PBX (`capabilities.sounds`). */
@Serializable(with = SoundsAccess.Serializer::class)
enum class SoundsAccess(override val wire: String) : WireEnum {
    MANAGE("manage"),
    NONE("none"),
    ;

    object Serializer : TolerantEnumSerializer<SoundsAccess>("SoundsAccess", entries, NONE, strictEncode = false)
}

@Serializable
data class ContactCapabilities(
    val read: Boolean = true,
    val write: Boolean = true,
    /** Deleting is the restricted one: a missing value means no. */
    val delete: Boolean = false,
)

@Serializable
data class AppCapabilities(
    /** The "Centrale" section (the pbx routes). */
    val pbxManage: Boolean = false,
    /** Call recordings (`/calls/{id}/recording`). */
    val recordings: Boolean = false,
    val voicemail: VoicemailAccess = VoicemailAccess.NONE,
    val calls: CallsAccess = CallsAccess.OWN,
    val contacts: ContactCapabilities = ContactCapabilities(),
    /** `GET`/`PATCH /me/extension`: the own extension (every role). A server from before it leaves it out: `false`. */
    val selfExtension: Boolean = false,
    /** The sounds routes (the sounds routes under pbx). */
    val sounds: SoundsAccess = SoundsAccess.NONE,
    /** Invite a colleague (`POST /pbx/devices/{id}/app-pairing`). */
    val invite: Boolean = false,
    /** Parking works on this PBX; `false` = do not show the park button or the "On hold" tab. */
    val park: Boolean = false,
    /** The PBX understands `X-FSS-From`: only then may the app choose the number to call out with. */
    val callerChoice: Boolean = false,
)

@Serializable(with = PbxState.Serializer::class)
enum class PbxState(override val wire: String) : WireEnum {
    ACTIVE("active"),
    FROZEN("frozen"),
    SETUP("setup"),
    ERROR("error"),
    UNKNOWN("unknown"),
    ;

    object Serializer : TolerantEnumSerializer<PbxState>("PbxState", entries, UNKNOWN)
}

/** The PBX as the app sees it in `/me` and `/pbx/overview`. `readOnly`: frozen, still being set up or in an error state. */
@Serializable
data class PbxStatus(
    val name: String,
    val state: PbxState,
    /** Fail closed: an unknown or missing flag means the app does not offer changes. */
    val readOnly: Boolean = true,
)

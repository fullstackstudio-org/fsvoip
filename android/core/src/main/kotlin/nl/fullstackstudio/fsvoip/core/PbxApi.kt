// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The `/pbx/*` routes of the "Centrale" section (admin only): overview, devices, ring groups, hours, the routing of a number and
// the numbers as a chain. Which routes a pairing may use comes from `MeResponse.role`/`capabilities`; the server enforces every
// one of them (`ApiException.Forbidden`). Failures worth knowing: `StaleChain` (keep what the user typed, show the new state),
// `Advanced`, `GreetingRequired`, `BlockedDestination`, `ReadOnly`, `InUse`, `CostNotAccepted`.

package nl.fullstackstudio.fsvoip.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

suspend fun FsVoipApiClient.pbxOverview(): PbxOverview = get("pbx/overview", PbxOverview.serializer())

suspend fun FsVoipApiClient.pbxDevices(): PbxDevicesResponse = get("pbx/devices", PbxDevicesResponse.serializer())

suspend fun FsVoipApiClient.updatePbxDevice(id: String, patch: PbxDevicePatch): OkResponse =
    perform("PATCH", "pbx/devices/$id", patch.toJson(), authenticated = true, OkResponse.serializer())

suspend fun FsVoipApiClient.pbxRingGroups(): PbxRingGroupsResponse = get("pbx/ring-groups", PbxRingGroupsResponse.serializer())

suspend fun FsVoipApiClient.createPbxRingGroup(group: PbxRingGroupCreate): PbxRingGroupCreated =
    perform("POST", "pbx/ring-groups", group.toJson(), authenticated = true, PbxRingGroupCreated.serializer())

suspend fun FsVoipApiClient.updatePbxRingGroup(id: String, patch: PbxRingGroupPatch): OkResponse =
    perform("PATCH", "pbx/ring-groups/$id", patch.toJson(), authenticated = true, OkResponse.serializer())

suspend fun FsVoipApiClient.pbxHours(): PbxHoursResponse = get("pbx/hours", PbxHoursResponse.serializer())

suspend fun FsVoipApiClient.updatePbxHours(id: String, patch: PbxHoursPatch): OkResponse =
    perform("PATCH", "pbx/hours/$id", patch.toJson(), authenticated = true, OkResponse.serializer())

suspend fun FsVoipApiClient.setPbxNumberRouting(numberId: String, patch: PbxRoutingPatch): OkResponse =
    perform("PATCH", "pbx/numbers/$numberId/routing", patch.toJson(), authenticated = true, OkResponse.serializer())

// MARK: - Numbers as a chain

suspend fun FsVoipApiClient.pbxNumbers(): PbxNumbersPage = get("pbx/numbers", PbxNumbersPage.serializer())

suspend fun FsVoipApiClient.numberChain(numberId: String): NumberChain = get("pbx/numbers/$numberId/chain", NumberChain.serializer())

/**
 * `PUT /pbx/numbers/{id}/chain/{step}`: one step, answered with the fresh chain. A step with a read-only fallback throws before
 * any request is made.
 */
suspend fun FsVoipApiClient.saveChainStep(numberId: String, step: ChainStepRequest): NumberChain {
    val body = step.toJson()

    return perform("PUT", "pbx/numbers/$numberId/chain/${step.step.wire}", body, authenticated = true, NumberChain.serializer())
}

/** `PATCH /pbx/numbers/{id}/recording`: the fresh chain. `CostNotAccepted` = show the price, then repeat with `costAccepted`. */
suspend fun FsVoipApiClient.setNumberRecording(numberId: String, patch: NumberRecordingPatch): NumberChain =
    perform("PATCH", "pbx/numbers/$numberId/recording", patch.toJson(), authenticated = true, NumberChain.serializer())

// MARK: - Sounds (only what the number pickers need)

/** A sound of the PBX as the pickers need it: its id and name. (The sounds feature has the full model.) */
@Serializable
data class PbxSoundChoice(val id: String, val name: String)

@Serializable
private data class PbxSoundChoicePage(val sounds: List<PbxSoundChoice> = emptyList())

/** `GET /pbx/sounds`, reduced to id and name. */
suspend fun FsVoipApiClient.pbxSoundChoices(): List<PbxSoundChoice> = get("pbx/sounds", PbxSoundChoicePage.serializer()).sounds

// MARK: - Error bodies

/** The fresh chain a `409 stale` of a chain step carries; `null` when it cannot be read (treat as a plain `stale`). */
fun ApiException.StaleChain.freshChain(): NumberChain? = decodeOrNull(chain, NumberChain.serializer())

/** The price a `cost_not_accepted` asks the customer to accept; `null` when the server did not say. */
fun ApiException.CostNotAccepted.recordingCost(): RecordingCost? = cost?.let { decodeOrNull(it, RecordingCost.serializer()) }

private fun <T> decodeOrNull(element: JsonElement, serializer: kotlinx.serialization.KSerializer<T>): T? = try {
    FsJson.default.decodeFromJsonElement(serializer, element)
} catch (_: SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

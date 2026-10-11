// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What a "Toestel" or "Belgroep" screen edits, as one value, and the translation to a PATCH body that holds only what changed
// (the iOS `PbxDrafts`). Plain Kotlin so it is tested without a screen.

package nl.fullstackstudio.fsvoip.core

data class PbxDeviceDraft(
    val dnd: Boolean = false,
    val voicemailEnabled: Boolean = false,
    val voicemailToEmail: Boolean = false,
    val voicemailAttachFile: Boolean = false,
    val outboundNumberId: String? = null,
    val noAnswerSeconds: Int = 25,
    val noAnswerTarget: PbxTarget? = null,
    val busyTarget: PbxTarget? = null,
    val notRegisteredTarget: PbxTarget? = null,
    val forwardAlways: PbxTarget? = null,
    val followMe: List<FollowMeStep> = emptyList(),
) {
    /** Only the keys that differ from [original], with [version]. `null` = nothing changed. */
    fun patch(original: PbxDeviceDraft, version: Int): PbxDevicePatch? {
        fun target(value: PbxTarget?, initial: PbxTarget?): Change<PbxTarget> = if (value == initial) Change.Keep else Change.of(value)

        val patch = PbxDevicePatch(
            version = version,
            voicemailEnabled = voicemailEnabled.takeIf { it != original.voicemailEnabled },
            voicemailToEmail = voicemailToEmail.takeIf { it != original.voicemailToEmail },
            voicemailAttachFile = voicemailAttachFile.takeIf { it != original.voicemailAttachFile },
            outboundNumberId = if (outboundNumberId == original.outboundNumberId) Change.Keep else Change.of(outboundNumberId),
            dnd = dnd.takeIf { it != original.dnd },
            noAnswerSeconds = noAnswerSeconds.takeIf { it != original.noAnswerSeconds },
            noAnswerTarget = target(noAnswerTarget, original.noAnswerTarget),
            busyTarget = target(busyTarget, original.busyTarget),
            notRegisteredTarget = target(notRegisteredTarget, original.notRegisteredTarget),
            forwardAlways = target(forwardAlways, original.forwardAlways),
            followMe = followMe.takeIf { it != original.followMe },
        )

        return patch.takeUnless { it.isEmpty }
    }

    /** The server has a newer state: every field the user did not touch follows it, every field they did touch stays as typed. */
    fun rebase(fresh: PbxDeviceDraft, baseline: PbxDeviceDraft): PbxDeviceDraft {
        fun <T> pick(mine: T, initial: T, theirs: T): T = if (mine == initial) theirs else mine

        return PbxDeviceDraft(
            dnd = pick(dnd, baseline.dnd, fresh.dnd),
            voicemailEnabled = pick(voicemailEnabled, baseline.voicemailEnabled, fresh.voicemailEnabled),
            voicemailToEmail = pick(voicemailToEmail, baseline.voicemailToEmail, fresh.voicemailToEmail),
            voicemailAttachFile = pick(voicemailAttachFile, baseline.voicemailAttachFile, fresh.voicemailAttachFile),
            outboundNumberId = pick(outboundNumberId, baseline.outboundNumberId, fresh.outboundNumberId),
            noAnswerSeconds = pick(noAnswerSeconds, baseline.noAnswerSeconds, fresh.noAnswerSeconds),
            noAnswerTarget = pick(noAnswerTarget, baseline.noAnswerTarget, fresh.noAnswerTarget),
            busyTarget = pick(busyTarget, baseline.busyTarget, fresh.busyTarget),
            notRegisteredTarget = pick(notRegisteredTarget, baseline.notRegisteredTarget, fresh.notRegisteredTarget),
            forwardAlways = pick(forwardAlways, baseline.forwardAlways, fresh.forwardAlways),
            followMe = pick(followMe, baseline.followMe, fresh.followMe),
        )
    }

    companion object {
        val NO_ANSWER_RANGE = 5..120
        const val FOLLOW_ME_MAX = 10

        fun of(device: PbxDevice) = PbxDeviceDraft(
            dnd = device.dnd,
            voicemailEnabled = device.voicemailEnabled,
            voicemailToEmail = device.voicemailToEmail,
            voicemailAttachFile = device.voicemailAttachFile,
            outboundNumberId = device.outboundNumberId,
            noAnswerSeconds = device.noAnswerSeconds,
            noAnswerTarget = device.noAnswerTarget,
            busyTarget = device.busyTarget,
            notRegisteredTarget = device.notRegisteredTarget,
            forwardAlways = device.forwardAlways,
            followMe = device.followMe,
        )
    }
}

data class PbxRingGroupDraft(
    val name: String = "",
    val strategy: RingStrategy = RingStrategy.ALL,
    val members: List<RingGroupMember> = emptyList(),
    val timeoutTarget: PbxTarget? = null,
) {
    val cleanName: String
        get() = name.trim()

    /** A group needs a name and at least one extension (the server refuses an empty one). */
    val isValid: Boolean
        get() = cleanName.isNotEmpty() && members.isNotEmpty()

    fun patch(original: PbxRingGroupDraft, version: Int): PbxRingGroupPatch? {
        val patch = PbxRingGroupPatch(
            version = version,
            name = cleanName.takeIf { it != original.cleanName },
            strategy = strategy.takeIf { it != original.strategy },
            members = members.takeIf { it != original.members },
            timeoutTarget = if (timeoutTarget == original.timeoutTarget) Change.Keep else Change.of(timeoutTarget),
        )

        return patch.takeUnless { it.isEmpty }
    }

    fun rebase(fresh: PbxRingGroupDraft, baseline: PbxRingGroupDraft): PbxRingGroupDraft {
        fun <T> pick(mine: T, initial: T, theirs: T): T = if (mine == initial) theirs else mine

        return PbxRingGroupDraft(
            name = pick(cleanName, baseline.cleanName, fresh.name),
            strategy = pick(strategy, baseline.strategy, fresh.strategy),
            members = pick(members, baseline.members, fresh.members),
            timeoutTarget = pick(timeoutTarget, baseline.timeoutTarget, fresh.timeoutTarget),
        )
    }

    fun create(): PbxRingGroupCreate = PbxRingGroupCreate(cleanName, strategy, members, if (timeoutTarget == null) Change.Keep else Change.Set(timeoutTarget))

    /** Adds or removes one extension, keeping the order of the others. */
    fun toggle(deviceId: String): PbxRingGroupDraft =
        if (members.any { it.extensionId == deviceId }) copy(members = members.filterNot { it.extensionId == deviceId })
        else copy(members = members + RingGroupMember(deviceId, delaySeconds = if (strategy == RingStrategy.SEQUENCE) members.sumOf { it.timeoutSeconds } else 0, timeoutSeconds = DEFAULT_RING_SECONDS))

    fun update(deviceId: String, change: (RingGroupMember) -> RingGroupMember): PbxRingGroupDraft =
        copy(members = members.map { if (it.extensionId == deviceId) change(it) else it })

    companion object {
        const val DEFAULT_RING_SECONDS = 25
        val RING_RANGE = 5..120
        val DELAY_RANGE = 0..120

        fun of(group: PbxRingGroup) = PbxRingGroupDraft(group.name, group.strategy, group.members, group.timeoutTarget)
    }
}

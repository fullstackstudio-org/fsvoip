// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The state of a form while it is open, and the translation to a PATCH body that holds only what changed. The server decides what
// is valid; these types only keep "what did the user touch" so an untouched field is never sent (and never overwritten).

package nl.fullstackstudio.fsvoip.ui.pbx

import java.time.LocalDate
import java.util.UUID
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.Change
import nl.fullstackstudio.fsvoip.core.FollowMeStep
import nl.fullstackstudio.fsvoip.core.HoursWeekEntry
import nl.fullstackstudio.fsvoip.core.PbxDevice
import nl.fullstackstudio.fsvoip.core.PbxDevicePatch
import nl.fullstackstudio.fsvoip.core.PbxHolidayInput
import nl.fullstackstudio.fsvoip.core.PbxHours
import nl.fullstackstudio.fsvoip.core.PbxHoliday
import nl.fullstackstudio.fsvoip.core.PbxHoursPatch
import nl.fullstackstudio.fsvoip.core.PbxRingGroup
import nl.fullstackstudio.fsvoip.core.PbxRingGroupCreate
import nl.fullstackstudio.fsvoip.core.PbxRingGroupPatch
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.PbxTargetOption
import nl.fullstackstudio.fsvoip.core.RingGroupMember
import nl.fullstackstudio.fsvoip.core.RingStrategy
import nl.fullstackstudio.fsvoip.core.TargetType

// MARK: - Device

data class DeviceDraft(
    val voicemailEnabled: Boolean,
    val dnd: Boolean,
    val noAnswerSeconds: Int,
    val noAnswerTarget: PbxTarget?,
    val busyTarget: PbxTarget?,
    val notRegisteredTarget: PbxTarget?,
    val forwardAlways: PbxTarget?,
    val followMe: List<FollowMeStep>,
) {
    constructor(device: PbxDevice) : this(
        voicemailEnabled = device.voicemailEnabled,
        dnd = device.dnd,
        noAnswerSeconds = device.noAnswerSeconds,
        noAnswerTarget = device.noAnswerTarget,
        busyTarget = device.busyTarget,
        notRegisteredTarget = device.notRegisteredTarget,
        forwardAlways = device.forwardAlways,
        followMe = device.followMe,
    )

    /** Only the changed keys, always with the `version` the form was opened with. `null` = nothing changed. */
    fun patch(original: PbxDevice): PbxDevicePatch? {
        val initial = DeviceDraft(original)

        if (this == initial) return null

        return PbxDevicePatch(
            version = original.version,
            voicemailEnabled = voicemailEnabled.takeIf { it != initial.voicemailEnabled },
            dnd = dnd.takeIf { it != initial.dnd },
            noAnswerSeconds = noAnswerSeconds.takeIf { it != initial.noAnswerSeconds },
            noAnswerTarget = change(noAnswerTarget, initial.noAnswerTarget),
            busyTarget = change(busyTarget, initial.busyTarget),
            notRegisteredTarget = change(notRegisteredTarget, initial.notRegisteredTarget),
            forwardAlways = change(forwardAlways, initial.forwardAlways),
            followMe = followMe.takeIf { it != initial.followMe },
        )
    }

    companion object {
        val noAnswerRange = 5..120
        const val FOLLOW_ME_LIMIT = 10

        fun change(value: PbxTarget?, initial: PbxTarget?): Change<PbxTarget> = if (value == initial) Change.Keep else Change.of(value)
    }
}

// MARK: - Ring group

data class RingGroupDraft(
    val name: String = "",
    val strategy: RingStrategy = RingStrategy.ALL,
    val members: List<RingGroupMember> = emptyList(),
    val timeoutTarget: PbxTarget? = null,
) {
    constructor(group: PbxRingGroup) : this(
        name = group.name,
        strategy = if (group.strategy == RingStrategy.UNKNOWN) RingStrategy.ALL else group.strategy,
        members = group.members,
        timeoutTarget = group.timeoutTarget,
    )

    val cleanName: String get() = name.trim()

    /** Form check only: a name and at least one member. The server holds the other rules. */
    val isFilledIn: Boolean get() = cleanName.isNotEmpty() && members.isNotEmpty()

    fun creation(): PbxRingGroupCreate = PbxRingGroupCreate(cleanName, strategy, members, Change.of(timeoutTarget))

    fun patch(original: PbxRingGroup): PbxRingGroupPatch? {
        val initial = RingGroupDraft(original)
        val current = copy(name = cleanName)

        if (current == initial) return null

        return PbxRingGroupPatch(
            version = original.version,
            name = current.name.takeIf { it != initial.name },
            strategy = strategy.takeIf { it != initial.strategy },
            members = members.takeIf { it != initial.members },
            timeoutTarget = DeviceDraft.change(timeoutTarget, initial.timeoutTarget),
        )
    }

    companion object {
        const val MEMBER_LIMIT = 50
    }
}

// MARK: - Opening hours

/** One opening interval of a day, edited as text (`HH:MM`; `24:00` = until midnight). Equal when from and to are equal. */
class HoursInterval(val from: String, val to: String, val id: String = UUID.randomUUID().toString()) {
    fun copy(from: String = this.from, to: String = this.to) = HoursInterval(from, to, id)

    override fun equals(other: Any?): Boolean = other is HoursInterval && other.from == from && other.to == to

    override fun hashCode(): Int = from.hashCode() * 31 + to.hashCode()

    override fun toString(): String = "$from-$to"
}

data class HoursDraft(
    /** Monday (1) to Sunday (7). */
    val days: Map<Int, List<HoursInterval>>,
    val openTarget: PbxTarget?,
    val closedTarget: PbxTarget?,
) {
    constructor(hours: PbxHours) : this(
        days = hours.week.groupBy({ it.day }) { HoursInterval(it.from, it.to) },
        openTarget = hours.openTarget,
        closedTarget = hours.closedTarget,
    )

    val week: List<HoursWeekEntry>
        get() = weekdays.flatMap { day -> (days[day] ?: emptyList()).map { HoursWeekEntry(day, it.from, it.to) } }

    /** Equal when the week reads the same (a day switched off and a day that never had hours are the same). */
    fun sameAs(other: HoursDraft): Boolean = week == other.week && openTarget == other.openTarget && closedTarget == other.closedTarget

    fun patch(original: PbxHours): PbxHoursPatch? {
        val initial = HoursDraft(original)

        if (sameAs(initial)) return null

        return PbxHoursPatch(
            version = original.version,
            week = week.takeIf { it != initial.week },
            openTarget = DeviceDraft.change(openTarget, initial.openTarget),
            closedTarget = DeviceDraft.change(closedTarget, initial.closedTarget),
        )
    }

    companion object {
        val weekdays = (1..7).toList()
        const val INTERVALS_PER_DAY = 4
        val defaultInterval = HoursInterval("09:00", "17:00")
    }
}

// MARK: - Temporarily closed

/**
 * "Tijdelijk dicht": own dates in the holiday list of the opening hours. The list in a PATCH REPLACES the old one, so every
 * existing holiday (fixed rule or own date) goes along; only the own dates are added or removed.
 */
object TemporaryClosure {
    const val CUSTOM_DATE_LIMIT = 20

    sealed class PlanError {
        data object EmptyRange : PlanError()

        /** More own dates than the centrale takes; `room` = how many more fit. */
        data class TooMany(val room: Int) : PlanError()
    }

    sealed class Plan {
        data class Success(val holidays: List<PbxHolidayInput>) : Plan()

        data class Failure(val error: PlanError) : Plan()
    }

    /** Every day from `from` to `to` (inclusive) as `YYYY-MM-DD`. */
    fun dates(from: LocalDate, to: LocalDate): List<String> {
        if (from.isAfter(to)) return emptyList()

        val result = mutableListOf<String>()
        var day = from

        while (!day.isAfter(to) && result.size <= CUSTOM_DATE_LIMIT + 1) {
            result += day.toString()
            day = day.plusDays(1)
        }

        return result
    }

    /** The holiday list to send: all existing ones plus one own date per day of the range (a date already there stays as it is). */
    fun closing(hours: PbxHours, name: String, dates: List<String>, defaultName: String): Plan {
        if (dates.isEmpty()) return Plan.Failure(PlanError.EmptyRange)

        val existing = preserved(hours.holidays)
        val have = hours.holidays.mapNotNull { it.date }.toSet()
        val fresh = dates.filter { it !in have }
        val customNow = hours.holidays.count { it.date != null }
        val room = maxOf(0, CUSTOM_DATE_LIMIT - customNow)

        if (fresh.size > room) return Plan.Failure(PlanError.TooMany(room))

        val label = name.trim().ifEmpty { defaultName }

        return Plan.Success(existing + fresh.map { PbxHolidayInput.Custom(label, it) })
    }

    /** The list without one own date. */
    fun reopening(hours: PbxHours, date: String): List<PbxHolidayInput> = preserved(hours.holidays.filter { it.date != date })

    /** Every holiday of the opening hours as a PATCH item. */
    fun preserved(holidays: List<PbxHoliday>): List<PbxHolidayInput> = holidays.mapNotNull { holiday ->
        when {
            holiday.rule != null -> PbxHolidayInput.Rule(holiday.rule!!)
            holiday.date != null -> PbxHolidayInput.Custom(holiday.name, holiday.date!!)
            else -> null
        }
    }
}

// MARK: - Targets

/** Turns the choices of the server into what a picker shows and the other way round. */
object TargetChoice {
    /** The option the target belongs to (`external` has no id; `hangup` is not an option of the server). */
    fun option(target: PbxTarget?, options: List<PbxTargetOption>): PbxTargetOption? {
        if (target == null) return null

        return options.firstOrNull { it.type == target.type && it.id == target.id }
    }

    fun target(option: PbxTargetOption, externalNumber: String = ""): PbxTarget? = when (option.type) {
        TargetType.EXTERNAL -> externalNumber.trim().takeIf { it.isNotEmpty() }?.let(PbxTarget::external)
        TargetType.HANGUP -> PbxTarget.HANGUP
        TargetType.UNKNOWN -> null
        else -> option.id?.let { PbxTarget.obj(option.type, it) }
    }

    /** The choices without the object that is being edited (a belgroep cannot send its callers to itself). */
    fun excluding(id: String?, options: List<PbxTargetOption>): List<PbxTargetOption> = if (id == null) options else options.filter { it.id != id }
}

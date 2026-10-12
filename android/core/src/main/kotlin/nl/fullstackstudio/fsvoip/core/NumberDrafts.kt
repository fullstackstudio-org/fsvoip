// SPDX-License-Identifier: AGPL-3.0-or-later
//
// What the "Openingstijden", "Tijdelijk dicht" and number-chain screens edit, as plain values, and the translation to the request
// that holds what changed (the iOS `HoursDraft` / `TemporaryClosure` / `NumberDrafts`). Pure Kotlin, tested without a screen.

package nl.fullstackstudio.fsvoip.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** One opening interval of a day, edited as text (`HH:MM`; `24:00` = until midnight). */
data class WeekSlot(val from: String, val to: String) {
    val isValid: Boolean
        get() {
            val start = WeekDraft.minutes(from) ?: return false
            val end = WeekDraft.minutes(to) ?: return false

            return end > start
        }
}

/** The opening hours of a week: Monday (1) to Sunday (7), up to four intervals a day. */
object WeekDraft {
    const val SLOTS_PER_DAY = 4
    val DEFAULT_SLOT = WeekSlot("09:00", "17:00")
    val DAYS = (1..7).toList()

    fun fromEntries(week: List<HoursWeekEntry>): Map<Int, List<WeekSlot>> = week.groupBy({ it.day }, { WeekSlot(it.from, it.to) })

    fun toEntries(days: Map<Int, List<WeekSlot>>): List<HoursWeekEntry> =
        DAYS.flatMap { day -> days[day].orEmpty().map { HoursWeekEntry(day, it.from, it.to) } }

    /** Minutes since midnight of `HH:MM`; `24:00` is allowed. `null` = not a time. */
    fun minutes(text: String): Int? {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(text.trim()) ?: return null
        val hours = match.groupValues[1].toInt()
        val minutes = match.groupValues[2].toInt()

        if (minutes > 59 || hours > 24 || (hours == 24 && minutes != 0)) return null

        return hours * 60 + minutes
    }

    /** `9:5` / `0900` / `09:00` -> `09:00`; `24:00` stays; anything else is returned as typed. */
    fun normalize(text: String): String {
        val digits = text.trim()
        val match = Regex("^(\\d{1,2})[:.]?(\\d{2})$").matchEntire(digits) ?: return text
        val result = "%02d:%s".format(match.groupValues[1].toInt(), match.groupValues[2])

        return if (minutes(result) != null) result else text
    }

    /** Every day's intervals are valid and do not overlap. */
    fun isValid(days: Map<Int, List<WeekSlot>>): Boolean = days.values.all { slots ->
        slots.size <= SLOTS_PER_DAY && slots.all { it.isValid } && slots.sortedBy { minutes(it.from) }.zipWithNext().all { (a, b) -> minutes(a.to)!! <= minutes(b.from)!! }
    }

    /** "ma–vr 09:00–17:00, za 10:00–12:00", or [none] when the week is empty. Days with the same intervals are grouped when they follow each other. */
    fun summary(week: List<HoursWeekEntry>, none: String, locale: Locale = Locale.getDefault()): String {
        val days = fromEntries(week)
        val used = DAYS.filter { days[it].orEmpty().isNotEmpty() }

        if (used.isEmpty()) return none

        val groups = mutableListOf<MutableList<Int>>()

        for (day in used) {
            val last = groups.lastOrNull()

            if (last != null && last.last() == day - 1 && days[last.last()] == days[day]) last += day else groups += mutableListOf(day)
        }

        return groups.joinToString(", ") { group ->
            val name = { day: Int -> DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, locale).trimEnd('.') }
            val label = if (group.size == 1) name(group.first()) else "${name(group.first())}–${name(group.last())}"

            label + " " + days.getValue(group.first()).joinToString(" + ") { "${it.from}–${it.to}" }
        }
    }
}

/** What "Openingstijden" of the PBX (`/pbx/hours`) edits. */
data class HoursDraft(
    val days: Map<Int, List<WeekSlot>> = emptyMap(),
    val openTarget: PbxTarget? = null,
    val closedTarget: PbxTarget? = null,
) {
    val week: List<HoursWeekEntry>
        get() = WeekDraft.toEntries(days)

    /** Only what changed, with the version of [original]. `null` = nothing changed. */
    fun patch(original: PbxHoursItem): PbxHoursPatch? {
        val initial = of(original)
        val patch = PbxHoursPatch(
            version = original.version,
            week = week.takeIf { it != initial.week },
            openTarget = if (openTarget == initial.openTarget) Change.Keep else Change.of(openTarget),
            closedTarget = if (closedTarget == initial.closedTarget) Change.Keep else Change.of(closedTarget),
        )

        return patch.takeUnless { it.isEmpty }
    }

    companion object {
        fun of(item: PbxHoursItem) = HoursDraft(WeekDraft.fromEntries(item.week), item.openTarget, item.closedTarget)
    }
}

/**
 * "Tijdelijk dicht": own dates in the holiday list of the opening hours. The list in a PATCH REPLACES the old one, so every existing
 * holiday (fixed rule or own date) goes along; only the own dates are added or removed.
 */
object TemporaryClosure {
    const val CUSTOM_DATE_LIMIT = 20

    sealed interface Plan {
        data class Holidays(val holidays: List<HolidayInput>) : Plan

        data object EmptyRange : Plan

        /** More own dates than the PBX takes; [room] = how many more fit. */
        data class TooMany(val room: Int) : Plan
    }

    /** Every day from [from] to [to] (inclusive) as `YYYY-MM-DD`, at most one more than the limit (enough to see it is too many). */
    fun dates(from: LocalDate, to: LocalDate): List<String> {
        if (from.isAfter(to)) return emptyList()

        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.take(CUSTOM_DATE_LIMIT + 2).map { it.toString() }.toList()
    }

    fun closing(hours: PbxHoursItem, name: String, dates: List<String>, defaultName: String): Plan {
        if (dates.isEmpty()) return Plan.EmptyRange

        val have = hours.holidays.mapNotNull { it.date }.toSet()
        val fresh = dates.filterNot { it in have }
        val room = (CUSTOM_DATE_LIMIT - have.size).coerceAtLeast(0)

        if (fresh.size > room) return Plan.TooMany(room)

        val label = name.trim().ifEmpty { defaultName }

        return Plan.Holidays(preserved(hours.holidays) + fresh.map { HolidayInput.Custom(label, it) })
    }

    /** The list without one own date. */
    fun reopening(hours: PbxHoursItem, date: String): List<HolidayInput> = preserved(hours.holidays.filter { it.date != date })

    /** Every holiday of the opening hours as a PATCH item. */
    fun preserved(holidays: List<PbxHoliday>): List<HolidayInput> = holidays.mapNotNull { holiday ->
        when {
            holiday.rule != null -> HolidayInput.Rule(holiday.rule)
            holiday.date != null -> HolidayInput.Custom(holiday.name, holiday.date)
            else -> null
        }
    }
}

// MARK: Number chain

/** The value after a change on the server: what the user did not touch ([mine] still equals [initial]) follows [theirs]. */
internal fun <T> rebased(mine: T, initial: T, theirs: T): T = if (mine == initial) theirs else mine

/** The opening-hours step of a number. */
data class ChainHoursDraft(
    val enabled: Boolean = false,
    val days: Map<Int, List<WeekSlot>> = emptyMap(),
    val national: Boolean = false,
    val rules: List<String> = emptyList(),
    val dates: List<ChainHolidayDate> = emptyList(),
    val closed: Fallback = Fallback.Voicemail(),
    val holiday: Fallback? = null,
) {
    val week: List<HoursWeekEntry>
        get() = WeekDraft.toEntries(days)

    val isValid: Boolean
        get() = !enabled || WeekDraft.isValid(days)

    /** The step to send, or `null` when nothing differs from [original]. A fallback the chain cannot show ([Fallback.Other]) is never sent. */
    fun step(original: ChainHoursDraft): ChainStep.Hours? {
        if (this == original) return null

        if (!enabled) return ChainStep.Hours(enabled = false)

        return ChainStep.Hours(
            enabled = true,
            week = week,
            nationalHolidays = national,
            // Fixed holidays only count when "all national holidays" is off; the ones that are on stay on.
            holidayRules = if (national) null else rules,
            holidayDates = dates,
            closed = closed.takeIf { it.isSelectable },
            holiday = when (val value = holiday) {
                null -> Change.Clear
                is Fallback.Other -> Change.Keep
                else -> Change.Set(value)
            },
        )
    }

    /** The server has a newer state: the fields the user did not touch follow it, the touched ones stay as typed. */
    fun rebase(fresh: ChainHoursDraft, baseline: ChainHoursDraft): ChainHoursDraft = ChainHoursDraft(
        enabled = rebased(enabled, baseline.enabled, fresh.enabled),
        days = rebased(days, baseline.days, fresh.days),
        national = rebased(national, baseline.national, fresh.national),
        rules = rebased(rules, baseline.rules, fresh.rules),
        dates = rebased(dates, baseline.dates, fresh.dates),
        closed = rebased(closed, baseline.closed, fresh.closed),
        holiday = rebased(holiday, baseline.holiday, fresh.holiday),
    )

    companion object {
        fun of(chain: NumberChain): ChainHoursDraft {
            val hours = chain.hours ?: return ChainHoursDraft(enabled = false, days = WeekDraft.DAYS.take(5).associateWith { listOf(WeekDraft.DEFAULT_SLOT) })

            return ChainHoursDraft(true, WeekDraft.fromEntries(hours.week), hours.holidays.national, hours.holidays.rules, hours.holidays.dates, hours.closed, hours.holiday)
        }
    }
}

data class ChainWelcomeDraft(val enabled: Boolean = false, val soundId: String? = null) {
    val isValid: Boolean
        get() = !enabled || soundId != null

    fun step(original: ChainWelcomeDraft): ChainStep.Welcome? {
        if (this == original) return null

        return ChainStep.Welcome(enabled, if (enabled && soundId != null) Change.Set(soundId) else Change.Keep)
    }

    fun rebase(fresh: ChainWelcomeDraft, baseline: ChainWelcomeDraft) =
        ChainWelcomeDraft(rebased(enabled, baseline.enabled, fresh.enabled), rebased(soundId, baseline.soundId, fresh.soundId))

    companion object {
        fun of(chain: NumberChain) = ChainWelcomeDraft(chain.welcome != null, chain.welcome?.soundId)
    }
}

/** The forwarding step: one of two shapes, switched with [kind]. */
data class ChainForwardingDraft(
    val kind: ForwardingKind = ForwardingKind.STANDARD,
    // standard
    val strategy: RingStrategy = RingStrategy.ALL,
    val members: List<ChainMember> = emptyList(),
    val unanswered: Fallback = Fallback.Voicemail(),
    // menu
    val greetingSoundId: String? = null,
    val repeats: Int = 2,
    val timeoutSeconds: Int = 8,
    val defaultKey: String? = null,
    val noChoice: Fallback = Fallback.Hangup,
    val keys: List<ChainKey> = emptyList(),
) {
    val isValid: Boolean
        get() = when (kind) {
            ForwardingKind.STANDARD -> members.isNotEmpty()
            ForwardingKind.MENU -> keys.isNotEmpty() && keys.map { it.digit }.distinct().size == keys.size && keys.all { it.target.type != TargetType.UNKNOWN }
            else -> false
        }

    fun step(original: ChainForwardingDraft): ChainStep? {
        if (this == original) return null

        return when (kind) {
            ForwardingKind.STANDARD -> ChainStep.ForwardingStandard(strategy, members, unanswered.takeIf { it.isSelectable })
            ForwardingKind.MENU -> ChainStep.ForwardingMenu(
                greetingSoundId = greetingSoundId?.let { Change.Set(it) } ?: Change.Keep,
                repeats = repeats,
                timeoutSeconds = timeoutSeconds,
                defaultKey = defaultKey?.let { Change.Set(it) } ?: Change.Clear,
                noChoice = noChoice.takeIf { it.isSelectable },
                keys = keys,
            )
            else -> null
        }
    }

    fun rebase(fresh: ChainForwardingDraft, baseline: ChainForwardingDraft) = ChainForwardingDraft(
        kind = rebased(kind, baseline.kind, fresh.kind),
        strategy = rebased(strategy, baseline.strategy, fresh.strategy),
        members = rebased(members, baseline.members, fresh.members),
        unanswered = rebased(unanswered, baseline.unanswered, fresh.unanswered),
        greetingSoundId = rebased(greetingSoundId, baseline.greetingSoundId, fresh.greetingSoundId),
        repeats = rebased(repeats, baseline.repeats, fresh.repeats),
        timeoutSeconds = rebased(timeoutSeconds, baseline.timeoutSeconds, fresh.timeoutSeconds),
        defaultKey = rebased(defaultKey, baseline.defaultKey, fresh.defaultKey),
        noChoice = rebased(noChoice, baseline.noChoice, fresh.noChoice),
        keys = rebased(keys, baseline.keys, fresh.keys),
    )

    /** The next free key (`1`..`9`, `0`, `*`, `#`). */
    fun nextFreeDigit(): String? = (('1'..'9').map { it.toString() } + listOf("0", "*", "#")).firstOrNull { digit -> keys.none { it.digit == digit } }

    companion object {
        val DIGITS = (('1'..'9').map { it.toString() } + listOf("0", "*", "#"))
        val MENU_REPEATS = 0..5
        val MENU_TIMEOUT = 3..30

        fun of(chain: NumberChain): ChainForwardingDraft = when (val forwarding = chain.forwarding) {
            is ChainForwarding.Standard -> ChainForwardingDraft(ForwardingKind.STANDARD, forwarding.strategy, forwarding.members, forwarding.unanswered)
            is ChainForwarding.Menu -> ChainForwardingDraft(
                ForwardingKind.MENU,
                greetingSoundId = forwarding.greetingSoundId,
                repeats = forwarding.repeats,
                timeoutSeconds = forwarding.timeoutSeconds,
                defaultKey = forwarding.defaultKey,
                noChoice = forwarding.noChoice,
                keys = forwarding.keys,
            )
            else -> ChainForwardingDraft()
        }
    }
}

data class ChainRecordingDraft(val enabled: Boolean = false, val announcementSoundId: String? = null, val costAccepted: Boolean = false) {
    /** Switching on something that costs money needs the consent, unless it is billed already or free. */
    fun needsConsent(chain: NumberChain): Boolean = enabled && !chain.recording.billingActive && chain.recording.cost != null && !chain.recording.enabled

    fun canSave(chain: NumberChain): Boolean = (!enabled || chain.recording.available) && (!needsConsent(chain) || costAccepted)

    fun rebase(fresh: ChainRecordingDraft, baseline: ChainRecordingDraft) = ChainRecordingDraft(
        rebased(enabled, baseline.enabled, fresh.enabled),
        rebased(announcementSoundId, baseline.announcementSoundId, fresh.announcementSoundId),
        costAccepted,
    )

    fun patch(original: ChainRecordingDraft, chain: NumberChain): NumberRecordingPatch? {
        if (enabled == original.enabled && announcementSoundId == original.announcementSoundId) return null

        return NumberRecordingPatch(
            enabled = enabled,
            announcementSoundId = if (announcementSoundId == original.announcementSoundId) Change.Keep else Change.of(announcementSoundId),
            costAccepted = true.takeIf { needsConsent(chain) && costAccepted },
            version = chain.version,
        )
    }

    companion object {
        fun of(chain: NumberChain) = ChainRecordingDraft(chain.recording.enabled, chain.recording.announcementSoundId)
    }
}

/** The price of recording as text, in euros: `20000` -> `2,00`. */
object RecordingPrice {
    fun format(cost: RecordingCost, locale: Locale = Locale.getDefault()): String =
        java.text.NumberFormat.getCurrencyInstance(locale).also { it.currency = java.util.Currency.getInstance("EUR") }.format(cost.priceE4 / 10_000.0)
}

/** The rules for recording a sound with the microphone (the iOS `SoundRecording`): five minutes at most, one second at least. */
object SoundRecordingRules {
    const val MAX_SECONDS = 300
    const val MIN_SECONDS = 1

    /** `true` when the recording is long enough to keep. */
    fun isLongEnough(seconds: Double): Boolean = seconds >= MIN_SECONDS

    /** The seconds as `m:ss`, never past the limit. */
    fun clock(seconds: Double): String {
        val total = seconds.toInt().coerceIn(0, MAX_SECONDS)

        return "%d:%02d".format(total / 60, total % 60)
    }

    /** The file name and type to upload a picked or recorded file under. */
    fun contentTypeFor(fileName: String, reported: String?): String {
        val lower = fileName.lowercase()

        return when {
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".mp3") -> "audio/mpeg"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") || lower.endsWith(".aac") -> "audio/mp4"
            reported != null && reported.startsWith("audio/") -> reported
            else -> "application/octet-stream"
        }
    }

    const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
}

/** "Voicemailbegroeting (Voorbeeld BV)": what the place is, then its name. [kindLabel] is the label of the kind, or `null` when unknown. */
object SoundPlaces {
    fun label(place: UsePlace, kindLabel: String?): String = when {
        kindLabel == null -> place.name
        place.name.isEmpty() -> kindLabel
        else -> "$kindLabel (${place.name})"
    }
}

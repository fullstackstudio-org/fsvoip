// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The forms of a number's chain, as values. Each draft is made from a `NumberChain`, remembers nothing else, and turns into the
// request of its step with only what differs from the BASELINE (the chain the form was opened with, or rebased onto after a
// "changed in the meantime"). Pure, so the bodies and the stale recovery can be tested without a screen.

package nl.fullstackstudio.fsvoip.ui.pbx

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.Change
import nl.fullstackstudio.fsvoip.core.ChainClosedStep
import nl.fullstackstudio.fsvoip.core.ChainForwarding
import nl.fullstackstudio.fsvoip.core.ChainHolidayDate
import nl.fullstackstudio.fsvoip.core.ChainHolidaysInput
import nl.fullstackstudio.fsvoip.core.ChainHoursStep
import nl.fullstackstudio.fsvoip.core.ChainKey
import nl.fullstackstudio.fsvoip.core.ChainMember
import nl.fullstackstudio.fsvoip.core.ChainMenuForwardingStep
import nl.fullstackstudio.fsvoip.core.ChainNameStep
import nl.fullstackstudio.fsvoip.core.ChainOptions
import nl.fullstackstudio.fsvoip.core.ChainRecording
import nl.fullstackstudio.fsvoip.core.ChainStandardForwardingStep
import nl.fullstackstudio.fsvoip.core.ChainStepRequest
import nl.fullstackstudio.fsvoip.core.ChainVersions
import nl.fullstackstudio.fsvoip.core.ChainWelcomeStep
import nl.fullstackstudio.fsvoip.core.Fallback
import nl.fullstackstudio.fsvoip.core.HoursWeekEntry
import nl.fullstackstudio.fsvoip.core.NumberChain
import nl.fullstackstudio.fsvoip.core.NumberRecordingPatch
import nl.fullstackstudio.fsvoip.core.PbxNumberEntry
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.ForwardingKind
import nl.fullstackstudio.fsvoip.core.RecordingCost
import nl.fullstackstudio.fsvoip.core.RingStrategy
import nl.fullstackstudio.fsvoip.core.SyncState

/** Keep what the user changed, take the rest from the fresh chain: field by field, mine wins only where it differs from the baseline. */
fun <T> rebased(mine: T, base: T, fresh: T): T = if (mine == base) fresh else mine

/** A form of one step: able to move onto a fresh chain without losing what the user changed. */
interface ChainDraft<D : ChainDraft<D>> {
    /** `this` = what the user has now; [base] = what the form started from; [fresh] = the fresh chain's draft. */
    fun rebase(base: D, fresh: D): D
}

/** After "changed in the meantime": the rebased draft and the new baseline (so a second save sends the user's changes with the new versions). */
fun <D : ChainDraft<D>> D.rebaseOnto(fresh: NumberChain, baseline: D, make: (NumberChain) -> D): Pair<D, D> {
    val freshDraft = make(fresh)

    return rebase(baseline, freshDraft) to freshDraft
}

/** The versions of the chain and its parts, sent with every step so the server can see a lost update. */
val NumberChain.stepVersions: ChainVersions
    get() {
        val objects = linkedMapOf<String, Int>()

        hours?.let { objects[it.id] = it.version }
        welcome?.let { objects[it.menuId] = it.version }

        when (val value = forwarding) {
            is ChainForwarding.Standard -> value.value.groupId?.let { objects[it] = value.value.version }
            is ChainForwarding.Menu -> objects[value.value.menuId] = value.value.version
            is ChainForwarding.Unknown, null -> Unit
        }

        return ChainVersions(objects.takeIf { it.isNotEmpty() }, version)
    }

/** The label of a number: its name, else the number itself. */
val NumberChain.displayName: String
    get() = name?.trim().takeUnless { it.isNullOrEmpty() } ?: PbxVocabulary.formatNumber(number)

/** The label of a list entry: its name, else the number itself. */
val PbxNumberEntry.displayName: String
    get() = name?.trim().takeUnless { it.isNullOrEmpty() } ?: PbxVocabulary.formatNumber(number)

/** Only a selectable fallback can be sent; `other` stays as it is on the server. */
private fun sendable(fallback: Fallback): Fallback? = fallback.takeIf { it.isSelectable }

// MARK: - The week as days with time slots (pure)

/**
 * A week of opening hours: per day (1 = Monday ... 7 = Sunday) up to four time slots, as text (`HH:MM`; `24:00` = until midnight).
 * Equal when it reads the same: a day that is switched off and a day that never had hours are the same.
 */
class WeekPlan(val days: Map<Int, List<HoursInterval>> = emptyMap()) {
    constructor(week: List<HoursWeekEntry>) : this(week.sortedWith(compareBy({ it.day }, { it.from })).groupBy({ it.day }) { HoursInterval(it.from, it.to) })

    /** The week as the API wants it. */
    val entries: List<HoursWeekEntry>
        get() = weekdays.flatMap { day -> (days[day] ?: emptyList()).map { HoursWeekEntry(day, it.from, it.to) } }

    fun slots(day: Int): List<HoursInterval> = days[day] ?: emptyList()

    fun with(day: Int, slots: List<HoursInterval>): WeekPlan = WeekPlan(days + (day to slots))

    override fun equals(other: Any?): Boolean = other is WeekPlan && other.entries == entries

    override fun hashCode(): Int = entries.hashCode()

    /** The preset this week is, if it is one. */
    val matchingPreset: Preset? get() = Preset.entries.firstOrNull { preset(it) == this }

    enum class Preset {
        /** Monday to Friday 09:00-17:00. */
        OFFICE,

        /** Every day all day. */
        ALWAYS,

        /** Closed every day. */
        CLOSED,
    }

    companion object {
        val weekdays = (1..7).toList()

        /** The most slots one day can have. */
        const val SLOTS_PER_DAY = 4
        val defaultSlot = HoursInterval("09:00", "17:00")

        fun preset(preset: Preset): WeekPlan = when (preset) {
            Preset.OFFICE -> WeekPlan((1..5).associateWith { listOf(HoursInterval("09:00", "17:00")) })
            Preset.ALWAYS -> WeekPlan(weekdays.associateWith { listOf(HoursInterval("00:00", "24:00")) })
            Preset.CLOSED -> WeekPlan()
        }
    }
}

/** The arithmetic behind a day bar: where the blocks go, and whether a slot has a valid shape. Pure, so it can be tested. */
object DayBarsLogic {
    const val MINUTES_PER_DAY = 24 * 60

    /** `"09:30"` -> 570, `"24:00"` -> 1440. `null` when it is not `HH:MM` within the day. */
    fun minutes(text: String): Int? {
        val parts = text.split(":")

        if (parts.size != 2 || parts[0].length != 2 || parts[1].length != 2) return null

        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null

        if (hour !in 0..24 || minute !in 0..59 || (hour == 24 && minute != 0)) return null

        return hour * 60 + minute
    }

    /** The shape the server accepts: two valid times, the end after the start. (Overlaps and the rest are the server's call.) */
    fun isValid(slot: HoursInterval): Boolean {
        val from = minutes(slot.from) ?: return false
        val to = minutes(slot.to) ?: return false

        return from < to && from < MINUTES_PER_DAY
    }

    /** The blocks of one day as fractions of the bar (0 = 00:00, 1 = 24:00), sorted, invalid slots left out. */
    fun segments(slots: List<HoursInterval>): List<ClosedFloatingPointRange<Double>> = slots.mapNotNull { slot ->
        if (!isValid(slot)) return@mapNotNull null

        val from = minutes(slot.from) ?: return@mapNotNull null
        val to = minutes(slot.to) ?: return@mapNotNull null

        (from.toDouble() / MINUTES_PER_DAY)..(to.toDouble() / MINUTES_PER_DAY)
    }.sortedBy { it.start }

    /** A new slot for a day that already has some: after the last one, if there is room; else the default. */
    fun nextSlot(after: List<HoursInterval>): HoursInterval {
        val last = after.mapNotNull { minutes(it.to) }.maxOrNull()

        if (last == null || last >= MINUTES_PER_DAY - 60) return WeekPlan.defaultSlot

        val end = minOf(last + 4 * 60, MINUTES_PER_DAY)

        return HoursInterval(format(last), format(end))
    }

    fun format(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)
}

// MARK: - Name

data class NumberNameDraft(val name: String) : ChainDraft<NumberNameDraft> {
    constructor(chain: NumberChain) : this(chain.name ?: "")

    private val cleaned: String get() = name.trim()

    override fun rebase(base: NumberNameDraft, fresh: NumberNameDraft) = NumberNameDraft(rebased(name, base.name, fresh.name))

    fun step(baseline: NumberNameDraft, chain: NumberChain): ChainNameStep? {
        if (cleaned == baseline.cleaned) return null

        return ChainNameStep(cleaned.ifEmpty { null }, chain.stepVersions)
    }
}

// MARK: - Opening hours

data class NumberHoursDraft(
    val enabled: Boolean,
    val week: WeekPlan,
    /** All Dutch national holidays use the "closed" behaviour. */
    val national: Boolean,
    /** Own dates (holiday, building holiday, a day off). */
    val dates: List<ChainHolidayDate>,
    val closed: Fallback,
    /** `null` = on a holiday the same as outside opening hours. */
    val holiday: Fallback?,
) : ChainDraft<NumberHoursDraft> {
    constructor(chain: NumberChain) : this(
        enabled = chain.hours != null,
        // Switching opening hours on starts from office hours, closed = the shared voicemail.
        week = chain.hours?.let { WeekPlan(it.week) } ?: WeekPlan.preset(WeekPlan.Preset.OFFICE),
        national = chain.hours?.holidays?.national ?: true,
        dates = chain.hours?.holidays?.dates?.sortedBy { it.date } ?: emptyList(),
        closed = chain.hours?.closed ?: Fallback.DEFAULT,
        holiday = chain.hours?.holiday,
    )

    /** Every slot has the shape the server takes (the rest is the server's call). */
    val isWellFormed: Boolean
        get() = !enabled || week.days.values.all { slots -> slots.all(DayBarsLogic::isValid) && slots.size <= WeekPlan.SLOTS_PER_DAY }

    override fun rebase(base: NumberHoursDraft, fresh: NumberHoursDraft) = NumberHoursDraft(
        enabled = rebased(enabled, base.enabled, fresh.enabled),
        week = rebased(week, base.week, fresh.week),
        national = rebased(national, base.national, fresh.national),
        dates = rebased(dates, base.dates, fresh.dates),
        closed = rebased(closed, base.closed, fresh.closed),
        holiday = rebased(holiday, base.holiday, fresh.holiday),
    )

    /** The request: `hours` (on, off or the week/holidays changed) or `closed` (only what happens when it is closed). */
    fun step(baseline: NumberHoursDraft, chain: NumberChain): ChainStepRequest? {
        val versions = chain.stepVersions

        if (!enabled && !baseline.enabled) return null

        if (!enabled) return ChainHoursStep(enabled = false, versions = versions)

        if (!baseline.enabled) {
            // Switching on: everything the new (or re-linked) opening hours need.
            return ChainHoursStep(
                enabled = true,
                week = week.entries,
                holidays = ChainHolidaysInput(national = national, dates = dates),
                closed = sendable(closed),
                holiday = holidayChange(baseline, always = true),
                versions = versions,
            )
        }

        val weekChanged = week != baseline.week
        val nationalChanged = national != baseline.national
        val datesChanged = dates != baseline.dates
        val closedChanged = closed != baseline.closed
        val holidayChanged = holiday != baseline.holiday

        if (!(weekChanged || nationalChanged || datesChanged || closedChanged || holidayChanged)) return null

        if (!weekChanged && !nationalChanged && !datesChanged) {
            return ChainClosedStep(
                closed = if (closedChanged) sendable(closed) else null,
                holiday = holidayChange(baseline, always = false),
                versions = versions,
            )
        }

        val holidays = if (nationalChanged || datesChanged) {
            ChainHolidaysInput(national = national.takeIf { nationalChanged }, dates = dates.takeIf { datesChanged })
        } else {
            null
        }

        return ChainHoursStep(
            enabled = true,
            week = if (weekChanged) week.entries else null,
            holidays = holidays,
            closed = if (closedChanged) sendable(closed) else null,
            holiday = holidayChange(baseline, always = false),
            versions = versions,
        )
    }

    private fun holidayChange(baseline: NumberHoursDraft, always: Boolean): Change<Fallback> {
        if (!always && holiday == baseline.holiday) return Change.Keep

        val value = holiday ?: return if (always) Change.Keep else Change.Clear

        return if (value.isSelectable) Change.Set(value) else Change.Keep
    }

    /**
     * Adds every day of a range as an own date (a date that is already there stays as it is). `null` when the limit is passed.
     */
    fun adding(newDates: List<String>, name: String, defaultName: String): List<ChainHolidayDate>? {
        val label = name.trim().ifEmpty { defaultName }
        val result = dates.toMutableList()

        for (date in newDates) {
            if (result.none { it.date == date }) result += ChainHolidayDate(label, date)
        }

        if (result.size > OWN_DATE_LIMIT) return null

        return result.sortedBy { it.date }
    }

    companion object {
        const val OWN_DATE_LIMIT = TemporaryClosure.CUSTOM_DATE_LIMIT
    }
}

// MARK: - Welcome message

data class NumberWelcomeDraft(val enabled: Boolean, val soundId: String?) : ChainDraft<NumberWelcomeDraft> {
    constructor(chain: NumberChain) : this(chain.welcome != null, chain.welcome?.soundId)

    /** A welcome message needs a sound. */
    val canSave: Boolean get() = !enabled || soundId != null

    override fun rebase(base: NumberWelcomeDraft, fresh: NumberWelcomeDraft) = NumberWelcomeDraft(
        rebased(enabled, base.enabled, fresh.enabled),
        rebased(soundId, base.soundId, fresh.soundId),
    )

    fun step(baseline: NumberWelcomeDraft, chain: NumberChain): ChainWelcomeStep? {
        if (this == baseline) return null

        val sound: Change<String> = if (enabled && soundId != baseline.soundId) Change.of(soundId) else Change.Keep

        return ChainWelcomeStep(enabled, sound, chain.stepVersions)
    }
}

// MARK: - Forwarding

data class NumberForwardingDraft(
    val kind: Kind,
    // Standard
    val strategy: RingStrategy,
    val members: List<ChainMember>,
    val unanswered: Fallback,
    // Menu
    val greetingSoundId: String?,
    val repeats: Int,
    val timeoutSeconds: Int,
    val defaultKey: String?,
    val noChoice: Fallback,
    val keys: List<ChainKey>,
) : ChainDraft<NumberForwardingDraft> {
    enum class Kind { STANDARD, MENU }

    constructor(chain: NumberChain) : this(
        when (chain.forwarding) {
            is ChainForwarding.Menu -> Kind.MENU
            else -> Kind.STANDARD
        },
        strategy = (chain.forwarding as? ChainForwarding.Standard)?.value?.strategy?.takeIf { it != RingStrategy.UNKNOWN } ?: RingStrategy.ALL,
        members = (chain.forwarding as? ChainForwarding.Standard)?.value?.members ?: emptyList(),
        unanswered = when (val f = chain.forwarding) {
            is ChainForwarding.Standard -> f.value.unanswered
            is ChainForwarding.Menu -> f.value.noChoice
            else -> Fallback.DEFAULT
        },
        greetingSoundId = (chain.forwarding as? ChainForwarding.Menu)?.value?.greetingSoundId,
        repeats = (chain.forwarding as? ChainForwarding.Menu)?.value?.repeats ?: 1,
        timeoutSeconds = (chain.forwarding as? ChainForwarding.Menu)?.value?.timeoutSeconds ?: 5,
        defaultKey = (chain.forwarding as? ChainForwarding.Menu)?.value?.defaultKey,
        noChoice = when (val f = chain.forwarding) {
            is ChainForwarding.Standard -> f.value.unanswered
            is ChainForwarding.Menu -> f.value.noChoice
            else -> Fallback.DEFAULT
        },
        keys = (chain.forwarding as? ChainForwarding.Menu)?.value?.keys ?: emptyList(),
    )

    // MARK: Standard helpers

    /** Members the device list does not know: kept and sent along as they are, so they are shown read-only instead of travelling invisibly. */
    fun membersNotIn(deviceIds: Set<String>): List<ChainMember> = members.filter { it.deviceId !in deviceIds }

    fun isMember(deviceId: String): Boolean = members.any { it.deviceId == deviceId }

    fun withMember(deviceId: String, on: Boolean): NumberForwardingDraft = if (on) {
        if (isMember(deviceId)) this else copy(members = members + ChainMember(deviceId, 0, ringSeconds ?: 25))
    } else {
        copy(members = members.filter { it.deviceId != deviceId })
    }

    /** How long the phones ring, when every member rings equally long. */
    val ringSeconds: Int? get() = members.map { it.timeoutSeconds }.toSet().singleOrNull()

    fun withRingSeconds(seconds: Int): NumberForwardingDraft = copy(members = members.map { it.copy(timeoutSeconds = seconds) })

    // MARK: Menu helpers

    fun key(digit: String): ChainKey? = keys.firstOrNull { it.digit == digit }

    /** `null` switches the key off. A key the portal set up (`editable == false`) is never changed here. */
    fun withKey(digit: String, target: PbxTarget?): NumberForwardingDraft {
        if (key(digit)?.editable == false) return this

        var result = keys.filter { it.digit != digit }

        if (target != null) result = (result + ChainKey(digit, target)).sortedBy { order(it.digit) }

        val newDefault = defaultKey?.takeIf { d -> result.any { it.digit == d } }

        return copy(keys = result, defaultKey = newDefault)
    }

    private val editableKeys: List<ChainKey> get() = keys.filter { it.editable }

    // MARK: Saving

    /** The form has what its kind needs: a menu has at least one key; a standard forwarding at least one phone. */
    val canSave: Boolean get() = when (kind) {
        Kind.STANDARD -> members.isNotEmpty()
        Kind.MENU -> keys.isNotEmpty()
    }

    override fun rebase(base: NumberForwardingDraft, fresh: NumberForwardingDraft) = NumberForwardingDraft(
        kind = rebased(kind, base.kind, fresh.kind),
        strategy = rebased(strategy, base.strategy, fresh.strategy),
        members = rebased(members, base.members, fresh.members),
        unanswered = rebased(unanswered, base.unanswered, fresh.unanswered),
        greetingSoundId = rebased(greetingSoundId, base.greetingSoundId, fresh.greetingSoundId),
        repeats = rebased(repeats, base.repeats, fresh.repeats),
        timeoutSeconds = rebased(timeoutSeconds, base.timeoutSeconds, fresh.timeoutSeconds),
        defaultKey = rebased(defaultKey, base.defaultKey, fresh.defaultKey),
        noChoice = rebased(noChoice, base.noChoice, fresh.noChoice),
        keys = rebased(keys, base.keys, fresh.keys),
    )

    fun step(baseline: NumberForwardingDraft, chain: NumberChain): ChainStepRequest? {
        val versions = chain.stepVersions
        val switched = kind != baseline.kind

        when (kind) {
            Kind.STANDARD -> {
                val strategyChanged = switched || strategy != baseline.strategy
                val membersChanged = switched || members != baseline.members
                val unansweredChanged = switched || unanswered != baseline.unanswered

                if (!(strategyChanged || membersChanged || unansweredChanged)) return null

                return ChainStandardForwardingStep(
                    strategy = strategy.takeIf { strategyChanged },
                    members = members.takeIf { membersChanged },
                    unanswered = if (unansweredChanged) sendable(unanswered) else null,
                    versions = versions,
                )
            }
            Kind.MENU -> {
                val greetingChanged = switched || greetingSoundId != baseline.greetingSoundId
                val repeatsChanged = switched || repeats != baseline.repeats
                val timeoutChanged = switched || timeoutSeconds != baseline.timeoutSeconds
                val defaultChanged = switched || defaultKey != baseline.defaultKey
                val noChoiceChanged = switched || noChoice != baseline.noChoice
                val keysChanged = switched || keys != baseline.keys

                if (!(greetingChanged || repeatsChanged || timeoutChanged || defaultChanged || noChoiceChanged || keysChanged)) return null

                return ChainMenuForwardingStep(
                    greetingSoundId = if (greetingChanged) (if (switched && greetingSoundId == null) Change.Keep else Change.of(greetingSoundId)) else Change.Keep,
                    repeats = repeats.takeIf { repeatsChanged },
                    timeoutSeconds = timeoutSeconds.takeIf { timeoutChanged },
                    defaultKey = if (defaultChanged) (if (switched && defaultKey == null) Change.Keep else Change.of(defaultKey)) else Change.Keep,
                    noChoice = if (noChoiceChanged) sendable(noChoice) else null,
                    keys = editableKeys.takeIf { keysChanged },
                    versions = versions,
                )
            }
        }
    }

    companion object {
        val menuDigits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val repeatsRange = 0..5
        val ringSecondsRange = 5..120

        private fun order(digit: String): Int = menuDigits.indexOf(digit).let { if (it < 0) menuDigits.size else it }
    }
}

// MARK: - Recording

data class NumberRecordingDraft(
    val enabled: Boolean,
    val announcementSoundId: String?,
    /** The customer accepted the monthly price (only asked when this save starts the billing). */
    val costAccepted: Boolean = false,
) : ChainDraft<NumberRecordingDraft> {
    constructor(chain: NumberChain) : this(chain.recording.enabled, chain.recording.announcementSoundId, false)

    /** Switching on starts the billing: show the price and ask for consent (only when billing is not active yet). */
    fun needsConsent(recording: ChainRecording, cost: RecordingCost? = null): Boolean {
        val price = cost ?: recording.cost

        return enabled && !recording.billingActive && (price?.priceE4 ?: 0) > 0
    }

    override fun rebase(base: NumberRecordingDraft, fresh: NumberRecordingDraft) = NumberRecordingDraft(
        rebased(enabled, base.enabled, fresh.enabled),
        rebased(announcementSoundId, base.announcementSoundId, fresh.announcementSoundId),
        costAccepted,
    )

    fun patch(baseline: NumberRecordingDraft, chain: NumberChain): NumberRecordingPatch? {
        if (enabled == baseline.enabled && announcementSoundId == baseline.announcementSoundId) return null

        val announcement: Change<String> = if (announcementSoundId != baseline.announcementSoundId) Change.of(announcementSoundId) else Change.Keep
        // Consent goes along only when it was asked and given (the toggle exists only when this save starts the billing).
        val consent: Boolean? = if (enabled && costAccepted) true else null

        return NumberRecordingPatch(enabled, announcement, consent, chain.version)
    }

    /** Dirty compared with the baseline: the consent checkbox alone is not a change. */
    fun differsFrom(baseline: NumberRecordingDraft): Boolean = enabled != baseline.enabled || announcementSoundId != baseline.announcementSoundId
}

// MARK: - Showing a price

object RecordingPrice {
    /** "€ 2,00 per maand (excl. btw)". */
    fun text(cost: RecordingCost, t: PbxText, locale: Locale = Locale.getDefault()): String {
        val formatter = NumberFormat.getCurrencyInstance(locale)
        formatter.currency = Currency.getInstance("EUR")
        val money = formatter.format(cost.priceE4 / 10_000.0)

        return t.get(if (cost.vatIncluded) R.string.adm_numbers_recording_price_incl else R.string.adm_numbers_recording_price_excl, money)
    }
}

// MARK: - Words for the rows of a number

/** The values on the rows of a number and the sentences of fallbacks and keys, in words. */
class NumberWords(private val t: PbxText, private val vocabulary: PbxVocabulary) {
    fun soundName(id: String?, options: ChainOptions): String? {
        if (id == null) return null

        return options.sounds.firstOrNull { it.id == id }?.name ?: t.get(R.string.adm_numbers_sound_unknown)
    }

    fun deviceName(id: String, options: ChainOptions): String = options.devices.firstOrNull { it.id == id }?.name ?: t.get(R.string.adm_numbers_device_unknown)

    /** "Voicemail", "Voicemail van Jan", "Bericht: Welkom", "Doorschakelen naar 06 ...", "Verbinding verbreken". */
    fun fallback(fallback: Fallback, options: ChainOptions): String = when (fallback) {
        is Fallback.Voicemail ->
            if (fallback.ofDevice && fallback.boxId != null) {
                t.get(R.string.adm_numbers_fallback_voicemailOf, deviceName(fallback.boxId!!, options))
            } else {
                t.get(R.string.adm_numbers_fallback_voicemail)
            }
        is Fallback.Message -> t.get(R.string.adm_numbers_fallback_messageNamed, soundName(fallback.soundId, options) ?: "")
        is Fallback.Forward -> t.get(R.string.adm_numbers_fallback_forwardTo, fallback.number)
        Fallback.Hangup -> t.get(R.string.adm_numbers_fallback_hangup)
        is Fallback.Device -> deviceName(fallback.deviceId, options)
        is Fallback.Other, is Fallback.Unknown -> t.get(R.string.adm_numbers_portalOnly)
    }

    /** Where a menu key goes. */
    fun target(target: PbxTarget, options: ChainOptions): String = when (target.type) {
        nl.fullstackstudio.fsvoip.core.TargetType.DEVICE -> target.id?.let { deviceName(it, options) } ?: t.get(R.string.adm_numbers_device_unknown)
        nl.fullstackstudio.fsvoip.core.TargetType.RING_GROUP -> options.groups.firstOrNull { it.id == target.id }?.name ?: t.get(R.string.adm_pbx_type_ringGroup)
        nl.fullstackstudio.fsvoip.core.TargetType.VOICEMAIL ->
            if (target.id != null && options.devices.any { it.id == target.id }) {
                t.get(R.string.adm_numbers_fallback_voicemailOf, deviceName(target.id!!, options))
            } else {
                t.get(R.string.adm_numbers_fallback_voicemail)
            }
        nl.fullstackstudio.fsvoip.core.TargetType.EXTERNAL -> t.get(R.string.adm_numbers_fallback_forwardTo, target.number ?: "")
        nl.fullstackstudio.fsvoip.core.TargetType.RECORDING -> t.get(R.string.adm_numbers_fallback_messageNamed, soundName(target.id, options) ?: "")
        nl.fullstackstudio.fsvoip.core.TargetType.HANGUP -> t.get(R.string.adm_numbers_fallback_hangup)
        nl.fullstackstudio.fsvoip.core.TargetType.QUEUE, nl.fullstackstudio.fsvoip.core.TargetType.IVR,
        nl.fullstackstudio.fsvoip.core.TargetType.BUSINESS_HOURS, nl.fullstackstudio.fsvoip.core.TargetType.UNKNOWN,
        -> t.get(R.string.adm_numbers_portalOnly)
    }

    fun hours(chain: NumberChain): String {
        val hours = chain.hours ?: return t.get(R.string.adm_numbers_off)

        return vocabulary.hoursSummary(hours.week)
    }

    fun welcome(chain: NumberChain): String {
        val welcome = chain.welcome ?: return t.get(R.string.adm_numbers_off)

        return soundName(welcome.soundId, chain.options) ?: t.get(R.string.adm_numbers_on)
    }

    fun forwarding(chain: NumberChain): String = when (chain.forwarding) {
        is ChainForwarding.Standard -> t.get(R.string.adm_numbers_forwarding_standard)
        is ChainForwarding.Menu -> t.get(R.string.adm_numbers_forwarding_menu)
        is ChainForwarding.Unknown, null -> t.get(R.string.adm_numbers_portalOnly)
    }

    fun forwardingDetail(chain: NumberChain): String? = when (val f = chain.forwarding) {
        is ChainForwarding.Standard -> {
            val line = "${t.get(R.string.adm_numbers_forwarding_phones, f.value.members.size)} · ${vocabulary.strategy(f.value.strategy)}"

            listOfNotNull(line, shared(f.value.sharedWith)).joinToString("\n")
        }
        is ChainForwarding.Menu -> listOfNotNull(t.get(R.string.adm_numbers_menu_keyCount, f.value.keys.size), shared(f.value.sharedWith)).joinToString("\n")
        is ChainForwarding.Unknown, null -> null
    }

    fun recording(chain: NumberChain): String {
        if (chain.recording.enabled) return t.get(R.string.adm_numbers_on)

        return if (chain.recording.available) t.get(R.string.adm_numbers_off) else t.get(R.string.adm_numbers_recording_notAvailable)
    }

    /** "Ook voor Servicenummer". */
    fun shared(names: List<String>?): String? {
        if (names.isNullOrEmpty()) return null

        return t.get(R.string.adm_numbers_alsoFor, names.joinToString(", "))
    }

    /** The one line of a number in the list: "ma-vr 09:00-17:00 · Welkom · Standaard". */
    fun listLine(entry: PbxNumberEntry): String {
        val parts = mutableListOf(entry.summary.hours.ifEmpty { t.get(R.string.adm_numbers_hours_alwaysOpen) })

        if (entry.summary.welcome) parts += t.get(R.string.adm_numbers_welcome_short)

        parts += when (entry.summary.forwarding) {
            ForwardingKind.STANDARD -> t.get(R.string.adm_numbers_forwarding_standard)
            ForwardingKind.MENU -> t.get(R.string.adm_numbers_forwarding_menu)
            ForwardingKind.ADVANCED, ForwardingKind.UNKNOWN -> t.get(R.string.adm_numbers_advanced_short)
        }

        if (entry.summary.recording) parts += t.get(R.string.adm_numbers_recording_short)

        when (entry.sync) {
            SyncState.PENDING -> parts += t.get(R.string.adm_pbx_sync_pending)
            SyncState.ERROR -> parts += t.get(R.string.adm_pbx_sync_error)
            else -> Unit
        }

        return parts.joinToString(" · ")
    }
}

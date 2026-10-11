// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The words of the "Centrale" section. They are the portal's: toestel, belgroep, wachtrij, keuzemenu, openingstijden, voicemail.
// Never extension, ring group, IVR, trunk or gateway.

package nl.fullstackstudio.fsvoip.ui.pbx

import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import nl.fullstackstudio.fsvoip.R
import nl.fullstackstudio.fsvoip.core.FlowKind
import nl.fullstackstudio.fsvoip.core.FlowNode
import nl.fullstackstudio.fsvoip.core.FlowWhen
import nl.fullstackstudio.fsvoip.core.FlowWhenKind
import nl.fullstackstudio.fsvoip.core.HoursWeekEntry
import nl.fullstackstudio.fsvoip.core.PbxTarget
import nl.fullstackstudio.fsvoip.core.PbxTargetOption
import nl.fullstackstudio.fsvoip.core.RingStrategy
import nl.fullstackstudio.fsvoip.core.TargetType

class PbxVocabulary(private val t: PbxText, private val locale: Locale = Locale.getDefault()) {
    // MARK: Targets

    fun typeName(type: TargetType): String = t.get(
        when (type) {
            TargetType.DEVICE -> R.string.adm_pbx_type_device
            TargetType.RING_GROUP -> R.string.adm_pbx_type_ringGroup
            TargetType.QUEUE -> R.string.adm_pbx_type_queue
            TargetType.IVR -> R.string.adm_pbx_type_ivr
            TargetType.BUSINESS_HOURS -> R.string.adm_pbx_type_hours
            TargetType.VOICEMAIL -> R.string.adm_pbx_type_voicemail
            TargetType.RECORDING -> R.string.adm_pbx_type_recording
            TargetType.HANGUP -> R.string.adm_pbx_type_hangup
            TargetType.EXTERNAL -> R.string.adm_pbx_type_external
            TargetType.UNKNOWN -> R.string.adm_pbx_type_unknown
        },
    )

    /** The plural heading of a group of choices in the picker. */
    fun groupName(type: TargetType): String = when (type) {
        TargetType.DEVICE -> t.get(R.string.adm_pbx_group_device)
        TargetType.RING_GROUP -> t.get(R.string.adm_pbx_group_ringGroup)
        TargetType.QUEUE -> t.get(R.string.adm_pbx_group_queue)
        TargetType.IVR -> t.get(R.string.adm_pbx_group_ivr)
        TargetType.BUSINESS_HOURS -> t.get(R.string.adm_pbx_group_hours)
        TargetType.VOICEMAIL -> t.get(R.string.adm_pbx_group_voicemail)
        TargetType.RECORDING -> t.get(R.string.adm_pbx_group_recording)
        TargetType.HANGUP, TargetType.EXTERNAL, TargetType.UNKNOWN -> typeName(type)
    }

    /** "Receptie · 100", "Voicemail van Jan de Vries", "+31 6 12345678", "Ophangen". */
    fun name(option: PbxTargetOption): String = when (option.type) {
        TargetType.VOICEMAIL -> if (option.ofDevice) t.get(R.string.adm_pbx_voicemail_of, option.name) else t.get(R.string.adm_pbx_voicemail_shared, option.name)
        TargetType.DEVICE, TargetType.RING_GROUP, TargetType.QUEUE, TargetType.IVR, TargetType.BUSINESS_HOURS ->
            listOfNotNull(option.name, option.extensionNumber).joinToString(" · ")
        else -> option.name
    }

    fun describe(target: PbxTarget, options: List<PbxTargetOption>): String = when (target.type) {
        TargetType.HANGUP -> t.get(R.string.adm_pbx_type_hangup)
        TargetType.EXTERNAL -> (target.number ?: "").trim().ifEmpty { t.get(R.string.adm_pbx_target_unset) }
        else -> TargetChoice.option(target, options)?.let(::name) ?: t.get(R.string.adm_pbx_target_gone)
    }

    // MARK: Call flow

    /** The step in words: "Belgroep Iedereen · 200". */
    fun title(node: FlowNode): String {
        val name = node.name ?: ""

        return when (node.kind) {
            FlowKind.DEVICE -> t.get(R.string.adm_pbx_flow_device, name)
            FlowKind.RING_GROUP -> t.get(R.string.adm_pbx_flow_ringGroup, name)
            FlowKind.QUEUE -> t.get(R.string.adm_pbx_flow_queue, name)
            FlowKind.IVR -> t.get(R.string.adm_pbx_flow_ivr, name)
            FlowKind.BUSINESS_HOURS -> t.get(R.string.adm_pbx_flow_hours, name)
            FlowKind.VOICEMAIL -> if (node.ofDevice) t.get(R.string.adm_pbx_voicemail_of, name) else t.get(R.string.adm_pbx_voicemail_shared, name)
            FlowKind.RECORDING -> t.get(R.string.adm_pbx_flow_recording, name)
            FlowKind.HANGUP -> t.get(R.string.adm_pbx_flow_hangup)
            FlowKind.EXTERNAL -> t.get(R.string.adm_pbx_flow_external, node.number ?: "")
            FlowKind.MISSING -> t.get(R.string.adm_pbx_flow_missing)
            FlowKind.LOOP -> t.get(R.string.adm_pbx_flow_loop)
            FlowKind.NO_DESTINATION -> t.get(R.string.adm_pbx_flow_none)
            FlowKind.MORE -> t.get(R.string.adm_pbx_flow_more)
            FlowKind.UNKNOWN -> t.get(R.string.adm_pbx_flow_unknown)
        }
    }

    fun detail(node: FlowNode): String? = when (node.kind) {
        FlowKind.DEVICE, FlowKind.RING_GROUP, FlowKind.QUEUE, FlowKind.IVR, FlowKind.BUSINESS_HOURS ->
            node.extensionNumber?.let { t.get(R.string.adm_pbx_flow_internalNumber, it) }
        else -> null
    }

    /** The line on the rail before a step: why this step follows ("Buiten openingstijden"). */
    fun whenText(`when`: FlowWhen): String = when (`when`.kind) {
        FlowWhenKind.OPEN -> t.get(R.string.adm_pbx_when_open)
        FlowWhenKind.CLOSED -> t.get(R.string.adm_pbx_when_closed)
        FlowWhenKind.NO_ANSWER -> `when`.seconds?.let { t.get(R.string.adm_pbx_when_noAnswer_seconds, it) } ?: t.get(R.string.adm_pbx_when_noAnswer)
        FlowWhenKind.BUSY -> t.get(R.string.adm_pbx_when_busy)
        FlowWhenKind.TIMEOUT -> t.get(R.string.adm_pbx_when_timeout)
        FlowWhenKind.KEY -> t.get(R.string.adm_pbx_when_key, `when`.digit ?: "?")
        FlowWhenKind.NO_CHOICE -> t.get(R.string.adm_pbx_when_noChoice)
        FlowWhenKind.FORWARD_ALWAYS -> t.get(R.string.adm_pbx_when_forwardAlways)
        FlowWhenKind.UNKNOWN -> t.get(R.string.adm_pbx_when_unknown)
    }

    // MARK: Other

    fun strategy(strategy: RingStrategy): String = when (strategy) {
        RingStrategy.ALL, RingStrategy.UNKNOWN -> t.get(R.string.adm_pbx_strategy_all)
        RingStrategy.SEQUENCE -> t.get(R.string.adm_pbx_strategy_sequence)
        RingStrategy.ROUND -> t.get(R.string.adm_pbx_strategy_round)
    }

    fun strategyHint(strategy: RingStrategy): String = when (strategy) {
        RingStrategy.ALL, RingStrategy.UNKNOWN -> t.get(R.string.adm_pbx_strategy_all_hint)
        RingStrategy.SEQUENCE -> t.get(R.string.adm_pbx_strategy_sequence_hint)
        RingStrategy.ROUND -> t.get(R.string.adm_pbx_strategy_round_hint)
    }

    /** The API counts 1 = Monday ... 7 = Sunday. */
    fun weekday(day: Int, short: Boolean = false): String {
        val name = DayOfWeek.of(((day - 1).mod(7)) + 1).getDisplayName(if (short) TextStyle.SHORT else TextStyle.FULL, locale)

        return if (short) name.trimEnd('.') else name
    }

    /** "ma–vr 09:00–17:00": days with the same hours are joined when they follow each other. */
    fun hoursSummary(week: List<HoursWeekEntry>): String {
        if (week.isEmpty()) return t.get(R.string.adm_pbx_hours_never)

        val byDay = mutableMapOf<Int, String>()

        for (entry in week.sortedWith(compareBy({ it.day }, { it.from }))) {
            val slot = "${entry.from}–${entry.to}"
            byDay[entry.day] = byDay[entry.day]?.let { "$it, $slot" } ?: slot
        }

        val parts = mutableListOf<String>()
        var day = 1

        while (day <= 7) {
            val slot = byDay[day]

            if (slot == null) {
                day += 1
                continue
            }

            var last = day

            while (last < 7 && byDay[last + 1] == slot) last += 1

            val name = if (last == day) weekday(day, short = true) else "${weekday(day, short = true)}–${weekday(last, short = true)}"
            parts += "$name $slot"
            day = last + 1
        }

        return parts.joinToString(" · ")
    }

    companion object {
        /** The target types a picker offers, in this order. */
        val pickerOrder = listOf(TargetType.DEVICE, TargetType.RING_GROUP, TargetType.QUEUE, TargetType.IVR, TargetType.BUSINESS_HOURS, TargetType.VOICEMAIL, TargetType.RECORDING)

        /** "085 060 7848" for a national ten-digit number; anything else as it is. */
        fun formatNumber(number: String): String {
            val digits = number.filter { it.isDigit() }

            if (digits.length != 10 || digits != number) return number

            return "${digits.take(3)} ${digits.drop(3).take(3)} ${digits.drop(6)}"
        }

        /** The symbol (Material icon name) of a step: used by the flow drawing. */
        fun isProblem(kind: FlowKind): Boolean = kind == FlowKind.MISSING || kind == FlowKind.NO_DESTINATION || kind == FlowKind.LOOP
    }
}

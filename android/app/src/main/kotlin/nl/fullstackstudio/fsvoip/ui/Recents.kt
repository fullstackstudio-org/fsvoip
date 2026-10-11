// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.ui

import androidx.compose.runtime.Composable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import nl.fullstackstudio.fsvoip.AppModel
import nl.fullstackstudio.fsvoip.ui.media.HistoryScreen

/**
 * The "Recent" tab. The body is the history of the calls slice (`ui/media/HistoryScreen.kt`): the calls of the whole team from
 * the server, merged with the calls made on this phone. [RecentFormat] stays here: it formats the time of a local call.
 */
@Composable
fun RecentsScreen(model: AppModel) = HistoryScreen(model)

object RecentFormat {
    /** `0:42`, `5:12`, `1:02:03`. */
    fun duration(seconds: Long): String {
        val total = seconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val rest = total % 60

        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
    }

    fun `when`(instant: Instant, yesterday: String, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
        val date = instant.atZone(zone)

        return when (date.toLocalDate()) {
            today -> date.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
            today.minusDays(1) -> yesterday
            else -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        }
    }
}

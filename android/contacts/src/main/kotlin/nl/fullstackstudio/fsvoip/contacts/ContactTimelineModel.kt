// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.fullstackstudio.fsvoip.core.TimelineItem
import nl.fullstackstudio.fsvoip.core.TimelinePage

/** "Activiteit" on the contact screen: the pages of `GET /contacts/{id}/timeline`, newest first. */
class ContactTimelineModel(
    private val load: suspend (cursor: String?, limit: Int) -> TimelinePage,
    private val pageSize: Int = 20,
) {
    enum class Phase { IDLE, LOADING, FAILED }

    private val _items = MutableStateFlow<List<TimelineItem>>(emptyList())
    private val _phase = MutableStateFlow(Phase.IDLE)
    private val _nextCursor = MutableStateFlow<String?>(null)
    private val _hasLoaded = MutableStateFlow(false)

    val items: StateFlow<List<TimelineItem>> = _items.asStateFlow()
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    val hasLoaded: StateFlow<Boolean> = _hasLoaded.asStateFlow()

    val canLoadMore: Boolean get() = _nextCursor.value != null && _phase.value != Phase.LOADING

    suspend fun loadFirstPage() {
        if (_hasLoaded.value || _phase.value == Phase.LOADING) return

        loadPage(null)
    }

    suspend fun loadMore() {
        val cursor = _nextCursor.value ?: return

        if (_phase.value == Phase.LOADING) return

        loadPage(cursor)
    }

    private suspend fun loadPage(cursor: String?) {
        _phase.value = Phase.LOADING

        try {
            val page = load(cursor, pageSize)
            val seen = _items.value.map { it.id }.toMutableSet()

            // The cursor can overlap a little: an id is shown once.
            _items.value = _items.value + page.items.filter { seen.add(it.id) }
            _nextCursor.value = page.nextCursor
            _hasLoaded.value = true
            _phase.value = Phase.IDLE
        } catch (error: CancellationException) {
            _phase.value = Phase.IDLE
            throw error
        } catch (_: Exception) {
            _phase.value = Phase.FAILED
        }
    }
}

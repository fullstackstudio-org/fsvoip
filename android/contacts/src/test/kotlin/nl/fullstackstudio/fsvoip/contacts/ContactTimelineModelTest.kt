// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.contacts

import kotlinx.coroutines.test.runTest
import nl.fullstackstudio.fsvoip.core.ApiException
import nl.fullstackstudio.fsvoip.core.TimelineItem
import nl.fullstackstudio.fsvoip.core.TimelinePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactTimelineModelTest {
    private fun item(id: String) = TimelineItem(id, "order", "Bestelling $id", occurredAt = "2026-10-09T14:02:11.000Z")

    private fun model(api: FakeContactsApi) = ContactTimelineModel({ cursor, limit -> api.contactTimeline("c", cursor, limit) })

    @Test
    fun pagesAreAppendedWithoutDuplicatesAndTheCursorIsFollowed() = runTest {
        val api = FakeContactsApi()
        api.timelinePages += Result.success(TimelinePage(listOf(item("1"), item("2")), "c1"))
        api.timelinePages += Result.success(TimelinePage(listOf(item("2"), item("3")), null))
        val model = model(api)

        model.loadFirstPage()
        assertEquals(listOf("1", "2"), model.items.value.map { it.id })
        assertTrue(model.canLoadMore)

        model.loadMore()
        assertEquals("an overlapping line is shown once", listOf("1", "2", "3"), model.items.value.map { it.id })
        assertFalse(model.canLoadMore)
        assertEquals(listOf<String?>(null, "c1"), api.timelineCursors)
    }

    @Test
    fun theFirstPageLoadsOnlyOnce() = runTest {
        val api = FakeContactsApi()
        api.timelinePages += Result.success(TimelinePage(listOf(item("1")), null))
        val model = model(api)

        model.loadFirstPage()
        model.loadFirstPage()

        assertEquals(1, api.timelineCursors.size)
    }

    @Test
    fun aFailureKeepsWhatIsOnScreenAndCanBeRetried() = runTest {
        val api = FakeContactsApi()
        api.timelinePages += Result.success(TimelinePage(listOf(item("1")), "c1"))
        api.timelinePages += Result.failure(ApiException.Unavailable(true, null))
        api.timelinePages += Result.success(TimelinePage(listOf(item("2")), null))
        val model = model(api)

        model.loadFirstPage()
        model.loadMore()
        assertEquals(ContactTimelineModel.Phase.FAILED, model.phase.value)
        assertEquals(listOf("1"), model.items.value.map { it.id })

        model.loadMore()
        assertEquals(ContactTimelineModel.Phase.IDLE, model.phase.value)
        assertEquals(listOf("1", "2"), model.items.value.map { it.id })
    }

    @Test
    fun aFailedFirstLoadCanBeRetried() = runTest {
        val api = FakeContactsApi()
        api.timelinePages += Result.failure(ApiException.Transport("offline"))
        api.timelinePages += Result.success(TimelinePage(listOf(item("1")), null))
        val model = model(api)

        model.loadFirstPage()
        assertEquals(ContactTimelineModel.Phase.FAILED, model.phase.value)
        assertFalse(model.hasLoaded.value)

        model.loadFirstPage()
        assertEquals(listOf("1"), model.items.value.map { it.id })
        assertTrue(model.hasLoaded.value)
    }
}

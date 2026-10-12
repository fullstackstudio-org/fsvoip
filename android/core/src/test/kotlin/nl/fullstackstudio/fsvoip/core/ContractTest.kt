// SPDX-License-Identifier: AGPL-3.0-or-later
package nl.fullstackstudio.fsvoip.core

import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Decodes every file in `shared/fixtures` with the Kotlin models. A fixture without a test here fails
 * [everyFixtureIsCovered]: a new server field or message type cannot slip past the Android app unnoticed.
 */
class ContractTest {
    private val json = FsJson.default
    private val covered = mutableSetOf<String>()

    private fun fixture(name: String): String {
        covered += name
        return Fixtures.text(name)
    }

    @Test
    fun everyFixtureIsCovered() {
        val tests = ContractTest()
        tests.pairRequests()
        tests.pairResponses()
        tests.meResponses()
        tests.mePatch()
        tests.pushTokenRequests()
        tests.okResponse()
        tests.errors()
        tests.pushMessages()
        tests.fcmMessage()
        tests.apnsBodies()
        tests.rolesAndCapabilities()
        tests.selfExtension()
        tests.callsAndVoicemail()
        tests.parking()
        tests.invitationLink()
        tests.contacts()
        tests.contactLists()
        tests.contactLookup()
        tests.errorBodies()
        tests.partTwoFixturesAreValidJson()

        assertEquals("not decoded: ${Fixtures.all() - tests.covered}", emptySet<String>(), Fixtures.all() - tests.covered)
        assertEquals("not a fixture: ${tests.covered - Fixtures.all()}", emptySet<String>(), tests.covered - Fixtures.all())
    }

    @Test
    fun rolesAndCapabilities() {
        val admin = json.decodeFromString(MeResponse.serializer(), fixture("me-response-admin-v2.json"))
        assertEquals(AppRole.ADMIN, admin.role)
        assertTrue(admin.canManagePbx)
        assertTrue(admin.canEditOwnExtension)
        assertTrue(admin.canPark)
        assertEquals(CallsAccess.ALL, admin.capabilities!!.calls)
        assertEquals(VoicemailAccess.ALL, admin.capabilities!!.voicemail)
        assertEquals(SoundsAccess.MANAGE, admin.capabilities!!.sounds)
        assertTrue(admin.capabilities!!.callerChoice)
        assertTrue(admin.capabilities!!.contacts.delete)
        assertEquals(148, admin.contacts.total)
        assertEquals(PbxState.ACTIVE, admin.pbx!!.state)
        assertFalse(admin.pbx!!.readOnly)

        val user = json.decodeFromString(MeResponse.serializer(), fixture("me-response-user-v2.json"))
        assertEquals(AppRole.USER, user.role)
        assertFalse(user.canManagePbx)
        assertFalse(user.canPark)
        assertEquals(CallsAccess.TEAM, user.capabilities!!.calls)
        assertEquals(VoicemailAccess.OWN, user.capabilities!!.voicemail)
        assertFalse(user.capabilities!!.contacts.delete)

        // The older admin fixtures have fewer capabilities: the missing ones are the restrictive choice.
        val older = json.decodeFromString(MeResponse.serializer(), fixture("me-response-admin.json"))
        assertFalse(older.capabilities!!.selfExtension)
        assertFalse(older.capabilities!!.callerChoice)
        assertFalse(older.canEditOwnExtension)

        // The previous role fixture: a user without the newer capabilities.
        val plainUser = json.decodeFromString(MeResponse.serializer(), fixture("me-response-user.json"))
        assertEquals(AppRole.USER, plainUser.role)
        assertFalse(plainUser.canManagePbx)

        val frozen = json.decodeFromString(MeResponse.serializer(), fixture("me-response-admin-frozen.json"))
        assertEquals(PbxState.FROZEN, frozen.pbx!!.state)
        assertTrue(frozen.pbx!!.readOnly)

        // A server from before roles: no role means user, and the PBX section stays hidden.
        val none = json.decodeFromString(MeResponse.serializer(), fixture("me-response.json"))
        assertNull(none.role)
        assertEquals(AppRole.USER, none.effectiveRole)
        assertFalse(none.canManagePbx)
    }

    @Test
    fun unknownCapabilityValuesAreRestrictive() {
        val text = Fixtures.text("me-response-admin-v2.json")
            .replace("\"voicemail\": \"all\"", "\"voicemail\": \"future\"")
            .replace("\"calls\": \"all\"", "\"calls\": \"future\"")
            .replace("\"role\": \"admin\"", "\"role\": \"owner\"")
            .replace("\"state\": \"active\"", "\"state\": \"hibernating\"")
        val me = json.decodeFromString(MeResponse.serializer(), text)
        assertEquals(VoicemailAccess.NONE, me.capabilities!!.voicemail)
        assertEquals(CallsAccess.OWN, me.capabilities!!.calls)
        assertEquals(AppRole.USER, me.effectiveRole)
        assertEquals(PbxState.UNKNOWN, me.pbx!!.state)
    }

    @Test
    fun selfExtension() {
        val state = json.decodeFromString(SelfExtension.serializer(), fixture("self-extension.json"))
        assertEquals("102", state.extensionNumber)
        assertEquals(4, state.version)
        assertFalse(state.dnd)
        assertNull(state.forwardAlways)
        assertEquals(TargetType.VOICEMAIL, state.noAnswerTarget!!.type)
        assertEquals(2, state.numbers.size)
        assertEquals("0850607848", state.defaultNumber)
        assertEquals(5, state.targets.size)
        assertEquals(TargetType.EXTERNAL, state.targets.last().type)
        assertNull(state.targets.last().id)

        // Requests: only the keys that changed, version always.
        val patch = SelfExtensionPatch(version = 4, dnd = true, forwardAlways = Change.Set(PbxTarget.external("+31612345678")))
        assertEquals(Fixtures.json(fixtureName("self-extension-patch-request.json")), patch.toJson())
        val cleared = SelfExtensionPatch(version = 5, forwardAlways = Change.Clear).toJson()
        assertEquals(JsonNull, cleared["forwardAlways"])
        assertFalse(cleared.containsKey("dnd"))

        val response = json.decodeFromString(SelfExtensionPatchResponse.serializer(), fixture("self-extension-patch-response.json"))
        assertTrue(response.ok)
        assertEquals(5, response.state()!!.version)
        assertTrue(response.state()!!.dnd)
        assertEquals("+31612345678", response.state()!!.forwardAlways!!.number)

        // The fresh state is best effort: a broken one is no reason to lose the answer.
        val broken = json.decodeFromString(SelfExtensionPatchResponse.serializer(), """{"ok":true,"extension":{"nope":1}}""")
        assertTrue(broken.ok)
        assertNull(broken.state())
    }

    @Test
    fun callerChoiceHeader() {
        val state = json.decodeFromString(SelfExtension.serializer(), Fixtures.text("self-extension.json"))
        val on = AppCapabilities(callerChoice = true)

        assertEquals("0850607849", CallerChoice.header("0850607849", on, state.numbers))
        // The PBX does not understand the header: never send it.
        assertNull(CallerChoice.header("0850607849", AppCapabilities(callerChoice = false), state.numbers))
        assertNull(CallerChoice.header("0850607849", null, state.numbers))
        // A number the PBX does not list, or something that is not a plain number.
        assertNull(CallerChoice.header("0201234567", on, state.numbers))
        assertNull(CallerChoice.header("0850607849\r\nX-Evil: 1", on, state.numbers))
        assertNull(CallerChoice.header(null, on, state.numbers))
        assertFalse(CallerChoice.isValidNumber("0050607848"))
        assertFalse(CallerChoice.isValidNumber("085060784"))
    }

    @Test
    fun callsAndVoicemail() {
        val team = json.decodeFromString(PbxCallsPage.serializer(), fixture("calls-page-team.json"))
        assertEquals("2026-10", team.month)
        assertEquals(listOf("2026-10", "2026-09"), team.months)
        assertEquals(3, team.calls.size)
        assertEquals(PbxCallDirection.INBOUND, team.calls[0].direction)
        assertEquals("Pieter Jansen", team.calls[0].extensionName)
        assertEquals(PbxLineType.FIXED, team.calls[0].lineType)
        assertEquals(PbxCallOutcome.MISSED, team.calls[2].outcome)
        assertNull(team.calls[2].number)
        assertFalse(team.calls.any { it.hasRecording })
        assertEquals(Instant.parse("2026-10-09T08:02:11.000Z"), team.calls[0].startedAt)

        val own = json.decodeFromString(PbxCallsPage.serializer(), fixture("calls-page-user.json"))
        assertTrue(own.calls.isNotEmpty())
        val admin = json.decodeFromString(PbxCallsPage.serializer(), fixture("calls-page.json"))
        assertTrue(admin.calls.any { it.hasRecording })

        val page = json.decodeFromString(VoicemailPage.serializer(), fixture("voicemail-page.json"))
        assertEquals(2, page.boxes.size)
        assertTrue(page.boxes.first().shared)
        assertNull(page.boxId)
        assertEquals(2, page.messages.size)
        assertTrue(page.messages[0].isNew)
        assertEquals(29, page.messages[0].daysLeft)
        assertEquals("Bel mij even terug.", page.messages[1].transcription)
        assertTrue(page.available)

        val unavailable = json.decodeFromString(VoicemailPage.serializer(), fixture("voicemail-page-unavailable.json"))
        assertFalse(unavailable.available)
        assertTrue(unavailable.messages.isEmpty())
    }

    @Test
    fun parking() {
        assertEquals(Fixtures.json(fixtureName("park-request.json")), ParkRequest("3b9c1f0e2a7d4c58@192.0.2.10").toJson())
        assertFalse(ParkRequest("3b9c1f0e2a7d4c58@192.0.2.10").toString().contains("3b9c"))

        val parked = json.decodeFromString(ParkedCall.serializer(), fixture("park-response.json"))
        assertEquals(1, parked.slot)
        assertEquals("*5901", parked.retrieveNumber)
        assertTrue(parked.mine)
        assertEquals(Instant.parse("2026-10-09T08:15:30.000Z"), parked.parkedAt)
        assertEquals("Jan (balie)", parked.parkedBy!!.name)

        val page = json.decodeFromString(ParkedCallsPage.serializer(), fixture("parked-calls.json"))
        assertTrue(page.available)
        assertEquals(3, page.calls.size)
        assertNull(page.calls[1].callerNumber)
        assertNull(page.calls[1].parkedBy!!.deviceId)
        assertFalse(page.calls[1].mine)
        // Everything but the slot may be missing.
        assertNull(page.calls[2].retrieveNumber)
        assertNull(page.calls[2].parkedAt)
        assertNull(page.calls[2].parkedBy)

        val none = json.decodeFromString(ParkedCallsPage.serializer(), fixture("parked-calls-unavailable.json"))
        assertFalse(none.available)
        assertTrue(none.calls.isEmpty())
    }

    @Test
    fun contacts() {
        val page = json.decodeFromString(ContactsPage.serializer(), fixture("contacts-page.json"))
        assertEquals(2, page.contacts.size)
        assertNull(page.nextCursor)
        assertEquals("2026-10-09T08:15:30.123Z", page.serverTime)
        // Opaque: the milliseconds stay exactly as the server wrote them.
        assertEquals("2026-10-07T09:30:12.345Z", page.contacts[0].updatedAt)
        assertEquals(ContactPhoneLabel.WORK, page.contacts[0].phones[0].label)
        assertEquals("klant", page.contacts[0].tags.single())
        assertEquals(ContactPhoneLabel.MOBILE, page.contacts[1].phones[0].label)
        assertFalse(page.contacts[1].phones[1].isPrimary)

        val since = json.decodeFromString(ContactsPage.serializer(), fixture("contacts-since.json"))
        assertEquals(listOf("8b9c0d1e-2f3a-4b4c-8d5e-6f7a8b9c0d1e"), since.deleted)
        assertTrue(since.nextCursor!!.isNotEmpty())

        val detail = json.decodeFromString(ContactSingleResponse.serializer(), fixture("contact-detail.json")).contact
        assertEquals("Levert brood op maandag.", detail.notes)
        assertEquals(1, detail.listIds.size)

        val create = ContactCreate(
            name = "Bakkerij Smit",
            company = Change.Set("Bakkerij Smit"),
            email = Change.Set("info@bakkerijsmit.nl"),
            phones = listOf(ContactPhone("+31701234567", ContactPhoneLabel.WORK, isPrimary = true)),
            tags = listOf("klant"),
            listIds = listOf("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"),
        )
        assertEquals(Fixtures.json(fixtureName("contact-create-request.json")), create.toJson())

        val update = ContactUpdate(
            expectedUpdatedAt = "2026-10-07T09:30:12.345Z",
            firstName = Change.Clear,
            phones = listOf(
                ContactPhone("+31701234567", ContactPhoneLabel.WORK, isPrimary = true),
                ContactPhone("+31612345678", ContactPhoneLabel.MOBILE),
            ),
            notes = Change.Set("Nieuw nummer van de eigenaar."),
        )
        assertEquals(Fixtures.json(fixtureName("contact-update-request.json")), update.toJson())

        val updated = json.decodeFromString(ContactUpdateResponse.serializer(), fixture("contact-update-response.json"))
        assertTrue(updated.changed)
        assertEquals(2, updated.contact.phones.size)

        assertEquals(1, json.decodeFromString(ContactDeleteResponse.serializer(), fixture("contact-delete-response.json")).deleted)
    }

    @Test
    fun contactLists() {
        val lists = json.decodeFromString(ContactListsResponse.serializer(), fixture("contact-lists.json"))
        assertEquals(listOf(3, 1), lists.lists.map { it.version })
        assertEquals(0, lists.lists[1].contactCount)

        val snapshot = json.decodeFromString(ContactListSnapshot.serializer(), fixture("contact-list-snapshot.json"))
        assertEquals(3, snapshot.list.version)
        assertEquals(2, snapshot.contacts.size)
        assertNull(snapshot.contacts[1].company)
        assertNull(snapshot.nextCursor)

        assertEquals("\"3\"", ContactListETag.make(3))
    }

    @Test
    fun contactLookup() {
        val known = json.decodeFromString(ContactLookup.serializer(), fixture("contact-lookup.json"))
        assertEquals("Bakkerij Smit", known.contact!!.name)
        assertEquals(1, known.counters.openRequests)
        assertEquals(2, known.counters.openOrders)
        assertEquals(3, known.timeline.size)
        assertNull(known.timeline[2].summary)

        val unknown = json.decodeFromString(ContactLookup.serializer(), fixture("contact-lookup-unknown.json"))
        assertNull(unknown.contact)
        assertTrue(unknown.timeline.isEmpty())

        val first = json.decodeFromString(ContactTimelinePage.serializer(), fixture("contact-timeline.json"))
        assertEquals(2, first.items.size)
        assertNotNull(first.nextCursor)
        assertNull(json.decodeFromString(ContactTimelinePage.serializer(), fixture("contact-timeline-last.json")).nextCursor)
    }

    @Test
    fun errorBodies() {
        val names = Fixtures.all().filter { it.startsWith("error-") }
        assertTrue(names.size > 20)

        // Every error fixture decodes, whatever its shape.
        val bodies = names.associateWith { json.decodeFromString(ApiErrorBody.serializer(), fixture(it)) }

        assertEquals("admin", bodies.getValue("error-forbidden.json").required)
        assertEquals("suspended", bodies.getValue("error-forbidden-field.json").field)
        assertEquals(5, bodies.getValue("error-stale.json").version)
        assertEquals("resync", bodies.getValue("error-resync.json").code)
        assertEquals("limit_reached", bodies.getValue("error-conflict-limit.json").code)
        assertNotNull(bodies.getValue("error-in-use.json").places)
        assertNotNull(bodies.getValue("error-cost-not-accepted.json").cost)
        assertNotNull(bodies.getValue("error-stale-chain.json").chain)
        assertNotNull(bodies.getValue("error-blocked-destination.json").blocked)
    }

    @Test
    fun errorsBecomeTypedExceptions() {
        fun mapped(status: Int, name: String, headers: Map<String, String> = emptyMap()) =
            FsVoipApiClient.errorFor(response(status, Fixtures.text(name), headers))

        val forbidden = mapped(403, "error-forbidden.json") as ApiException.Forbidden
        assertEquals("admin", forbidden.required)
        assertEquals("suspended", (mapped(403, "error-forbidden-field.json") as ApiException.Forbidden).field)

        assertTrue(mapped(404, "error-call-not-found.json") is ApiException.CallNotFound)
        assertTrue(mapped(404, "error-not-found.json") is ApiException.NotFound)
        assertTrue(mapped(410, "error-gone.json") is ApiException.Gone)
        assertTrue(mapped(503, "error-park-uncertain.json") is ApiException.ParkUncertain)
        assertTrue(mapped(503, "error-unavailable-retryable.json") is ApiException.Unavailable)

        val stale = mapped(409, "error-stale.json") as ApiException.Conflict
        assertEquals("stale", stale.error)
        assertEquals(5, stale.version)
        assertEquals("limit_reached", (mapped(409, "error-conflict-limit.json") as ApiException.Conflict).code)
        assertEquals("no_free_slot", (mapped(409, "error-no-free-slot.json") as ApiException.Conflict).error)

        val resync = mapped(400, "error-resync.json") as ApiException.InvalidRequest
        assertEquals("resync", resync.code)
        assertEquals("phones", (mapped(400, "error-invalid-field.json") as ApiException.InvalidRequest).field)
    }

    @Test
    fun invitationLink() {
        val link = json.decodeFromString(AppPairingResponse.serializer(), fixture("app-pairing-response.json"))
        assertTrue(link.url.contains("/fsvoip/pair?t="))
        assertFalse(link.toString().contains("fss_vpair_"))
    }

    @Test
    fun parkFailures() {
        assertEquals(ParkFailure.Uncertain, ParkFailure.classify(ApiException.ParkUncertain()))
        assertEquals(ParkFailure.CallNotFound, ParkFailure.classify(ApiException.CallNotFound()))
        assertEquals(ParkFailure.NoFreeSlot, ParkFailure.classify(ApiException.Conflict("no_free_slot", null, null)))
        assertEquals(ParkFailure.Unavailable, ParkFailure.classify(ApiException.Conflict("park_unavailable", null, null)))
        assertEquals(ParkFailure.Unavailable, ParkFailure.classify(ApiException.Conflict("park_busy", null, null)))
        assertEquals(ParkFailure.ReadOnly, ParkFailure.classify(ApiException.Conflict("read_only", null, null)))
        assertEquals(ParkFailure.Revoked, ParkFailure.classify(ApiException.Unauthorized()))
        assertEquals(ParkFailure.Forbidden, ParkFailure.classify(ApiException.Forbidden("admin", null)))
        assertEquals(ParkFailure.Offline, ParkFailure.classify(ApiException.Transport("SocketTimeoutException")))

        // A park POST that may have left: look, never repeat.
        assertTrue(ParkFailure.outcomeIsUncertain(ApiException.Transport("SocketTimeoutException")))
        assertTrue(ParkFailure.outcomeIsUncertain(ApiException.ParkUncertain()))
        assertTrue(ParkFailure.outcomeIsUncertain(ApiException.UnexpectedStatus(502)))
        assertTrue(ParkFailure.outcomeIsUncertain(kotlinx.coroutines.CancellationException()))
        assertFalse(ParkFailure.outcomeIsUncertain(ApiException.Conflict("no_free_slot", null, null)))
        assertFalse(ParkFailure.outcomeIsUncertain(ApiException.CallNotFound()))
    }

    /**
     * Fixtures of the "Centrale" section (numbers, sounds, groups): typed in part 2; here they only have to be valid JSON.
     * Also the live-calls fixtures (`live-*`, `error-call-not-ringing|transferable`, `error-live-unavailable`): iOS has them typed, Android
     * follows (plan fss-features-2026-10, Task 4).
     */
    @Test
    fun partTwoFixturesAreValidJson() {
        val names = Fixtures.all().filter { name ->
            listOf("pbx-", "chain-step-", "number-", "numbers-", "sound", "live-", "error-call-not-ringing", "error-call-not-transferable", "error-live-unavailable").any { name.startsWith(it) }
        }
        assertTrue(names.size >= 20)

        for (name in names) {
            covered += name
            assertNotNull(name, Fixtures.json(name))
        }
    }

    @Test
    fun pairRequests() {
        for (name in listOf("pair-request.json", "pair-request-minimal.json")) {
            val request = json.decodeFromString(PairRequest.serializer(), fixture(name))
            // Round trip: what we send is exactly what the contract shows (absent fields stay absent).
            assertEquals(Fixtures.json(name), json.encodeToJsonElement(PairRequest.serializer(), request))
        }

        // What Android sends: platform android, FCM, no alert token.
        val android = PairRequest(
            token = "fss_vpair_FIXTUREpairingFIXTUREpairingFIXTUREpairingF",
            device = DeviceInfo(platform = ApiPlatform.ANDROID, installId = "a1b2c3d4e5f60718", pushToken = "t", pushKind = PushKind.FCM),
        )
        val encoded = json.encodeToJsonElement(PairRequest.serializer(), android).jsonObject["device"]!!.jsonObject
        assertEquals("android", encoded["platform"]!!.jsonPrimitive.content)
        assertEquals("fcm", encoded["pushKind"]!!.jsonPrimitive.content)
        assertFalse(encoded.containsKey("alertPushToken"))
        assertFalse(encoded.containsKey("pushEnv"))
    }

    @Test
    fun pairResponses() {
        val tcp = json.decodeFromString(PairResponse.serializer(), fixture("pair-response.json"))
        assertEquals("3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", tcp.account.id)
        assertEquals("102", tcp.sip.username)
        assertEquals("fixture-not-a-real-password", tcp.sip.password.reveal())
        assertEquals("voorbeeld-bouw.powervoip.nl", tcp.sip.domain)
        assertEquals("sip.powervoip.nl", tcp.sip.proxy)
        assertEquals(ServerTransport.TCP, tcp.sip.transport)
        assertEquals(5060, tcp.sip.port)
        assertTrue(tcp.sip.srv)
        assertEquals("a1b2c3d4e5f60718", tcp.device.installId)
        assertEquals(2, tcp.contacts.listsAvailable)
        assertTrue(tcp.contacts.hasInternal)
        assertFalse(tcp.toString().contains("fixture-not-a-real-password"))

        val tls = json.decodeFromString(PairResponse.serializer(), fixture("pair-response-tls.json"))
        assertEquals(ServerTransport.TLS, tls.sip.transport)
        assertEquals(5061, tls.sip.port)
        assertNull(tls.account.extensionNumber)
    }

    @Test
    fun meResponses() {
        val me = json.decodeFromString(MeResponse.serializer(), fixture("me-response.json"))
        assertEquals("Jan (balie)", me.account.labelOverride)
        assertEquals(3, me.internalContacts.size)
        assertEquals(InternalContact("100", "Receptie"), me.internalContacts.first())
        assertEquals(PushKind.APNS_VOIP, me.push.kind)
        assertEquals(PushEnvironment.SANDBOX, me.push.env)
        assertEquals(Instant.parse("2026-10-08T12:34:56.789Z"), me.serverTime)
        assertEquals(ServerTransport.TCP, me.sip!!.transport)

        val noSip = json.decodeFromString(MeResponse.serializer(), fixture("me-response-no-sip.json"))
        assertNull(noSip.sip)
        assertNull(noSip.push.kind)
        assertNull(noSip.account.labelOverride)
        assertTrue(noSip.internalContacts.isEmpty())
    }

    @Test
    fun meTolerantOfUnknownValues() {
        val text = Fixtures.text("me-response.json")
            .replace("\"apns_voip\"", "\"future_kind\"")
            .replace("\"serverTime\"", "\"newField\": {\"x\": 1}, \"serverTime\"")
        val me = json.decodeFromString(MeResponse.serializer(), text)
        assertNull(me.push.kind)
    }

    @Test
    fun mePatch() {
        assertEquals(Fixtures.json(fixtureName("me-patch-request.json")), MePatchRequest("Jan (balie)").toJson())
        assertEquals(Fixtures.json(fixtureName("me-patch-request-clear.json")), MePatchRequest(null).toJson())

        val response = json.decodeFromString(MePatchResponse.serializer(), fixture("me-patch-response.json"))
        assertEquals("Jan (balie)", response.label)
        assertEquals("Jan (balie)", response.labelOverride)
    }

    @Test
    fun pushTokenRequests() {
        val ios = PushTokenUpdate(
            pushKind = PushKind.APNS_VOIP,
            pushToken = "ab".repeat(32),
            pushEnv = PushEnvironment.SANDBOX,
            alertPushToken = "cd".repeat(32),
        )
        assertEquals(Fixtures.json(fixtureName("push-token-request.json")), ios.toJson())
        assertEquals(Fixtures.json(fixtureName("push-token-request-clear.json")), PushTokenUpdate.CLEAR.toJson())

        // Android: FCM, no alert token.
        val android = PushTokenUpdate(pushKind = PushKind.FCM, pushToken = "fcm-token").toJson()
        assertEquals("fcm", android["pushKind"]!!.jsonPrimitive.content)
        assertFalse(android.containsKey("alertPushToken"))
    }

    @Test
    fun okResponse() {
        assertTrue(json.decodeFromString(OkResponse.serializer(), fixture("ok-response.json")).ok)
    }

    @Test
    fun errors() {
        val invalid = json.decodeFromString(ApiErrorBody.serializer(), fixture("error-invalid-request.json"))
        assertEquals("invalid_request", invalid.error)
        assertEquals("device.platform moet ios of android zijn.", invalid.message)

        assertEquals("not_found", json.decodeFromString(ApiErrorBody.serializer(), fixture("error-not-found.json")).error)
        assertEquals("rate_limited", json.decodeFromString(ApiErrorBody.serializer(), fixture("error-rate-limited.json")).error)
        assertEquals("unauthorized", json.decodeFromString(ApiErrorBody.serializer(), fixture("error-unauthorized.json")).error)

        val unavailable = json.decodeFromString(ApiErrorBody.serializer(), fixture("error-unavailable-retryable.json"))
        assertEquals("not_ready", unavailable.error)
        assertEquals(true, unavailable.retryable)
    }

    @Test
    fun pushMessages() {
        val ring = PushMessage.decode(fixture("push-ring.json")) as PushMessage.Ring
        assertEquals("9d3f5c52-7b1e-4a0c-8e6d-2f4a6b8c0d1e", ring.ring.callRef)
        assertEquals("+31701234567", ring.ring.from.number)
        assertEquals("Bakkerij Smit", ring.ring.from.name)
        assertEquals(Instant.parse("2026-10-08T12:35:08Z"), ring.ring.expiresAt)

        val anonymous = PushMessage.decode(fixture("push-ring-anonymous.json")) as PushMessage.Ring
        assertNull(anonymous.ring.from.number)
        assertNull(anonymous.ring.from.name)

        val revoked = PushMessage.decode(fixture("push-revoked.json")) as PushMessage.Revoked
        assertEquals("Voorbeeld Bouw · Jan de Vries", revoked.revoked.accountLabel)

        val notice = PushMessage.decode(fixture("push-notice.json")) as PushMessage.Notice
        assertEquals("Nieuwe bestelling", notice.notice.title)
        assertTrue(notice.notice.href.startsWith("https://fullstackstudio.nl/portal/"))

        val refresh = PushMessage.decode(fixture("push-refresh.json")) as PushMessage.Refresh
        assertEquals("3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", refresh.refresh.accountId)
    }

    @Test
    fun fcmMessage() {
        val message = Fixtures.json(fixtureName("fcm-message.json")).jsonObject["message"]!!.jsonObject
        assertEquals("HIGH", message["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content)

        val data = message["data"]!!.jsonObject.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
        val decoded = PushMessage.decodeFcm(data) as PushMessage.Ring
        assertEquals("9d3f5c52-7b1e-4a0c-8e6d-2f4a6b8c0d1e", decoded.ring.callRef)
        assertEquals("Voorbeeld Bouw · Jan de Vries", decoded.ring.accountLabel)
    }

    @Test
    fun apnsBodies() {
        // iOS envelopes; the `fsvoip` object inside is the same one Android decodes.
        val voip = Fixtures.json(fixtureName("apns-voip-body.json")).jsonObject["fsvoip"]!!.jsonObject
        assertTrue(PushMessage.decode(voip) is PushMessage.Ring)

        val alert = Fixtures.json(fixtureName("apns-alert-body.json")).jsonObject["fsvoip"]!!.jsonObject
        assertTrue(PushMessage.decode(alert) is PushMessage.Revoked)

        val notice = Fixtures.json(fixtureName("apns-notice-body.json")).jsonObject["fsvoip"]!!.jsonObject
        assertTrue(PushMessage.decode(notice) is PushMessage.Notice)
    }

    @Test
    fun unusablePushes() {
        expect<PushMessageException.MissingPayload> { PushMessage.decodeFcm(emptyMap()) }
        expect<PushMessageException.MissingPayload> { PushMessage.decode("not json") }
        expect<PushMessageException.UnsupportedVersion> { PushMessage.decode("""{"v":2,"type":"ring"}""") }
        expect<PushMessageException.UnknownType> { PushMessage.decode("""{"v":1,"type":"party"}""") }
        expect<PushMessageException.Malformed> { PushMessage.decode("""{"v":1,"type":"ring","callRef":"x"}""") }
    }

    private fun fixtureName(name: String): String {
        covered += name
        return name
    }

    private inline fun <reified T : Throwable> expect(block: () -> Unit) {
        try {
            block()
            fail("Expected ${T::class.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) {
                throw AssertionError("Expected ${T::class.simpleName}, got $error")
            }
        }
    }
}

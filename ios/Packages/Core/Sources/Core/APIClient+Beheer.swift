// SPDX-License-Identifier: AGPL-3.0-or-later
//
// The routes added by the "beheer" release of the API: PBX management (admin pairings), calls, voicemail and recordings, and
// the customer's contacts. Which routes a pairing may use comes from `MeResponse.role`/`capabilities`; the server enforces every
// one of them (`APIError.forbidden`).

import Foundation

private struct NoBody: Encodable {}

// MARK: - Centrale (`/pbx/*`, admin only)

extension FSVoipAPIClient {
    public func pbxOverview() async throws -> PbxOverview {
        try await get("pbx/overview")
    }

    public func pbxDevices() async throws -> PbxDevicesResponse {
        try await get("pbx/devices")
    }

    @discardableResult
    public func updatePbxDevice(id: String, patch: PbxDevicePatch) async throws -> OkResponse {
        try await perform("PATCH", "pbx/devices/\(id)", body: patch, authenticated: true)
    }

    public func pbxRingGroups() async throws -> PbxRingGroupsResponse {
        try await get("pbx/ring-groups")
    }

    public func createPbxRingGroup(_ group: PbxRingGroupCreate) async throws -> PbxRingGroupCreated {
        try await perform("POST", "pbx/ring-groups", body: group, authenticated: true)
    }

    @discardableResult
    public func updatePbxRingGroup(id: String, patch: PbxRingGroupPatch) async throws -> OkResponse {
        try await perform("PATCH", "pbx/ring-groups/\(id)", body: patch, authenticated: true)
    }

    public func pbxHours() async throws -> PbxHoursResponse {
        try await get("pbx/hours")
    }

    @discardableResult
    public func updatePbxHours(id: String, patch: PbxHoursPatch) async throws -> OkResponse {
        try await perform("PATCH", "pbx/hours/\(id)", body: patch, authenticated: true)
    }

    @discardableResult
    public func setPbxNumberRouting(numberId: String, patch: PbxRoutingPatch) async throws -> OkResponse {
        try await perform("PATCH", "pbx/numbers/\(numberId)/routing", body: patch, authenticated: true)
    }
}

// MARK: - Calls, voicemail, recordings

extension FSVoipAPIClient {
    /// `GET /calls?month=YYYY-MM&locale=nl|en`. No month = the current one.
    public func calls(month: String? = nil, locale: String? = nil) async throws -> CallsPage {
        var query: [URLQueryItem] = []

        if let month { query.append(URLQueryItem(name: "month", value: month)) }
        if let locale { query.append(URLQueryItem(name: "locale", value: locale)) }

        return try await get("calls", query: query)
    }

    /// `GET /voicemail?box=<boxId>&locale=`. No box = the own box (`user`) or all boxes (admin).
    public func voicemail(box: String? = nil, locale: String? = nil) async throws -> VoicemailPage {
        var query: [URLQueryItem] = []

        if let box { query.append(URLQueryItem(name: "box", value: box)) }
        if let locale { query.append(URLQueryItem(name: "locale", value: locale)) }

        return try await get("voicemail", query: query)
    }

    /// `DELETE /voicemail/{boxId}/{ref}` (a `user` only in the own box).
    @discardableResult
    public func deleteVoicemail(boxId: String, ref: String) async throws -> OkResponse {
        try await perform("DELETE", "voicemail/\(boxId)/\(ref)", body: Optional<NoBody>.none, authenticated: true)
    }

    /// The audio of a call recording (admin only; `410` = past retention). See `MediaRequest`.
    public func recordingMedia(callId: String) throws -> MediaRequest {
        try mediaRequest("calls/\(callId)/recording")
    }

    /// The audio of a voicemail message. See `MediaRequest`.
    public func voicemailMedia(boxId: String, ref: String) throws -> MediaRequest {
        try mediaRequest("voicemail/\(boxId)/\(ref)/audio")
    }

    private func mediaRequest(_ path: String) throws -> MediaRequest {
        guard let token = deviceTokenValue else {
            throw APIError.missingDeviceToken
        }

        return MediaRequest(url: url(path), deviceToken: token, userAgent: userAgentValue)
    }
}

// MARK: - Contacts

extension FSVoipAPIClient {
    /// One page of `GET /contacts`.
    public func contactsPage(since: String? = nil, cursor: String? = nil, limit: Int? = nil) async throws -> ContactsPage {
        var query: [URLQueryItem] = []

        if let since { query.append(URLQueryItem(name: "since", value: since)) }
        if let cursor { query.append(URLQueryItem(name: "cursor", value: cursor)) }
        if let limit { query.append(URLQueryItem(name: "limit", value: String(limit))) }

        return try await get("contacts", query: query)
    }

    /// A whole sync run: follows `nextCursor` until the last page. Without `since` it is the complete set (`isFull`).
    /// `APIError.resync` (a `since` older than 90 days) is thrown as is: call again without `since`.
    ///
    /// The server gives every page of a run the same `serverTime`; the result carries it. The app stores it as its next `since`
    /// only after this method returned (all pages in), never halfway: a run that fails midway keeps the old `since`.
    public func syncContacts(since: String? = nil, pageSize: Int? = nil, maxPages: Int = 200) async throws -> ContactsSyncResult {
        var byId: [String: Contact] = [:]
        var order: [String] = []
        var deleted: [String] = []
        var seenDeleted = Set<String>()
        var cursor: String?
        var serverTime: String?

        for _ in 0 ..< maxPages {
            let page = try await contactsPage(since: since, cursor: cursor, limit: pageSize)
            serverTime = serverTime ?? page.serverTime

            for contact in page.contacts {
                if byId[contact.id] == nil { order.append(contact.id) }
                byId[contact.id] = contact
            }

            for id in page.deleted where seenDeleted.insert(id).inserted {
                deleted.append(id)
            }

            guard let next = page.nextCursor else {
                // The 120 s overlap can send a contact twice (the last version wins). If an id is both changed and deleted, the
                // contact exists (it was re-created): the tombstone is stale.
                let contacts = order.compactMap { byId[$0] }
                let alive = Set(contacts.map(\.id))

                return ContactsSyncResult(contacts: contacts, deleted: deleted.filter { !alive.contains($0) }, serverTime: serverTime ?? page.serverTime, isFull: since == nil)
            }

            cursor = next
        }

        throw APIError.decoding("contacts: too many pages")
    }

    public func contact(id: String) async throws -> ContactDetail {
        let response: ContactSingleResponse = try await get("contacts/\(id)")

        return response.contact
    }

    public func createContact(_ contact: ContactCreate) async throws -> ContactDetail {
        let response: ContactSingleResponse = try await perform("POST", "contacts", body: contact, authenticated: true)

        return response.contact
    }

    /// `409 stale` = `APIError.stale`: someone else changed it; reload and let the user merge.
    public func updateContact(id: String, _ update: ContactUpdate) async throws -> ContactUpdateResponse {
        try await perform("PATCH", "contacts/\(id)", body: update, authenticated: true)
    }

    /// Admin only (`APIError.forbidden` for a `user`). Returns how many were deleted.
    @discardableResult
    public func deleteContact(id: String) async throws -> Int {
        let response: ContactDeleteResponse = try await perform("DELETE", "contacts/\(id)", body: Optional<NoBody>.none, authenticated: true)

        return response.deleted
    }

    public func contactLists() async throws -> [ContactListInfo] {
        let response: ContactListsResponse = try await get("contact-lists")

        return response.lists
    }

    /// One page of the snapshot of a list. `etag` = the ETag you stored with the previous snapshot (`ContactListETag.make(version:)`):
    /// an unchanged list answers `.notModified` and nothing is downloaded. The server ignores `If-None-Match` on a `cursor` page, so it
    /// is not sent there.
    public func contactListSnapshot(listId: String, cursor: String? = nil, etag: String? = nil) async throws -> ContactListSnapshotOutcome {
        var headers: [String: String] = [:]

        if let etag, cursor == nil { headers["If-None-Match"] = etag }

        let query = cursor.map { [URLQueryItem(name: "cursor", value: $0)] } ?? []
        let (data, response) = try await send("GET", "contact-lists/\(listId)/contacts", query: query, body: Optional<NoBody>.none, headers: headers, authenticated: true, acceptNotModified: true)
        let responseETag = response.value(forHTTPHeaderField: "ETag")

        if response.statusCode == 304 {
            return .notModified(etag: responseETag ?? etag)
        }

        do {
            return .snapshot(try FSVoipJSON.decoder().decode(ContactListSnapshot.self, from: data), etag: responseETag)
        } catch {
            throw APIError.decoding("contact-lists: \(error)")
        }
    }
}

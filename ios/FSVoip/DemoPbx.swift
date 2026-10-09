// SPDX-License-Identifier: AGPL-3.0-or-later
//
// DEBUG builds only: the "Centrale" section in the demo mode (`-FSVoipDemo YES -FSVoipDemoScreen pbx`). The data is a copy of
// `shared/fixtures/me-response-admin.json`, `me-response-user.json`, `pbx-overview.json`, `pbx-devices.json`,
// `pbx-ring-groups.json` and `pbx-hours.json`; changes are kept in memory and shown as "Wordt bijgewerkt" for a few seconds.

#if DEBUG
import Core
import Foundation

/// A Face ID that always says yes.
struct DemoLocalAuth: LocalAuthenticating {
    func availability() -> LocalAuthAvailability { .available }
    func evaluate(reason: String) async -> LocalAuthResult { .success }
}

/// Pretends to be the `/pbx/*` routes. The first account is an admin, every other one a plain user.
final class DemoPbxService: PbxServicing, @unchecked Sendable {
    private let lock = NSLock()
    private var overviewValue: PbxOverview
    private var devicesValue: PbxDevicesResponse
    private var ringGroupsValue: PbxRingGroupsResponse
    private var hoursValue: PbxHoursResponse
    /// The chains as JSON objects (a step edits them like the server would, then they are decoded again).
    private var chainObjects: [String: [String: Any]]
    private var numbersObject: [String: Any]
    private let adminId: String
    private var pendingUntil: Date?

    init(adminAccountId: String) {
        adminId = adminAccountId
        overviewValue = Self.decode(Self.overviewJSON)
        devicesValue = Self.decode(Self.devicesJSON)
        ringGroupsValue = Self.decode(Self.ringGroupsJSON)
        hoursValue = Self.decode(Self.hoursJSON)
        chainObjects = [:]
        for json in [Self.simpleChainJSON, Self.advancedChainJSON] {
            let object = try! JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
            chainObjects[object["id"] as! String] = object
        }
        numbersObject = try! JSONSerialization.jsonObject(with: Data(Self.numbersJSON.utf8)) as! [String: Any]
        // What the fixtures show as "being updated" settles after a few seconds, so the demo shows the polling.
        pendingUntil = Date().addingTimeInterval(6)
    }

    private static func decode<T: Decodable>(_ json: String) -> T {
        try! FSVoipJSON.decoder().decode(T.self, from: Data(json.utf8))
    }

    // MARK: PbxServicing

    /// What `GET /me` says in the demo: the example account is the admin of the demo PBX, the others are plain users.
    static func meResponse(isAdmin: Bool) -> MeResponse {
        isAdmin ? decode(meAdminJSON) : decode(meUserJSON)
    }

    func overview(for account: StoredAccount) async throws -> PbxOverview {
        read { overviewValue }
    }

    func devices(for account: StoredAccount) async throws -> PbxDevicesResponse {
        read { devicesValue }
    }

    func ringGroups(for account: StoredAccount) async throws -> PbxRingGroupsResponse {
        read { ringGroupsValue }
    }

    func hours(for account: StoredAccount) async throws -> PbxHoursResponse {
        read { hoursValue }
    }

    func updateDevice(for account: StoredAccount, id: String, patch: PbxDevicePatch) async throws {
        try write {
            guard let index = devicesValue.devices.firstIndex(where: { $0.id == id }) else { throw APIError.notFound }
            guard devicesValue.devices[index].version == patch.version else { throw APIError.stale(version: devicesValue.devices[index].version) }

            var device = devicesValue.devices[index]
            if let value = patch.voicemailEnabled { device.voicemailEnabled = value }
            if let value = patch.dnd { device.dnd = value }
            if let value = patch.noAnswerSeconds { device.noAnswerSeconds = value }
            Self.apply(patch.noAnswerTarget, to: &device.noAnswerTarget)
            Self.apply(patch.busyTarget, to: &device.busyTarget)
            Self.apply(patch.notRegisteredTarget, to: &device.notRegisteredTarget)
            Self.apply(patch.forwardAlways, to: &device.forwardAlways)
            if let value = patch.followMe { device.followMe = value }
            device.version += 1
            device.sync = .pending
            devicesValue.devices[index] = device
        }
    }

    func createRingGroup(for account: StoredAccount, _ group: PbxRingGroupCreate) async throws -> PbxRingGroupCreated {
        return try write {
            let id = UUID().uuidString.lowercased()
            let json = """
            {"id":"\(id)","name":\(Self.quoted(group.name)),"version":1,"extension":"2\(10 + ringGroupsValue.ringGroups.count)","strategy":"\(group.strategy?.rawValue ?? "all")","members":[],"sync":"pending"}
            """
            var created: PbxRingGroup = Self.decode(json)
            created.members = group.members ?? []
            Self.apply(group.timeoutTarget, to: &created.timeoutTarget)
            ringGroupsValue.ringGroups.append(created)
            overviewValue.ringGroupCount += 1

            return Self.decode("{\"ringGroupId\":\"\(id)\"}")
        }
    }

    func updateRingGroup(for account: StoredAccount, id: String, patch: PbxRingGroupPatch) async throws {
        try write {
            guard let index = ringGroupsValue.ringGroups.firstIndex(where: { $0.id == id }) else { throw APIError.notFound }
            guard ringGroupsValue.ringGroups[index].version == patch.version else { throw APIError.stale(version: ringGroupsValue.ringGroups[index].version) }

            var group = ringGroupsValue.ringGroups[index]
            if let value = patch.name { group.name = value }
            if let value = patch.strategy { group.strategy = value }
            if let value = patch.members { group.members = value }
            Self.apply(patch.timeoutTarget, to: &group.timeoutTarget)
            group.version += 1
            group.sync = .pending
            ringGroupsValue.ringGroups[index] = group
        }
    }

    func updateHours(for account: StoredAccount, id: String, patch: PbxHoursPatch) async throws {
        try write {
            guard let index = hoursValue.hours.firstIndex(where: { $0.id == id }) else { throw APIError.notFound }
            guard hoursValue.hours[index].version == patch.version else { throw APIError.stale(version: hoursValue.hours[index].version) }

            var hours = hoursValue.hours[index]
            if let value = patch.week { hours.week = value }
            Self.apply(patch.openTarget, to: &hours.openTarget)
            Self.apply(patch.closedTarget, to: &hours.closedTarget)

            if let holidays = patch.holidays {
                hours.holidays = holidays.map { input in
                    switch input {
                    case let .rule(rule):
                        let label = hoursValue.holidayRules.first { $0.rule == rule }?.label ?? rule
                        return Self.decode("{\"name\":\(Self.quoted(label)),\"rule\":\(Self.quoted(rule))}")
                    case let .custom(name, date):
                        return Self.decode("{\"name\":\(Self.quoted(name)),\"date\":\(Self.quoted(date))}")
                    }
                }
            }

            hours.version += 1
            hours.sync = .pending
            hoursValue.hours[index] = hours
        }
    }

    func setNumberRouting(for account: StoredAccount, numberId: String, patch: PbxRoutingPatch) async throws {
        try write {
            guard let index = overviewValue.numbers.firstIndex(where: { $0.id == numberId }) else { throw APIError.notFound }
            guard overviewValue.numbers[index].version == patch.version else { throw APIError.stale(version: overviewValue.numbers[index].version) }

            overviewValue.numbers[index].routing = patch.target
            overviewValue.numbers[index].version += 1
            overviewValue.numbers[index].sync = .pending
        }
    }

    // MARK: Numbers as chains

    func numbers(for account: StoredAccount) async throws -> PbxNumbersPage {
        try read { try Self.decodeObject(numbersObject) }
    }

    func numberChain(for account: StoredAccount, numberId: String) async throws -> NumberChain {
        try read {
            guard let object = chainObjects[numberId] else { throw APIError.notFound }
            return try Self.decodeObject(object)
        }
    }

    func saveChainStep<Step: NumberChainStepRequest>(for account: StoredAccount, numberId: String, step: Step) async throws -> NumberChain {
        let body = try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(step)) as! [String: Any]

        return try write {
            guard var chain = chainObjects[numberId] else { throw APIError.notFound }
            if let expected = body["numberVersion"] as? Int, expected != chain["version"] as? Int {
                throw APIError.staleChain(try Self.decodeObject(chain))
            }
            guard chain["mode"] as? String == "simple" || step.step == .name else { throw APIError.advanced }

            Self.apply(step.step, body: body, to: &chain)
            return try commit(chain, numberId: numberId)
        }
    }

    func setNumberRecording(for account: StoredAccount, numberId: String, patch: NumberRecordingPatch) async throws -> NumberChain {
        let body = try JSONSerialization.jsonObject(with: FSVoipJSON.encoder().encode(patch)) as! [String: Any]

        return try write {
            guard var chain = chainObjects[numberId] else { throw APIError.notFound }
            var recording = chain["recording"] as? [String: Any] ?? [:]
            let enabled = body["enabled"] as? Bool ?? false

            if enabled, recording["billingActive"] as? Bool != true, body["costAccepted"] as? Bool != true {
                throw APIError.costNotAccepted(cost: RecordingCost(priceE4: 20000, vatIncluded: false))
            }

            recording["enabled"] = enabled
            if enabled { recording["billingActive"] = true }
            if let sound = body["announcementSoundId"] { recording["announcementSoundId"] = sound }
            chain["recording"] = recording
            return try commit(chain, numberId: numberId)
        }
    }

    private func commit(_ chain: [String: Any], numberId: String) throws -> NumberChain {
        var chain = chain
        chain["version"] = (chain["version"] as? Int ?? 1) + 1
        chain["sync"] = "pending"
        chainObjects[numberId] = chain

        if var entries = numbersObject["numbers"] as? [[String: Any]], let index = entries.firstIndex(where: { $0["id"] as? String == numberId }) {
            var entry = entries[index]
            entry["name"] = chain["name"] ?? NSNull()
            entry["version"] = chain["version"]
            entry["sync"] = "pending"
            let forwarding = (chain["forwarding"] as? [String: Any])?["kind"] as? String ?? "advanced"
            entry["summary"] = [
                "hours": chain["hours"] is NSNull || chain["hours"] == nil ? "Uit" : "Openingstijden",
                "welcome": !(chain["welcome"] is NSNull || chain["welcome"] == nil),
                "forwarding": forwarding,
                "recording": (chain["recording"] as? [String: Any])?["enabled"] as? Bool ?? false,
            ] as [String: Any]
            entries[index] = entry
            numbersObject["numbers"] = entries
        }

        return try Self.decodeObject(chain)
    }

    /// What the server does with a step, roughly: enough for the demo to show the result.
    private static func apply(_ step: ChainStep, body: [String: Any], to chain: inout [String: Any]) {
        func merge(_ fields: [String], into object: inout [String: Any]) {
            for field in fields where body[field] != nil { object[field] = body[field] }
            object["version"] = (object["version"] as? Int ?? 0) + 1
        }

        switch step {
        case .name:
            chain["name"] = body["name"] ?? NSNull()
        case .welcome:
            if body["enabled"] as? Bool == true {
                var welcome = chain["welcome"] as? [String: Any] ?? ["menuId": UUID().uuidString.lowercased(), "version": 0, "sharedWith": []]
                merge(["soundId"], into: &welcome)
                chain["welcome"] = welcome
            } else {
                chain["welcome"] = NSNull()
            }
        case .hours, .closed:
            if step == .hours, body["enabled"] as? Bool == false {
                chain["hours"] = NSNull()
                return
            }
            var hours = chain["hours"] as? [String: Any] ?? [
                "id": UUID().uuidString.lowercased(), "version": 0, "week": [], "sharedWith": [],
                "holidays": ["national": true, "rules": [], "dates": []], "closed": ["mode": "hangup"], "holiday": NSNull(),
            ]
            if let holidays = body["holidays"] as? [String: Any] {
                var current = hours["holidays"] as? [String: Any] ?? [:]
                for (key, value) in holidays { current[key] = value }
                hours["holidays"] = current
            }
            merge(["week", "closed", "holiday"], into: &hours)
            chain["hours"] = hours
        case .forwarding:
            let kind = body["kind"] as? String ?? "standard"
            var forwarding = chain["forwarding"] as? [String: Any] ?? [:]
            if forwarding["kind"] as? String != kind {
                forwarding = kind == "standard"
                    ? ["kind": "standard", "groupId": UUID().uuidString.lowercased(), "version": 0, "strategy": "all", "members": [], "unanswered": ["mode": "hangup"], "sharedWith": []]
                    : ["kind": "menu", "menuId": UUID().uuidString.lowercased(), "version": 0, "greetingSoundId": NSNull(), "repeats": 1, "timeoutSeconds": 5, "defaultKey": NSNull(), "noChoice": ["mode": "hangup"], "keys": [], "sharedWith": []]
            }
            merge(["strategy", "members", "unanswered", "greetingSoundId", "repeats", "timeoutSeconds", "defaultKey", "noChoice", "keys"], into: &forwarding)
            if var keys = forwarding["keys"] as? [[String: Any]] {
                for index in keys.indices where keys[index]["editable"] == nil { keys[index]["editable"] = true }
                forwarding["keys"] = keys
            }
            chain["forwarding"] = forwarding
        }
    }

    private static func decodeObject<T: Decodable>(_ object: [String: Any]) throws -> T {
        try FSVoipJSON.decoder().decode(T.self, from: JSONSerialization.data(withJSONObject: object))
    }

    // MARK: Plumbing

    private func read<T>(_ value: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }

        if let pendingUntil, Date() > pendingUntil {
            settle()
        }

        return try value()
    }

    @discardableResult
    private func write<T>(_ change: () throws -> T) throws -> T {
        lock.lock()
        defer { lock.unlock() }

        let result = try change()
        pendingUntil = Date().addingTimeInterval(8)

        return result
    }

    private func settle() {
        pendingUntil = nil
        for index in devicesValue.devices.indices { devicesValue.devices[index].sync = .ok }
        for index in ringGroupsValue.ringGroups.indices { ringGroupsValue.ringGroups[index].sync = .ok }
        for index in hoursValue.hours.indices { hoursValue.hours[index].sync = .ok }
        for index in overviewValue.numbers.indices { overviewValue.numbers[index].sync = .ok }
        for id in chainObjects.keys { chainObjects[id]?["sync"] = "ok" }
        if var entries = numbersObject["numbers"] as? [[String: Any]] {
            for index in entries.indices { entries[index]["sync"] = "ok" }
            numbersObject["numbers"] = entries
        }
    }

    private static func apply(_ change: Change<PbxTarget>, to target: inout PbxTarget?) {
        switch change {
        case .keep: break
        case .clear: target = nil
        case let .set(value): target = value
        }
    }

    private static func quoted(_ text: String) -> String {
        String(data: try! JSONEncoder().encode(text), encoding: .utf8)!
    }

    private static let meAdminJSON = #"""
{
 "account": {
  "id": "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b",
  "label": "Jan (balie)",
  "labelOverride": "Jan (balie)",
  "pbxName": "Voorbeeld Bouw",
  "extensionName": "Jan de Vries",
  "extensionNumber": "102",
  "customerName": "Voorbeeld Bouw B.V."
 },
 "sip": {
  "domain": "voorbeeld-bouw.powervoip.nl",
  "proxy": "sip.powervoip.nl",
  "port": 5060,
  "transport": "tcp",
  "srv": true
 },
 "internal": [
  {
   "number": "100",
   "name": "Receptie"
  },
  {
   "number": "101",
   "name": "Pieter Jansen"
  },
  {
   "number": "103",
   "name": "Werkplaats"
  }
 ],
 "contacts": {
  "internal": true,
  "listsAvailable": 2,
  "total": 148
 },
 "push": {
  "registered": true,
  "kind": "apns_voip",
  "env": "sandbox",
  "invalid": false
 },
 "serverTime": "2026-10-08T12:34:56.789Z",
 "role": "admin",
 "capabilities": {
  "pbxManage": true,
  "recordings": true,
  "voicemail": "all",
  "calls": "all",
  "selfExtension": true,
  "callerChoice": true,
  "park": true,
  "contacts": {
   "read": true,
   "write": true,
   "delete": true
  }
 },
 "pbx": {
  "name": "Voorbeeld Bouw",
  "state": "active",
  "readOnly": false
 }
}
"""#

    private static let meUserJSON = #"""
{
 "account": {
  "id": "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b",
  "label": "Jan (balie)",
  "labelOverride": "Jan (balie)",
  "pbxName": "Voorbeeld Bouw",
  "extensionName": "Jan de Vries",
  "extensionNumber": "102",
  "customerName": "Voorbeeld Bouw B.V."
 },
 "sip": {
  "domain": "voorbeeld-bouw.powervoip.nl",
  "proxy": "sip.powervoip.nl",
  "port": 5060,
  "transport": "tcp",
  "srv": true
 },
 "internal": [
  {
   "number": "100",
   "name": "Receptie"
  },
  {
   "number": "101",
   "name": "Pieter Jansen"
  },
  {
   "number": "103",
   "name": "Werkplaats"
  }
 ],
 "contacts": {
  "internal": true,
  "listsAvailable": 2,
  "total": 148
 },
 "push": {
  "registered": true,
  "kind": "apns_voip",
  "env": "sandbox",
  "invalid": false
 },
 "serverTime": "2026-10-08T12:34:56.789Z",
 "role": "user",
 "capabilities": {
  "pbxManage": false,
  "recordings": false,
  "voicemail": "own",
  "calls": "team",
  "selfExtension": true,
  "callerChoice": true,
  "park": true,
  "contacts": {
   "read": true,
   "write": true,
   "delete": false
  }
 },
 "pbx": {
  "name": "Voorbeeld Bouw",
  "state": "active",
  "readOnly": false
 }
}
"""#

    private static let overviewJSON = #"""
{
 "pbx": {
  "name": "Voorbeeld Bouw",
  "state": "active",
  "readOnly": false
 },
 "numbers": [
  {
   "id": "d4e5f6a7-b8c9-4d0e-a1f2-b3c4d5e6f7a8",
   "number": "0850607848",
   "routing": {
    "type": "business_hours",
    "id": "c3b2a1f0-e9d8-4c7b-a6f5-e4d3c2b1a0f9"
   },
   "blockListId": null,
   "isDefault": true,
   "version": 3,
   "sync": "ok",
   "recordCalls": false,
   "recordAnnouncementSoundId": null,
   "recordBillingActive": false,
   "flow": {
    "kind": "business_hours",
    "id": "c3b2a1f0-e9d8-4c7b-a6f5-e4d3c2b1a0f9",
    "name": "Kantoortijden",
    "extension": "500",
    "ofDevice": false,
    "number": null,
    "dnd": false,
    "branches": [
     {
      "when": {
       "kind": "open"
      },
      "node": {
       "kind": "ring_group",
       "id": "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
       "name": "Iedereen",
       "extension": "200",
       "ofDevice": false,
       "number": null,
       "dnd": false,
       "branches": [
        {
         "when": {
          "kind": "no_answer",
          "seconds": 25
         },
         "node": {
          "kind": "voicemail",
          "id": "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f",
          "name": "Algemeen",
          "extension": null,
          "ofDevice": false,
          "number": null,
          "dnd": false,
          "branches": []
         }
        }
       ]
      }
     },
     {
      "when": {
       "kind": "closed"
      },
      "node": {
       "kind": "voicemail",
       "id": "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f",
       "name": "Algemeen",
       "extension": null,
       "ofDevice": false,
       "number": null,
       "dnd": false,
       "branches": []
      }
     }
    ]
   }
  },
  {
   "id": "e5f6a7b8-c9d0-4e1f-b2a3-c4d5e6f7a8b9",
   "number": "0850607849",
   "routing": null,
   "blockListId": null,
   "isDefault": false,
   "version": 1,
   "sync": "pending",
   "recordCalls": false,
   "recordAnnouncementSoundId": null,
   "recordBillingActive": false,
   "flow": {
    "kind": "none",
    "id": null,
    "name": null,
    "extension": null,
    "ofDevice": false,
    "number": null,
    "dnd": false,
    "branches": []
   }
  }
 ],
 "entryFlow": null,
 "deviceCount": 3,
 "connectedCount": 2,
 "ringGroupCount": 1,
 "outboundBlocked": false
}
"""#

    private static let devicesJSON = #"""
{
 "devices": [
  {
   "id": "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
   "name": "Receptie",
   "version": 2,
   "musicSoundId": null,
   "voicemailGreetingSoundId": null,
   "extension": "100",
   "email": null,
   "voicemailEnabled": true,
   "voicemailToEmail": false,
   "voicemailAttachFile": false,
   "outboundNumberId": null,
   "dnd": false,
   "suspended": false,
   "recordCalls": false,
   "noAnswerSeconds": 25,
   "noAnswerTarget": null,
   "busyTarget": null,
   "notRegisteredTarget": null,
   "forwardAlways": null,
   "followMe": [],
   "sync": "ok",
   "registration": {
    "connected": true,
    "count": 1,
    "agent": "Yealink T54W"
   }
  },
  {
   "id": "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
   "name": "Pieter Jansen",
   "version": 5,
   "musicSoundId": null,
   "voicemailGreetingSoundId": null,
   "extension": "101",
   "email": "pieter@voorbeeld-bouw.nl",
   "voicemailEnabled": true,
   "voicemailToEmail": true,
   "voicemailAttachFile": false,
   "outboundNumberId": null,
   "dnd": true,
   "suspended": false,
   "recordCalls": false,
   "noAnswerSeconds": 25,
   "noAnswerTarget": null,
   "busyTarget": null,
   "notRegisteredTarget": null,
   "forwardAlways": {
    "type": "external",
    "number": "+31612345678"
   },
   "followMe": [],
   "sync": "ok",
   "registration": {
    "connected": false,
    "count": 0,
    "agent": null
   }
  },
  {
   "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "name": "Jan de Vries",
   "version": 4,
   "musicSoundId": null,
   "voicemailGreetingSoundId": null,
   "extension": "102",
   "email": null,
   "voicemailEnabled": true,
   "voicemailToEmail": false,
   "voicemailAttachFile": false,
   "outboundNumberId": null,
   "dnd": false,
   "suspended": false,
   "recordCalls": false,
   "noAnswerSeconds": 25,
   "noAnswerTarget": {
    "type": "voicemail",
    "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57"
   },
   "busyTarget": null,
   "notRegisteredTarget": null,
   "forwardAlways": null,
   "followMe": [
    {
     "target": {
      "type": "external",
      "number": "+31687654321"
     },
     "delaySeconds": 10,
     "timeoutSeconds": 20
    }
   ],
   "sync": "pending",
   "registration": null
  }
 ],
 "targets": [
  {
   "value": "extension:5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
   "type": "extension",
   "id": "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
   "name": "Receptie",
   "extension": "100"
  },
  {
   "value": "extension:1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
   "type": "extension",
   "id": "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
   "name": "Pieter Jansen",
   "extension": "101"
  },
  {
   "value": "extension:7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "type": "extension",
   "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "name": "Jan de Vries",
   "extension": "102"
  },
  {
   "value": "ring_group:9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
   "type": "ring_group",
   "id": "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
   "name": "Iedereen",
   "extension": "200"
  },
  {
   "value": "voicemail:2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f",
   "type": "voicemail",
   "id": "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f",
   "name": "Algemeen",
   "extension": "900"
  },
  {
   "value": "voicemail:7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "type": "voicemail",
   "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "name": "Jan de Vries",
   "extension": "102",
   "ofDevice": true
  },
  {
   "value": "external",
   "type": "external",
   "id": null,
   "name": "Extern nummer",
   "extension": null
  }
 ],
 "numbers": [
  {
   "id": "d4e5f6a7-b8c9-4d0e-a1f2-b3c4d5e6f7a8",
   "number": "0850607848"
  },
  {
   "id": "e5f6a7b8-c9d0-4e1f-b2a3-c4d5e6f7a8b9",
   "number": "0850607849"
  }
 ]
}
"""#

    private static let ringGroupsJSON = #"""
{
 "ringGroups": [
  {
   "id": "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
   "name": "Iedereen",
   "version": 2,
   "extension": "200",
   "strategy": "all",
   "members": [
    {
     "extensionId": "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
     "delaySeconds": 0,
     "timeoutSeconds": 25
    },
    {
     "extensionId": "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
     "delaySeconds": 0,
     "timeoutSeconds": 25
    }
   ],
   "timeoutTarget": {
    "type": "voicemail",
    "id": "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"
   },
   "sync": "ok"
  }
 ],
 "devices": [
  {
   "id": "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
   "name": "Receptie",
   "extension": "100"
  },
  {
   "id": "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
   "name": "Pieter Jansen",
   "extension": "101"
  },
  {
   "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "name": "Jan de Vries",
   "extension": "102"
  }
 ],
 "targets": [
  {
   "value": "extension:5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
   "type": "extension",
   "id": "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
   "name": "Receptie",
   "extension": "100"
  },
  {
   "value": "extension:1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
   "type": "extension",
   "id": "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
   "name": "Pieter Jansen",
   "extension": "101"
  },
  {
   "value": "extension:7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "type": "extension",
   "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "name": "Jan de Vries",
   "extension": "102"
  },
  {
   "value": "ring_group:9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
   "type": "ring_group",
   "id": "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
   "name": "Iedereen",
   "extension": "200"
  },
  {
   "value": "voicemail:2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f",
   "type": "voicemail",
   "id": "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f",
   "name": "Algemeen",
   "extension": "900"
  },
  {
   "value": "voicemail:7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "type": "voicemail",
   "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "name": "Jan de Vries",
   "extension": "102",
   "ofDevice": true
  },
  {
   "value": "external",
   "type": "external",
   "id": null,
   "name": "Extern nummer",
   "extension": null
  }
 ]
}
"""#

    private static let hoursJSON = #"""
{
 "hours": [
  {
   "id": "c3b2a1f0-e9d8-4c7b-a6f5-e4d3c2b1a0f9",
   "name": "Kantoortijden",
   "extension": "500",
   "timezone": "Europe/Amsterdam",
   "week": [
    {
     "day": 1,
     "from": "09:00",
     "to": "17:00"
    },
    {
     "day": 2,
     "from": "09:00",
     "to": "17:00"
    },
    {
     "day": 3,
     "from": "09:00",
     "to": "17:00"
    },
    {
     "day": 4,
     "from": "09:00",
     "to": "17:00"
    },
    {
     "day": 5,
     "from": "09:00",
     "to": "17:00"
    }
   ],
   "openTarget": {
    "type": "ring_group",
    "id": "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4"
   },
   "closedTarget": {
    "type": "voicemail",
    "id": "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"
   },
   "holidays": [
    {
     "name": "Eerste kerstdag",
     "date": null,
     "rule": "christmas_day",
     "target": null
    },
    {
     "name": "Bouwvak",
     "date": "2026-08-03",
     "rule": null,
     "target": {
      "type": "voicemail",
      "id": "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f"
     }
    }
   ],
   "version": 6,
   "sync": "ok"
  }
 ],
 "targets": [
  {
   "value": "extension:5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
   "type": "extension",
   "id": "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
   "name": "Receptie",
   "extension": "100"
  },
  {
   "value": "extension:1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
   "type": "extension",
   "id": "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
   "name": "Pieter Jansen",
   "extension": "101"
  },
  {
   "value": "extension:7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "type": "extension",
   "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "name": "Jan de Vries",
   "extension": "102"
  },
  {
   "value": "ring_group:9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
   "type": "ring_group",
   "id": "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
   "name": "Iedereen",
   "extension": "200"
  },
  {
   "value": "voicemail:2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f",
   "type": "voicemail",
   "id": "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f",
   "name": "Algemeen",
   "extension": "900"
  },
  {
   "value": "voicemail:7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "type": "voicemail",
   "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
   "name": "Jan de Vries",
   "extension": "102",
   "ofDevice": true
  },
  {
   "value": "external",
   "type": "external",
   "id": null,
   "name": "Extern nummer",
   "extension": null
  }
 ],
 "holidayRules": [
  {
   "rule": "new_year",
   "label": "Nieuwjaarsdag"
  },
  {
   "rule": "kings_day",
   "label": "Koningsdag"
  },
  {
   "rule": "christmas_day",
   "label": "Eerste kerstdag"
  },
  {
   "rule": "boxing_day",
   "label": "Tweede kerstdag"
  }
 ]
}
"""#
    private static let simpleChainJSON = #"""
{
  "id": "d4e5f6a7-b8c9-4d0e-a1f2-b3c4d5e6f7a8",
  "number": "0850607848",
  "name": "Hoofdnummer",
  "version": 4,
  "mode": "simple",
  "sync": "ok",
  "hours": {
    "id": "c3b2a1f0-e9d8-4c7b-a6f5-e4d3c2b1a0f9",
    "version": 6,
    "week": [
      {
        "day": 1,
        "from": "09:00",
        "to": "17:00"
      },
      {
        "day": 2,
        "from": "09:00",
        "to": "17:00"
      },
      {
        "day": 3,
        "from": "09:00",
        "to": "17:00"
      },
      {
        "day": 4,
        "from": "09:00",
        "to": "17:00"
      },
      {
        "day": 5,
        "from": "09:00",
        "to": "17:00"
      }
    ],
    "holidays": {
      "national": true,
      "rules": [
        "new_year",
        "easter_sunday",
        "easter_monday",
        "kings_day",
        "ascension_day",
        "whit_sunday",
        "whit_monday",
        "christmas_day",
        "boxing_day"
      ],
      "dates": [
        {
          "name": "Bouwvak",
          "date": "2026-08-03"
        }
      ]
    },
    "closed": {
      "mode": "voicemail",
      "boxId": "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f",
      "ofDevice": false
    },
    "holiday": null,
    "sharedWith": []
  },
  "welcome": {
    "menuId": "4a5b6c7d-8e9f-4a0b-9c1d-2e3f4a5b6c7d",
    "version": 3,
    "soundId": "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d",
    "sharedWith": []
  },
  "forwarding": {
    "kind": "standard",
    "groupId": "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
    "version": 3,
    "strategy": "all",
    "members": [
      {
        "deviceId": "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
        "delaySeconds": 0,
        "timeoutSeconds": 25
      },
      {
        "deviceId": "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
        "delaySeconds": 0,
        "timeoutSeconds": 25
      },
      {
        "deviceId": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
        "delaySeconds": 5,
        "timeoutSeconds": 20
      }
    ],
    "unanswered": {
      "mode": "voicemail",
      "boxId": "2f4a6c8e-1b3d-4f5a-8c7e-9a0b1c2d3e4f",
      "ofDevice": false
    },
    "sharedWith": [
      "Servicenummer"
    ]
  },
  "advanced": null,
  "recording": {
    "enabled": false,
    "announcementSoundId": null,
    "billingActive": false,
    "available": true,
    "cost": {
      "priceE4": 20000,
      "vatIncluded": false
    }
  },
  "options": {
    "devices": [
      {
        "id": "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
        "name": "Receptie",
        "extension": "100"
      },
      {
        "id": "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
        "name": "Pieter Jansen",
        "extension": "101"
      },
      {
        "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
        "name": "Jan de Vries",
        "extension": "102"
      }
    ],
    "sounds": [
      {
        "id": "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d",
        "name": "Welkom"
      },
      {
        "id": "a1b2c3d4-2222-4a2b-8c3d-4e5f6a7b8c9d",
        "name": "Buiten kantoortijd"
      }
    ],
    "groups": [
      {
        "id": "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
        "name": "Iedereen"
      }
    ]
  }
}
"""#

    private static let advancedChainJSON = #"""
{
  "id": "e5f6a7b8-c9d0-4e1f-b2a3-c4d5e6f7a8b9",
  "number": "0850607849",
  "name": "Support",
  "version": 2,
  "mode": "advanced",
  "sync": "ok",
  "hours": null,
  "welcome": null,
  "forwarding": null,
  "advanced": {
    "summary": [
      "Openingstijden: Kantoortijden",
      "Wachtrij: Support (3 medewerkers)",
      "Keuzemenu met een submenu"
    ]
  },
  "recording": {
    "enabled": true,
    "announcementSoundId": null,
    "billingActive": true,
    "available": true,
    "cost": {
      "priceE4": 20000,
      "vatIncluded": false
    }
  },
  "options": {
    "devices": [
      {
        "id": "5c0a8e1f-2d7b-4a39-8f46-0b1c2d3e4f50",
        "name": "Receptie",
        "extension": "100"
      },
      {
        "id": "1b2c3d4e-0a1b-4c2d-8e3f-5a6b7c8d9e01",
        "name": "Pieter Jansen",
        "extension": "101"
      },
      {
        "id": "7a1d9c3e-5b2f-4e8a-9c01-6d3e8f2a4b57",
        "name": "Jan de Vries",
        "extension": "102"
      }
    ],
    "sounds": [
      {
        "id": "a1b2c3d4-1111-4a2b-8c3d-4e5f6a7b8c9d",
        "name": "Welkom"
      },
      {
        "id": "a1b2c3d4-2222-4a2b-8c3d-4e5f6a7b8c9d",
        "name": "Buiten kantoortijd"
      }
    ],
    "groups": [
      {
        "id": "9e8d7c6b-5a49-4382-b1a0-f9e8d7c6b5a4",
        "name": "Iedereen"
      }
    ]
  }
}
"""#

    private static let numbersJSON = #"""
{
 "numbers": [
  {
   "id": "d4e5f6a7-b8c9-4d0e-a1f2-b3c4d5e6f7a8",
   "number": "0850607848",
   "name": "Hoofdnummer",
   "mode": "simple",
   "summary": {
    "hours": "ma-vr 9:00-17:00",
    "welcome": true,
    "forwarding": "standard",
    "recording": false
   },
   "sync": "ok",
   "version": 4
  },
  {
   "id": "e5f6a7b8-c9d0-4e1f-b2a3-c4d5e6f7a8b9",
   "number": "0850607849",
   "name": "Support",
   "mode": "advanced",
   "summary": {
    "hours": "Uit",
    "welcome": false,
    "forwarding": "advanced",
    "recording": true
   },
   "sync": "ok",
   "version": 2
  }
 ]
}
"""#
}
#endif

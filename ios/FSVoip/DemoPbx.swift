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
    private let adminId: String
    private var pendingUntil: Date?

    init(adminAccountId: String) {
        adminId = adminAccountId
        overviewValue = Self.decode(Self.overviewJSON)
        devicesValue = Self.decode(Self.devicesJSON)
        ringGroupsValue = Self.decode(Self.ringGroupsJSON)
        hoursValue = Self.decode(Self.hoursJSON)
        // What the fixtures show as "being updated" settles after a few seconds, so the demo shows the polling.
        pendingUntil = Date().addingTimeInterval(6)
    }

    private static func decode<T: Decodable>(_ json: String) -> T {
        try! FSVoipJSON.decoder().decode(T.self, from: Data(json.utf8))
    }

    // MARK: PbxServicing

    func me(for account: StoredAccount) async throws -> MeResponse {
        account.id == adminId ? Self.decode(Self.meAdminJSON) : Self.decode(Self.meUserJSON)
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

    // MARK: Plumbing

    private func read<T>(_ value: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }

        if let pendingUntil, Date() > pendingUntil {
            settle()
        }

        return value()
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
  "calls": "own",
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
}
#endif

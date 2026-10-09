// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Validates shared/fixtures/*.json against shared/openapi.yaml and shared/push-payload.schema.json,
// so the fixtures the iOS (and later Android) contract tests decode are provably valid.
//
// Run:  bun install --cwd scripts && bun scripts/validate-contract.ts
//
// Every fixture MUST be listed below; an unlisted fixture is an error, so nothing is decoded
// by the apps without also being validated here.

import Ajv2020 from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import { parse } from "yaml";
import { readdirSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..", "shared");
const fixturesDir = join(root, "fixtures");

const openapi = parse(readFileSync(join(root, "openapi.yaml"), "utf8"));
const pushSchema = JSON.parse(readFileSync(join(root, "push-payload.schema.json"), "utf8"));

if (openapi.openapi !== "3.1.0") {
    throw new Error("openapi.yaml must be OpenAPI 3.1.0");
}

// OpenAPI 3.1 schemas are JSON Schema 2020-12; lift them into a standalone document.
const components = JSON.parse(JSON.stringify({ $id: "https://fsvoip.test/api", $defs: openapi.components.schemas }).replaceAll("#/components/schemas/", "#/$defs/"));

const ajv = new Ajv2020({ strict: false, allErrors: true });
addFormats(ajv);
ajv.addSchema(components);
ajv.addSchema(pushSchema);

const api = (name: string) => `https://fsvoip.test/api#/$defs/${name}`;
const push = (name: string) => `https://fullstackstudio.nl/fsvoip/push-payload.schema.json#/$defs/${name}`;

const mapping: Record<string, string> = {
    "pair-request.json": api("PairRequest"),
    "pair-request-minimal.json": api("PairRequest"),
    "pair-response.json": api("PairResponse"),
    "pair-response-tls.json": api("PairResponse"),
    "me-response.json": api("MeResponse"),
    "me-response-no-sip.json": api("MeResponse"),
    "me-patch-request.json": api("MePatchRequest"),
    "me-patch-request-clear.json": api("MePatchRequest"),
    "me-patch-response.json": api("MePatchResponse"),
    "push-token-request.json": api("PushTokenUpdate"),
    "push-token-request-clear.json": api("PushTokenUpdate"),
    "ok-response.json": api("OkResponse"),
    "error-not-found.json": api("Error"),
    "error-unauthorized.json": api("Error"),
    "error-rate-limited.json": api("Error"),
    "error-invalid-request.json": api("Error"),
    "error-unavailable-retryable.json": api("Error"),
    "me-response-admin.json": api("MeResponse"),
    "me-response-admin-frozen.json": api("MeResponse"),
    "me-response-user.json": api("MeResponse"),
    "pbx-overview.json": api("PbxOverview"),
    "pbx-devices.json": api("PbxDevices"),
    "pbx-device-patch.json": api("PbxDevicePatch"),
    "pbx-ring-groups.json": api("PbxRingGroups"),
    "pbx-ring-group-create.json": api("PbxRingGroupCreate"),
    "pbx-ring-group-created.json": api("PbxRingGroupCreated"),
    "pbx-ring-group-patch.json": api("PbxRingGroupPatch"),
    "pbx-hours.json": api("PbxHours"),
    "pbx-hours-patch.json": api("PbxHoursPatch"),
    "pbx-routing-patch.json": api("PbxRoutingPatch"),
    "pbx-routing-patch-entry.json": api("PbxRoutingPatch"),
    "calls-page.json": api("CallsPage"),
    "calls-page-user.json": api("CallsPage"),
    "voicemail-page.json": api("VoicemailPage"),
    "voicemail-page-unavailable.json": api("VoicemailPage"),
    "contacts-page.json": api("ContactsPage"),
    "contacts-since.json": api("ContactsPage"),
    "contact-detail.json": api("ContactDetailResponse"),
    "contact-create-request.json": api("ContactCreateRequest"),
    "contact-update-request.json": api("ContactUpdateRequest"),
    "contact-update-response.json": api("ContactUpdateResponse"),
    "contact-delete-response.json": api("ContactDeleteResponse"),
    "contact-lists.json": api("ContactLists"),
    "contact-list-snapshot.json": api("ContactListSnapshot"),
    "error-forbidden.json": api("Error"),
    "error-stale.json": api("Error"),
    "error-stale-contact.json": api("Error"),
    "error-blocked-destination.json": api("Error"),
    "error-read-only.json": api("Error"),
    "error-gone.json": api("Error"),
    "error-resync.json": api("Error"),
    "error-invalid-field.json": api("Error"),
    "error-in-use.json": api("Error"),
    "error-conflict-limit.json": api("Error"),
    "push-ring.json": push("ring"),
    "push-ring-anonymous.json": push("ring"),
    "push-revoked.json": push("revoked"),
    "push-refresh.json": push("refresh"),
    "apns-voip-body.json": push("apnsVoipBody"),
    "apns-alert-body.json": push("apnsAlertBody"),
    "fcm-message.json": push("fcmMessage"),
};

let failures = 0;

// Every `$ref` in the document must point at something that exists (a typo in a path or response reference is otherwise silent).
function resolvePointer(pointer: string): unknown {
    return pointer
        .replace(/^#\//, "")
        .split("/")
        .reduce<any>((node, part) => (node === undefined ? undefined : node[part.replaceAll("~1", "/").replaceAll("~0", "~")]), openapi);
}

function checkRefs(node: unknown, where: string): void {
    if (Array.isArray(node)) {
        node.forEach((item, index) => checkRefs(item, `${where}[${index}]`));
    } else if (node && typeof node === "object") {
        for (const [key, value] of Object.entries(node)) {
            if (key === "$ref" && typeof value === "string" && resolvePointer(value) === undefined) {
                console.error(`FAIL openapi.yaml ${where}: $ref ${value} does not resolve`);
                failures++;
            } else {
                checkRefs(value, `${where}.${key}`);
            }
        }
    }
}

checkRefs(openapi, "");

// Every operation needs an operationId and answers with at least one documented success.
for (const [path, item] of Object.entries<any>(openapi.paths)) {
    for (const [method, operation] of Object.entries<any>(item)) {
        if (!operation.operationId || !Object.keys(operation.responses ?? {}).some((code) => code.startsWith("2"))) {
            console.error(`FAIL openapi.yaml ${method.toUpperCase()} ${path}: operationId or a 2xx response is missing`);
            failures++;
        }
    }
}

const files = readdirSync(fixturesDir).filter((name) => name.endsWith(".json")).sort();

for (const file of files) {
    const schemaRef = mapping[file];

    if (!schemaRef) {
        console.error(`FAIL ${file}: not listed in scripts/validate-contract.ts`);
        failures++;
        continue;
    }

    const validate = ajv.getSchema(schemaRef);

    if (!validate) {
        console.error(`FAIL ${file}: schema ${schemaRef} not found`);
        failures++;
        continue;
    }

    const data = JSON.parse(readFileSync(join(fixturesDir, file), "utf8"));

    if (validate(data)) {
        console.log(`ok   ${file}`);
    } else {
        console.error(`FAIL ${file}: ${ajv.errorsText(validate.errors)}`);
        failures++;
    }
}

for (const file of Object.keys(mapping)) {
    if (!files.includes(file)) {
        console.error(`FAIL ${file}: listed but missing in shared/fixtures`);
        failures++;
    }
}

// The FCM fixture carries the message as JSON text: it must itself be a valid `ring`.
const fcm = JSON.parse(readFileSync(join(fixturesDir, "fcm-message.json"), "utf8"));
const inner = JSON.parse(fcm.message.data.fsvoip);
const validateRing = ajv.getSchema(push("message"))!;

if (!validateRing(inner)) {
    console.error(`FAIL fcm-message.json data.fsvoip: ${ajv.errorsText(validateRing.errors)}`);
    failures++;
}

// Negative controls: the schemas must actually reject things (guards against a no-op validator).
const negatives: [string, string, unknown][] = [
    ["PairRequest rejects an unknown field", api("PairRequest"), { token: "fss_vpair_" + "A".repeat(43), device: { platform: "ios" }, extra: 1 }],
    ["PairRequest rejects a malformed token", api("PairRequest"), { token: "nope", device: { platform: "ios" } }],
    ["push ring rejects a SIP password", push("ring"), { ...JSON.parse(readFileSync(join(fixturesDir, "push-ring.json"), "utf8")), sipPassword: "x" }],
    ["PbxDevicePatch needs a version", api("PbxDevicePatch"), { dnd: true }],
    ["PbxDevicePatch rejects a field that stays in the portal (name)", api("PbxDevicePatch"), { version: 1, name: "x" }],
    ["PbxDevicePatch rejects an empty change", api("PbxDevicePatch"), { version: 1 }],
    ["PbxRoutingPatch needs the target key", api("PbxRoutingPatch"), { version: 1 }],
    ["PbxHoursPatch rejects the name", api("PbxHoursPatch"), { version: 1, name: "x" }],
    ["ContactUpdateRequest needs expectedUpdatedAt", api("ContactUpdateRequest"), { company: "x" }],
    ["ContactCreateRequest rejects expectedUpdatedAt", api("ContactCreateRequest"), { name: "x", expectedUpdatedAt: "2026-10-07T09:30:12.345Z" }],
    ["ContactCreateRequest rejects an unknown phone label", api("ContactCreateRequest"), { name: "x", phones: [{ number: "+31701234567", label: "pager" }] }],
    ["Target rejects an unknown field", api("Target"), { type: "hangup", extra: 1 }],
    ["revoked rejects a wrong version", push("revoked"), { v: 2, type: "revoked", accountId: "3f0c2b1e-8a4d-4d6f-9b7a-1c2d3e4f5a6b", accountLabel: "x" }],
];

for (const [name, ref, data] of negatives) {
    if (ajv.getSchema(ref)!(data)) {
        console.error(`FAIL negative control: ${name}`);
        failures++;
    }
}

if (failures > 0) {
    console.error(`\n${failures} problem(s)`);
    process.exit(1);
}

console.log(`\n${files.length} fixtures valid, ${negatives.length} negative controls ok`);

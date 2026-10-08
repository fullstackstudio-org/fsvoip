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
    "push-ring.json": push("ring"),
    "push-ring-anonymous.json": push("ring"),
    "push-revoked.json": push("revoked"),
    "push-refresh.json": push("refresh"),
    "apns-voip-body.json": push("apnsVoipBody"),
    "apns-alert-body.json": push("apnsAlertBody"),
    "fcm-message.json": push("fcmMessage"),
};

let failures = 0;
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

#!/usr/bin/env node
// Verifies a Back Office provenance attestation (DSSE envelope, Ed25519) offline. Node 22+, no dependencies.
//
//   node verify-attestation.mjs <attestation.intoto.json> <keys.json | https://backoffice.example.com/.well-known/back-office/keys>
//
// Exit code 0 = at least one signature verifies with a published key and the payload is an in-toto Statement v1;
// 1 = invalid; 2 = usage or input error. Prints the subjects and the predicate summary on success.
import { createPublicKey, verify } from "node:crypto";
import { readFile } from "node:fs/promises";

const [envelopePath, keysSource] = process.argv.slice(2);
if (!envelopePath || !keysSource) {
  console.error("usage: verify-attestation.mjs <envelope.json> <keys.json|keys-url>");
  process.exit(2);
}

const envelope = JSON.parse(await readFile(envelopePath, "utf8"));
const jwks = /^https?:\/\//.test(keysSource)
  ? await (await fetch(keysSource)).json()
  : JSON.parse(await readFile(keysSource, "utf8"));

// DSSE v1 pre-authentication encoding: "DSSEv1" SP len(type) SP type SP len(body) SP body
const pae = (type, body) => {
  const t = Buffer.from(type, "utf8");
  return Buffer.concat([Buffer.from(`DSSEv1 ${t.length} `), t, Buffer.from(` ${body.length} `), body]);
};

const payload = Buffer.from(envelope.payload, "base64");
const message = pae(envelope.payloadType, payload);
const keys = new Map((jwks.keys ?? []).map((k) => [k.kid, k]));

let validKey = null;
for (const signature of envelope.signatures ?? []) {
  const jwk = keys.get(signature.keyid);
  if (!jwk || jwk.kty !== "OKP" || jwk.crv !== "Ed25519") continue;
  const key = createPublicKey({ key: { kty: jwk.kty, crv: jwk.crv, x: jwk.x }, format: "jwk" });
  if (verify(null, message, key, Buffer.from(signature.sig, "base64"))) {
    validKey = jwk;
    break;
  }
}

if (!validKey) {
  console.error("INVALID: no signature verifies with a published Back Office key");
  process.exit(1);
}

const statement = JSON.parse(payload.toString("utf8"));
if (envelope.payloadType !== "application/vnd.in-toto+json" || statement._type !== "https://in-toto.io/Statement/v1") {
  console.error("INVALID: signature ok, but the payload is not an in-toto Statement v1");
  process.exit(1);
}

console.log(`OK: signed by ${validKey.kid} (${validKey.status ?? "unknown status"})`);
console.log(`predicateType: ${statement.predicateType}`);
for (const subject of statement.subject) {
  console.log(`subject: ${subject.name} ${Object.entries(subject.digest).map(([alg, value]) => `${alg}:${value}`).join(" ")}`);
}
const p = statement.predicate ?? {};
console.log(`run: ${p.runId} ${p.kind ?? ""} workflow=${p.workflow?.name ?? ""} v${p.workflow?.version ?? ""}`);
if (p.model) console.log(`model: ${p.model.ref ?? "?"} effort=${p.model.effort ?? "?"}`);
for (const d of p.gateDecisions ?? []) console.log(`gate: ${d.nodeId} ${d.option} by ${d.decidedBy?.displayName ?? "?"} at ${d.decidedAt}`);

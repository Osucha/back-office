# Verifying AI provenance attestations

Every finished agent job that produced a commit or a diff, and every finished run, gets a signed
attestation (ADR 0025, docs/architecture/11-governance.md): an in-toto Statement v1 in a DSSE envelope,
signed with the platform's Ed25519 key. You can verify it without trusting the Back Office server that
handed it to you, as long as you trust the public key.

## 1. Download

```bash
BO=https://backoffice.example.com
TOKEN=bo_pat_...        # personal access token with project.view on the project
curl -sH "Authorization: Bearer $TOKEN" "$BO/api/v1/runs/<runId>/attestations"            # summaries: id, kind, subjects
curl -sH "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.in-toto+json" \
     "$BO/api/v1/attestations/<attestationId>" -o attestation.intoto.json                  # the DSSE envelope
curl -s "$BO/.well-known/back-office/keys" -o keys.json                                    # JWK set (public, no token)
```

Pin `keys.json` (or the key fingerprint) out of band, e.g. commit it to the repository that consumes the
attestations; retired keys stay in the set with `"status": "retired"` so old attestations keep verifying.

## 2. Verify offline

With Node 22 or newer (no packages needed), using [`verify-attestation.mjs`](verify-attestation.mjs):

```bash
node docs/guides/verify-attestation.mjs attestation.intoto.json keys.json
# OK: signed by sha256:cc40…2c54 (active)
# predicateType: https://back-office.dev/attestations/agent-run/v1
# subject: git+https://git.example.com/shop.git@4f2a9c1e0b7d sha256:cc1b… gitCommit:4f2a9c1e0b7d
# subject: diff:8d36e132-…/impl/2 sha256:d546…
# run: 8d36e132-… AGENT_JOB workflow=Issue to MR v3
# model: anthropic/claude-opus-5 effort=HIGH
```

Exit code 0 means a signature verifies with a published key over the DSSE pre-authentication encoding
`"DSSEv1" SP len(payloadType) SP payloadType SP len(payload) SP payload` and the payload is an in-toto
Statement v1; 1 means invalid (tampered payload, wrong type, unknown key), 2 a usage error. The script also
accepts the keys URL instead of a file.

The same check is available online: `POST /api/v1/attestations/verify` with the envelope as body returns
`{valid, keyId, statement, reason}`.

## 3. Check the subjects against your repository

A job attestation names the commit (`gitCommit` digest; `sha256` is SHA-256 of the commit id string) and the
diff (`sha256` of the unified diff `git diff <base>...<commit>` as collected by the runner):

```bash
git cat-file -e 4f2a9c1e0b7d^{commit} && echo "commit exists"
printf %s 4f2a9c1e0b7d | sha256sum            # equals subject[0].digest.sha256
```

## 4. Other tools

Any DSSE / Ed25519 implementation works: the envelope is the standard DSSE JSON
(`payloadType`, `payload` base64, `signatures[{keyid, sig}]`) and the JWK is `{"kty":"OKP","crv":"Ed25519","x":…}`.
For `cosign verify-blob`-style tooling export the key as PEM:

```bash
node -e "const k=require('node:crypto').createPublicKey({key:require('./keys.json').keys[0],format:'jwk'});process.stdout.write(k.export({type:'spki',format:'pem'}))" > backoffice.pub
```

## Operating the key

- `backoffice.signing.keys` (Helm: a generated secret) = `[{id, privateKeyPkcs8Base64, publicKeyX509Base64, active}]`.
  Generate: `openssl genpkey -algorithm ed25519 -out k.pem`, then
  `openssl pkey -in k.pem -outform DER | base64 -w0` (private, PKCS#8) and
  `openssl pkey -in k.pem -pubout -outform DER | base64 -w0` (public, X.509).
- Rotate by adding the new key as `active: true` and keeping the old one with `active: false` and only its
  public key.
- Without configured keys the server generates an ephemeral key and logs a warning: fine for local development,
  wrong for production (every restart and every replica signs with a different key).

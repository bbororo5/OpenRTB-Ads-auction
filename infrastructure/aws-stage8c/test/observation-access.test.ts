import assert from "node:assert/strict";
import test from "node:test";
import { generateKeyPairSync, privateDecrypt, constants } from "node:crypto";
import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync, rmSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { accessNames, activateAccess, connectObservation, observationUrl, prepareAccess, sealKey, stopAccess } from "../lib/observation-access.js";
import { createObservationKey, observationAccessToken, observationAudience } from "../lib/observation-identity.js";

const run = "rtb-test";
const pair = generateKeyPairSync("rsa", { modulusLength: 3072 });
const pub = pair.publicKey.export({ type: "spki", format: "pem" }).toString();
const auth = "tskey-auth-test-not-a-real-credential";
const dns = `${accessNames(run).name}.taild7dd00.ts.net`;
const online = { BackendState: "Running", Self: { Online: true, DNSName: `${dns}.` }, CertDomains: [dns] };

test("RSA-OAEP SHA256 transport interoperates with OpenSSL and hides key in command", () => {
  const cipher = sealKey(pub, auth);
  assert.equal(privateDecrypt({ key: pair.privateKey, padding: constants.RSA_PKCS1_OAEP_PADDING, oaepHash: "sha256" }, Buffer.from(cipher, "base64")).toString(), auth);
  const dir = mkdtempSync(path.join(tmpdir(), "rtb-seal-test-"));
  try {
    const pem = path.join(dir, "private.pem");
    writeFileSync(pem, pair.privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 });
    const output = execFileSync("openssl", ["pkeyutl", "-decrypt", "-inkey", pem, "-pkeyopt", "rsa_padding_mode:oaep",
      "-pkeyopt", "rsa_oaep_md:sha256", "-pkeyopt", "rsa_mgf1_md:sha256"], { input: Buffer.from(cipher, "base64") });
    assert.equal(output.toString(), auth);
  } finally { rmSync(dir, { recursive: true, force: true }); }
  assert.ok(!activateAccess(run, cipher).includes(auth));
  assert.throws(() => sealKey(generateKeyPairSync("rsa", { modulusLength: 2048 }).publicKey.export({ type: "spki", format: "pem" }).toString(), auth));
});
test("scripts are syntactically valid, private, run-scoped and never expose a public port", () => {
  for (const script of [prepareAccess(run), activateAccess(run, sealKey(pub, auth)), stopAccess(run)]) {
    execFileSync("bash", ["-n"], { input: script });
    assert.doesNotMatch(script, /--privileged|--cap-add|funnel|--advertise-routes|TS_AUTHKEY|set -x/);
  }
  assert.match(prepareAccess(run), /--tun=userspace-networking --state=mem:/);
  assert.match(prepareAccess(run), /--state=mem: --statedir=\/var\/lib\/tailscale/);
  assert.match(prepareAccess(run), /--tmpfs \/var\/lib\/tailscale:mode=700/);
  assert.match(activateAccess(run, "YWJj"), /--auth-key=file:/);
  assert.match(activateAccess(run, "YWJj"), /serve --bg --https=443 http:\/\/127.0.0.1:3000/);
  assert.ok(activateAccess(run, "YWJj").indexOf(" cert --cert-file=") < activateAccess(run, "YWJj").indexOf(" serve --bg"));
  assert.throws(() => accessNames("rtb-../../other"));
  assert.throws(() => activateAccess(run, "'; echo bad"));
  assert.notEqual(accessNames(run).dir, accessNames("rtb-other").dir);
});
test("only the expected online HTTPS identity produces an observation URL", () => {
  assert.equal(observationUrl(online, run), `https://${dns}`);
  for (const status of [{}, { ...online, CertDomains: [] }, { ...online, BackendState: "NeedsLogin" },
    { ...online, Self: { Online: true, DNSName: "attacker.example" } }]) assert.throws(() => observationUrl(status, run));
});
test("one-use key is revoked both after successful join and after join failure", async () => {
  for (const fail of [false, true]) {
    const events: string[] = [];
    const connect = connectObservation(run, async script => {
      if (script.includes("genpkey")) { events.push("prepare"); return pub; }
      events.push("join");
      assert.ok(!script.includes(auth));
      if (fail) throw new Error("join failed");
      return JSON.stringify(online);
    }, async () => { events.push("key"); return { key: auth, revoke: async () => { events.push("revoke"); } }; });
    if (fail) await assert.rejects(connect, /join failed/); else assert.equal(await connect, `https://${dns}`);
    assert.deepEqual(events, ["prepare", "key", "join", "revoke"]);
  }
});
test("OIDC uses explicit audience, bounded calls and sanitized errors", async () => {
  const env = { GITHUB_ACTIONS: "true", ACTIONS_ID_TOKEN_REQUEST_URL: "https://run.actions.githubusercontent.com/token?x=1",
    ACTIONS_ID_TOKEN_REQUEST_TOKEN: "github-request-secret" };
  const calls: { url: string; init: RequestInit }[] = [];
  const request = (async (url: any, init: any) => {
    calls.push({ url: String(url), init });
    return new Response(JSON.stringify(calls.length === 1 ? { value: "jwt-secret" } : { access_token: "access-secret" }));
  }) as typeof fetch;
  assert.equal(await observationAccessToken(request, env), "access-secret");
  assert.equal(new URL(calls[0]!.url).searchParams.get("audience"), observationAudience);
  assert.match(String(calls[1]!.init.body), /jwt=jwt-secret/);
  assert.equal(calls[1]!.init.redirect, "error");
  assert.ok(calls[1]!.init.signal);
  await assert.rejects(observationAccessToken(request, {}), /requires GitHub/);
  await assert.rejects(observationAccessToken(request, { ...env, ACTIONS_ID_TOKEN_REQUEST_URL: "https://evil.example" }), /Unexpected/);
  await assert.rejects(observationAccessToken((async () => { throw new Error("jwt-secret"); }) as typeof fetch, env), error => {
    assert.ok(!String(error).includes("jwt-secret")); return true;
  });
});
test("preflight precedes AWS creation; cleanup is unconditional even when tailnet logout fails", () => {
  const workflow = readFileSync(new URL("../../../.github/workflows/stage8c-experiment.yml", import.meta.url), "utf8");
  assert.ok(workflow.indexOf("scripts/observation-identity-check.ts") < workflow.indexOf("Create ephemeral watchdog"));
  const runner = readFileSync(new URL("../scripts/experiment.ts", import.meta.url), "utf8");
  assert.match(runner, /finally \{ await cleanup\(\); \}/);
  assert.ok(runner.indexOf("await connectObservation") < runner.indexOf('await gate("screen-ready")'));
  const check = readFileSync(new URL("../../../.github/workflows/stage8c-observation-check.yml", import.meta.url), "utf8");
  assert.doesNotMatch(check, /configure-aws|role-to-assume|ack-cost/);
});
test("auth keys expire in ten minutes, are single-use/ephemeral/tagged, and are explicitly revoked", async () => {
  const calls: { url: string; init: RequestInit }[] = [];
  const request = (async (url: any, init: any) => {
    calls.push({ url: String(url), init });
    const values = [{ value: "jwt" }, { access_token: "token" }, { id: "key-id", key: auth }];
    return calls.length === 4 ? new Response(null, { status: 404 }) : new Response(JSON.stringify(values[calls.length - 1]));
  }) as typeof fetch;
  const credential = await createObservationKey(run, request, { GITHUB_ACTIONS: "true",
    ACTIONS_ID_TOKEN_REQUEST_URL: "https://run.actions.githubusercontent.com/token", ACTIONS_ID_TOKEN_REQUEST_TOKEN: "request-token" });
  const body = JSON.parse(String(calls[2]!.init.body));
  assert.equal(body.expirySeconds, 600);
  assert.deepEqual(body.capabilities.devices.create, { reusable: false, ephemeral: true, preauthorized: true, tags: ["tag:rtb-observer"] });
  await credential.revoke();
  assert.equal(calls[3]!.init.method, "DELETE");
  assert.ok(calls[3]!.url.endsWith("/keys/key-id"));
});

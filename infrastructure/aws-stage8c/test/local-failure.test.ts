import test from "node:test";
import assert from "node:assert/strict";
import { withCleanup } from "../lib/local-lifecycle.js";
import { LocalSession, waitFor } from "../lib/local-session.js";
import { jsonRequest, retrieveTrace } from "../lib/local-telemetry.js";
import http from "node:http";

test("startup or load failure always invokes cleanup and preserves failure", async () => {
  let calls = 0;
  await assert.rejects(withCleanup(async () => { throw new Error("load failed"); }, () => { calls++; }), /load failed/);
  assert.equal(calls, 1);
  await assert.rejects(withCleanup(async () => { throw new Error("startup failed"); }, () => { throw new Error("cleanup failed"); }), AggregateError);
  assert.throws(() => new LocalSession().down(true), /restricted/);
});
test("delayed/unavailable telemetry remains bounded and is not success", async () => {
  const server = http.createServer((_req, res) => { res.writeHead(503); res.end(); });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${(server.address() as any).port}`;
  try {
    await assert.rejects(jsonRequest(base, 100), /503/);
    await assert.rejects(retrieveTrace("a".repeat(32), base, 5), /timed out/);
    await assert.rejects(waitFor(async () => false, 5, 1), /timed out/);
  } finally { server.close(); }
});

import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { gateKey, isApproval, waitForObservation, type Gate } from "../lib/observation-gate.js";
import { runExperiment } from "../lib/experiment-lifecycle.js";

const gate: Gate = { runId: "rtb-test", phase: "screen-ready", nonce: "fresh", expiresAt: new Date(1000).toISOString() };
test("approval requires current run, phase, nonce and unexpired deadline", () => {
  const valid = { ...gate, approved: true };
  assert.ok(isApproval(gate, valid, 0));
  for (const value of [undefined, {}, { ...valid, runId: "rtb-other" }, { ...valid, phase: "review-done" },
    { ...valid, nonce: "stale" }, { ...valid, approved: false }]) assert.equal(isApproval(gate, value, 0), false);
  assert.equal(isApproval(gate, valid, 1000), false);
  assert.throws(() => gateKey("../../other", "screen-ready"));
  assert.throws(() => gateKey("rtb-test", "deploy"));
});
test("no test runs before approval, and timeout still invokes lifecycle cleanup", async () => {
  let now = 0;
  const calls: string[] = [];
  await assert.rejects(runExperiment({
    acquire: async () => {}, deploy: async () => {},
    verify: async () => {
      await waitForObservation(gate, { now: () => now, checkCancellation: () => {},
        publish: async () => { calls.push("publish"); }, read: async () => undefined,
        pause: async () => { now += 500; } });
      calls.push("load");
    }, cleanup: async () => { calls.push("cleanup"); },
  }), /timed out/);
  assert.deepEqual(calls, ["publish", "cleanup"]);
});
test("approval at read completion must still be before deadline", async () => {
  let now = 0;
  await assert.rejects(waitForObservation(gate, { now: () => now, checkCancellation: () => {},
    publish: async () => {}, read: async () => { now = 1000; return { ...gate, approved: true }; },
    pause: async () => {},
  }), /timed out/);
});
test("valid approval proceeds; cancellation and read errors abort rather than keep waiting", async () => {
  const io = { now: () => 0, checkCancellation: () => {}, publish: async () => {},
    read: async () => ({ ...gate, approved: true }), pause: async () => {} };
  await waitForObservation(gate, io);
  await assert.rejects(waitForObservation(gate, { ...io, read: async () => { throw new Error("AccessDenied"); } }), /AccessDenied/);
  await assert.rejects(waitForObservation(gate, { ...io, checkCancellation: () => { throw new Error("cancelled"); } }), /cancelled/);
});
test("approval workflow is manual and cannot deadlock behind the experiment concurrency group", () => {
  const yaml = readFileSync(new URL("../../../.github/workflows/stage8c-observation-approve.yml", import.meta.url), "utf8");
  assert.match(yaml, /workflow_dispatch:/);
  assert.match(yaml, /github.ref == 'refs\/heads\/main'/);
  assert.match(yaml, /group: stage8c-observation-approval-/);
  assert.doesNotMatch(yaml, /run:.*\$\{\{ inputs\./);
  const runner = readFileSync(new URL("../scripts/experiment.ts", import.meta.url), "utf8");
  assert.ok(runner.indexOf('await gate("screen-ready")') < runner.indexOf('const label = `${runId}-observed-10`'));
  assert.ok(runner.indexOf('if (command === "observe")') < runner.indexOf('// Cold-start warmup'));
});

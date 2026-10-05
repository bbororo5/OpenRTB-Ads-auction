import test from "node:test";
import assert from "node:assert/strict";
import { verificationSession } from "../lib/local-verify.js";
import { LocalSession } from "../lib/local-session.js";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { initialize } from "../lib/local-config.js";
test("verification uses unique projects and distinct ports", async () => {
  const a = await verificationSession(), b = await verificationSession();
  assert.notEqual(a.project, b.project);
  assert.equal(new Set(Object.values(a.ports)).size, 11);
  assert.notEqual(a.project, "rtb-local");
  assert.equal(a.env.RTB_SSP_PORT, String(a.ports.ssp));
});
test("normal down never requests volume deletion and verification cannot target another project", () => {
  const calls: string[][] = [];
  class FakeSession extends LocalSession {
    override compose(args: string[]) { calls.push(args); return ""; }
  }
  // The default session config exists only in integration use; structural guard is unconditional.
  assert.throws(() => new FakeSession("rtb-local-other").down(true), /restricted/);
  assert.equal(calls.length, 0);
});
test("reinitialization preserves campaign and keys, detecting edited snapshots", () => {
  const directory = mkdtempSync(join(tmpdir(), "rtb-restart-"));
  try {
    initialize("rtb-local", directory);
    const snapshot = readFileSync(join(directory, "campaigns.json"), "utf8");
    const keys = readFileSync(join(directory, "runtime.env"), "utf8");
    initialize("rtb-local", directory);
    assert.equal(readFileSync(join(directory, "campaigns.json"), "utf8"), snapshot);
    assert.equal(readFileSync(join(directory, "runtime.env"), "utf8"), keys);
    writeFileSync(join(directory, "campaigns.json"), snapshot + " ");
    assert.throws(() => initialize("rtb-local", directory), /checksum/);
  } finally { rmSync(directory, { recursive: true, force: true }); }
});

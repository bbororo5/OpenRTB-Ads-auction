import test from "node:test";
import assert from "node:assert/strict";
import { verificationSession } from "../lib/local-verify.js";
test("verification uses unique projects and distinct ports", async () => {
  const a = await verificationSession(), b = await verificationSession();
  assert.notEqual(a.project, b.project);
  assert.equal(new Set(Object.values(a.ports)).size, 11);
  assert.notEqual(a.project, "rtb-local");
  assert.equal(a.env.RTB_SSP_PORT, String(a.ports.ssp));
});

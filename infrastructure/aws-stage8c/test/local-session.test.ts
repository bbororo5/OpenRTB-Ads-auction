import assert from "node:assert/strict";
import test from "node:test";
import { LocalSession, waitFor } from "../lib/local-session.js";
test("local session rejects unscoped project names", () => {
  assert.throws(() => new LocalSession("other-project"), /Invalid/);
});
test("readiness retries transient failures but has a deadline", async () => {
  let count = 0;
  await waitFor(async () => ++count === 2, 100, 1);
  assert.equal(count, 2);
  await assert.rejects(waitFor(async () => false, 3, 1), /timed out/);
});

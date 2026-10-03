import test from "node:test";
import assert from "node:assert/strict";
import { loadSettings } from "../lib/local-evidence.js";
test("local runs explicitly avoid the 500 VU default", () => {
  assert.deepEqual(loadSettings, { RPS: 10, preAllocatedVUs: 20, maxVUs: 100 });
});

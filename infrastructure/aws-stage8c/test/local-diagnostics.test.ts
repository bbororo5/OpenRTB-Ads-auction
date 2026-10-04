import test from "node:test";
import assert from "node:assert/strict";
import { redact } from "../lib/local-diagnostics.js";
test("diagnostics redact runtime secrets, proof tokens and bound output", () => {
  const result = redact('password=secret123 {"renderProof":"proof999"} abcdefg', ["abcdefg"]);
  assert.ok(!result.includes("secret123")); assert.ok(!result.includes("proof999")); assert.ok(!result.includes("abcdefg"));
  assert.equal(redact("x".repeat(200000), []).length, 128000);
});

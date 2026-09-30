import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { initialize, validateProject } from "../lib/local-config.js";
test("configuration preserves identity and rejects expired campaigns", () => {
  const dir = mkdtempSync(join(tmpdir(), "rtb-config-"));
  try {
    initialize("rtb-local", dir, 1_000_000);
    const before = readFileSync(join(dir, "runtime.env"), "utf8");
    initialize("rtb-local", dir, 1_001_000);
    assert.equal(readFileSync(join(dir, "runtime.env"), "utf8"), before);
    assert.throws(() => initialize("rtb-local", dir, 9 * 86400000), /expired/);
    assert.throws(() => validateProject("../other"), /Invalid/);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

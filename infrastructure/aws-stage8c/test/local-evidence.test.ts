import test from "node:test";
import assert from "node:assert/strict";
import { loadSettings, save, recordCaptureFailure } from "../lib/local-evidence.js";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { analyzeRequestJournal } from "../lib/request-evidence.js";
import { metricQueries } from "../lib/local-capture.js";
test("local runs explicitly avoid the 500 VU default", () => {
  assert.deepEqual(loadSettings, { RPS: 10, preAllocatedVUs: 20, maxVUs: 100 });
});
test("missing local journal cannot be reported as a successful empty run", () => {
  assert.throws(() => analyzeRequestJournal("", { metrics: { http_reqs: { count: 10 } } }), /incomplete/);
});
test("capture queries are scoped to local data and preserve server histogram semantics", () => {
  assert.equal(Object.keys(metricQueries).length, 5);
  for (const query of Object.values(metricQueries)) assert.match(query, /job="otel-collector"/);
  assert.match(metricQueries.serverP99, /histogram_quantile/);
});
test("capture failure does not overwrite the original k6 outcome", () => {
  const dir = mkdtempSync(join(tmpdir(), "rtb-outcome-"));
  try {
    save(dir, "result.json", { code: 99 }); recordCaptureFailure(dir, new Error("Missing trace"));
    assert.equal(JSON.parse(readFileSync(join(dir, "result.json"), "utf8")).code, 99);
    assert.equal(JSON.parse(readFileSync(join(dir, "manifest.json"), "utf8")).complete, false);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

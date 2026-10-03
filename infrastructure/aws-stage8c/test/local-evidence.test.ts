import test from "node:test";
import assert from "node:assert/strict";
import { loadSettings } from "../lib/local-evidence.js";
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

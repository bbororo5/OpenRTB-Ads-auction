import test from "node:test";
import assert from "node:assert/strict";
import { metricSeries, logStreams, retrieveTrace } from "../lib/local-telemetry.js";
test("successful empty metrics are missing, not zero", () => {
  assert.throws(() => metricSeries({ status: "success", data: { result: [] } }), /missing/);
  assert.throws(() => metricSeries({ status: "error" }), /missing/);
  assert.equal(metricSeries({ status: "success", data: { result: [{ value: [0, "0"] }] } }).length, 1);
});
test("logs and traces cannot report empty payloads as observed", async () => {
  assert.throws(() => logStreams({ status: "success", data: { result: [] } }), /No log/);
  await assert.rejects(retrieveTrace("../bad"), /Invalid/);
});

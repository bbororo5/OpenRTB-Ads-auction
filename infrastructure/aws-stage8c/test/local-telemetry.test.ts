import test from "node:test";
import assert from "node:assert/strict";
import { metricSeries } from "../lib/local-telemetry.js";
test("successful empty metrics are missing, not zero", () => {
  assert.throws(() => metricSeries({ status: "success", data: { result: [] } }), /missing/);
  assert.throws(() => metricSeries({ status: "error" }), /missing/);
  assert.equal(metricSeries({ status: "success", data: { result: [{ value: [0, "0"] }] } }).length, 1);
});

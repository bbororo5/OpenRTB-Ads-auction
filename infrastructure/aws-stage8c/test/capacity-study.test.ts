import assert from "node:assert/strict";
import test from "node:test";
import { capacityStudy, readObservation, Observation } from "../lib/capacity-study.js";

const result = (passed: boolean): Observation => ({ requests: 600, p99Ms: passed ? 20 : 100,
  errorRate: 0, invalid: 0, dropped: 0, projectWinRate: .25, projectWins: 150, statuses: {}, passed });

test("first failure stops escalation, narrows twice, repeats and checks recovery", async () => {
  const study = await capacityStudy(async (_label, rps) => result(rps < 80));
  assert.deepEqual(study.phases.map(p => p.rps), [10, 25, 50, 100, 75, 87, 75, 10]);
  assert.equal(study.lastPassingRps, 75);
  assert.equal(study.firstFailingRps, 100);
  assert.equal(study.lowestFailingRps, 87);
  assert.equal(study.repeatPassed, true);
});
test("baseline failure does not escalate; ceiling is a lower bound, not a discovered limit", async () => {
  const failed = await capacityStudy(async () => result(false));
  assert.deepEqual(failed.phases.map(p => p.rps), [10, 10]);
  assert.equal(failed.lastPassingRps, null);
  const passed = await capacityStudy(async () => result(true));
  assert.equal(passed.ceilingReached, true);
  assert.equal(passed.firstFailingRps, null);
  assert.equal(passed.phases.length, 8);
});
test("unstable repeat remains visible and transport errors abort instead of escalating", async () => {
  const study = await capacityStudy(async (label, rps) => result(!label.startsWith("repeat") && rps < 80));
  assert.equal(study.repeatPassed, false);
  let calls = 0;
  await assert.rejects(capacityStudy(async () => { calls++; throw new Error("SSM unavailable"); }), /SSM unavailable/);
  assert.equal(calls, 1);
});
test("summary parser fails closed and evaluates all SLO dimensions", () => {
  const metrics = { http_reqs: { count: 600 }, http_req_duration: { "p(99)": 20 },
    http_req_failed: { value: 0 }, stage8c_invalid_auctions: { count: 0 }, dropped_iterations: { count: 0 },
    stage8c_project_dsp_win_rate: { value: .25, passes: 150 } };
  assert.equal(readObservation({ metrics }, 10, 60).passed, true);
  assert.equal(readObservation({ metrics }, 20, 60).passed, false);
  metrics.stage8c_project_dsp_win_rate.value = .1;
  assert.equal(readObservation({ metrics }, 10, 60).passed, false);
  assert.throws(() => readObservation({}, 10, 60), /Missing k6 metric/);
  const allFailed = { ...metrics, stage8c_invalid_auctions: { count: 600 },
    stage8c_project_dsp_win_rate: undefined, http_req_failed: { value: 1 } };
  assert.equal(readObservation({ metrics: allFailed }, 10, 60).passed, false);
});

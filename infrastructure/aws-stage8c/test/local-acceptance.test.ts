import test from "node:test";
import assert from "node:assert/strict";
import { connectedApplicationTrace } from "../lib/local-acceptance.js";
test("two services must occur in the same request trace", () => {
  const id = "a".repeat(32);
  const sample = (services: string[]) => ({ state: "retrieved", request: { traceId: id }, trace: { batches: services.map(service => ({
    resource: { attributes: [{ key: "service.name", value: { stringValue: service } }] }, scopeSpans: [{ spans: [
      { traceId: id, startTimeUnixNano: "1", endTimeUnixNano: "2" }] }] })) } });
  assert.equal(connectedApplicationTrace([sample(["rtb-ssp"]), sample(["rtb-dsp"])]), false);
  assert.equal(connectedApplicationTrace([sample(["rtb-ssp", "rtb-dsp"])]), true);
  assert.equal(connectedApplicationTrace([{ state: "unavailable" }]), false);
});

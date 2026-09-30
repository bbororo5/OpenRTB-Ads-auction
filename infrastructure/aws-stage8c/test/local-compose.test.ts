import test from "node:test";
import assert from "node:assert/strict";
import { requireSuccess } from "../lib/local-runtime.js";

export function configuration() {
  return JSON.parse(requireSuccess(["docker", "compose", "-f", "docker-compose.local.yml", "config", "--format", "json"], 30000,
    { ...process.env, LOCAL_DB_PASSWORD: "test-only", LOCAL_NOTICE_KEY: "test-only", LOCAL_RENDER_KEY: "test-only", LOCAL_CAMPAIGN_SHA: "test-only", RTB_LOCAL_STATE: "/tmp/rtb-compose-test" }));
}
test("databases have isolated volumes, readiness checks and no published ports", () => {
  const c = configuration();
  for (const name of ["provider-store", "ledger-store", "outcome-store"]) {
    assert.equal(c.services[name].ports, undefined);
    assert.ok(c.services[name].healthcheck);
    assert.equal(c.services[name].volumes[0].type, "volume");
  }
});

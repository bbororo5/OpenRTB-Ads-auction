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
test("application topology uses internal DNS and loopback ingress", () => {
  const { services } = configuration();
  assert.match(services.ssp.environment.DSP_ENDPOINTS, /support:8080/);
  assert.equal(services.support.environment.DSP_BASE_URL, "http://dsp:8081");
  assert.equal(services.ssp.ports[0].host_ip, "127.0.0.1");
  assert.equal(services.dsp.ports, undefined);
});
test("shared observability uses the same project network and loopback ports", () => {
  const { services } = configuration();
  for (const name of ["grafana", "tempo", "loki", "prometheus", "pyroscope", "otel-collector"]) {
    assert.ok(services[name]);
    for (const port of services[name].ports ?? []) assert.equal(port.host_ip, "127.0.0.1");
  }
  assert.match(services.grafana.volumes[0].source, /observability\/grafana\/provisioning$/);
});

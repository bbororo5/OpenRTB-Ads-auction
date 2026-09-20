import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const root = new URL("../../../observability/grafana/provisioning/", import.meta.url);
const dashboard = JSON.parse(readFileSync(new URL("dashboards/json/rtb-observation.json", root), "utf8"));

test("observation dashboard is provisioned read-only with the existing datasource", () => {
  const provider = readFileSync(new URL("dashboards/rtb.yaml", root), "utf8");
  const sources = readFileSync(new URL("datasources/datasources.yaml", root), "utf8");
  assert.match(provider, /editable: false/);
  assert.match(provider, /path: \/etc\/grafana\/provisioning\/dashboards\/json/);
  assert.match(sources, /uid: prometheus/);
  assert.equal(dashboard.uid, "rtb-observation");
  assert.equal(dashboard.editable, false);
  assert.equal(new Set(dashboard.panels.map((p: { id: number }) => p.id)).size, dashboard.panels.length);
  for (const panel of dashboard.panels.filter((p: { type: string }) => p.type !== "text")) {
    assert.equal(panel.datasource.uid, "prometheus");
    assert.ok(panel.targets.every((t: { expr: string }) => t.expr.length > 0));
  }
});

test("dashboard keeps infrastructure availability distinct from auction success", () => {
  const help = dashboard.panels.find((p: { type: string }) => p.type === "text").options.content;
  assert.match(help, /HTTP 200 is not proof/);
  assert.match(help, /missing data as zero/);
  const queries = dashboard.panels.flatMap((p: { targets?: { expr: string }[] }) => p.targets ?? []).map((t: { expr: string }) => t.expr);
  assert.ok(queries.includes('up{job="stage8c-hosts"}'));
  assert.ok(queries.some((q: string) => q.includes('service_name="rtb-ssp"')));
  assert.ok(queries.some((q: string) => q.includes('service_name="rtb-dsp"')));
  assert.ok(queries.some((q: string) => q.includes('http_response_status_code=~"5.."')));
  assert.ok(queries.some((q: string) => q.includes("histogram_quantile(0.99")));
  assert.ok(queries.some((q: string) => q.includes("jvm_gc_duration_seconds_count")));
  assert.match(help, /including notices/);
  assert.match(help, /NOT the k6 SLO/);
});

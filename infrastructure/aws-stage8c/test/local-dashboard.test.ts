import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { root } from "../lib/local-runtime.js";
export const dashboard = JSON.parse(readFileSync(resolve(root, "observability/grafana/provisioning/dashboards/json/rtb-observation.json"), "utf8"));
test("dashboard only offers known local and AWS scrape jobs", () => {
  assert.match(dashboard.templating.list[0].query, /stage8c-hosts\|otel-collector/);
  assert.match(dashboard.panels.find((p: any) => p.id === 2).targets[0].expr, /\$scrape_job/);
});
test("CPU panel explains shared Linux VM scope", () => {
  const panel = dashboard.panels.find((p: any) => p.id === 5);
  assert.match(panel.description, /neither per-service CPU nor macOS/);
  assert.match(panel.targets[0].expr, /\$scrape_job/);
});
test("all application panels filter the selected collection environment", () => {
  for (const panel of dashboard.panels.filter((p: any) => [3, 4, 6, 7, 8, 9].includes(p.id))) {
    assert.match(panel.targets[0].expr, /job="\$scrape_job"/);
    assert.match(panel.targets[0].expr, /service_name=/);
  }
});
test("dashboard separates client evidence, scrape status and application health", () => {
  const text = JSON.stringify(dashboard);
  assert.match(text, /NOT application health/);
  assert.match(text, /No data means no matching series/);
  assert.match(text, /NOT k6 p99/);
});
test("both configurations resolve the dashboard variable without mixing environments", () => {
  for (const [file, job] of [["local.yaml", "otel-collector"], ["aws-stage8c.yaml", "stage8c-hosts"]]) {
    const config = readFileSync(resolve(root, "observability/prometheus", file!), "utf8");
    assert.ok(config.includes(`job_name: ${job}`));
    for (const panel of dashboard.panels) for (const target of panel.targets ?? []) {
      const query = target.expr.replaceAll("$scrape_job", job);
      assert.ok(query.includes(`job="${job}"`));
      assert.ok(!query.includes("$"));
    }
  }
});

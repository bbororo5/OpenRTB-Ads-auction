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

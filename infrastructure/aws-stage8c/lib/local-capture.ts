import { retrieveTrace, jsonRequest, metricSeries } from "./local-telemetry.js";
import { type RequestEvidence } from "./request-evidence.js";
import { save } from "./local-evidence.js";

export const metricQueries = {
  memory: 'sum by (service_name) (jvm_memory_used_bytes{job="otel-collector",service_name=~"rtb-ssp|rtb-dsp"})',
  gc: 'sum by (service_name,jvm_gc_name) (jvm_gc_duration_seconds_count{job="otel-collector",service_name=~"rtb-ssp|rtb-dsp"})',
  cpu: 'sum by (node_role) (rate(system_cpu_time_seconds_total{job="otel-collector",state!="idle"}[1m]))',
  http: 'sum by (service_name,http_route,http_response_status_code) (http_server_request_duration_seconds_count{job="otel-collector",service_name=~"rtb-ssp|rtb-dsp"})',
  serverP99: 'histogram_quantile(0.99, sum by (le,service_name,http_route) (rate(http_server_request_duration_seconds_bucket{job="otel-collector",service_name=~"rtb-ssp|rtb-dsp"}[1m]))) * 1000',
};
export async function capture(directory: string, selected: RequestEvidence[], start: string,
  endpoints = { tempo: "http://127.0.0.1:3200", prometheus: "http://127.0.0.1:9090" }) {
  const traces: any[] = [];
  // Selected samples only; one delayed trace cannot starve the whole run.
  await Promise.all(selected.map(async request => {
    try { traces.push({ request, state: "retrieved", trace: await retrieveTrace(request.traceId, endpoints.tempo) }); }
    catch (error) { traces.push({ request, state: "unavailable", error: String(error) }); }
    save(directory, "traces.json", traces);
  }));
  const end = new Date().toISOString();
  const metrics: Record<string, unknown> = {};
  await Promise.all(Object.entries(metricQueries).map(async ([name, query]) => {
    try {
      const parameters = new URLSearchParams({ query, start, end, step: "5s" });
      const data = await jsonRequest(`${endpoints.prometheus}/api/v1/query_range?${parameters}`);
      metricSeries(data); metrics[name] = { query, data };
    } catch (error) { metrics[name] = { query, error: String(error) }; }
    save(directory, "metrics.json", { start, end, metrics });
  }));
  return { traces, metrics, complete: traces.length > 0 && traces.every(t => t.state === "retrieved")
    && Object.values(metrics).every((m: any) => !m.error) };
}

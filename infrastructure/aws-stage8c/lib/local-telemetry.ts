import { waitFor } from "./local-session.js";
import { traceSpans } from "./request-evidence.js";

export async function jsonRequest(url: string, timeout = 5000): Promise<any> {
  const response = await fetch(url, { signal: AbortSignal.timeout(timeout) });
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${new URL(url).pathname}`);
  return response.json();
}
export function metricSeries(data: any): any[] {
  if (data.status !== "success" || !Array.isArray(data.data?.result) || !data.data.result.length)
    throw new Error("Metric missing; empty is not zero");
  return data.data.result;
}
export async function verifyMetrics(base = "http://127.0.0.1:9090", timeout = 45000) {
  const result: Record<string, unknown> = {};
  for (const service of ["rtb-ssp", "rtb-dsp"]) {
    for (const [kind, query] of Object.entries({
      jvm: `jvm_memory_used_bytes{service_name="${service}"}`,
      http: `{__name__=~"http_server_request_duration_seconds_count|http_server_duration_milliseconds_count",service_name="${service}"}`,
    })) {
      await waitFor(async () => {
        result[`${service}-${kind}`] = metricSeries(await jsonRequest(`${base}/api/v1/query?query=${encodeURIComponent(query)}`));
        return true;
      }, timeout);
    }
  }
  return result;
}
export async function retrieveTrace(traceId: string, base = "http://127.0.0.1:3200", timeout = 15000) {
  if (!/^[a-f0-9]{32}$/.test(traceId)) throw new Error("Invalid trace ID");
  let trace: any;
  await waitFor(async () => {
    trace = await jsonRequest(`${base}/api/traces/${traceId}`);
    traceSpans(trace, traceId);
    return true;
  }, timeout);
  return trace;
}
export function logStreams(data: any) {
  if (data.status !== "success" || !data.data?.result?.some((s: any) => s.values?.length))
    throw new Error("No log records observed");
  return data.data.result;
}
export async function verifySyntheticLogs(base = "http://127.0.0.1:3100", timeout = 30000) {
  let streams: any;
  await waitFor(async () => {
    streams = logStreams(await jsonRequest(`${base}/loki/api/v1/query_range?query=${encodeURIComponent('{service_name="telemetrygen"}')}&limit=10`));
    return true;
  }, timeout);
  return { source: "synthetic-telemetrygen-not-application", streams };
}
export async function backendStatus(ports: Record<string, number> = {}) {
  const targets: Record<string, [number, string]> = {
    grafana: [ports.grafana ?? 3000, "/api/health"], prometheus: [ports.prometheus ?? 9090, "/-/ready"],
    tempo: [ports.tempo ?? 3200, "/ready"], loki: [ports.loki ?? 3100, "/ready"],
    pyroscope: [ports.pyroscope ?? 4040, "/ready"], collector: [ports.collector ?? 13133, "/"],
  };
  return Object.fromEntries(await Promise.all(Object.entries(targets).map(async ([name, [port, path]]) => {
    try {
      const response = await fetch(`http://127.0.0.1:${port}${path}`, { signal: AbortSignal.timeout(2000) });
      return [name, { state: response.ok ? "ready" : "unhealthy", httpStatus: response.status }];
    } catch { return [name, { state: "unreachable" }]; }
  })));
}

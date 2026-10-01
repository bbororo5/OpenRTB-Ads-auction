import { waitFor } from "./local-session.js";

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

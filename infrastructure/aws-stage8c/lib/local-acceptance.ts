import { traceSpans } from "./request-evidence.js";
import { backendStatus, verifyMetrics, verifySyntheticLogs, jsonRequest } from "./local-telemetry.js";
import { LocalSession, waitFor } from "./local-session.js";
import { runLoad, save } from "./local-evidence.js";

export function connectedApplicationTrace(traces: any[]): boolean {
  return traces.some(t => {
    if (t.state !== "retrieved") return false;
    const spans = traceSpans(t.trace, t.request.traceId);
    return spans.some((s: any) => s.service === "rtb-ssp") && spans.some((s: any) => s.service === "rtb-dsp");
  });
}
export async function acceptance(session: LocalSession) {
  await waitFor(async () => Object.values(await backendStatus(session.ports)).every((v: any) => v.state === "ready"), 60000);
  const result = await runLoad(session, "observe");
  const checks: Record<string, unknown> = { environmentReady: true, evidenceComplete: result.collected.complete,
    applicationExitCode: result.code, correlatedSspDspTrace: connectedApplicationTrace(result.collected.traces) };
  try { checks.metrics = await verifyMetrics(session.endpoint("prometheus", 9090)); }
  catch (error) { checks.metricsError = String(error); }
  try {
    if (await session.execute(["run", "--rm", "--no-deps", "telemetrygen-logs"], 90000)) throw new Error("Synthetic log generator failed");
    checks.syntheticLogs = await verifySyntheticLogs(session.endpoint("loki", 3100));
  } catch (error) { checks.logsError = String(error); }
  try {
    const dashboard = await jsonRequest(`${session.endpoint("grafana", 3000)}/api/dashboards/uid/rtb-observation`);
    const queries = dashboard.dashboard.panels.flatMap((p: any) => p.targets ?? []).map((t: any) => t.expr.replaceAll("$scrape_job", "otel-collector"));
    checks.dashboardQueries = await Promise.all(queries.map(async (query: string) => {
      const data = await jsonRequest(`${session.endpoint("prometheus", 9090)}/api/v1/query?query=${encodeURIComponent(query)}`);
      if (data.status !== "success") throw new Error("Dashboard query rejected");
      return { query, series: data.data.result.length, note: "Empty 5xx series can mean no 5xx observed; not inferred zero" };
    }));
  } catch (error) { checks.dashboardError = String(error); }
  const infrastructurePassed = checks.evidenceComplete === true && checks.correlatedSspDspTrace === true
    && !checks.metricsError && !checks.logsError && !checks.dashboardError;
  save(result.directory, "acceptance.json", { ...checks, infrastructurePassed, at: new Date().toISOString() });
  return { result, infrastructurePassed };
}

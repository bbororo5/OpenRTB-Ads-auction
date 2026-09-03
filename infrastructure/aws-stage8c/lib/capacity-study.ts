export interface Observation {
  requests: number;
  p99Ms: number;
  errorRate: number;
  invalid: number;
  dropped: number;
  projectWinRate: number;
  projectWins: number;
  statuses: Record<string, number>;
  passed: boolean;
}

// k6 1.2.1 --summary-export has flat metrics; threshold booleans mean FAILED.
export function readObservation(raw: any, rps: number, seconds: number): Observation {
  const metric = (name: string, key: string): number => {
    const value = raw?.metrics?.[name]?.[key];
    if (typeof value !== "number" || !Number.isFinite(value)) throw new Error(`Missing k6 metric ${name}.${key}`);
    return value;
  };
  const requests = metric("http_reqs", "count");
  const invalid = metric("stage8c_invalid_auctions", "count");
  const noValidAuctions = requests > 0 && invalid === requests;
  const value = {
    requests,
    p99Ms: metric("http_req_duration", "p(99)"),
    errorRate: metric("http_req_failed", "value"),
    invalid,
    dropped: metric("dropped_iterations", "count"),
    projectWinRate: noValidAuctions ? 0 : metric("stage8c_project_dsp_win_rate", "value"),
    projectWins: noValidAuctions ? 0 : metric("stage8c_project_dsp_win_rate", "passes"),
    statuses: Object.fromEntries([0, 200, 400, 401, 500, 503, 504].map(status =>
      [String(status), raw.metrics[`stage8c_http_statuses{status:${status}}`]?.count ?? 0])),
  };
  return { ...value, passed: value.requests >= rps * seconds * 0.99 && value.p99Ms <= 50
    && value.errorRate === 0 && value.invalid === 0 && value.dropped === 0
    && value.projectWinRate >= 0.20 && value.projectWinRate <= 0.28 };
}

export async function capacityStudy(
  trial: (label: string, rps: number) => Promise<Observation>,
): Promise<{ phases: Array<{ label: string; rps: number } & Observation>;
  lastPassingRps: number | null; firstFailingRps: number | null; lowestFailingRps: number | null; ceilingReached: boolean; repeatPassed: boolean | null }> {
  const phases: Array<{ label: string; rps: number } & Observation> = [];
  let low: number | null = null, high: number | null = null, repeatPassed: boolean | null = null;
  const run = async (label: string, rps: number) => {
    const result = await trial(label, rps);
    phases.push({ label, rps, ...result });
    return result;
  };
  for (const rps of [10, 25, 50, 100, 200, 400, 800]) {
    const result = await run(`ramp-${rps}`, rps);
    if (!result.passed) { high = rps; break; }
    low = rps;
  }
  const firstFailure = high;
  // A noisy/non-monotonic boundary is reported, never hidden by retry-until-green.
  if (low !== null && high !== null) {
    for (let attempt = 0; attempt < 2 && high - low > 1; attempt++) {
      const middle = Math.floor((low + high) / 2);
      if ((await run(`refine-${attempt + 1}-${middle}`, middle)).passed) low = middle;
      else high = middle;
    }
    repeatPassed = (await run(`repeat-${low}`, low)).passed;
  }
  await run("recovery-10", 10);
  return { phases, lastPassingRps: low, firstFailingRps: firstFailure, lowestFailingRps: high, ceilingReached: high === null, repeatPassed };
}

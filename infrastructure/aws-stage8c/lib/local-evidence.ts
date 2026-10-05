import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { LocalSession } from "./local-session.js";
import { command } from "./local-runtime.js";
import { analyzeRequestJournal, evidenceHtml } from "./request-evidence.js";
import { capture } from "./local-capture.js";
import { diagnostics } from "./local-diagnostics.js";
import { save } from "./local-artifacts.js";
export { save } from "./local-artifacts.js";

export const loadSettings = { RPS: 10, preAllocatedVUs: 20, maxVUs: 100 } as const;
export function recordCaptureFailure(directory: string, error: unknown) {
  save(directory, "manifest.json", { complete: false, error: String(error), at: new Date().toISOString() });
}
function snapshot(session: LocalSession, directory: string, name: string) {
  try { save(directory, name, diagnostics(session)); }
  catch (error) { save(directory, name, { incomplete: true, error: String(error) }); }
}
export async function runLoad(session: LocalSession, mode: "smoke" | "observe") {
  const id = `${mode}-${Date.now()}-${randomUUID().slice(0, 8)}`;
  const directory = resolve(session.directory, "runs", id);
  mkdirSync(directory, { recursive: true });
  snapshot(session, directory, "pre-runtime.json");
  const startedAt = new Date().toISOString();
  save(directory, "run.json", { id, startedAt, project: session.project, mode, ...loadSettings,
    duration: mode === "smoke" ? "10s" : "60s", source: command(["git", "rev-parse", "HEAD"]).stdout.trim(),
    trackedChanges: command(["git", "status", "--porcelain", "--untracked-files=no"]).stdout.trim() });
  let code = 1;
  try {
  code = await session.execute(["run", "--rm", "--no-deps", "-e", `DURATION=${mode === "smoke" ? "10s" : "60s"}`,
    "-e", "REQUEST_EVIDENCE=true", "-e", `K6_CONSOLE_OUTPUT=/results/${id}/requests.log`,
    "k6", "run", `--summary-export=/results/${id}/summary.json`, "/scripts/stage8c-capacity.js"], 120000);
  save(directory, "result.json", { code, finishedAt: new Date().toISOString(), interpretation: "Original k6 thresholds, not AWS capacity certification" });
  snapshot(session, directory, "post-runtime.json");
  const summary = JSON.parse(readFileSync(resolve(directory, "summary.json"), "utf8"));
  const journal = readFileSync(resolve(directory, "requests.log"), "utf8");
  const analysis = analyzeRequestJournal(journal, summary);
  save(directory, "requests.json", analysis.records);
  writeFileSync(resolve(directory, "review.md"), analysis.markdown);
  const collected = await capture(directory, analysis.selected, startedAt,
    { tempo: session.endpoint("tempo", 3200), prometheus: session.endpoint("prometheus", 9090) });
  writeFileSync(resolve(directory, "review.html"), evidenceHtml(analysis.records, summary, collected.traces,
    { ...JSON.parse(readFileSync(resolve(directory, "run.json"), "utf8")), evidenceComplete: collected.complete, k6ExitCode: code }));
  save(directory, "manifest.json", { complete: collected.complete, requestCount: analysis.records.length,
    selectedTraces: analysis.selected.length, retrievedTraces: collected.traces.filter(t => t.state === "retrieved").length,
    scope: "Request journal, at most 10 selected traces, five metric windows. Not a complete log/profile archive.",
    runMetadata: "run.json", result: "result.json" });
  return { directory, summary, code, startedAt, analysis, collected };
  } catch (error) {
    recordCaptureFailure(directory, error);
    snapshot(session, directory, "failure-runtime.json");
    throw new Error(`Local run incomplete; k6 code=${code}; evidence=${directory}; ${String(error)}`);
  }
}

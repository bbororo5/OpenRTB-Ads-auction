import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { randomUUID } from "node:crypto";
import { LocalSession } from "./local-session.js";
import { command } from "./local-runtime.js";
import { analyzeRequestJournal, evidenceHtml } from "./request-evidence.js";
import { capture } from "./local-capture.js";
import { diagnostics } from "./local-diagnostics.js";

export const loadSettings = { RPS: 10, preAllocatedVUs: 20, maxVUs: 100 } as const;
export function save(directory: string, name: string, data: unknown) {
  writeFileSync(resolve(directory, name), JSON.stringify(data, null, 2) + "\n", { mode: 0o600 });
}
export async function runLoad(session: LocalSession, mode: "smoke" | "observe") {
  const id = `${mode}-${Date.now()}-${randomUUID().slice(0, 8)}`;
  const directory = resolve(session.directory, "runs", id);
  mkdirSync(directory, { recursive: true });
  save(directory, "pre-runtime.json", diagnostics(session));
  const startedAt = new Date().toISOString();
  save(directory, "run.json", { id, startedAt, project: session.project, mode, ...loadSettings,
    duration: mode === "smoke" ? "10s" : "60s", source: command(["git", "rev-parse", "HEAD"]).stdout.trim(),
    trackedChanges: command(["git", "status", "--porcelain", "--untracked-files=no"]).stdout.trim() });
  const code = await session.execute(["run", "--rm", "--no-deps", "-e", `DURATION=${mode === "smoke" ? "10s" : "60s"}`,
    "-e", "REQUEST_EVIDENCE=true", "-e", `K6_CONSOLE_OUTPUT=/results/${id}/requests.log`,
    "k6", "run", `--summary-export=/results/${id}/summary.json`, "/scripts/stage8c-capacity.js"], 120000);
  save(directory, "result.json", { code, finishedAt: new Date().toISOString(), interpretation: "Original k6 thresholds, not AWS capacity certification" });
  save(directory, "post-runtime.json", diagnostics(session));
  const summary = JSON.parse(readFileSync(resolve(directory, "summary.json"), "utf8"));
  const journal = readFileSync(resolve(directory, "requests.log"), "utf8");
  const analysis = analyzeRequestJournal(journal, summary);
  save(directory, "requests.json", analysis.records);
  writeFileSync(resolve(directory, "review.md"), analysis.markdown);
  const collected = await capture(directory, analysis.selected, startedAt);
  writeFileSync(resolve(directory, "review.html"), evidenceHtml(analysis.records, summary, collected.traces));
  save(directory, "manifest.json", { complete: collected.complete, requestCount: analysis.records.length,
    selectedTraces: analysis.selected.length, retrievedTraces: collected.traces.filter(t => t.state === "retrieved").length,
    scope: "Request journal, at most 10 selected traces, five metric windows. Not a complete log/profile archive.",
    runMetadata: "run.json", result: "result.json" });
  return { directory, summary, code, startedAt, analysis, collected };
}

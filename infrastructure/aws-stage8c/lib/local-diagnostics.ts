import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { command } from "./local-runtime.js";
import { LocalSession } from "./local-session.js";

export function redact(text: string, secrets: string[]) {
  let safe = text;
  for (const secret of secrets.filter(s => s.length > 5)) safe = safe.split(secret).join("[REDACTED]");
  return safe.replace(/((?:renderProof|authorization|password|token|providerKeyId)["']?\s*[:=]\s*["']?)[^\s,"'}]+/gi, "$1[REDACTED]").slice(-128000);
}
export function diagnostics(session: LocalSession) {
  const at = new Date().toISOString();
  const secrets = readFileSync(resolve(session.directory, "runtime.env"), "utf8").split("\n").map(s => s.slice(s.indexOf("=") + 1));
  const containers = session.status();
  const ids = containers.map((c: any) => c.ID).filter(Boolean);
  const stats = ids.length ? command(["docker", "stats", "--no-stream", "--format", "{{json .}}", ...ids], 15000) : null;
  const states = ids.length ? command(["docker", "inspect", "--format", "{{json .State}}", ...ids]) : null;
  const logs = command(session.args(["logs", "--no-color", "--tail", "100", "--since", "5m"]), 15000, session.env);
  return { at, containers, stats, states, logs: { code: logs.code, text: redact(logs.stdout + logs.stderr, secrets),
    scope: "Last 100 lines per service in last 5 minutes; redacted and capped at 128k characters" } };
}

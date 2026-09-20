// Local integration check only. No AWS and no real auction service.
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { mkdtempSync, chmodSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseRequestJournal } from "../lib/request-evidence.js";

const folder = mkdtempSync(path.join(tmpdir(), "rtb-journal-check-"));
chmodSync(folder, 0o777); // disposable output directory for the unprivileged k6 container
const ids = new Set<string>();
let count = 0;
const server = createServer((request, response) => {
  const parent = String(request.headers.traceparent);
  if (!/^00-[a-f0-9]{32}-[a-f0-9]{16}-01$/.test(parent)) {
    response.writeHead(400).end(); return;
  }
  ids.add(parent.split("-")[1]!);
  request.resume();
  count++;
  response.setHeader("Content-Type", "application/json");
  if (count % 5 === 0) response.writeHead(504).end('{}');
  else response.end(JSON.stringify({ auctionId: "fixture", slots: [{ impId: "imp-1", cpmKrw: 1,
    renderProof: "fixture-not-a-secret", dspId: "project-dsp" }] }));
});
try {
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as { port: number }).port;
  const scripts = fileURLToPath(new URL("../../../performance/k6/", import.meta.url));
  const child = spawn("docker", ["run", "--rm", "--cap-drop=ALL", "--security-opt=no-new-privileges",
    "-v", `${scripts}:/scripts:ro`, "-v", `${folder}:/results`,
    "-e", `BASE_URL=http://host.docker.internal:${port}`, "-e", "RPS=10", "-e", "DURATION=2s",
    "-e", "PRE_ALLOCATED_VUS=10", "-e", "MAX_VUS=10", "-e", "REQUEST_EVIDENCE=true",
    "-e", "K6_CONSOLE_OUTPUT=/results/requests.log", "grafana/k6:1.2.1", "run",
    "--summary-export=/results/summary.json", "/scripts/stage8c-capacity.js"], { stdio: "pipe" });
  let output = "";
  child.stdout.on("data", chunk => { output += chunk; });
  child.stderr.on("data", chunk => { output += chunk; });
  const code = await new Promise<number | null>((resolve, reject) => { child.on("error", reject); child.on("exit", resolve); });
  assert.equal(code, 99, output); // fixture deliberately violates error/win thresholds
  const summary = JSON.parse(readFileSync(path.join(folder, "summary.json"), "utf8"));
  const journal = readFileSync(path.join(folder, "requests.log"), "utf8");
  const records = parseRequestJournal(journal, summary.metrics.http_reqs.count);
  assert.ok(records.length >= 19);
  assert.equal(records.length, count);
  assert.equal(records.filter(r => r.status === 504).length, Math.floor(count / 5));
  assert.ok(records.every(r => ids.has(r.traceId)));
  assert.doesNotMatch(journal, /fixture-not-a-secret|providerKeyId|renderProof/);
  console.log(`PASS: k6 1.2.1, ${records.length} correlated requests, ${records.filter(r => r.status === 504).length} retained failures; no payload logging.`);
} finally {
  server.closeAllConnections();
  await new Promise<void>(resolve => server.close(() => resolve()));
  rmSync(folder, { recursive: true, force: true });
}

import test from "node:test";
import assert from "node:assert/strict";
import { withCleanup } from "../lib/local-lifecycle.js";
import { LocalSession, waitFor } from "../lib/local-session.js";
import { jsonRequest, retrieveTrace } from "../lib/local-telemetry.js";
import http from "node:http";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

test("startup or load failure always invokes cleanup and preserves failure", async () => {
  let calls = 0;
  await assert.rejects(withCleanup(async () => { throw new Error("load failed"); }, () => { calls++; }), /load failed/);
  assert.equal(calls, 1);
  await assert.rejects(withCleanup(async () => { throw new Error("startup failed"); }, () => { throw new Error("cleanup failed"); }), AggregateError);
  assert.throws(() => new LocalSession().down(true), /restricted/);
});
test("delayed/unavailable telemetry remains bounded and is not success", async () => {
  const server = http.createServer((_req, res) => { res.writeHead(503); res.end(); });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  const base = `http://127.0.0.1:${(server.address() as any).port}`;
  try {
    await assert.rejects(jsonRequest(base, 100), /503/);
    await assert.rejects(retrieveTrace("a".repeat(32), base, 5), /timed out/);
    await assert.rejects(waitFor(async () => false, 5, 1), /timed out/);
  } finally { server.close(); }
});
test("SIGTERM executes cleanup once before exiting 143", async () => {
  const module = new URL("../lib/local-lifecycle.ts", import.meta.url).href;
  const child = spawn(process.execPath, ["--import", "tsx", "--input-type=module", "-e",
    `import {installSignalCleanup} from ${JSON.stringify(module)}; installSignalCleanup(()=>console.log('cleaned')); console.log('ready'); setInterval(()=>{},1000);`],
    { cwd: fileURLToPath(new URL("../", import.meta.url)), stdio: ["ignore", "pipe", "pipe"] });
  let output = "";
  const timer = setTimeout(() => child.kill("SIGKILL"), 5000);
  child.stdout.on("data", chunk => { output += chunk; if (output.includes("ready") && !output.includes("cleaned")) child.kill("SIGTERM"); });
  try {
    const code = await new Promise<number | null>((resolve, reject) => { child.on("error", reject); child.on("close", resolve); });
    assert.equal(code, 143); assert.equal(output.match(/cleaned/g)?.length, 1);
  } finally { clearTimeout(timer); child.kill("SIGKILL"); }
});

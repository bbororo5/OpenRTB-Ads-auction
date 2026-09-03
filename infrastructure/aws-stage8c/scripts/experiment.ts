import { createHash, randomUUID } from "node:crypto";
import { execFileSync, spawn } from "node:child_process";
import { readFileSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { runExperiment } from "../lib/experiment-lifecycle.js";
import { capacityStudy, readObservation } from "../lib/capacity-study.js";
import { reaperName, ruleName, executionRole, bucketName } from "../lib/experiment-control-stack.js";

const account = "333982363617", region = "ap-northeast-2";
const directory = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const [command = "help", ...args] = process.argv.slice(2);
const runId = args.find(value => value.startsWith("--run-id="))?.slice(9) ?? `rtb-${randomUUID()}`;
const expiresAt = new Date(Date.now() + 40 * 60_000).toISOString();
const evidenceDirectory = path.resolve(directory, "../../docs/evidence/performance", new Date().toISOString().slice(0, 10));
const summary: Record<string, unknown> = { runId, startedAt: new Date().toISOString() };
let cancelled = false;
let interrupt: (() => void) | undefined;
for (const signal of ["SIGINT", "SIGTERM"] as const) process.on(signal, () => { cancelled = true; interrupt?.(); });

function aws(arguments_: string[]): any {
  const output = execFileSync("aws", [...arguments_, "--region", region, "--output", "json", "--no-cli-pager",
    "--cli-connect-timeout", "10", "--cli-read-timeout", "130"], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  return JSON.parse(output || "null");
}
function getStack(name: string): any | undefined {
  try { return aws(["cloudformation", "describe-stacks", "--stack-name", name]).Stacks?.[0]; }
  catch (error) {
    const stderr = error && typeof error === "object" && "stderr" in error ? String(error.stderr) : "";
    if (stderr.includes("does not exist")) return undefined;
    throw error;
  }
}
function tags(stack: any): Record<string, string> {
  return Object.fromEntries((stack?.Tags ?? []).map((tag: any) => [tag.Key, tag.Value]));
}
const pause = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));
function checkCancellation(): void { if (cancelled) throw new Error("Experiment interrupted"); }
function createMarker(name: string, markerRunId: string, expiry: string): string {
  return aws(["cloudformation", "create-stack", "--stack-name", name,
    "--role-arn", `arn:aws:iam::${account}:role/${executionRole}`,
    "--template-body", JSON.stringify({ AWSTemplateFormatVersion: "2010-09-09", Resources: { Marker: { Type: "AWS::CloudFormation::WaitConditionHandle" } } }),
    "--tags", `Key=Project,Value=low-latency-rtb`, `Key=Stage,Value=8c`, `Key=RunId,Value=${markerRunId}`, `Key=ExpiresAt,Value=${expiry}`,
  ]).StackId;
}

async function safetyCheck(): Promise<void> {
  const rule = aws(["events", "describe-rule", "--name", ruleName]);
  const targets = aws(["events", "list-targets-by-rule", "--rule", ruleName]).Targets;
  const fn = aws(["lambda", "get-function-configuration", "--function-name", reaperName]);
  const digest = createHash("sha256").update(readFileSync(path.join(directory, "runtime/reaper.cjs"))).digest("hex");
  if (rule.State !== "ENABLED" || rule.ScheduleExpression !== "rate(1 minute)"
    || fn.State !== "Active" || fn.LastUpdateStatus !== "Successful" || fn.Description !== `reaper-sha256:${digest}`
    || !targets.some((target: any) => target.Arn === fn.FunctionArn)) throw new Error("Installed reaper does not match the verified control plane");
  if (getStack("RtbStage8cSafetyCanary")) throw new Error("Previous canary exists; wait for independent cleanup");
  const id = createMarker("RtbStage8cSafetyCanary", runId, new Date(Date.now() + 15_000).toISOString());
  console.log("Waiting for EventBridge → Lambda to delete the zero-compute canary (not invoking cleanup manually).");
  const limit = Date.now() + 240_000;
  while (Date.now() < limit) {
    checkCancellation();
    // Query immutable stack ID: a missing name during creation is not proof of deletion.
    const state = getStack(id);
    if (state?.StackStatus === "DELETE_COMPLETE") {
      summary.safetyCanary = { id, status: "DELETE_COMPLETE" };
      console.log("Independent scheduled cleanup verified.");
      // Distinct fencing token: delayed cleanup from this probe cannot own a later run.
      const probeId = `rtb-probe-${randomUUID()}`;
      try {
        createMarker("RtbStage8cLease", probeId, new Date(Date.now() + 5 * 60_000).toISOString());
        aws(["s3api", "put-object", "--bucket", bucketName(account, region), "--key", `${probeId}/cleanup-probe.cjs`,
          "--body", path.join(directory, "runtime/reaper.cjs")]);
      } finally {
        await cleanup(probeId);
      }
      summary.safetyImmediateCleanup = { runId: probeId, status: "complete" };
      return;
    }
    await pause(5000);
  }
  throw new Error("Independent cleanup did not pass within 4 minutes; deployment refused");
}

async function invokeCleanup(targetRunId: string): Promise<any> {
  const file = path.join(tmpdir(), `rtb-reaper-${randomUUID()}.json`);
  // Lambda response is operational status only, never credentials.
  const result = aws(["lambda", "invoke", "--function-name", reaperName, "--cli-binary-format", "raw-in-base64-out",
    "--payload", JSON.stringify({ mode: "cleanup", runId: targetRunId }), file]);
  const payload = JSON.parse(readFileSync(file, "utf8"));
  if (result.FunctionError) throw new Error(`Reaper failed: ${payload.errorMessage ?? result.FunctionError}`);
  return payload;
}
async function cleanup(targetRunId = runId): Promise<void> {
  const limit = Date.now() + 10 * 60_000;
  while (Date.now() < limit) {
    const lease = getStack("RtbStage8cLease");
    if (!lease) {
      const workload = getStack("RtbStage8c");
      if (workload && tags(workload).RunId === targetRunId) throw new Error("Workload remains without its lease");
      summary.cleanup = "complete";
      console.log("Cleanup complete: owned lease released after resource and asset checks.");
      return;
    }
    if (tags(lease).RunId !== targetRunId) throw new Error("Refusing to clean another run's lease");
    console.log(JSON.stringify(await invokeCleanup(targetRunId)));
    await pause(5000);
  }
  throw new Error("Cleanup timeout; lease retained and AWS reaper still responsible");
}
async function stage(arguments_: string[], timeoutMs = 25 * 60_000): Promise<void> {
  checkCancellation();
  await new Promise<void>((resolve, reject) => {
    const child = spawn("npm", ["run", "stage8c", "--", ...arguments_], {
      cwd: directory, stdio: "inherit", detached: true,
      env: { ...process.env, AWS_REGION: region, RTB_RUN_ID: runId, RTB_EXPIRES_AT: expiresAt },
    });
    const kill = (signal: NodeJS.Signals) => { if (child.pid) { try { process.kill(-child.pid, signal); } catch { /* already exited */ } } };
    let escalation: ReturnType<typeof setTimeout> | undefined;
    const stop = () => { kill("SIGTERM"); escalation = setTimeout(() => kill("SIGKILL"), 5000); };
    interrupt = stop;
    const timeout = setTimeout(stop, timeoutMs);
    child.on("error", error => { clearTimeout(timeout); interrupt = undefined; reject(error); });
    child.on("exit", code => {
      clearTimeout(timeout); if (escalation) clearTimeout(escalation); interrupt = undefined;
      if (code === 0 && !cancelled) resolve(); else reject(new Error(`Stage ${arguments_[0]} exited ${code}`));
    });
  });
}
async function ready(): Promise<void> {
  const stack = getStack("RtbStage8c");
  const instances = (stack?.Outputs ?? []).filter((output: any) => output.OutputKey.endsWith("InstanceId")).map((output: any) => output.OutputValue);
  if (instances.length !== 5) throw new Error("Expected five experiment hosts");
  const limit = Date.now() + 10 * 60_000;
  while (Date.now() < limit) {
    checkCancellation();
    const result = aws(["ssm", "describe-instance-information", "--filters", `Key=InstanceIds,Values=${instances.join(",")}`]);
    if (result.InstanceInformationList?.filter((host: any) => host.PingStatus === "Online").length === 5) {
      try { await stage(["observability"], 120_000); return; }
      catch (error) { if (cancelled) throw error; console.log("Hosts not ready yet; bounded retry."); }
    }
    await pause(10_000);
  }
  throw new Error("Hosts did not become ready within 10 minutes");
}

async function measureCapacity(): Promise<void> {
  const limit = Math.min(Date.now() + 20 * 60_000, Date.parse(expiresAt) - 5 * 60_000);
  summary.capacity = await capacityStudy(async (label, rps) => {
    checkCancellation();
    if (Date.now() + 180_000 > limit) throw new Error("Capacity time budget reached; stopping for cleanup");
    const fullLabel = `${runId}-${label}`;
    let stageError: unknown;
    try {
      await stage(["capacity", "--label", fullLabel, "--rps", String(rps), "--duration", "60s",
        "--pre-allocated-vus", "100", "--max-vus", "200", "--sample-seconds", "60"], 180_000);
    } catch (error) { if (cancelled) throw error; stageError = error; }
    const raw = JSON.parse(readFileSync(path.join(evidenceDirectory, `stage8c-aws-${fullLabel}-summary.json`), "utf8"));
    const observed = readObservation(raw, rps, 60);
    const execution = JSON.parse(readFileSync(path.join(evidenceDirectory, `stage8c-aws-${fullLabel}-result.json`), "utf8"));
    if (stageError && (observed.passed || execution.responseCode !== 99)) throw stageError;
    console.log(`CAPACITY ${label} ${JSON.stringify(observed)}`);
    // Preserve each observation immediately, even if a later stage is interrupted.
    mkdirSync(evidenceDirectory, { recursive: true });
    writeFileSync(path.join(evidenceDirectory, `${fullLabel}-observation.json`), JSON.stringify(observed, null, 2));
    return observed;
  });
}

try {
  if (!/^rtb-[a-z0-9-]{1,64}$/.test(runId)) throw new Error("Invalid run ID");
  if (!["safety-check", "run", "capacity", "cleanup"].includes(command)) throw new Error("Use safety-check | run/capacity --ack-cost | cleanup --ack-cost --run-id=rtb-...");
  if (aws(["sts", "get-caller-identity"]).Account !== account) throw new Error("Wrong AWS account");
  if (command !== "safety-check" && !args.includes("--ack-cost")) throw new Error("Require --ack-cost");
  if (command === "cleanup") {
    if (!args.some(value => value.startsWith("--run-id="))) throw new Error("Cleanup requires an explicit run ID");
    await cleanup();
  } else {
    await safetyCheck();
    if (command === "run" || command === "capacity") await runExperiment({
      acquire: async () => {
        checkCancellation();
        if (getStack("RtbStage8c")) throw new Error("Previous workload exists; refusing overlapping deployment");
        createMarker("RtbStage8cLease", runId, expiresAt);
        summary.expiresAt = expiresAt;
      },
      deploy: () => stage(["deploy", "--ack-cost"]),
      verify: async () => {
        await ready();
        // Cold-start warmup is recorded separately, never counted as formal success.
        try { await stage(["smoke", "--label", command === "capacity" ? `${runId}-warmup` : "warmup", "--rps", "10", "--duration", command === "capacity" ? "30s" : "10s"], 240_000); }
        catch (error) { if (cancelled) throw error; summary.warmup = String(error); }
        if (command === "capacity") { await measureCapacity(); return; }
        await stage(["smoke", "--rps", "10", "--duration", "30s"], 240_000);
        summary.smoke = "passed";
      },
      cleanup,
    });
  }
} catch (error) {
  summary.error = String(error);
  console.error(error);
  process.exitCode = 1;
} finally {
  summary.finishedAt = new Date().toISOString();
  mkdirSync(evidenceDirectory, { recursive: true });
  writeFileSync(path.join(evidenceDirectory, `${runId}-${command}.json`), JSON.stringify(summary, null, 2));
}

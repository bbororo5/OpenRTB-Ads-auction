#!/usr/bin/env node
import { randomBytes } from "node:crypto";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

type Outputs = Record<string, string>;

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const infrastructureDirectory = path.resolve(scriptDirectory, "..");
const repositoryRoot = path.resolve(infrastructureDirectory, "../..");
const stackName = "RtbStage8c";
const terminalStatuses = new Set(["Success", "Cancelled", "TimedOut", "Failed"]);

const [command = "help", ...rawArguments] = process.argv.slice(2);
const options = parseOptions(rawArguments);
const region = options.region ?? process.env.AWS_REGION ?? "ap-northeast-2";
const profile = options.profile;
const instanceType = options["instance-type"] ?? "t4g.small";

try {
  await main();
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  process.stderr.write(`stage8c: ${message}\n`);
  process.exitCode = 1;
}

async function main(): Promise<void> {
  switch (command) {
    case "doctor":
      doctor();
      return;
    case "build":
      run("npm", ["run", "build"]);
      run("npm", ["test"]);
      runCdk(["synth"]);
      return;
    case "bootstrap":
      throw new Error("Use npm run experiment-control -- install --ack-cost; the guarded runner does not use CDKToolkit administrator roles.");
    case "diff":
      runCdk(["diff"]);
      return;
    case "deploy":
      requireCostAcknowledgement();
      deploy();
      return;
    case "status":
      await status();
      return;
    case "observability":
      await observabilityStatus();
      return;
    case "grafana-tunnel":
      grafanaTunnel();
      return;
    case "smoke":
      await runLoadTest(options.label ?? "smoke", "stage8c-capacity.js", {
        RPS: options.rps ?? "10",
        DURATION: options.duration ?? "10s",
        PRE_ALLOCATED_VUS: "50",
        MAX_VUS: "100",
      }, 180);
      return;
    case "capacity":
      await runLoadTest("capacity", "stage8c-capacity.js", {
        RPS: options.rps ?? "500",
        DURATION: options.duration ?? "10m",
        PRE_ALLOCATED_VUS: options["pre-allocated-vus"] ?? "1000",
        MAX_VUS: options["max-vus"] ?? "2000",
      }, 900);
      return;
    case "overload":
      await runLoadTest("overload", "stage8c-overload-recovery.js", {
        NORMAL_RPS: options["normal-rps"] ?? "500",
        OVERLOAD_RPS: options["overload-rps"] ?? "1000",
        RECOVERY_RPS: options["recovery-rps"] ?? "100",
        PRE_ALLOCATED_VUS: options["pre-allocated-vus"] ?? "2000",
        MAX_VUS: options["max-vus"] ?? "4000",
      }, 600);
      return;
    case "collect":
      await collectEvidence(options.label ?? "manual");
      return;
    case "destroy":
      throw new Error("Use npm run experiment -- cleanup --ack-cost --run-id=rtb-... so assets and lease are also reclaimed.");
    case "help":
    default:
      usage(command === "help" ? 0 : 1);
  }
}

function doctor(): void {
  run("node", ["--version"]);
  run("docker", ["version", "--format", "Docker {{.Server.Version}}"]);
  run("aws", ["--version"]);
  const identity = awsJson(["sts", "get-caller-identity"]);
  const freeTierEligible = awsJson([
    "ec2", "describe-instance-types",
    "--instance-types", instanceType,
    "--query", "InstanceTypes[0].FreeTierEligible",
  ]);
  process.stdout.write(`${JSON.stringify({
    account: identity.Account,
    arn: identity.Arn,
    region,
    instanceType,
    freeTierEligible,
  }, null, 2)}\n`);
  process.stdout.write(
    "주의: FreeTierEligible=true는 계정의 남은 기간·크레딧·EBS·공인 IPv4 비용을 보장하지 않습니다.\n",
  );
}

function deploy(): void {
  const runId = process.env.RTB_RUN_ID;
  const lease = awsJson(["cloudformation", "describe-stacks", "--stack-name", "RtbStage8cLease"]).Stacks?.[0];
  const tags = Object.fromEntries((lease?.Tags ?? []).map((tag: { Key: string; Value: string }) => [tag.Key, tag.Value]));
  if (!runId || tags.RunId !== runId || tags.Project !== "low-latency-rtb"
    || tags.ExpiresAt !== process.env.RTB_EXPIRES_AT || Date.parse(tags.ExpiresAt) < Date.now() + 300_000) {
    throw new Error("Deployment requires an active owned lease. Use npm run experiment -- run --ack-cost.");
  }
  const databasePassword = randomBytes(24).toString("base64url");
  runCdk([
    "deploy",
    "--require-approval", "never",
    "--outputs-file", "cdk-outputs.json",
    "--parameters", `DatabasePassword=${databasePassword}`,
  ]);
  process.stdout.write(
    "배포 요청이 완료됐습니다. user-data 완료 여부는 `npm run stage8c -- status`로 확인하세요.\n",
  );
}

async function status(): Promise<void> {
  const outputs = stackOutputs();
  const hosts = hostInstances(outputs);
  const information = awsJson([
    "ssm", "describe-instance-information",
    "--filters", `Key=InstanceIds,Values=${Object.values(hosts).join(",")}`,
  ]);
  process.stdout.write(`${JSON.stringify({ outputs, ssm: information.InstanceInformationList }, null, 2)}\n`);

  await Promise.all(Object.entries(hosts).map(async ([role, instanceId]) => {
    try {
      const result = await sendCommand(instanceId, [
        "systemctl is-active docker",
        "cloud-init status --long || true",
        "docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}'",
      ], 60);
      process.stdout.write(`\n[${role}]\n${result.stdout}\n`);
      if (result.stderr) {
        process.stderr.write(`${result.stderr}\n`);
      }
    } catch (error) {
      process.stderr.write(`[${role}] ${String(error)}\n`);
    }
  }));
}

async function observabilityStatus(): Promise<void> {
  const outputs = stackOutputs();
  const hosts = hostInstances(outputs);
  const observerId = requireOutput(outputs, "ObserverInstanceId");
  const hostResults = await Promise.all(Object.entries(hosts).map(async ([role, instanceId]) => ({
    role,
    result: await sendCommand(instanceId, [
      "curl --fail --silent --show-error http://127.0.0.1:13133/",
      "curl --fail --silent --show-error http://127.0.0.1:13134/",
    ], 90),
  })));
  for (const { role, result: hostResult } of hostResults) {
    process.stdout.write(`[${role} collectors] ${hostResult.status}\n`);
    if (hostResult.status !== "Success") {
      throw new Error(`${role} telemetry agents ended with ${hostResult.status}`);
    }
  }
  const result = await sendCommand(observerId, [
    "curl --fail --silent --show-error http://127.0.0.1:13133/",
    "curl --fail --silent --show-error http://127.0.0.1:9090/-/ready",
    "curl --fail --silent --show-error http://127.0.0.1:3200/ready",
    "curl --fail --silent --show-error http://127.0.0.1:3100/ready",
    "curl --fail --silent --show-error http://127.0.0.1:4040/ready",
    "curl --fail --silent --show-error http://127.0.0.1:3000/api/health",
    "curl --fail --silent --show-error http://127.0.0.1:9090/api/v1/targets",
    "curl --fail --silent --show-error http://127.0.0.1:3200/metrics | grep tempo_distributor_spans_received_total || true",
    "curl --fail --silent --show-error http://127.0.0.1:3100/metrics | grep loki_distributor_lines_received_total || true",
    "curl --fail --silent --show-error http://127.0.0.1:4040/metrics | grep pyroscope_distributor_profiles_received_total",
  ], 90);
  process.stdout.write(`${result.stdout}\n`);
  if (result.stderr) {
    process.stderr.write(`${result.stderr}\n`);
  }
  if (result.status !== "Success") {
    throw new Error(`Observability check ended with ${result.status}`);
  }
}

function grafanaTunnel(): void {
  const observerId = requireOutput(stackOutputs(), "ObserverInstanceId");
  run("aws", [
    "ssm", "start-session",
    "--target", observerId,
    "--document-name", "AWS-StartPortForwardingSession",
    "--parameters", JSON.stringify({ portNumber: ["3000"], localPortNumber: ["3000"] }),
  ]);
}

async function runLoadTest(
  label: string,
  script: string,
  environment: Record<string, string>,
  timeoutSeconds: number,
): Promise<void> {
  if (!/^[a-z0-9-]+$/.test(label)) throw new Error("Invalid load-test evidence label");
  const outputs = stackOutputs();
  const loadgenId = requireOutput(outputs, "LoadgenInstanceId");
  const baseUrl = requireOutput(outputs, "SspBaseUrl");
  const envFlags = Object.entries({ BASE_URL: baseUrl, ...environment })
    .flatMap(([name, value]) => ["-e", `${name}=${shellQuote(value)}`])
    .join(" ");
  const remoteCommand = [
    "docker run --rm --network host",
    envFlags,
    "-v /opt/rtb/k6:/scripts:ro",
    "grafana/k6:1.2.1 run",
    `--summary-export=/tmp/${label}-summary.json`,
    `/scripts/${script}`,
  ].join(" ");

  await collectEvidence(`pre-${label}`);
  const startedAt = new Date().toISOString();
  const result = await sendCommand(loadgenId, [remoteCommand], timeoutSeconds);
  const record = [
    `# Stage 8C AWS ${label}`,
    "",
    `- Started at: ${startedAt}`,
    `- Finished at: ${new Date().toISOString()}`,
    `- Region: ${region}`,
    `- Instance type: ${instanceType}`,
    `- SSP: ${baseUrl}`,
    `- Commit: ${gitCommit()}`,
    "",
    "```text",
    result.stdout,
    result.stderr,
    "```",
    "",
  ].join("\n");
  const evidencePath = writeEvidence(`${label}.md`, record);
  process.stdout.write(`${result.stdout}\nEvidence: ${evidencePath}\n`);
  if (result.stderr) {
    process.stderr.write(`${result.stderr}\n`);
  }
  await collectEvidence(`post-${label}`);
  if (result.status !== "Success") {
    throw new Error(`Remote k6 command ended with ${result.status}`);
  }
}

async function collectEvidence(label: string): Promise<void> {
  const outputs = stackOutputs();
  const hosts = hostInstances(outputs);
  const collected = await Promise.all(Object.entries(hosts).map(async ([role, instanceId]) => {
    const command = [
      "date -u +%Y-%m-%dT%H:%M:%SZ",
      "uptime",
      "lscpu | sed -n '1,24p'",
      "free -h",
      "df -hT",
      "ulimit -a",
      "docker stats --no-stream || true",
      "docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}' || true",
    ];
    const result = await sendCommand(instanceId, command, 90);
    const cloudWatch = cloudWatchMetrics(instanceId);
    return { role, instanceId, result, cloudWatch };
  }));
  const content = [
    `# Stage 8C AWS host evidence: ${label}`,
    "",
    `Collected at: ${new Date().toISOString()}`,
    `Commit: ${gitCommit()}`,
    "",
    ...collected.flatMap(({ role, instanceId, result, cloudWatch }) => [
      `## ${role} (${instanceId})`,
      "",
      "```text",
      result.stdout,
      result.stderr,
      "```",
      "",
      "```json",
      JSON.stringify(cloudWatch, null, 2),
      "```",
      "",
    ]),
  ].join("\n");
  const evidencePath = writeEvidence(`${label}-hosts.md`, content);
  process.stdout.write(`Host evidence: ${evidencePath}\n`);
}

function cloudWatchMetrics(instanceId: string): Record<string, unknown> {
  const end = new Date();
  const start = new Date(end.getTime() - 20 * 60_000);
  const metrics = ["CPUUtilization", "CPUCreditBalance", "CPUCreditUsage", "NetworkIn", "NetworkOut"];
  return Object.fromEntries(metrics.map((metric) => [metric, awsJson([
    "cloudwatch", "get-metric-statistics",
    "--namespace", "AWS/EC2",
    "--metric-name", metric,
    "--dimensions", `Name=InstanceId,Value=${instanceId}`,
    "--start-time", start.toISOString(),
    "--end-time", end.toISOString(),
    "--period", "300",
    "--statistics", "Minimum", "Average", "Maximum",
  ])]));
}

async function sendCommand(
  instanceId: string,
  commands: string[],
  timeoutSeconds: number,
): Promise<{ status: string; stdout: string; stderr: string }> {
  const sent = awsJson([
    "ssm", "send-command",
    "--instance-ids", instanceId,
    "--document-name", "AWS-RunShellScript",
    "--timeout-seconds", String(timeoutSeconds),
    "--parameters", JSON.stringify({ commands: ["set -eu", ...commands], executionTimeout: [String(timeoutSeconds)] }),
  ]);
  const commandId = sent.Command.CommandId as string;
  const deadline = Date.now() + (timeoutSeconds + 60) * 1_000;
  while (Date.now() < deadline) {
    await delay(1_000);
    try {
      const invocation = awsJson([
        "ssm", "get-command-invocation",
        "--command-id", commandId,
        "--instance-id", instanceId,
      ]);
      if (terminalStatuses.has(invocation.Status)) {
        return {
          status: invocation.Status,
          stdout: invocation.StandardOutputContent ?? "",
          stderr: invocation.StandardErrorContent ?? "",
        };
      }
    } catch (error) {
      if (!String(error).includes("InvocationDoesNotExist")) {
        throw error;
      }
    }
  }
  throw new Error(`SSM command ${commandId} did not finish within ${timeoutSeconds + 60}s`);
}

function stackOutputs(): Outputs {
  const described = awsJson([
    "cloudformation", "describe-stacks",
    "--stack-name", stackName,
  ]);
  const outputList = described.Stacks?.[0]?.Outputs ?? [];
  return Object.fromEntries(outputList.map(
    (output: { OutputKey: string; OutputValue: string }) => [output.OutputKey, output.OutputValue],
  ));
}

function hostInstances(outputs: Outputs): Record<string, string> {
  return {
    loadgen: requireOutput(outputs, "LoadgenInstanceId"),
    ssp: requireOutput(outputs, "SspInstanceId"),
    dsp: requireOutput(outputs, "DspInstanceId"),
    support: requireOutput(outputs, "SupportInstanceId"),
    observer: requireOutput(outputs, "ObserverInstanceId"),
  };
}

function requireOutput(outputs: Outputs, name: string): string {
  const value = outputs[name];
  if (!value) {
    throw new Error(`Stack output ${name} is missing`);
  }
  return value;
}

function runCdk(arguments_: string[]): void {
  run("npx", [
    "cdk",
    ...arguments_,
    "-c", `region=${region}`,
    "-c", `instanceType=${instanceType}`,
    ...(profile ? ["--profile", profile] : []),
  ], infrastructureDirectory);
}

function awsJson(arguments_: string[]): any {
  try {
    const output = execFileSync("aws", [
      ...arguments_,
      "--region", region,
      ...(profile ? ["--profile", profile] : []),
      "--output", "json",
    ], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
    return JSON.parse(output || "null");
  } catch (error) {
    const stderr = error && typeof error === "object" && "stderr" in error
      ? String(error.stderr).trim()
      : "";
    if (stderr.includes("session has expired")) {
      throw new Error("AWS session expired. Run `aws login`, then retry the command.");
    }
    throw new Error(stderr || `AWS CLI failed: aws ${arguments_.join(" ")}`);
  }
}

function run(executable: string, arguments_: string[], cwd = infrastructureDirectory): void {
  const result = spawnSync(executable, arguments_, {
    cwd,
    env: {
      ...process.env,
      AWS_REGION: region,
      AWS_PROFILE: profile ?? process.env.AWS_PROFILE,
    },
    stdio: "inherit",
  });
  if (result.status !== 0) {
    const safeArguments = arguments_.map(argument => argument.startsWith("DatabasePassword=") ? "DatabasePassword=<redacted>" : argument);
    throw new Error(`${executable} ${safeArguments.join(" ")} failed with ${result.status}`);
  }
}

function writeEvidence(suffix: string, content: string): string {
  const day = new Date().toISOString().slice(0, 10);
  const directory = path.join(repositoryRoot, "docs/evidence/performance", day);
  mkdirSync(directory, { recursive: true });
  const outputPath = path.join(directory, `stage8c-aws-${suffix}`);
  writeFileSync(outputPath, content, "utf8");
  return path.relative(repositoryRoot, outputPath);
}

function gitCommit(): string {
  return execFileSync("git", ["rev-parse", "HEAD"], {
    cwd: repositoryRoot,
    encoding: "utf8",
  }).trim();
}

function parseOptions(arguments_: string[]): Record<string, string | undefined> {
  const parsed: Record<string, string | undefined> = {};
  for (let index = 0; index < arguments_.length; index += 1) {
    const item = arguments_[index];
    if (!item?.startsWith("--")) {
      throw new Error(`Unexpected argument: ${item}`);
    }
    const name = item.slice(2);
    const next = arguments_[index + 1];
    if (!next || next.startsWith("--")) {
      parsed[name] = "true";
    } else {
      parsed[name] = next;
      index += 1;
    }
  }
  return parsed;
}

function requireCostAcknowledgement(): void {
  if (options["ack-cost"] !== "true") {
    throw new Error(
      "This command changes billable AWS resources. Re-run with --ack-cost after reviewing `diff`.",
    );
  }
}

function shellQuote(value: string): string {
  return `'${value.replaceAll("'", `'"'"'`)}'`;
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function usage(exitCode: number): never {
  process.stdout.write(`Stage 8C AWS experiment runner

Usage: npm run stage8c -- <command> [options]

Read-only/local:
  doctor       Check CLI identity and whether the selected instance type is marked eligible
  build        Type-check, test, and synthesize CloudFormation
  diff         Preview the AWS changes
  status       Show SSM, cloud-init, and container status
  observability Check Collector, Prometheus, Tempo, Grafana, and scrape targets
  grafana-tunnel Open an SSM tunnel from localhost:3000 to the private Grafana
  collect      Save host and CloudWatch evidence

Mutating (requires --ack-cost):
  deploy       Internal guarded step; requires the experiment runner's active lease

Lifecycle: npm run experiment -- run --ack-cost
Recovery: npm run experiment -- cleanup --ack-cost --run-id=rtb-...

Tests:
  smoke        10 RPS for 10 seconds by default
  capacity     500 RPS for 10 minutes by default
  overload     500 -> 1000 -> 100 RPS by default

Common options:
  --profile NAME              AWS CLI profile
  --region REGION             Default: ap-northeast-2
  --instance-type TYPE        Default: t4g.small
  --ack-cost                  Confirm billable resource changes
`);
  process.exit(exitCode);
}

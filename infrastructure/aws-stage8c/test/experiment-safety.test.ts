import assert from "node:assert/strict";
import test from "node:test";
import { createRequire } from "node:module";
import { App, BootstraplessSynthesizer } from "aws-cdk-lib";
import { Template } from "aws-cdk-lib/assertions";
import { ExperimentControlStack } from "../lib/experiment-control-stack.js";
import { Stage8cStack } from "../lib/stage8c-stack.js";
import { experimentSynthesizer } from "../lib/experiment-synthesizer.js";
import { runExperiment } from "../lib/experiment-lifecycle.js";

const { createReaper, deadline } = createRequire(import.meta.url)("../runtime/reaper.cjs");
const now = Date.parse("2026-09-02T14:00:00Z");
const marker = (name: string, runId = "rtb-test", expiry = now - 1000) => ({
  StackName: name, StackId: `arn:aws:cloudformation:ap-northeast-2:333982363617:stack/${name}/id`,
  StackStatus: "CREATE_COMPLETE", CreationTime: new Date(now - 60_000),
  Tags: Object.entries({ Project: "low-latency-rtb", Stage: "8c", RunId: runId, ExpiresAt: new Date(expiry).toISOString() }).map(([Key, Value]) => ({ Key, Value })),
});
function fake(stacks: Record<string, any>, custom: (operation: string, input: any) => any = () => undefined) {
  const calls: { operation: string; input: any }[] = [];
  const api = async (_service: string, operation: string, input: any) => {
    calls.push({ operation, input });
    const override = custom(operation, input);
    if (override !== undefined) return override;
    if (operation === "DescribeStacks") {
      if (!stacks[input.StackName]) throw Object.assign(new Error("Stack does not exist"), { name: "ValidationError" });
      return { Stacks: [stacks[input.StackName]] };
    }
    return {};
  };
  return { calls, reap: createReaper(api, { bucket: "dedicated", repository: "dedicated" }, () => now) };
}

test("active lease is untouched and excessive lifetime is clamped to 45 minutes", async () => {
  const lease = marker("RtbStage8cLease", "rtb-test", now + 10_000);
  const { reap, calls } = fake({ RtbStage8cLease: lease });
  assert.equal((await reap()).state, "active");
  assert.equal(calls.some(call => call.operation === "DeleteStack"), false);
  assert.equal(deadline(marker("RtbStage8cLease", "rtb-test", now + 10 ** 9)), now - 60_000 + 45 * 60_000);
});
test("forced cleanup durably expires the lease before mutation so the scheduler can resume", async () => {
  const lease = marker("RtbStage8cLease", "rtb-test", now + 60_000);
  const { reap, calls } = fake({ RtbStage8cLease: lease }, (operation, input) => {
    if (operation === "UpdateStack") { lease.Tags = input.Tags; return {}; }
  });
  assert.equal((await reap({ mode: "cleanup", runId: "rtb-test" })).state, "cleanup-requested");
  assert.equal(deadline(lease), now);
  assert.equal(calls.some(call => call.operation === "DeleteStack"), false);
  // No forced event on the next tick: persisted intent is enough.
  assert.equal((await reap()).state, "deleting-lease");
});
test("lease tag update must settle before deleting the marker", async () => {
  const lease = { ...marker("RtbStage8cLease"), StackStatus: "UPDATE_IN_PROGRESS" };
  const { reap, calls } = fake({ RtbStage8cLease: lease });
  assert.equal((await reap()).state, "waiting-lease-ready");
  assert.equal(calls.some(call => call.operation === "DeleteStack"), false);
});
test("expired workload is terminated before deletion, without releasing the lease", async () => {
  const { reap, calls } = fake({ RtbStage8cLease: marker("RtbStage8cLease"), RtbStage8c: marker("RtbStage8c") }, operation => {
    if (operation === "DescribeInstances") return { Reservations: [{ Instances: [{ InstanceId: "i-test", State: { Name: "running" } }] }] };
  });
  assert.equal((await reap()).state, "deleting-stack");
  const writes = calls.filter(call => ["TerminateInstances", "DeleteStack"].includes(call.operation));
  assert.deepEqual(writes.map(call => call.operation), ["TerminateInstances", "DeleteStack"]);
  assert.match(writes[1]!.input.StackName, /stack\/RtbStage8c\//);
  assert.ok(calls.find(call => call.operation === "DescribeInstances")!.input.Filters.some((filter: any) => filter.Name === "tag:RunId"));
});
test("another run cannot force cleanup, and foreign stacks cannot be deleted", async () => {
  const { reap } = fake({ RtbStage8cLease: marker("RtbStage8cLease") });
  await assert.rejects(reap({ mode: "cleanup", runId: "rtb-other" }), /does not own/);
  const mismatch = fake({ RtbStage8cLease: marker("RtbStage8cLease"), RtbStage8c: marker("RtbStage8c", "rtb-other") });
  await assert.rejects(mismatch.reap(), /ownership differ/);
  const foreign = marker("RtbStage8cLease"); foreign.Tags = [];
  await assert.rejects(fake({ RtbStage8cLease: foreign }).reap(), /unowned/);
});
test("failed asset cleanup preserves lease for the next independent attempt", async () => {
  const { reap, calls } = fake({ RtbStage8cLease: marker("RtbStage8cLease") }, operation => {
    if (operation === "ListImages") return { imageIds: [{ imageTag: "rtb-test-hash" }] };
    if (operation === "BatchDeleteImage") return { failures: [{ failureCode: "Denied" }] };
  });
  await assert.rejects(reap(), /ECR cleanup incomplete/);
  assert.equal(calls.some(call => call.operation === "DeleteStack"), false);
});
test("only this run's assets are removed, then the durable lease is deleted last", async () => {
  const { reap, calls } = fake({ RtbStage8cLease: marker("RtbStage8cLease") }, operation => {
    if (operation === "ListImages") return { imageIds: [{ imageTag: "rtb-test-hash" }, { imageTag: "rtb-other-hash" }] };
    if (operation === "ListObjectsV2") return { Contents: [{ Key: "rtb-test/template" }] };
  });
  await reap();
  assert.deepEqual(calls.find(call => call.operation === "BatchDeleteImage")!.input.imageIds, [{ imageTag: "rtb-test-hash" }]);
  assert.equal(calls.find(call => call.operation === "ListObjectsV2")!.input.Prefix, "rtb-test/");
  assert.equal(calls.at(-1)!.operation, "DeleteStack");
  assert.match(calls.at(-1)!.input.StackName, /RtbStage8cLease/);
});
test("orphan volumes block success; canary is reclaimed without an experiment lease", async () => {
  const residual = fake({ RtbStage8cLease: marker("RtbStage8cLease") }, operation => operation === "DescribeVolumes" ? { Volumes: [{}] } : undefined);
  await assert.rejects(residual.reap(), /Orphan EBS/);
  const canary = fake({ RtbStage8cSafetyCanary: marker("RtbStage8cSafetyCanary") });
  assert.equal((await canary.reap()).state, "idle");
  assert.ok(canary.calls.some(call => call.operation === "DeleteStack"));
});
test("a failed canary never prevents cleanup of an expired billable workload", async () => {
  const broken = marker("RtbStage8cSafetyCanary"); broken.Tags = [];
  const { reap, calls } = fake({ RtbStage8cSafetyCanary: broken, RtbStage8cLease: marker("RtbStage8cLease"), RtbStage8c: marker("RtbStage8c") });
  await assert.rejects(reap(), /unowned/);
  assert.ok(calls.some(call => call.operation === "DeleteStack" && call.input.StackName.includes("stack/RtbStage8c/")));
});
test("cleanup runs after success, partial acquisition, deployment, or verification failure", async () => {
  for (const failure of ["none", "acquire", "deploy", "verify"]) {
    const calls: string[] = [];
    const step = (name: string) => async () => { calls.push(name); if (failure === name) throw new Error(name); };
    const promise = runExperiment({ acquire: step("acquire"), deploy: step("deploy"), verify: step("verify"), cleanup: step("cleanup") });
    if (failure === "none") await promise; else await assert.rejects(promise, new RegExp(failure));
    assert.equal(calls.at(-1), "cleanup");
  }
});
test("cleanup failures remain visible alongside the original test failure", async () => {
  await assert.rejects(runExperiment({ acquire: async () => {}, deploy: async () => {},
    verify: async () => { throw new Error("test failed"); }, cleanup: async () => { throw new Error("delete failed"); },
  }), (error: any) => error instanceof AggregateError && error.errors.length === 2);
});
test("control plane contains no EC2, administrator policy, or GitHub AssumeRole grant", () => {
  const stack = new ExperimentControlStack(new App(), "ControlTest", {
    env: { account: "333982363617", region: "ap-northeast-2" }, synthesizer: new BootstraplessSynthesizer(),
  });
  const template = Template.fromStack(stack);
  template.resourceCountIs("AWS::EC2::Instance", 0);
  template.hasResourceProperties("AWS::Events::Rule", { ScheduleExpression: "rate(1 minute)", State: "ENABLED" });
  const resources = template.toJSON().Resources;
  const deploy = Object.values(resources).find((resource: any) => resource.Properties?.RoleName === "RtbStage8cDeploy") as any;
  const grants = JSON.stringify(deploy.Properties.Policies);
  assert.doesNotMatch(grants, /AdministratorAccess|sts:AssumeRole|iam:CreateRole|lambda:UpdateFunction|events:DisableRule/);
  assert.doesNotMatch(JSON.stringify(template.toJSON()), /AdministratorAccess|cdk-hnb659fds/);
});
test("controlled workload imports host profiles and never creates IAM roles or policies", () => {
  const app = new App();
  const stack = new Stage8cStack(app, "WorkloadTest", { env: { account: "333982363617", region: "ap-northeast-2" }, controlledRuntime: true,
    runId: "rtb-test", expiresAt: new Date(now).toISOString(), synthesizer: experimentSynthesizer("333982363617", "ap-northeast-2", "rtb-test"),
  });
  const template = Template.fromStack(stack);
  for (const type of ["AWS::IAM::Role", "AWS::IAM::Policy", "AWS::IAM::InstanceProfile"]) template.resourceCountIs(type, 0);
  template.allResourcesProperties("AWS::EC2::Instance", { IamInstanceProfile: "RtbStage8cHost" });
  const assembly = app.synth();
  const artifact = assembly.getStackByName("WorkloadTest");
  assert.equal(artifact.assumeRoleArn, "");
  assert.equal(artifact.cloudFormationExecutionRoleArn, "arn:aws:iam::333982363617:role/RtbStage8cExecution");
});

import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { App, BootstraplessSynthesizer } from "aws-cdk-lib";
import { Template } from "aws-cdk-lib/assertions";
import { ExperimentIdentityStack } from "../lib/experiment-identity-stack.js";
import { ExperimentControlStack } from "../lib/experiment-control-stack.js";
import { assertControlCanBeRemoved } from "../lib/control-lifecycle.js";

const { createReaper } = createRequire(import.meta.url)("../runtime/reaper.cjs");
const env = { account: "333982363617", region: "ap-northeast-2" };
function resources(kind: typeof ExperimentIdentityStack | typeof ExperimentControlStack): any[] {
  return Object.values(Template.fromStack(new kind(new App(), "Test", { env, synthesizer: new BootstraplessSynthesizer() })).toJSON().Resources);
}
test("persistent stack contains only IAM; control contains no IAM or cross-stack exports", () => {
  const identity = resources(ExperimentIdentityStack);
  assert.ok(identity.length > 0);
  assert.ok(identity.every(r => ["AWS::IAM::Role", "AWS::IAM::InstanceProfile"].includes(r.Type)));
  const control = resources(ExperimentControlStack);
  assert.ok(control.every(r => !r.Type.startsWith("AWS::IAM::")));
  assert.doesNotMatch(JSON.stringify(control), /Fn::ImportValue/);
  assert.equal(control.find(r => r.Type === "AWS::Lambda::Function").Properties.Role, "arn:aws:iam::333982363617:role/RtbStage8cReaper");
});
test("control bootstrap cannot mutate IAM or provision workload, while test role cannot alter watchdog", () => {
  const identity = resources(ExperimentIdentityStack);
  const role = (name: string) => identity.find(r => r.Properties.RoleName === name).Properties;
  const bootstrap = role("RtbStage8cControlRunner");
  assert.deepEqual(bootstrap.AssumeRolePolicyDocument, role("RtbStage8cDeploy").AssumeRolePolicyDocument);
  const runner = JSON.stringify(bootstrap.Policies);
  assert.doesNotMatch(runner, /iam:Create|iam:Put|lambda:|ec2:RunInstances|AdministratorAccess|sts:AssumeRole/);
  assert.doesNotMatch(JSON.stringify(role("RtbStage8cControlExecution").Policies), /iam:Create|iam:Put|ec2:RunInstances|AdministratorAccess/);
  assert.doesNotMatch(JSON.stringify(role("RtbStage8cDeploy").Policies), /RtbStage8cControl|lambda:Delete|lambda:Update|events:Delete|events:Disable/);
  const writes = bootstrap.Policies[0].PolicyDocument.Statement.filter((s: any) => s.Action.includes("cloudformation:DeleteStack"));
  assert.deepEqual(writes[0].Resource, ["arn:aws:cloudformation:ap-northeast-2:333982363617:stack/RtbStage8cControl/*"]);
});
test("normal control retirement requires empty inventory, including stopped instances and untagged images", () => {
  const empty = { stacks: [], instances: [], volumes: [], vpcs: [], images: [], objects: [] };
  assert.doesNotThrow(() => assertControlCanBeRemoved(empty));
  assert.doesNotThrow(() => assertControlCanBeRemoved({ ...empty, instances: [{ State: { Name: "terminated" } }] }));
  for (const field of Object.keys(empty)) {
    assert.throws(() => assertControlCanBeRemoved({ ...empty, [field]: [{}] }), /retain the watchdog/);
  }
  assert.throws(() => assertControlCanBeRemoved({ ...empty, instances: [{ State: { Name: "stopped" } }] }), /retain the watchdog/);
});

const now = Date.parse("2026-09-06T12:00:00Z");
function retirement(age: number, override: (operation: string, input: any) => any = () => undefined) {
  const calls: any[] = [];
  const control = { StackName: "RtbStage8cControl", StackId: "control-immutable-id", StackStatus: "CREATE_COMPLETE", CreationTime: new Date(now - age) };
  const api = async (_service: string, operation: string, input: any) => {
    calls.push({ operation, input });
    const value = override(operation, input);
    if (value !== undefined) return value;
    if (operation === "DescribeStacks") {
      if (input.StackName === "RtbStage8cControl") return { Stacks: [control] };
      throw Object.assign(new Error("does not exist"), { name: "ValidationError" });
    }
    return {};
  };
  return { calls, run: createReaper(api, { bucket: "dedicated", repository: "dedicated", controlStack: "RtbStage8cControl" }, () => now) };
}
test("runner-loss fallback waits one hour, then deletes only the empty control by immutable ID", async () => {
  const early = retirement(59 * 60_000);
  assert.equal((await early.run()).state, "idle");
  assert.equal(early.calls.some(c => c.operation === "DeleteStack"), false);
  const expired = retirement(60 * 60_000);
  assert.equal((await expired.run()).state, "deleting-control");
  assert.deepEqual(expired.calls.at(-1), { operation: "DeleteStack", input: { StackName: "control-immutable-id" } });
});
test("runner-loss fallback never retires control while assets, volumes or unknown instance state remain", async () => {
  for (const [operation, result] of [
    ["ListImages", { imageIds: [{}] }], ["ListObjectsV2", { Contents: [{}] }],
    ["DescribeVolumes", { Volumes: [{}] }], ["DescribeVpcs", { Vpcs: [{}] }],
    ["DescribeInstances", { Reservations: [{ Instances: [{}] }] }],
  ] as const) {
    const attempt = retirement(61 * 60_000, op => op === operation ? result : undefined);
    await assert.rejects(attempt.run(), /retirement blocked/);
    assert.equal(attempt.calls.some(c => c.operation === "DeleteStack"), false);
  }
});
test("failed inventory request cannot be treated as an empty inventory", async () => {
  const attempt = retirement(61 * 60_000, op => { if (op === "DescribeVolumes") throw new Error("AccessDenied"); });
  await assert.rejects(attempt.run(), /AccessDenied/);
  assert.equal(attempt.calls.some(c => c.operation === "DeleteStack"), false);
});
test("workflow creates control before deployment identity and removes it after evidence preservation", () => {
  const yaml = readFileSync(new URL("../../../.github/workflows/stage8c-experiment.yml", import.meta.url), "utf8");
  assert.ok(yaml.indexOf("id: control") < yaml.indexOf("id: deployment_identity"));
  assert.ok(yaml.indexOf("Preserve evidence") < yaml.indexOf("experiment-control -- remove"));
  assert.match(yaml, /steps.teardown_identity.outcome == 'success'/);
  assert.match(yaml, /unset-current-credentials: true/);
  assert.doesNotMatch(yaml, /aws-access-key-id:|aws-secret-access-key:|secrets\./);
});

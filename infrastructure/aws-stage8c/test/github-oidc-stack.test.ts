import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { App, BootstraplessSynthesizer } from "aws-cdk-lib";
import { Template } from "aws-cdk-lib/assertions";
import { GitHubOidcStack } from "../lib/github-oidc-stack.js";

function template(existingProviderArn?: string): Template {
  return Template.fromStack(new GitHubOidcStack(new App(), "AuthTest", {
    env: { account: "333982363617", region: "ap-northeast-2" },
    synthesizer: new BootstraplessSynthesizer(),
    existingProviderArn,
  }));
}

test("OIDC trusts exactly this repository main branch and STS audience", () => {
  template().hasResourceProperties("AWS::IAM::Role", {
    RoleName: "RtbStage8cGitHub",
    MaxSessionDuration: 3600,
    AssumeRolePolicyDocument: {
      Version: "2012-10-17",
      Statement: [{
        Effect: "Allow",
        Action: "sts:AssumeRoleWithWebIdentity",
        Principal: { Federated: { "Fn::GetAtt": ["GitHubProvider", "Arn"] } },
        Condition: { StringEquals: {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com",
          "token.actions.githubusercontent.com:sub": "repo:bbororo5/OpenRTB-Ads-auction:ref:refs/heads/main",
        } },
      }],
    },
  });
});

test("authentication baseline creates neither spending resources nor deployment grants", () => {
  const resources = Object.values(template().toJSON().Resources) as { Type: string; Properties: Record<string, unknown> }[];
  assert.deepEqual(resources.map(resource => resource.Type).sort(), ["AWS::IAM::OIDCProvider", "AWS::IAM::Role"]);
  const role = resources.find(resource => resource.Type === "AWS::IAM::Role")!;
  assert.equal(role.Properties.Policies, undefined);
  assert.equal(role.Properties.ManagedPolicyArns, undefined);
});

test("shared provider can be reused without modification or ownership transfer", () => {
  const arn = "arn:aws:iam::333982363617:oidc-provider/token.actions.githubusercontent.com";
  const stack = template(arn);
  stack.resourceCountIs("AWS::IAM::OIDCProvider", 0);
  assert.ok(JSON.stringify(stack.toJSON()).includes(arn));
});

test("foreign providers cannot be trusted accidentally", () => {
  assert.throws(() => template("arn:aws:iam::111111111111:oidc-provider/token.actions.githubusercontent.com"), /target account/);
});

test("workflow is manual, main-only, short-lived and contains no deployment or static keys", () => {
  const workflow = readFileSync(new URL("../../../.github/workflows/stage8c-oidc-check.yml", import.meta.url), "utf8");
  assert.match(workflow, /workflow_dispatch:/);
  assert.doesNotMatch(workflow, /^\s+(push|pull_request|pull_request_target|schedule):/m);
  assert.match(workflow, /github.ref == 'refs\/heads\/main'/);
  assert.match(workflow, /role-duration-seconds: 900/);
  assert.match(workflow, /allowed-account-ids: '333982363617'/);
  assert.doesNotMatch(workflow, /secrets\.|aws-access-key-id:|aws-secret-access-key:|run:.*deploy/);
  assert.match(workflow, /uses: aws-actions\/configure-aws-credentials@[a-f0-9]{40}/);
});

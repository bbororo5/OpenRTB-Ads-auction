import assert from "node:assert/strict";
import test from "node:test";

import { App } from "aws-cdk-lib";
import { Template } from "aws-cdk-lib/assertions";

import { Stage8cStack } from "../lib/stage8c-stack.js";

test("free-tier experiment isolates four roles without managed data-plane services", () => {
  const app = new App();
  const stack = new Stage8cStack(app, "TestStack", {
    env: { account: "111111111111", region: "ap-northeast-2" },
  });
  const template = Template.fromStack(stack);

  template.resourceCountIs("AWS::EC2::Instance", 4);
  template.resourceCountIs("AWS::EC2::NatGateway", 0);
  template.resourceCountIs("AWS::ElasticLoadBalancingV2::LoadBalancer", 0);
  template.resourceCountIs("AWS::RDS::DBInstance", 0);
  template.allResourcesProperties("AWS::EC2::Instance", {
    CreditSpecification: { CPUCredits: "standard" },
  });
});

test("application ports are never open to the public internet", () => {
  const app = new App();
  const stack = new Stage8cStack(app, "SecurityTestStack", {
    env: { account: "111111111111", region: "ap-northeast-2" },
  });
  const resources = Template.fromStack(stack).toJSON().Resources as Record<
    string,
    { Type: string; Properties: Record<string, unknown> }
  >;
  const ingress = Object.values(resources)
    .filter((resource) => resource.Type === "AWS::EC2::SecurityGroupIngress")
    .map((resource) => resource.Properties);

  assert.equal(ingress.some((rule) => rule.CidrIp === "0.0.0.0/0"), false);
  assert.equal(ingress.some((rule) => rule.CidrIpv6 === "::/0"), false);
});

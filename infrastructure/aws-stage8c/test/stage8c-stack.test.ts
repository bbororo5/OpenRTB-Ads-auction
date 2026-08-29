import assert from "node:assert/strict";
import test from "node:test";

import { App } from "aws-cdk-lib";
import { Template } from "aws-cdk-lib/assertions";

import { Stage8cStack } from "../lib/stage8c-stack.js";

test("performance experiment isolates four workload roles and one observer", () => {
  const app = new App();
  const stack = new Stage8cStack(app, "TestStack", {
    env: { account: "111111111111", region: "ap-northeast-2" },
  });
  const template = Template.fromStack(stack);

  template.resourceCountIs("AWS::EC2::Instance", 5);
  template.resourceCountIs("AWS::EC2::NatGateway", 0);
  template.resourceCountIs("AWS::ElasticLoadBalancingV2::LoadBalancer", 0);
  template.resourceCountIs("AWS::RDS::DBInstance", 0);
  template.allResourcesProperties("AWS::EC2::Instance", {
    CreditSpecification: { CPUCredits: "standard" },
  });
});

test("SSP and DSP attach the Java agent while logs remain outside the OTel pipeline", () => {
  const app = new App();
  const stack = new Stage8cStack(app, "TelemetryTestStack", {
    env: { account: "111111111111", region: "ap-northeast-2" },
  });
  const template = JSON.stringify(Template.fromStack(stack).toJSON());

  assert.match(template, /-javaagent:\/otel\/opentelemetry-javaagent\.jar/);
  assert.match(template, /OTEL_SERVICE_NAME=rtb-ssp/);
  assert.match(template, /OTEL_SERVICE_NAME=rtb-dsp/);
  assert.match(template, /OTEL_LOGS_EXPORTER=none/);
  assert.match(template, /aws-stage8c\.yaml/);
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

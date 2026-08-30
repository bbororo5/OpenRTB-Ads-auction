import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
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

test("SSP and DSP attach the Java agent and export all stable OTel signals", () => {
  const app = new App();
  const stack = new Stage8cStack(app, "TelemetryTestStack", {
    env: { account: "111111111111", region: "ap-northeast-2" },
  });
  const template = JSON.stringify(Template.fromStack(stack).toJSON());

  assert.match(template, /-javaagent:\/otel\/opentelemetry-javaagent\.jar/);
  assert.match(template, /OTEL_SERVICE_NAME=rtb-ssp/);
  assert.match(template, /OTEL_SERVICE_NAME=rtb-dsp/);
  assert.match(template, /OTEL_LOGS_EXPORTER=otlp/);
  assert.match(template, /OTEL_PROPAGATORS=tracecontext,baggage/);
  assert.match(template, /grafana\/loki:3\.7\.7/);
  assert.match(template, /grafana\/pyroscope:2\.3\.0/);
  assert.match(template, /opentelemetry-collector-ebpf-profiler:0\.159\.0/);
  assert.match(template, /feature-gates=service\.profilesSupport/);
  assert.match(template, /PYROSCOPE_OTLP_ENDPOINT=10\.42\.0\.50:4040/);
  assert.equal(template.match(/--name otel-ebpf-profiler/g)?.length, 5);
  assert.match(template, /aws-stage8c\.yaml/);
  assert.match(template, /docker network create observability/);
  assert.match(template, /--network observability -p 4317:4317/);
  assert.match(template, /TEMPO_OTLP_ENDPOINT=tempo:4317/);
  assert.match(template, /PROMETHEUS_URL=http:\/\/prometheus:9090/);
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
  assert.equal(ingress.filter((rule) => rule.FromPort === 3100).length, 4);
  assert.equal(ingress.filter((rule) => rule.FromPort === 4040).length, 4);
});

test("host profiler does not compete with the Collector internal metrics port", () => {
  const profilerConfig = readFileSync(
    new URL("../../../observability/profiler/host.yaml", import.meta.url),
    "utf8",
  );

  assert.match(profilerConfig, /telemetry:[\s\S]*metrics:\s*#[\s\S]*level: none/);
});

#!/usr/bin/env node
import { App } from "aws-cdk-lib";

import { Stage8cStack } from "../lib/stage8c-stack.js";
import { experimentSynthesizer } from "../lib/experiment-synthesizer.js";

const app = new App();
const region = app.node.tryGetContext("region")
  ?? process.env.CDK_DEFAULT_REGION
  ?? process.env.AWS_REGION
  ?? "ap-northeast-2";

new Stage8cStack(app, "RtbStage8c", {
  env: {
    account: process.env.CDK_DEFAULT_ACCOUNT,
    region,
  },
  instanceType: app.node.tryGetContext("instanceType") ?? "t4g.small",
  controlledRuntime: true,
  runId: process.env.RTB_RUN_ID ?? "rtb-preview",
  expiresAt: process.env.RTB_EXPIRES_AT,
  synthesizer: experimentSynthesizer(process.env.CDK_DEFAULT_ACCOUNT ?? "333982363617", region, process.env.RTB_RUN_ID ?? "rtb-preview"),
});
